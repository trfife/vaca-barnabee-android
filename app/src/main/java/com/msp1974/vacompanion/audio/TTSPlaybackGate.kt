package com.msp1974.vacompanion.audio

/**
 * Cross-component gate that lets the wake-word detector know when the
 * device itself is producing audio (TTS, announcement, media). While the
 * gate is open, wake detections are suppressed to prevent the satellite
 * from hearing itself and entering a self-trigger loop — a classic
 * failure mode when AEC doesn't fully cancel the loudspeaker signal.
 *
 * The gate also keeps a short tail after playback stops to give the AEC
 * and the acoustic room impulse response time to decay.
 */
object TTSPlaybackGate {

    /** How long after stopSpeaking() we continue to suppress wake events. */
    private const val TAIL_MS = 800L

    @Volatile
    private var speaking: Boolean = false

    @Volatile
    private var clearAt: Long = 0L

    fun startSpeaking() {
        speaking = true
        clearAt = 0L
    }

    fun stopSpeaking() {
        speaking = false
        clearAt = System.currentTimeMillis() + TAIL_MS
    }

    /**
     * @return true while the device is actively speaking, or during the
     *         post-playback tail. Wake-detections in this window should
     *         be dropped.
     */
    fun isSpeaking(): Boolean {
        if (speaking) return true
        val until = clearAt
        return until != 0L && System.currentTimeMillis() < until
    }
}
