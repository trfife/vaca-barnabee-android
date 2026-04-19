package com.msp1974.vacompanion.wyoming

import android.os.SystemClock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Global mic-level observer. Wake-word engines call [onFrame] with each
 * captured PCM-16 frame. We accumulate peak+RMS across frames and emit a
 * Wyoming `mic-level` custom-event at most every [MIN_INTERVAL_MS] to bound
 * traffic.
 *
 * Silently drops frames when no [ClientHandler] is registered or the sensor
 * is disabled.
 */
object MicLevelMonitor {
    private const val MIN_INTERVAL_MS = 500L

    @Volatile private var sink: ClientHandler? = null
    @Volatile private var enabled: Boolean = true

    private var lastEmitMs: Long = 0L
    private var peakWindow: Int = 0
    private var sumSquaresWindow: Double = 0.0
    private var sampleCountWindow: Long = 0L

    fun register(handler: ClientHandler) {
        sink = handler
        reset()
    }

    fun unregister(handler: ClientHandler) {
        if (sink === handler) {
            sink = null
        }
        reset()
    }

    fun setEnabled(v: Boolean) {
        enabled = v
    }

    @Synchronized
    fun onFrame(frame: ByteArray) {
        if (!enabled) return
        val target = sink ?: return
        if (frame.size < 2) return

        var peak = peakWindow
        var ss = sumSquaresWindow
        var n = sampleCountWindow
        var i = 0
        while (i + 1 < frame.size) {
            val lo = frame[i].toInt() and 0xFF
            val hi = frame[i + 1].toInt()
            val s = (hi shl 8) or lo
            val a = if (s == Short.MIN_VALUE.toInt()) 32767 else abs(s)
            if (a > peak) peak = a
            val v = s.toDouble()
            ss += v * v
            n++
            i += 2
        }
        peakWindow = peak
        sumSquaresWindow = ss
        sampleCountWindow = n
        maybeEmit()
    }

    @Synchronized
    fun onFrame(frame: ShortArray) {
        if (!enabled) return
        val target = sink ?: return
        if (frame.isEmpty()) return

        var peak = peakWindow
        var ss = sumSquaresWindow
        var n = sampleCountWindow
        for (s in frame) {
            val a = if (s.toInt() == Short.MIN_VALUE.toInt()) 32767 else abs(s.toInt())
            if (a > peak) peak = a
            val v = s.toDouble()
            ss += v * v
            n++
        }
        peakWindow = peak
        sumSquaresWindow = ss
        sampleCountWindow = n
        maybeEmit()
    }

    @Synchronized
    fun onFrame(frame: FloatArray) {
        if (!enabled) return
        val target = sink ?: return
        if (frame.isEmpty()) return

        var peak = peakWindow
        var ss = sumSquaresWindow
        var n = sampleCountWindow
        for (f in frame) {
            // Normalised float [-1,1] → short-scale (32768) for consistent units.
            val s = (f * 32768.0f).toInt().coerceIn(-32768, 32767)
            val a = abs(s)
            if (a > peak) peak = a
            val v = s.toDouble()
            ss += v * v
            n++
        }
        peakWindow = peak
        sumSquaresWindow = ss
        sampleCountWindow = n
        maybeEmit()
    }

    private fun maybeEmit() {
        val target = sink ?: return
        val peak = peakWindow
        val ss = sumSquaresWindow
        val n = sampleCountWindow

        val now = SystemClock.elapsedRealtime()
        if (now - lastEmitMs < MIN_INTERVAL_MS) return

        val rms = if (n > 0) sqrt(ss / n.toDouble()) else 0.0
        val peakDbfs = if (peak > 0) 20.0 * log10(peak.toDouble() / 32768.0) else -120.0
        val rmsDbfs = if (rms > 0) 20.0 * log10(rms / 32768.0) else -120.0

        try {
            target.sendCustomEvent(
                "mic-level",
                buildJsonObject {
                    put("peak", peak)
                    put("rms", rms.toInt())
                    put("peak_dbfs", (peakDbfs * 10).toInt() / 10.0)
                    put("rms_dbfs", (rmsDbfs * 10).toInt() / 10.0)
                    put("samples", n)
                },
            )
        } catch (ex: Exception) {
            Timber.w("MicLevelMonitor emit failed: $ex")
        }
        lastEmitMs = now
        reset()
    }

    private fun reset() {
        peakWindow = 0
        sumSquaresWindow = 0.0
        sampleCountWindow = 0L
    }
}
