package com.msp1974.vacompanion.wyoming

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.msp1974.vacompanion.audio.Alarm
import com.msp1974.vacompanion.audio.PCMMediaPlayer
import com.msp1974.vacompanion.audio.VAMediaPlayer
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.DeviceCapabilitiesManager
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.Logger
import com.msp1974.vacompanion.utils.ScreenUtils
import com.msp1974.vacompanion.utils.WakeWords
import com.msp1974.vacompanion.wakeword.microwakeword.providers.AssetWakeWordProvider
import io.github.z4kn4fein.semver.toVersion
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addAll
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.json.JSONException
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.net.SocketException
import java.nio.charset.Charset
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.atomics.minusAssign
import kotlin.concurrent.atomics.plusAssign
import kotlin.concurrent.thread

class ClientHandler(private val context: Context, private val server: WyomingTCPServer, private val client: Socket) {
    private val log = Logger()
    private val config: APPConfig = APPConfig.getInstance(context)
    private val client_id = client.port
    private val reader: DataInputStream = DataInputStream(client.getInputStream())
    private val writer: DataOutputStream = DataOutputStream(client.getOutputStream())

    private var handler: Handler = Handler(Looper.getMainLooper())

    private var runClient: Boolean = true
    private var satelliteStatus: SatelliteState = SatelliteState.STOPPED
    private var pipelineStatus: PipelineStatus = PipelineStatus.INACTIVE
    private val connectionID: String = "${client.inetAddress.hostAddress}"

    private var pingTimer: Timer = Timer()

    private var alarmPlayer: Alarm = Alarm(context)
    private var pcmMediaPlayer: PCMMediaPlayer = PCMMediaPlayer(context)
    private var musicPlayer: VAMediaPlayer = VAMediaPlayer.getInstance(context)

    private var expectingTTSResponse: Boolean = false
    private var lastResponseIsQuestion: Boolean = false

    /**
     * Monotonic pipeline epoch — increments on every [resetPipeline] entry and
     * every [PipelineStatus.INACTIVE] → [PipelineStatus.LISTENING] transition.
     *
     * Stage timeouts capture the current epoch when scheduled and drop their
     * work if the epoch changed by the time they fire. This kills a whole
     * class of "stale timeout from a previous pipeline resets the new one"
     * bugs.
     *
     * See docs/state-machine.md §2.
     */
    private val pipelineEpoch = AtomicLong(0L)

    /**
     * The stage currently being waited on, for logging and the 120s hard cap.
     */
    private var currentStage: PipelineStage? = null

    /** Epoch at which [currentStage] was armed — used for hard-cap accounting. */
    private var currentStageArmedAt: Long = 0L

    /**
     * Pending hard-cap token. Kept separate so it can survive stage changes
     * within the same pipeline turn (i.e. not cancelled by cancelPipelineNextStageTimeout).
     */
    private var hardCapRunnable: Runnable? = null

    // --- Satellite phase (user-facing coarse state) --------------------------
    //
    // Emitted to HA as `sensors.satellite_state` for the Barnabee dashboard
    // "listening / thinking / talking" indicators. See SatellitePhase.kt for
    // the rules (notably: ERROR is sticky; audio-stop continue stays THINKING
    // until HA's next `transcribe` since the mic isn't actually live in that
    // window).
    //
    // All phase + timestamp mutations MUST go through [setPhase]/[markWakeNow]/
    // [markPipelineSuccessNow]/[markErrorNow] under [phaseLock]. Callers come
    // from three threads (reader, wake-word broadcast receiver, ping timer)
    // and `sendStatus` path is not otherwise synchronized.
    private val phaseLock = Any()

    @Volatile private var satellitePhase: SatellitePhase = SatellitePhase.IDLE
    @Volatile private var lastWakeAtMs: Long = 0L
    @Volatile private var lastPipelineSuccessAtMs: Long = 0L
    @Volatile private var lastErrorAtMs: Long = 0L

    /** Monotonic counter for throttled periodic heartbeats off pingTimer. */
    private var pingTickCounter: Int = 0

    private fun setPhase(newPhase: SatellitePhase) {
        synchronized(phaseLock) {
            if (satellitePhase == newPhase) return
            log.d("SatellitePhase: $satellitePhase → $newPhase")
            satellitePhase = newPhase
            emitStatusSnapshotLocked()
        }
    }

