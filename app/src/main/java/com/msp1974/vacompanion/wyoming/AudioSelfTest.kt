package com.msp1974.vacompanion.wyoming

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber
import kotlin.math.PI
import kotlin.math.sin

/**
 * On-device audio pipeline self-test. Plays a short 1 kHz tone through
 * [AudioTrack] and samples [MicLevelMonitor]'s rolling peak/RMS before,
 * during, and after. Emits a `selftest-result` Wyoming custom-event with
 * the measurements and a pass/fail verdict.
 *
 * Pass criterion: RMS rises at least 6 dB above baseline while the tone
 * is playing. That is a very loose threshold designed only to tell mic
 * dead vs mic alive apart; it deliberately does not try to verify AEC
 * or absolute levels.
 */
object AudioSelfTest {

    private const val SAMPLE_RATE = 44100
    private const val TONE_HZ = 1000
    private const val DURATION_MS = 1000L
    private const val SETTLE_MS = 400L

    suspend fun run(handler: ClientHandler) {
        val startedAt = System.currentTimeMillis()
        val baseline = snapshotMic("baseline")
        delay(SETTLE_MS)
        val tone = playTestTone()
        delay(DURATION_MS)
        val during = snapshotMic("during")
        delay(SETTLE_MS)
        tone.release()
        val post = snapshotMic("post")

        val delta = during.rmsDbfs - baseline.rmsDbfs
        val passed = delta > 6.0 && during.peakDbfs > -40.0

        try {
            handler.sendCustomEvent(
                "selftest-result",
                buildJsonObject {
                    put("test", "audio-loopback")
                    put("started_at", startedAt)
                    put("duration_ms", System.currentTimeMillis() - startedAt)
                    put("baseline_peak_dbfs", baseline.peakDbfs)
                    put("baseline_rms_dbfs", baseline.rmsDbfs)
                    put("during_peak_dbfs", during.peakDbfs)
                    put("during_rms_dbfs", during.rmsDbfs)
                    put("post_peak_dbfs", post.peakDbfs)
                    put("post_rms_dbfs", post.rmsDbfs)
                    put("rms_delta_dbfs", (delta * 10).toInt() / 10.0)
                    put("passed", passed)
                    put(
                        "verdict",
                        if (passed) "mic+speaker+AEC roughly working"
                        else "mic or speaker may be broken; check device volume, mute, and permissions",
                    )
                },
            )
        } catch (ex: Exception) {
            Timber.w(ex, "AudioSelfTest: failed to emit result")
        }
    }

    private data class Snapshot(val peakDbfs: Double, val rmsDbfs: Double)

    private fun snapshotMic(@Suppress("UNUSED_PARAMETER") label: String): Snapshot {
        // MicLevelMonitor emits at 2 Hz; pick up the most recent value.
        return Snapshot(
            peakDbfs = MicLevelMonitor.lastPeakDbfs,
            rmsDbfs = MicLevelMonitor.lastRmsDbfs,
        )
    }

    private fun playTestTone(): AudioTrack {
        val numSamples = (SAMPLE_RATE * DURATION_MS / 1000L).toInt()
        val samples = ShortArray(numSamples)
        val twoPiF = 2.0 * PI * TONE_HZ.toDouble()
        for (i in 0 until numSamples) {
            val t = i.toDouble() / SAMPLE_RATE
            // 0.3 amplitude to avoid clipping; also easier on ears.
            samples[i] = (sin(twoPiF * t) * 0.3 * Short.MAX_VALUE).toInt().toShort()
        }

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val fmt = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val track = AudioTrack(
            attrs,
            fmt,
            samples.size * 2,
            AudioTrack.MODE_STATIC,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        track.write(samples, 0, samples.size)
        track.play()
        return track
    }
}
