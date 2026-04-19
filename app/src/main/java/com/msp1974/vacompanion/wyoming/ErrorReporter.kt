package com.msp1974.vacompanion.wyoming

/**
 * Process-global router for structured error events.
 *
 * Any code in the app (services, audio, wakeword) can call [report] to emit
 * a Wyoming `error-event` custom-event; the call is silently dropped when no
 * Wyoming client is currently connected. ClientHandler registers itself on
 * connect and clears on disconnect.
 */
object ErrorReporter {

    @Volatile
    private var sink: ClientHandler? = null

    fun register(handler: ClientHandler) {
        sink = handler
    }

    fun unregister(handler: ClientHandler) {
        if (sink === handler) sink = null
    }

    fun report(
        code: String,
        component: String,
        severity: String,
        message: String,
        cause: Throwable? = null,
        context: Map<String, String>? = null,
    ) {
        sink?.emitError(code, component, severity, message, cause, context)
    }
}
