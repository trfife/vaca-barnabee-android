package com.msp1974.vacompanion.wyoming

/**
 * Stage-specific timeouts for the Wyoming pipeline.
 *
 * Replaces the blunt int-second timeouts previously used. Each stage reflects
 * a concrete Wyoming-protocol wait and has a rationale. Durations are tunable
 * via [APPConfig] in a future phase — constants here are the Barnabee defaults
 * derived from production observation plus the state-machine spec
 * (docs/state-machine.md §3).
 *
 * Critically, [TRANSCRIPT_TO_SYNTHESIZE] is long — slow-but-healthy HA is not
 * broken HA. Shortening it would cause the very "stuck / false reset" class
 * of bugs this fork is trying to eliminate.
 */
enum class PipelineStage(val durationMs: Long, val rationale: String) {
    /** `transcribe` sent → HA responds `voice-started` (VAD trip). */
    TRANSCRIBE_TO_VOICE_STARTED(5_000L, "VAD should fire fast once streaming starts"),

    /** `voice-started` → `voice-stopped` (end of user speech). */
    VOICE_STARTED_TO_STOPPED(30_000L, "User can speak for a while"),

    /** `voice-stopped` → `transcript` (STT processing). */
    VOICE_STOPPED_TO_TRANSCRIPT(15_000L, "STT"),

    /** `transcript` → `synthesize` (conversation/LLM response). */
    TRANSCRIPT_TO_SYNTHESIZE(60_000L, "LLM can legitimately be slow — do not reset"),

    /** `synthesize` → `audio-start` (TTS). */
    SYNTHESIZE_TO_AUDIO_START(10_000L, "TTS synthesis"),

    /** After `audio-stop` when we re-arm for follow-up (continueConversation/question). */
    AUDIO_STOP_TO_NEXT_TURN(15_000L,
        "Re-arm window, was unset upstream — THIS is the stuck-listening bug site"),

    /** After `audio-stop` with no follow-up — defensive cleanup only. */
    AUDIO_STOP_TO_IDLE(2_000L, "Defensive teardown window"),

    /** Absolute cap on any single pipeline turn. */
    HARD_UPPER_BOUND(120_000L, "Backstop — force-reset regardless of stage");
}
