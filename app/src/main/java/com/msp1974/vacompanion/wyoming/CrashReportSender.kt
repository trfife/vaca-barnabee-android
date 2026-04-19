package com.msp1974.vacompanion.wyoming

import android.content.Context
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * On connect, drain any persisted uncaught-exception crash files from
 * [AppExceptionHandler] and emit each as a Wyoming `crash-report`
 * custom-event. Successfully sent files are renamed with `.sent` so they
 * are not re-sent next connect but remain available for on-device
 * forensics until the rolling limit prunes them.
 */
object CrashReportSender {

    fun drainIfAny(ctx: Context, handler: ClientHandler) {
        try {
            val dir = File(ctx.filesDir, "crashes")
            if (!dir.isDirectory) return
            val files = dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") && f.name.endsWith(".txt") }
                ?: return
            if (files.isEmpty()) return
            Timber.i("CrashReportSender: draining ${files.size} persisted crash report(s)")
            for (f in files.sortedBy { it.lastModified() }) {
                try {
                    val text = f.readText()
                    // Truncate to bound Wyoming payload size.
                    val capped = if (text.length > 8000) text.substring(0, 8000) + "...[truncated]" else text
                    val payload = buildJsonObject {
                        put("filename", f.name)
                        put("size", f.length())
                        put("mtime", f.lastModified())
                        put("ts", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
                        put("content", capped)
                    }
                    handler.sendCustomEvent("crash-report", payload)
                    val sent = File(f.parentFile, f.name + ".sent")
                    if (!f.renameTo(sent)) {
                        // Fallback: delete if rename fails to avoid resending.
                        f.delete()
                    }
                } catch (ex: Exception) {
                    Timber.w(ex, "CrashReportSender: failed to send ${f.name}")
                }
            }
        } catch (ex: Exception) {
            Timber.w(ex, "CrashReportSender.drainIfAny failed")
        }
    }
}