    private fun markWakeNow() {
        synchronized(phaseLock) {
            lastWakeAtMs = System.currentTimeMillis()
            // Treat wake as a real transition — clears a sticky ERROR.
            if (satellitePhase == SatellitePhase.ERROR) {
                satellitePhase = SatellitePhase.LISTENING
            }
            emitStatusSnapshotLocked()
        }
    }

    private fun markPipelineSuccessNow() {
        synchronized(phaseLock) {
            lastPipelineSuccessAtMs = System.currentTimeMillis()
            emitStatusSnapshotLocked()
        }
    }

    private fun markErrorNow() {
        synchronized(phaseLock) {
            lastErrorAtMs = System.currentTimeMillis()
            satellitePhase = SatellitePhase.ERROR
            emitStatusSnapshotLocked()
        }
    }

    /** Must be called under [phaseLock]. Builds + sends the sensors payload. */
    private fun emitStatusSnapshotLocked() {
        val stageName = currentStage?.name ?: "NONE"
        val payload = buildJsonObject {
            put("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
            putJsonObject("sensors") {
                put("satellite_state", satellitePhase.wireValue)
                put("pipeline_stage", stageName)
                // Emit ISO-8601 UTC for TIMESTAMP device-class compatibility
                // with the HA-side integration (see vaca-barnabee-integration
                // _get_timestamp_from_string). Epoch 0 → "1970-01-01..." which
                // the integration treats as "never" and returns None.
                put("last_wake_at", isoOrEpochZero(lastWakeAtMs))
                put("last_pipeline_success_at", isoOrEpochZero(lastPipelineSuccessAtMs))
                put("last_error_at", isoOrEpochZero(lastErrorAtMs))
            }
        }
        try {
            sendStatus(payload)
        } catch (ex: Exception) {
            log.e("emitStatusSnapshot failed: ${ex.message}")
        }
    }

    private fun isoOrEpochZero(ms: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(ms))

    // Initiate wake word broadcast receiver
    var wakeWordBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (satelliteStatus == SatelliteState.RUNNING) {
                Thread(object : Runnable {
                    override fun run() {
                        when {
                            pcmMediaPlayer.isPlaying -> {
                                sendAudioStop()
                                pcmMediaPlayer.stop()
                                volumeDucking("music", false)
                            }
                            alarmPlayer.isSounding -> {
                                actionAlarm(false)
                            }
                            else -> {
                                if (intent.action == BroadcastSender.WAKE_WORD_DETECTED) {
                                    volumeDucking("all", true)
                                    markWakeNow()
                                    setPhase(SatellitePhase.LISTENING)
                                    sendWakeWordDetection()
                                    sendStartPipeline()
                                }
                            }
                        }
                    }
                }).start()
            }
        }
    }
    val filter = IntentFilter().apply {
        addAction(BroadcastSender.WAKE_WORD_DETECTED)
        addAction(BroadcastSender.STOP_WORD_DETECTED)
    }

    fun run() {
        val connections = config.atomicConnectionCount.incrementAndGet()
        log.d("Client $client_id connected from ${client.inetAddress.hostAddress}. Connections: $connections")
        startIntervalPing()
        while (runClient) {
            try {
                if (reader.available() > 0) {
                    val event: WyomingPacket? = readEvent()
                    if (event != null) {
                        handleEvent(event)
                    }
                }
                if (client.isClosed) {
                    runClient = false
                }
                Thread.sleep(10)
            } catch (ex: Exception) {
                // TODO: Implement exception handling
                log.e("Ending connection $client_id due to client handler exception: $ex")
                runClient = false
            }
        }
        stop()
    }

    @OptIn(ExperimentalAtomicApi::class)
    fun stop() {
        log.d("Stopping client $client_id connection handler")
        stopIntervalPing()

        if (satelliteStatus == SatelliteState.RUNNING) {
            stopSatellite()
        }
        client.close()

        if (config.atomicConnectionCount.get() > 0) {
            config.atomicConnectionCount.andDecrement
        }
        log.w("${client.inetAddress.hostAddress}:$client_id closed the connection.  Connections remaining: ${config.atomicConnectionCount.get()}")
    }

    private fun startSatellite() {

        // Barnabee-fork-local builds use a versionName suffixed with "barnabee"
        // (e.g. "0.10.0-barnabee", "0.10.0-barnabee-dev"). SemVer treats those
        // as pre-releases of the base version, so a strict `<` comparison
        // against HA's `min_required_apk_version` (e.g. "0.10.0") spuriously
        // fails and produces a "needs update" loop at pair-time. Our fork
        // short-circuits the check — we ship independent of upstream's APK
        // update channel anyway (Barnabee has its own signing + update path).
        val isBarnabeeBuild = config.version.contains("barnabee", ignoreCase = true)
        if (!isBarnabeeBuild &&
            config.version.toVersion() < config.minRequiredApkVersion.toVersion()) {
            log.d("App update needed. App is ${config.version}, Min required is ${config.minRequiredApkVersion}")
            BroadcastSender.sendBroadcast(context, BroadcastSender.VERSION_MISMATCH)
            return
        }
        if (isBarnabeeBuild) {
            log.d("Barnabee build ${config.version} — skipping upstream min_required_apk_version gate " +
                    "(HA sent: ${config.minRequiredApkVersion})")
        }

        if (config.pairedDeviceID == "") {
            config.pairedDeviceID = connectionID
        }

        if (config.pairedDeviceID == connectionID) {
            log.d("Starting satellite for ${client.port}")
            LocalBroadcastManager.getInstance(context)
                .registerReceiver(wakeWordBroadcastReceiver, filter)

            // If HA url was blank in config sent from server set it here based on connected IP and port provided
            // in config
            config.homeAssistantConnectedIP = "${client.inetAddress.hostAddress}"

            // Reset status vars
            expectingTTSResponse = false
            lastResponseIsQuestion = false

            if (server.pipelineClient != null) {
                log.d("Satellite taken over by $client_id from ${server.pipelineClient?.client_id}")
                server.pipelineClient = this
                satelliteStatus = SatelliteState.RUNNING
            } else {
                // Ensure alarm is inactive
                actionAlarm(false)

                // Start satellite functions
                server.pipelineClient = this
                satelliteStatus = SatelliteState.RUNNING
                server.satelliteStarted()
                setPhase(SatellitePhase.IDLE)
                log.d("Satellite started for $client_id")
            }
        } else {
            log.i("Invalid connection (${client.inetAddress.hostAddress}:$client_id) attempting to start satellite!")
            log.i("Aborting connection")
            stop()
        }
        config.isRunning = satelliteStatus == SatelliteState.RUNNING
    }

    private fun stopSatellite() {
        log.d("Stopping satellite for $client_id")
        LocalBroadcastManager.getInstance(context).unregisterReceiver(wakeWordBroadcastReceiver)
        if (server.pipelineClient == this) {
            if (pipelineStatus == PipelineStatus.LISTENING) {
                releaseInputAudioStream()
            }

            // Stop media players
            actionAlarm(false)
            musicPlayer.stop()

            pipelineStatus = PipelineStatus.INACTIVE
            satelliteStatus = SatelliteState.STOPPED
            setPhase(SatellitePhase.PAUSED)
            server.pipelineClient = null
            config.homeAssistantConnectedIP = ""
            server.satelliteStopped()
        } else {
            log.e("Closing orphaned satellite connection - $client_id")
        }
        runClient = false
        log.d("Satellite stopped")
        config.isRunning = satelliteStatus == SatelliteState.RUNNING
    }

    private fun requestInputAudioStream() {
        if (pipelineStatus != PipelineStatus.LISTENING) {
            // Entering a new pipeline turn — bump epoch so any stale timeouts
            // or late inbound events from previous turns self-drop.
            val epoch = pipelineEpoch.incrementAndGet()
            log.d("Streaming audio to server for $client_id (epoch $epoch)")
            pipelineStatus = PipelineStatus.LISTENING
            server.requestInputAudioStream()
        }
    }

    private fun releaseInputAudioStream() {
        if (pipelineStatus != PipelineStatus.INACTIVE) {
            log.d("Stopping streaming audio to server for $client_id")
            pipelineStatus = PipelineStatus.INACTIVE
            server.releaseInputAudioStream()
        }
    }

    private fun handleEvent(event: WyomingPacket) {

        if (event.type != "ping" && event.type != "pong" && event.type != "audio-chunk") {
            log.d("Received event - $client_id: ${event.toMap()}")
        }

        // Events not requiring running satellite
        try {
            when (event.type) {
                "ping" -> {
                    sendPong()
                }
                "describe" -> {
                    sendInfo()
                }
                "custom-settings" -> {
                    config.processSettings(event.getProp("settings"))
                }
                "capabilities" -> {
                    sendCapabilities()
                }
                "run-satellite" -> {
                    startSatellite()
                }
                "custom-event" -> {
                    handleCustomEvent(event)
                }
            }

            // Events that must have a running satellite to be processed
            if (satelliteStatus == SatelliteState.RUNNING) {
                when (event.type) {
                    "pause-satellite" -> {
                        stopSatellite()
                    }

                    "transcribe" -> {
                        // Sent when requesting voice command
                        volumeDucking("all", true)
                        setPhase(SatellitePhase.LISTENING)
                        requestInputAudioStream()
                        setPipelineNextStageTimeout(PipelineStage.TRANSCRIBE_TO_VOICE_STARTED)
                    }

                    "voice-started" -> {
                        // Sent when detected voice command started
                        setPhase(SatellitePhase.LISTENING)
                        setPipelineNextStageTimeout(PipelineStage.VOICE_STARTED_TO_STOPPED)
                    }

                    "voice-stopped" -> {
                        // Sent when detected voice command stopped
                        setPhase(SatellitePhase.THINKING)
                        setPipelineNextStageTimeout(PipelineStage.VOICE_STOPPED_TO_TRANSCRIPT)
                    }

                    "transcript" -> {
                        // Sent when STT converted voice command to text
                        releaseInputAudioStream()
                        if (event.getProp("text").lowercase().contains("never mind")) {
                            volumeDucking("all", false)
                            setPhase(SatellitePhase.IDLE)
                        } else {
                            setPhase(SatellitePhase.THINKING)
                            // LLM/conversation engine can legitimately be slow.
                            setPipelineNextStageTimeout(PipelineStage.TRANSCRIPT_TO_SYNTHESIZE)
                        }
                    }

                    "synthesize" -> {
                        // Sent when conversation engine sent response to command
                        lastResponseIsQuestion =
                            (event.getProp("text").replace("\n", "").endsWith("?"))
                        expectingTTSResponse = true
                        setPhase(SatellitePhase.THINKING)
                        setPipelineNextStageTimeout(PipelineStage.SYNTHESIZE_TO_AUDIO_START)
                    }

                    "pipeline-ended" -> {
                        // Sent when pipeline has finished
                        if (!expectingTTSResponse) {
                            cancelPipelineNextStageTimeout()
                            volumeDucking("all", false)
                            // No TTS coming — we're done with this turn.
                            if (satellitePhase != SatellitePhase.TALKING) {
                                setPhase(SatellitePhase.IDLE)
                            }
                        }
                        if (pipelineStatus != PipelineStatus.STREAMING) {
                            releaseInputAudioStream()
                        }
                    }

                    "audio-start" -> {
                        // Sent when audio stream about to start
                        expectingTTSResponse = false  // This is it so reset expecting
                        cancelPipelineNextStageTimeout() // Playing audio, cancel any timeout
                        pipelineStatus = PipelineStatus.STREAMING
                        setPhase(SatellitePhase.TALKING)
                        volumeDucking("all", true)  // Duck here if announcement
                        pcmMediaPlayer.play()
                    }

                    "audio-chunk" -> {
                        // Audio chunk
                        if (pcmMediaPlayer.isPlaying) {
                            pcmMediaPlayer.writeAudio(event.payload)
                        }
                    }

                    "audio-stop" -> {
                        // Sent when all audio chunks sent
                        if (pcmMediaPlayer.isPlaying) {
                            pcmMediaPlayer.stop()
                        }
                        pipelineStatus = PipelineStatus.INACTIVE
                        sendEvent(
                            "played",
                        )

                        // Turn completed successfully — mark the timestamp
                        // before possibly queuing a follow-up turn.
                        markPipelineSuccessNow()

                        if (config.continueConversation || lastResponseIsQuestion) {
                            // Re-arm for the follow-up turn. Upstream bug: no timeout was
                            // armed here, so if HA never replied to the new pipeline we
                            // hung in LISTENING forever. That was the "stuck listening"
                            // pain point; fix per docs/state-machine.md §3.
                            //
                            // Phase stays THINKING (not LISTENING): the mic isn't open
                            // until HA sends `transcribe`. Per rubber-duck review, we
                            // would otherwise falsely flash "listening" for up to 15s.
                            setPhase(SatellitePhase.THINKING)
                            sendStartPipeline()
                            setPipelineNextStageTimeout(PipelineStage.AUDIO_STOP_TO_NEXT_TURN)
                        } else {
                            // Defensive teardown — no follow-up expected.
                            setPhase(SatellitePhase.IDLE)
                            setPipelineNextStageTimeout(PipelineStage.AUDIO_STOP_TO_IDLE)
                        }

                    }

                    "error" -> {
                        markErrorNow()
                        config.eventBroadcaster.notifyEvent(Event("recognitionError", "", event.getProp("code")))
                        resetPipeline()
                    }

                    "custom-action" -> {
                        handleCustomAction(event)
                    }

                    "timer-finished" -> {
                        actionAlarm(true)
                    }
                }
            }
        } catch (ex: Exception) {
            log.e("Error handling event: $ex")
            ex.printStackTrace()
        }
    }

    private fun setPipelineNextStageTimeout(stage: PipelineStage) {
        cancelPipelineNextStageTimeout()
        val epoch = pipelineEpoch.get()
        currentStage = stage
        currentStageArmedAt = System.currentTimeMillis()
        log.d("Arm stage timeout $stage (${stage.durationMs}ms) at epoch $epoch")
        val runnable = Runnable {
            if (pipelineEpoch.get() == epoch) {
                log.d("Pipeline stage $stage timed out at epoch $epoch (${stage.rationale})")
                handlePipelineTimeout(stage)
            } else {
                log.d("Dropped stale $stage timeout (armed epoch $epoch, current ${pipelineEpoch.get()})")
            }
        }
        handler.postDelayed(runnable, stage.durationMs)

        // Also arm the hard-cap backstop if one isn't already armed this turn.
        if (hardCapRunnable == null) {
            armHardCap(epoch)
        }
    }

    /**
     * Legacy int-second overload kept for any external callers; prefer
     * [setPipelineNextStageTimeout]`(stage)` inside this class. Mapped to
     * a synthetic stage so we still benefit from epoch-based stale dropping.
     */
    private fun setPipelineNextStageTimeout(durationSeconds: Int) {
        val mapped = when (durationSeconds) {
            2  -> PipelineStage.AUDIO_STOP_TO_IDLE
            5  -> PipelineStage.TRANSCRIBE_TO_VOICE_STARTED
            10 -> PipelineStage.SYNTHESIZE_TO_AUDIO_START
            15 -> PipelineStage.VOICE_STOPPED_TO_TRANSCRIPT
            30 -> PipelineStage.VOICE_STARTED_TO_STOPPED
            else -> null
        }
        if (mapped != null) {
            setPipelineNextStageTimeout(mapped)
        } else {
            // Fallback: schedule with raw duration but still epoch-guarded.
            cancelPipelineNextStageTimeout()
            val epoch = pipelineEpoch.get()
            handler.postDelayed({
                if (pipelineEpoch.get() == epoch) {
                    handlePipelineTimeout(null)
                }
            }, durationSeconds * 1000L)
        }
    }

    private fun cancelPipelineNextStageTimeout() {
        try {
            handler.removeCallbacksAndMessages(null)
        } catch (ex: Exception) {}
        currentStage = null
        hardCapRunnable = null
    }

    private fun armHardCap(epoch: Long) {
        val runnable = Runnable {
            if (pipelineEpoch.get() == epoch) {
                log.w("Pipeline hard cap (${PipelineStage.HARD_UPPER_BOUND.durationMs}ms) hit at epoch $epoch — force resetting")
                handlePipelineTimeout(PipelineStage.HARD_UPPER_BOUND)
            }
        }
        hardCapRunnable = runnable
        handler.postDelayed(runnable, PipelineStage.HARD_UPPER_BOUND.durationMs)
    }

    private fun handlePipelineTimeout(stage: PipelineStage?) {
        log.d("Pipeline timed out${stage?.let { " at stage $it" } ?: ""}")
        resetPipeline()
    }

    private fun resetPipeline() {
        // Bump the epoch first so any in-flight callbacks see the new value and bail.
        val newEpoch = pipelineEpoch.incrementAndGet()
        log.d("resetPipeline → epoch $newEpoch")
        expectingTTSResponse = false
        currentStage = null
        hardCapRunnable = null

        volumeDucking("all", false)

        if (pipelineStatus != PipelineStatus.STREAMING) {
            releaseInputAudioStream()
        }
        sendAudioStop()

        // Sticky ERROR: if we got here because of an `error` event we just
        // recorded (markErrorNow), leave the phase as ERROR so the dashboard
        // actually observes it. The next wake / transcribe / startSatellite
        // will clear it. Otherwise go to IDLE.
        if (satellitePhase != SatellitePhase.ERROR) {
            setPhase(SatellitePhase.IDLE)
        }
    }

    private fun handleCustomEvent(event: WyomingPacket) {
        when (event.getProp("event_type")) {
            "action" -> {
                handleCustomAction(event)
            }
            "settings" -> {
                config.processSettings(event.getProp("settings"))
            }
            "capabilities" -> {
                sendCapabilities()
            }
        }
    }

    private fun handleCustomAction(event: WyomingPacket) {
        when (event.getProp("action")) {
            "play-media" -> {
                if (event.getProp("payload") != "") {
                    val values = JSONObject(event.getProp("payload"))
                    musicPlayer.play(values.getString("url"))
                    musicPlayer.setVolume(values.getInt("volume"))
                }
            }

            "play" -> {
                musicPlayer.resume()
            }

            "pause" -> {
                musicPlayer.pause()
            }

            "stop" -> {
                musicPlayer.stop()
            }

            "set-volume" -> {
                if (event.getProp("payload") != "") {
                    val values = JSONObject(event.getProp("payload"))
                    musicPlayer.setVolume(values.getInt("volume"))
                }
            }

            "toast-message" -> {
                if (event.getProp("payload") != "") {
                    try {
                        val values = JSONObject(event.getProp("payload"))
                        BroadcastSender.sendBroadcast(
                            context,
                            BroadcastSender.TOAST_MESSAGE,
                            values.getString("message")
                        )
                    } catch (ex: Exception) {
                        log.e("Error sending toast message: $ex")
                    }
                }
            }

            "refresh" -> {
                config.eventBroadcaster.notifyEvent(Event("refresh", "", ""))
            }

            "screen-wake" -> {
                config.eventBroadcaster.notifyEvent(Event("screenWake", "", ""))
            }

            "screen-sleep" -> {
                config.eventBroadcaster.notifyEvent(Event("screenSleep", "", ""))
            }
            "wake" -> {
                config.eventBroadcaster.notifyEvent(Event("wakeWordTrigger", "", ""))
            }
            "alarm" -> {
                if (event.getProp("payload") != "") {
                    val values = JSONObject(event.getProp("payload"))
                    val active = try {
                        values.getBoolean("activate")
                    } catch (ex: JSONException) {
                        false
                    }
                    val url = try {
                        values.getString("url")
                    } catch (ex: JSONException) {
                        ""
                    }
                    if (active) {
                        actionAlarm(true, url)
                    } else {
                        actionAlarm(false)
                    }
                }
            }
        }
    }

    private fun volumeDucking(type: String, active: Boolean) {
        if (active) {
            if (type == "alarm") {
                alarmPlayer.duckVolume()
            } else if (type == "music") {
                musicPlayer.duckVolume()
            } else {
                alarmPlayer.duckVolume()
                musicPlayer.duckVolume()
            }
        } else {
            if (type == "alarm") {
                alarmPlayer.unDuckVolume()
            } else if (type == "music") {
                musicPlayer.unDuckVolume()
            } else {
                alarmPlayer.unDuckVolume()
                if (!alarmPlayer.isSounding) {
                    musicPlayer.unDuckVolume()
                }
            }

        }
    }

    private fun actionAlarm(enable: Boolean, url: String = "") {
        if (enable) {
            volumeDucking("music", true)
            alarmPlayer.startAlarm(url)
            config.eventBroadcaster.notifyEvent(Event("screenWake", "", ""))
        } else {
            alarmPlayer.stopAlarm()
            volumeDucking("music", false)
        }
        sendSettingChange("alarm", enable)
    }

    private fun startIntervalPing() {
        pingTimer.schedule(object: TimerTask() {
            override fun run() {
                sendEvent(
                    "ping",
                    buildJsonObject {
                        put("text", "")
                    }
                )
                // Heartbeat: re-emit the status snapshot every ~14s (7 ticks
                // * 2s) so HA sees a fresh timestamp even if no state has
                // changed. Cheap compared to ping itself.
                pingTickCounter++
                if (pingTickCounter % 7 == 0) {
                    synchronized(phaseLock) {
                        emitStatusSnapshotLocked()
                    }
                }
            }
        },0,2000)
    }

    private fun stopIntervalPing() {
        pingTimer.cancel()
    }

    fun sendPong() {
        sendEvent(
            "pong",
            buildJsonObject {
                put("text", "")
            }
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun sendInfo() {
        val owwWakeWords = WakeWords(context).getWakeWords()
        val mwwWakeWords = listOf("alexa","hey_home_assistant","hey_jarvis","hey_luna","hey_mycroft","okay_computer","okay_nabu")
        sendEvent(
            "info",
            buildJsonObject {
                put("version", config.version)
                putJsonArray("asr") {}
                putJsonArray("tts") {}
                putJsonArray("handle") {}
                putJsonArray("intent") {}
                putJsonArray("wake") {
                    add(
                        buildJsonObject {
                            put("name", "available_wake_words")
                            putJsonObject("attribution") {
                                put("name", "")
                                put("url", "")
                            }
                            put("installed", true)
                            putJsonArray("models") {
                                addAll(owwWakeWords.map {
                                    buildJsonObject {
                                        put("name", it.key)
                                        putJsonObject("attribution") {
                                            put("name", "openwakeword")
                                            put("url", "")
                                        }
                                        put("installed", true)
                                        putJsonArray("languages") {
                                            addAll(listOf("en"))
                                        }
                                        put("phrase", it.value.name)
                                    }
                                })
                                addAll(mwwWakeWords.map {
                                    buildJsonObject {
                                        put("name", it)
                                        putJsonObject("attribution") {
                                            put("name", "microwakeword")
                                            put("url", "")
                                        }
                                        put("installed", true)
                                        putJsonArray("languages") {
                                            addAll(listOf("en"))
                                        }
                                        put("phrase", it.replace("_", " "))
                                    }
                                })
                            }
                        }
                    )
                }
                putJsonArray("stt") {}

                putJsonObject("satellite") {
                    put("name", "VACA ${config.uuid}")
                    putJsonObject("attribution") {
                        put("name", "")
                        put("url", "")
                    }
                    put("installed", true)
                    put("description", "View Assist Companion App")
                    put("version", config.version)
                    put("area", "")
                    put("has_vad", false)
                    putJsonObject("snd_format") {
                        put("channels", 1)
                        put("rate", 16000)
                        put("width", 2)
                    }
                    putJsonArray("active_wake_words") {
                        addAll(listOf(config.wakeWord))
                    }
                    put("max_active_wake_words", 1)
                }
            }
        )
    }

    fun sendWakeWordDetection() {
        //status.pipelineStatus = PipelineStatus.LISTENING
        sendEvent(
            "detection",
            buildJsonObject {
                put("name", config.wakeWord)
                put("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
                put("speaker", "")
            }
        )
    }

    fun sendStartPipeline() {
        sendEvent(
            "run-pipeline",
            buildJsonObject {
                put("name", "VACA ${config.uuid}")
                put("start_stage", "asr")
                put("end_stage", "tts")
                put("restart_on_end", false)
                putJsonObject("snd_format") {
                    put("rate", config.sampleRate)
                    put("width", config.audioWidth)
                    put("channels", config.audioChannels)
                }
            }
        )
        lastResponseIsQuestion = false
    }

    fun sendAudioStop() {
        sendEvent(
            "audio-stop",
            buildJsonObject {
                put("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
            }
        )
    }

    fun sendAudio(audio: ByteArray) {
        val data = buildJsonObject {
            put("rate", config.sampleRate)
            put("width", config.audioWidth)
            put("channels", config.audioChannels)
        }
        val event = WyomingPacket(JSONObject(mapOf("type" to "audio-chunk", "data" to JSONObject(data.toString()))))
        event.payload = audio

        try {
            writeEvent(event)
        } catch (ex: Exception) {
            log.e("Error sending audio event: $ex")
        }
    }

    fun sendSettingChange(name: String, value: String) {
        sendCustomEvent(
            "settings",
            buildJsonObject {
            put("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
            putJsonObject("settings") {
                put(name, value)
            }
        })
    }

    fun sendSettingChange(name: String, value: Boolean) {
        sendCustomEvent(
            "settings",
            buildJsonObject {
                put("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
                putJsonObject("settings") {
                    put(name, value)
                }
            })
    }

    fun sendSettingChange(name: String, value: Int) {
        sendCustomEvent(
            "settings",
            buildJsonObject {
                put("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
                putJsonObject("settings") {
                    put(name, value)
                }
            })
    }


    fun sendStatus(data: JsonObject) {
        sendCustomEvent(
            "status",
            data
        )
    }

    fun sendCapabilities() {
        val data = DeviceCapabilitiesManager.toJson(server.deviceInfo).toMap()
        sendCustomEvent("capabilities", buildJsonObject {
            for (key in data.keys) {
                put(key, data[key] as JsonElement)
            }
        })
    }

    fun sendCustomEvent(type: String, data: JsonObject) {
        val customEventData = buildJsonObject {
            put("event_type", type)
            put("data", data)
        }
        sendEvent("custom-event", customEventData)
    }

    fun sendEvent(type: String, data: JsonObject = buildJsonObject {  }) {
        try {
            val event = WyomingPacket(JSONObject(mapOf("type" to type, "data" to JSONObject(data.toString()))))
            writeEvent(event)
        } catch (ex: Exception) {
            log.e("Error sending event: $type - $ex")
        }
    }

    private fun readEvent(): WyomingPacket? {
        try {
            val jsonString = StringBuilder()
            var jsonLine = reader.read()
            while (jsonLine != '\n'.code) {
                jsonString.append(jsonLine.toChar())
                jsonLine = reader.read()
            }
            if (jsonString.isEmpty()) {
                return null
            }

            val eventDict = JSONObject(jsonString.toString())

            if (!eventDict.has("type")) {
                return null
            }
            // In wyoming 1.7.1 data can be part of main message
            if (!eventDict.has("data")) {
                var dataLength = 0
                if (eventDict.has("data_length")) {
                    dataLength = eventDict.getInt("data_length")
                }
                // Read data
                if (dataLength != 0) {
                    val dataBytes = ByteArray(dataLength)
                    var i = 0
                    while (reader.available() < dataLength && i < 100) {
                        Thread.sleep(10)
                        i++
                    }
                    reader.read(dataBytes, 0, dataLength)
                    eventDict.put("data", JSONObject(String(dataBytes)))
                } else {
                    eventDict.put("data", JSONObject())
                }
            }

            val wyomingPacket = WyomingPacket(eventDict)

            // Read payload
            var payloadLength: Int = 0
            if (eventDict.has("payload_length")) {
                payloadLength = eventDict.getInt("payload_length")
            }

            if (payloadLength != 0) {
                val payloadBytes = ByteArray(payloadLength)
                var i = 0
                while (reader.available() < payloadLength && i < 100) {
                    log.w("Payload not fully received")
                    Thread.sleep(10)
                    i++
                }
                reader.read(payloadBytes, 0, payloadLength)
                wyomingPacket.payload = payloadBytes
            }
            return wyomingPacket

        } catch (ex: Exception) {
            log.e("Event read exception ${ex.toString().substring(0, ex.toString().length.coerceAtMost(50))}")
        }
        return null
    }

    private fun writeEvent(p: WyomingPacket) {
        if (p.type != "ping" && p.type != "pong" && p.type != "audio-chunk") {
            log.d("Sending to $client_id: ${p.toMap()}")
        }
        val eventDict: MutableMap<String, Any> = p.toMap()
        eventDict["version"] = config.version

        val dataDict: JSONObject = eventDict["data"] as JSONObject
        eventDict -= "data"

        var dataBytes = ByteArray(0)
        if (dataDict.length() > 0) {
            dataBytes = dataDict.toString().toByteArray(Charset.defaultCharset())
            eventDict["data_length"] = dataBytes.size
        }

        if (p.payload.isNotEmpty()) {
            eventDict["payload_length"] = p.payload.size
        }

        var jsonLine = (eventDict as Map<*, *>?)?.let { JSONObject(it).toString() }
        jsonLine += '\n'

        try {
            writer.write(jsonLine.toByteArray(Charset.defaultCharset()))

            if (dataBytes.isNotEmpty()) {
                writer.write(dataBytes)
            }

            if (p.payload.isNotEmpty()) {
                writer.write(p.payload)
            }
            writer.flush()
        } catch (ex: SocketException) {
            log.e("Error sending event: $ex. Likely just a closed socket and not an error!")
            runClient = false
        } catch (ex: Exception) {
            log.e("Unknown error sending event: $ex")
        }

    }



}