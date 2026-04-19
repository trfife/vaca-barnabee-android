package com.msp1974.vacompanion.utils

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.core.os.bundleOf

/**
 * Barnabee: Firebase/Crashlytics has been stripped from this fork. This file
 * preserves the public surface of [FirebaseManager] so upstream call sites
 * keep working without changes, while routing everything to local-only sinks:
 * - Analytics events → dropped (logged at VERBOSE in debug)
 * - Crash logs / exceptions → persisted via AppExceptionHandler (files/crashes/)
 *   and will be forwarded to HA via P4.5-h crash-report channel.
 */
class Logger {
    companion object {
        const val TAG = "Barnabee"
    }
    fun d(message: String) {
        Log.d(TAG, message)
    }
    fun e(message: String) {
        Log.e(TAG, message)
    }
    fun i(message: String) {
        Log.i(TAG, message)
    }
    fun w(message: String) {
        Log.w(TAG, message)
    }
}

class FirebaseManager private constructor(@Suppress("UNUSED_PARAMETER") context: Context? = null) {

    companion object {
        @Volatile
        private var instance: FirebaseManager? = null

        fun getInstance(context: Context? = null): FirebaseManager {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: FirebaseManager(context).also { instance = it }
            }
        }

        const val DIAGNOSTIC_POPUP_SHOWN = "diagnostic_popup_shown"
        const val WAKE_WORD_DETECTED = "wake_word_detected"
        const val SATELLITE_ALREADY_RUNNING_MAIN = "satellite_already_running_main"
        const val RENDER_PROCESS_KILLED = "render_process_killed"
        const val RENDER_PROCESS_CRASHED = "render_process_crashed"
        const val MAIN_ACTIVITY_BACKGROUND_TASK_ALREADY_RUNNING = "main_background_task_already_running"
        const val TRIM_MEMORY_UI_HIDDEN = "trim_memory_ui_hidden"
        const val TRIM_MEMORY_BACKGROUND = "trim_memory_background"
        const val LOST_NETWORK = "lost_network"
    }

    fun Map<String, Any?>.toBundle(): Bundle = bundleOf(*this.toList().toTypedArray())

    fun setCustomKeys(keys: Map<String, Any>) {
        if (BuildConfigHelper.DEBUG) {
            Log.v(Logger.TAG, "analytics.setCustomKeys: $keys")
        }
    }

    fun logEvent(event: String, params: Map<String, String>) {
        if (BuildConfigHelper.DEBUG) {
            Log.v(Logger.TAG, "analytics.logEvent: $event $params")
        }
    }

    fun setUserProperty(key: String, value: String) {
        if (BuildConfigHelper.DEBUG) {
            Log.v(Logger.TAG, "analytics.setUserProperty: $key=$value")
        }
    }

    fun addToCrashLog(message: String) {
        // Breadcrumb -> Logcat only. Full crash persistence happens in
        // AppExceptionHandler.persistCrash() when an uncaught throws.
        Log.i(Logger.TAG, "crash-breadcrumb: $message")
    }

    fun logException(exception: Exception) {
        Log.e(Logger.TAG, "logException (non-fatal)", exception)
    }
}

private object BuildConfigHelper {
    // Keep independent of generated BuildConfig so this file has no compile-order dep.
    val DEBUG: Boolean = try {
        Class.forName("com.msp1974.vacompanion.BuildConfig")
            .getField("DEBUG")
            .getBoolean(null)
    } catch (_: Throwable) { false }
}
