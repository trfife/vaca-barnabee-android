# Barnabee Satellite State Machine — Specification

**Status**: Draft v1 — pre-implementation spec for stuck-listening fix (P4-d + dependencies)

**Motivation**: Upstream VACA has a subtle class of bugs where `pipelineStatus` drifts out of sync with actual HA pipeline state, producing "stuck forever in listening" or misrouted events from stale pipelines. Today's remedy is a blunt 10-second whole-app restart watchdog. This spec replaces it with a deterministic two-layer state machine, a session-token protocol, and stage-specific timeouts.

Before writing a line of code, we ratify this contract.

---

## 1. Two orthogonal state variables

```
SatelliteState  = STOPPED | STARTING | RUNNING | PAUSED | STOPPING
PipelineStatus  = INACTIVE | LISTENING | STREAMING | RESETTING
```

Upstream conflates some of these; our fork keeps them strictly orthogonal and adds `RESETTING`.

- `SatelliteState` = the Wyoming-protocol satellite lifecycle (run-satellite, pause-satellite, stop-satellite)
- `PipelineStatus` = the current voice-interaction turn: are we listening for speech, streaming TTS, actively tearing down, or idle

### Legal transitions

```
SatelliteState:
  STOPPED   → STARTING   (on run-satellite)
  STARTING  → RUNNING    (on ready)
  RUNNING   → PAUSED     (on pause-satellite)
  PAUSED    → RUNNING    (on run-satellite)
  *         → STOPPING   (on stop-satellite)
  STOPPING  → STOPPED    (on cleanup complete)

PipelineStatus:
  INACTIVE  → LISTENING  (on wake-word OR sendStartPipeline)
  LISTENING → STREAMING  (on audio-start)
  STREAMING → INACTIVE   (on audio-stop, natural end)
  *         → RESETTING  (on timeout, error, local force-reset)
  RESETTING → INACTIVE   (after cleanup settled)
```

**Illegal transitions crash-fast in debug, log + force-reset in release.**

---

## 2. Pipeline session token (the core fix for stale-event bugs)

Each pipeline turn is tagged with a monotonic `pipelineEpoch: Long`.

- Incremented on every `PipelineStatus.INACTIVE → LISTENING` transition
- Incremented on every `resetPipeline()` entry
- Stamped on every outbound Wyoming event from the client side
- Every inbound Wyoming event is checked: if its epoch (or the current epoch at receive time ≠ epoch when inbound was expected) indicates a stale pipeline, the event is **dropped with a warn-level log**, never mutates state

### Why this is needed

Consider: pipeline times out, we reset, start a new pipeline. A late `transcript` from the OLD pipeline arrives. Without an epoch token, upstream would process it in the new pipeline's context — garbage in, garbage out. With the epoch, it's dropped.

### Wyoming protocol compatibility

HA doesn't know about our epoch. We stamp events on our side only: when we send `transcribe`/`audio-chunk`/etc. we record the epoch at send-time. Inbound events whose "conversation" (identified by the last outbound epoch at the moment HA's reply was produced) is stale are dropped. Implementation: tag the `audio-start`-matching epoch, reject audio-chunks/audio-stops whose matching `audio-start` epoch doesn't equal current.

For events not clearly tied to an outbound (e.g. `transcript`), we use the epoch at the last `transcribe` we sent. If pipeline was reset between, discard.

---

## 3. Stage-specific timeouts (replaces blunt 10s)

Each pipeline stage has its own timeout:

| From → To                          | Timeout | Rationale                                        |
|-----------------------------------:|--------:|--------------------------------------------------|
| `transcribe` → `voice-started`     |     5s  | VAD should fire fast once streaming starts       |
| `voice-started` → `voice-stopped`  |    30s  | User can speak for a while                       |
| `voice-stopped` → `transcript`     |    15s  | STT                                              |
| `transcript` → `synthesize`        |    60s  | **LLM can legitimately be slow — do not reset**  |
| `synthesize` → `audio-start`       |    10s  | TTS synthesis                                    |
| `audio-stop` → next turn (continue-conversation or follow-up question) | 15s | Re-arm window, was 0 upstream — THIS is the stuck-listening bug site |
| `audio-stop` → idle (no continue)  |     2s  | Same as upstream                                 |

All durations tunable via Settings. The 60s post-transcript timeout **must not be shortened** just because "usually LLM is fast" — slow-but-healthy HA is not broken HA.

### Hard upper bound
If any single pipeline turn exceeds **120s total** (from `LISTENING → INACTIVE`), force-reset regardless of stage. This is the backstop.

---

## 4. Wake-during-reset deterministic behavior

Today: undefined (race).

New rule, implemented in the wake-word dispatcher:

