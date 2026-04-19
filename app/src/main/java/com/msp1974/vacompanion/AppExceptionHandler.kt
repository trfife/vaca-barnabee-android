package com.msp1974.vacompanion

/*
 * Copyright (c) 2022 Wallpanel
 * Modifications (c) 2026 Barnabee contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import timber.log.Timber
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class AppExceptionHandler(private val activity: Activity) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, ex: Throwable) {
        Timber.e("AppExceptionHandler: $ex")
        ex.printStackTrace()

        val ctx = activity.applicationContext
        val now = System.currentTimeMillis()

        persistCrash(ctx, thread, ex, now)

        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val history = (prefs.getString(KEY_CRASH_TIMES, "") ?: "")
            .split(",")
            .mapNotNull { it.trim().toLongOrNull() }
            .filter { now - it < WINDOW_MS }
            .toMutableList()
        history.add(now)

        prefs.edit()
            .putString(KEY_CRASH_TIMES, history.joinToString(","))
            .apply()

        if (history.size > MAX_CRASHES_IN_WINDOW) {
            // Crash loop: do NOT relaunch. Persist a sentinel so MainActivity
            // can surface a diagnostics screen on next manual launch.
            Timber.e("Crash loop detected (${history.size} crashes in ${WINDOW_MS / 1000}s). Suppressing relaunch.")
            prefs.edit()
                .putBoolean(KEY_CRASH_LOOP_ACTIVE, true)
                .putLong(KEY_CRASH_LOOP_DETECTED_AT, now)
                .apply()
            activity.finish()
            android.os.Process.killProcess(android.os.Process.myPid())
            return
        }

        val intent = Intent(activity, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                or Intent.FLAG_ACTIVITY_CLEAR_TASK
                or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingIntent = PendingIntent.getActivity(
            activity.applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val mgr = activity.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        mgr[AlarmManager.RTC, now + 1000] = pendingIntent
        activity.finish()
    }

    private fun persistCrash(ctx: Context, thread: Thread, ex: Throwable, now: Long) {
        try {
            val dir = File(ctx.filesDir, "crashes").apply { mkdirs() }
            // Rolling: keep last MAX_CRASH_FILES files
            val existing = dir.listFiles()?.sortedBy { it.lastModified() } ?: emptyList()
            if (existing.size >= MAX_CRASH_FILES) {
                existing.take(existing.size - MAX_CRASH_FILES + 1).forEach { it.delete() }
            }
            val sw = StringWriter()
            PrintWriter(sw).use { pw ->
                pw.println("timestamp_ms=$now")
                pw.println("thread=${thread.name}")
                pw.println("app_version=${getAppVersion(ctx)}")
                pw.println("---")
                ex.printStackTrace(pw)
            }
            File(dir, "crash-$now.txt").writeText(sw.toString())
        } catch (t: Throwable) {
            Timber.w(t, "Failed to persist crash")
        }
    }

    private fun getAppVersion(ctx: Context): String = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (t: Throwable) { "?" }

    companion object {
        private const val PREFS = "barnabee_crash_prefs"
        private const val KEY_CRASH_TIMES = "crash_times"
        private const val KEY_CRASH_LOOP_ACTIVE = "crash_loop_active"
        private const val KEY_CRASH_LOOP_DETECTED_AT = "crash_loop_detected_at"
        private const val WINDOW_MS = 5 * 60 * 1000L
        private const val MAX_CRASHES_IN_WINDOW = 3
        private const val MAX_CRASH_FILES = 20

        /** Returns true if a crash loop was detected and relaunch was suppressed. */
        fun isCrashLoopActive(ctx: Context): Boolean {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_CRASH_LOOP_ACTIVE, false)
        }

        /** Clears the crash loop sentinel (call after user acknowledges). */
        fun clearCrashLoop(ctx: Context) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_CRASH_LOOP_ACTIVE)
                .remove(KEY_CRASH_LOOP_DETECTED_AT)
                .remove(KEY_CRASH_TIMES)
                .apply()
        }

        /** Timestamp (ms) when loop was detected, or 0 if none. */
        fun crashLoopDetectedAt(ctx: Context): Long {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_CRASH_LOOP_DETECTED_AT, 0L)
        }

        /** Returns persisted crash files (most recent first). */
        fun listPersistedCrashes(ctx: Context): List<File> {
            val dir = File(ctx.filesDir, "crashes")
            return (dir.listFiles()?.toList() ?: emptyList())
                .sortedByDescending { it.lastModified() }
        }
    }
}