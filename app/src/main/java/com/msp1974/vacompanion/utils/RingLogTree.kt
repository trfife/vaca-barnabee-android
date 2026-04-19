package com.msp1974.vacompanion.utils

import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.zip.GZIPOutputStream
import java.io.ByteArrayOutputStream
import android.util.Base64

/**
 * In-memory ring buffer Timber tree. Keeps the last [capacity] log lines
 * so they can be pulled on-demand via the `get-logs` custom-action — no
 * need to adb into the device for post-mortem.
 */
class RingLogTree(private val capacity: Int = 2000) : Timber.Tree() {

    private val buffer = ArrayBlockingQueue<String>(capacity)
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val pri = when (priority) {
            2 -> "V"; 3 -> "D"; 4 -> "I"; 5 -> "W"; 6 -> "E"; 7 -> "A"; else -> "?"
        }
        val line = "${fmt.format(Date())} $pri/${tag ?: "-"}: $message"
        // drop oldest when full
        while (!buffer.offer(line)) {
            buffer.poll()
        }
        if (t != null) {
            val sw = java.io.StringWriter()
            t.printStackTrace(java.io.PrintWriter(sw))
            val trace = sw.toString().lineSequence().take(40).joinToString("\n")
            while (!buffer.offer(trace)) {
                buffer.poll()
            }
        }
    }

    /** Returns the full buffer as a single string (oldest first). */
    fun snapshot(): String = buffer.toTypedArray().joinToString("\n")

    /** Returns the buffer gzipped + base64-encoded (standard base64, no wrap). */
    fun snapshotGzipBase64(): String {
        val raw = snapshot().toByteArray(Charsets.UTF_8)
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(raw) }
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    fun size(): Int = buffer.size

    companion object {
        @Volatile
        var instance: RingLogTree? = null
            private set

        fun plant(capacity: Int = 2000): RingLogTree {
            val t = RingLogTree(capacity)
            Timber.plant(t)
            instance = t
            return t
        }
    }
}