```
fun onWakeDetected(epoch: Long):
    when (pipelineStatus) {
      INACTIVE  → startPipeline()                 // happy path
      LISTENING → resetPipeline(); queueOneWake() // user double-spoke, restart
      STREAMING → resetPipeline(); queueOneWake() // barge-in (P6-a)
      RESETTING → queueOneWake()                   // AT MOST ONE queued wake
    }
```

`queueOneWake()` stores the wake intent; when `RESETTING → INACTIVE`, if a queued wake exists, fire `startPipeline()` once and clear the queue. Additional wake events during RESETTING overwrite (not queue) — we never dispatch multiple back-to-back.

---

## 5. Reset semantics (`resetPipeline()`)

Entering `RESETTING`:
1. Increment `pipelineEpoch` atomically
2. Cancel all pending stage timeouts
3. `sendAudioStop()` to HA (sever streaming)
4. Release input audio stream if held
5. `volumeDucking("all", false)`
6. If `pcmMediaPlayer.isPlaying`: stop it
7. Clear `expectingTTSResponse`, `lastResponseIsQuestion`
8. Transition to `INACTIVE`
9. Fire `state-changed` event to HA with reason code

Reset is idempotent: calling it while already `RESETTING` is a no-op.

---

## 6. HA-side compensating protocol (new in our fork)

HA integration (`vaca-barnabee-integration`) gains:

- On `pipeline-ended` where no `transcript` was received: send `run-satellite` re-init. This is a safety net for cases our client-side fix misses, not the primary mechanism.
- On receiving our new `state-changed` event with `reason=timeout` three times in 5 minutes: surface a persistent notification "VACA satellite {device} is flaky — consider reloading."

---

## 7. What the 10-second app restart watchdog becomes

Gone. Replaced by:

1. Client-side stage timeouts (§3) → pipeline reset, not app restart
2. Supervisor watchdog (P4.5-g) → component-level restart, not app restart
3. External HA-side watchdog (P4.5 new) → if device silent >2min, force-kill via ADB as last resort
4. `Thread.setDefaultUncaughtExceptionHandler` with crash-loop detection: >3 crashes in 5min ⇒ stop auto-relaunching, surface to HA

**Explicit non-goal**: the app never auto-restarts itself as a "cure." Restarts are for genuine crashes, not state drift.

---

## 8. Observability hooks (prereq for P4.5)

Every state transition fires a local `StateTransition` object with:
- `from`, `to` (both state vars)
- `epoch` (pipeline epoch if applicable)
- `reason` (enum: event_driven, timeout, error, force_reset)
- `triggering_event` (Wyoming event name or `"local"`)
- `timestamp_ns`

These are:
- Logged to the in-memory ring buffer (P4.5-d)
- Emitted over Wyoming as `state-changed` custom-events (throttled to 1Hz burst, then 5s coalescing)
- Used as the data source for the Barnabee dashboard state badge (listening/thinking/talking)

---

## 9. Mapping to upstream code (where each change lands)

- `ClientHandler.kt`
  - Add `pipelineEpoch: AtomicLong`, stamp + check
  - Replace `setPipelineNextStageTimeout(Int)` with `setPipelineNextStageTimeout(stage: PipelineStage)`
  - Fix `audio-stop` branch: arm timeout after `sendStartPipeline()` too
  - Add `state-changed` event emitter
- `BackgroundTask.kt` (wake dispatcher)
  - Implement `queueOneWake()` logic
- New: `PipelineStateMachine.kt`
  - Encapsulate transitions; `ClientHandler` calls into it instead of mutating `pipelineStatus` directly
- Remove: the restart-after-10s watchdog in `VAForegroundService`

---

## 10. Acceptance tests (must pass before merge)

Added to test matrix (prerequisite to P5):

1. Continue-conversation × 5 turns, HA responds in 2s each → no stuck state, no false resets
2. Slow HA: mock 30s LLM response time → no reset (transcript→synthesize stage allows 60s)
3. HA drops connection mid-pipeline → pipeline resets after stage timeout, satellite stays RUNNING
4. Wake fires while `STREAMING` (barge-in) → reset + new pipeline starts within 300ms
5. Wake fires while `RESETTING` → exactly one new pipeline after reset completes
6. Stale `transcript` arrives after forced reset → logged, dropped, no state change
7. App restart mid-pipeline → foreground service restores; pipeline starts `INACTIVE` regardless of prior state
8. 120s hard upper bound triggers → force-reset fires even if individual stages didn't timeout

---

## 11. Open questions (resolved before implementation)

- [ ] Should `pipelineEpoch` persist across foreground-service restart, or reset to 0? (Lean: reset to 0; service restart is a clean slate.)
- [ ] How do we distinguish "HA slow" from "HA gone" during the 60s post-transcript window? (Heartbeat? TCP keepalive? Will revisit after P4.5-a ships.)
- [ ] Do we need epoch tags on wake-word events from MicroWakeWord/OpenWakeWord engines? (Probably not — wakes are inherently forward-going, only reply events need stale detection.)
