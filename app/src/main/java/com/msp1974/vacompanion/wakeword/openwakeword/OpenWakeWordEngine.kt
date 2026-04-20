package com.msp1974.vacompanion.wakeword.openwakeword

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.AssetManager
import androidx.annotation.RequiresPermission
import com.google.protobuf.ByteString
import com.msp1974.vacompanion.audio.AudioDSP
import com.msp1974.vacompanion.audio.MicrophoneInput
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.wakeword.WakeWordEngineProvider
import com.msp1974.vacompanion.wakeword.microwakeword.microwakeword.MicroWakeWord
import com.msp1974.vacompanion.wakeword.microwakeword.microwakeword.MicroWakeWordDetector
import com.msp1974.vacompanion.wakeword.microwakeword.providers.AssetWakeWordProvider
import com.msp1974.vacompanion.wakeword.openwakeword.audio.AudioProcessor
import com.msp1974.vacompanion.wakeword.openwakeword.ml.OnnxModelRunner
import com.msp1974.vacompanion.wakeword.openwakeword.model.WakeWordModel
import com.msp1974.vacompanion.wakeword.openwakeword.model.WakeWordScore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs


/**
 * Main entry point for wake word detection using ONNX Runtime.
 *
 * This class manages multiple wake word models and emits detection events through a Kotlin Flow.
 * It provides real-time audio processing with configurable detection modes and cooldown periods.
 */
class OpenWakeWordEngine(
    private val context: Context,
    private val models: List<WakeWordModel>,
    private val detectionCooldownMs: Long = 2000L,
    muted: Boolean = false
): WakeWordEngineProvider() {

    private val config = APPConfig.getInstance(context)
    private val assetManager: AssetManager = context.assets
    private val modelProcessors = mutableMapOf<WakeWordModel, ModelProcessor>()
    private val detectionCooldowns = mutableMapOf<String, Long>()

    var isEnabled = true

    private var _audioProcessor: AudioProcessor = AudioProcessor(assetManager)
    private val slidingWindowSize = 3
    private val probabilities = ArrayDeque<Float>(slidingWindowSize)

    /**
     * Flow of wake word detection events.
     *
     * This Flow emits [WakeWordDetection] objects whenever a wake word is detected.
     * The Flow is hot and shared, meaning multiple collectors will receive the same events.
     *
     * ## Example: Basic Collection
     * ```kotlin
     * engine.detections.collect { detection ->
     *     showToast("${detection.model.name} detected!")
     * }
     * ```
     *
     * ## Example: Filtering High-Confidence Detections
     * ```kotlin
     * engine.detections
     *     .filter { it.score > 0.8f }
     *     .collect { detection ->
     *         // Only process high-confidence detections
     *     }
     * ```
     *
     * ## Example: Debouncing Rapid Detections
     * ```kotlin
     * engine.detections
     *     .debounce(500) // Additional debounce on top of cooldown
     *     .collect { detection ->
     *         // Process debounced detections
     *     }
     * ```
     */

    /**
     * Flow of real-time wake word scores.
     *
     * This Flow emits [WakeWordScore] objects continuously for all models,
     * regardless of whether they exceed the detection threshold.
     * Useful for real-time monitoring and visualization.
     */

    init {
        require(models.isNotEmpty()) { "At least one wake word model must be provided" }
        initializeModels()
    }

    private fun initializeModels() {
        models.forEach { model ->
            val processor = ModelProcessor(assetManager, model)
            modelProcessors[model] = processor
        }
    }

    fun addModel(model: WakeWordModel) {
        /**
        Add model to detections
         */
        Timber.w("Adding model ${model.name} to engine")
        modelProcessors.forEach {(wakeWordModel, processor) ->
            if (wakeWordModel.name == model.name) {
                throw IllegalArgumentException("Model with name ${model.name} already exists")
            }
        }
        modelProcessors[model] = ModelProcessor(assetManager, model)
    }

    fun removeModel(modelName: String) {
        /**
        Remove model from detections
         */
        Timber.w("Removing model $modelName from engine")
        modelProcessors.forEach {(wakeWordModel, processor) ->
            if (wakeWordModel.name == modelName) {
                processor.close()
                modelProcessors.remove(wakeWordModel)
                return
            }
        }
        throw IllegalArgumentException("Model with name $modelName not found")
    }

    private val _muted = MutableStateFlow(muted)
    val muted = _muted.asStateFlow()
    override fun setMuted(value: Boolean) {
        _muted.value = value
    }

    override fun isMuted(): Boolean {
        return _muted.value
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun start() = muted.flatMapLatest {
        if (it) emptyFlow()
        else flow {
            val microphoneInput = MicrophoneInput(frameSize = 1280)

            // --- Pre-wake audio ring buffer -----------------------------------
            // Keeps the last ~1.5s of PCM audio so that when a wake word fires
            // and HA requests streaming, we can flush the buffered frames
            // *before* switching to live audio. This lets HA's VAD see the
            // user's command immediately instead of waiting for them to speak
            // again, shaving ~1s off every turn.
            //
            // 16kHz × 2 bytes × 1.5s = 48000 bytes. Each frame is 1280 samples
            // × 2 bytes = 2560 bytes, so ~18–19 frames fit in 1.5s.
            val PRE_WAKE_BUFFER_FRAMES = 19
            val preWakeBuffer = ArrayDeque<ByteArray>(PRE_WAKE_BUFFER_FRAMES + 1)
            var wasStreaming = false

            // Load stop-word models from assets/stopWords/ (MicroWakeWord TFLite)
            // so "stop" can interrupt a pipeline even when using OpenWakeWord for wake.
            val stopDetector = try {
                val stopWords = AssetWakeWordProvider(assetManager, "stopWords").get()
                if (stopWords.isNotEmpty()) {
                    val micros = stopWords.mapNotNull { ww ->
                        runCatching { MicroWakeWord.fromWakeWord(ww) }
                            .onFailure { Timber.w(it, "Failed to load stop-word model: ${ww.id}") }
                            .getOrNull()
                    }
                    if (micros.isNotEmpty()) {
                        Timber.i("Stop-word detector loaded with ${micros.size} model(s): ${micros.joinToString { it.wakeWord }}")
                        MicroWakeWordDetector(micros)
                    } else null
                } else null
            } catch (ex: Exception) {
                Timber.w(ex, "Could not create stop-word detector")
                null
            }
            try {
                microphoneInput.start()
                emit(AudioResult.EngineStatus("Started"))
                while (true) {
                    val audio = microphoneInput.readFloat()

                    if (audio.isNotEmpty()) {

                        if (config.diagnosticsEnabled) {
                            com.msp1974.vacompanion.wyoming.MicLevelMonitor.onFrame(audio)
                            emit(AudioResult.AudioLevel(AudioDSP().audioLevel(audio)))
                        }

                        // Convert once — reused for streaming, buffer, and stop-word.
                        val pcmBytes = AudioDSP().floatArrayToByteBuffer(audio)

                        if (isStreaming) {
                            // Flush pre-wake buffer on the first streaming frame.
                            if (!wasStreaming) {
                                wasStreaming = true
                                val buffered = preWakeBuffer.size
                                if (buffered > 0) {
                                    Timber.i("Pre-wake buffer: flushing $buffered frames (~${buffered * 80}ms) of audio")
                                    for (chunk in preWakeBuffer) {
                                        emit(AudioResult.Audio(ByteString.copyFrom(chunk)))
                                    }
                                    preWakeBuffer.clear()
                                }
                            }
                            emit(AudioResult.Audio(ByteString.copyFrom(pcmBytes)))
                        } else {
                            // Clear buffer on the transition out of streaming so
                            // the next turn doesn't replay stale audio.
                            if (wasStreaming) {
                                wasStreaming = false
                                preWakeBuffer.clear()
                            }
                            // Don't buffer while TTS is playing — the mic is
                            // picking up the device's own speaker output, which
                            // would be transcribed as a self-loop on the next turn.
                            if (!com.msp1974.vacompanion.audio.TTSPlaybackGate.isSpeaking()) {
                                if (preWakeBuffer.size >= PRE_WAKE_BUFFER_FRAMES) {
                                    preWakeBuffer.removeFirst()
                                }
                                preWakeBuffer.addLast(pcmBytes.copyOf())
                            }
                        }

                        val detections = processAudio(audio)
                        for (detection in detections) {
                            if (detection.detected) {
                                emit(AudioResult.WakeDetected(detection))
                            }
                        }

                        // Feed the same frame to the stop-word detector (TFLite).
                        if (stopDetector != null) {
                            val buf = ByteBuffer.allocateDirect(pcmBytes.size)
                            buf.order(ByteOrder.LITTLE_ENDIAN)
                            buf.put(pcmBytes)
                            buf.rewind()
                            val stopHits = stopDetector.detect(buf)
                            for (hit in stopHits) {
                                if (hit.score > 0.1f) {
                                    emit(AudioResult.StopDetected(hit))
                                }
                            }
                        }
                    }
                    yield()
                }
            } finally {
                microphoneInput.close()
                stopDetector?.close()
                emit(AudioResult.EngineStatus("Stopped"))
            }
        }
    }

    @SuppressLint("DefaultLocale")
    fun processAudio(audioBuffer: FloatArray): List<WakeWordDetection> {
        val detections = mutableListOf<WakeWordDetection>()

        if (isEnabled) {
            val audioFeatures = _audioProcessor.getAudioFeatures(audioBuffer)
            modelProcessors.map { (model, processor) ->
                try {
                    val score = processor.process(audioFeatures)
                    if (score > model.threshold) {
                        Timber.d(
                            "DETECTION! ${model.name} - Score: ${
                                String.format("%.5f", score)
                            } > Threshold: ${String.format("%.5f", model.threshold)}"
                        )
                        detections.add(
                            WakeWordDetection(
                                model.name,
                                model.name,
                                isWakeWordDetected(model, score),
                                score
                            )
                        )
                    }
                } catch (e: Exception) {
                    Timber.e("Error processing model ${model.name} ->$e")
                    com.msp1974.vacompanion.wyoming.ErrorReporter.report(
                        code = "wake.inference",
                        component = "wakeword",
                        severity = "error",
                        message = "OpenWakeWord inference failed for ${model.name}",
                        cause = e,
                        context = mapOf("model" to model.name),
                    )
                }
            }
        }
        return detections
    }

    private fun isWakeWordDetected(model: WakeWordModel, probability: Float): Boolean {
        if (probabilities.size == slidingWindowSize)
            probabilities.removeFirst()
        probabilities.add(probability)

        return probabilities.size == slidingWindowSize && probabilities.average() > model.threshold
    }

    fun enable() {
        isEnabled = true
    }

    fun disable() {
        isEnabled = false
    }

    fun reset() {
        _audioProcessor.reset()
    }

    /**
     * Stops wake word detection.
     *
     * This method stops audio recording and cancels all ongoing detection processing.
     * The engine can be restarted by calling [start] again.
     *
     * ## Example
     * ```kotlin
     * override fun onPause() {
     *     super.onPause()
     *     engine.stop() // Stop detection when app goes to background
     * }
     * ```
     *
     * @see start
     */
    fun stop() {

    }

    /**
     * Releases all resources used by the engine.
     *
     * This method should be called when the engine is no longer needed to free up memory
     * and system resources. After calling this method, the engine cannot be reused.
     *
     * ## Important
     * Always call this method in your Activity/Fragment's onDestroy() to prevent memory leaks.
     *
     * ## Example
     * ```kotlin
     * override fun onDestroy() {
     *     super.onDestroy()
     *     wakeWordEngine?.release()
     * }
     * ```
     *
     * This method will:
     * - Stop any ongoing detection
     * - Release ONNX Runtime sessions
     * - Free audio processing resources
     * - Clear internal caches
     */
    override fun release() {
        stop()
        modelProcessors.values.forEach { it.close() }
        modelProcessors.clear()
    }

    /**
     * Internal class to process audio for a specific model.
     */
    private inner class ModelProcessor(
        assetManager: AssetManager,
        model: WakeWordModel
    ) : AutoCloseable {

        private val modelRunner = OnnxModelRunner(assetManager, model)

        fun process(audioFeatures: Array<Array<FloatArray>>): Float {
            val score = modelRunner.predictWakeWord(audioFeatures)
            return score
        }

        override fun close() {
            modelRunner.close()
        }
    }
}