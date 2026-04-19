package com.msp1974.vacompanion.wyoming

/**
 * User-facing coarse state of the voice satellite, emitted to Home Assistant
 * as `sensors.satellite_state` so the Barnabee dashboard can show clear
 * "listening / thinking / talking" indicators.
 *
 * This is deliberately coarser than [PipelineStage]. Stages are about timeout
 * accounting; phases are about what the user sees.
 *
 * Mapping rules live in [ClientHandler]. Notable choices (per rubber-duck
 * review):
 *   - `audio-stop` with continue-conversation stays in THINKING, not LISTENING,
 *     until HA actually sends the next `transcribe`. The mic isn't live during
 *     that gap, so flashing "listening" would be a lie.
 *   - ERROR is sticky — it is NOT cleared by the internal `resetPipeline()`
 *     teardown (which fires on every error). It clears on the next real
 *     transition (wake, transcribe, startSatellite). Ensures the dashboard
 *     actually shows that an error happened rather than flashing ERROR → IDLE
 *     in a single tick.
 */
enum class SatellitePhase(val wireValue: String) {
    /** No pipeline active, waiting for wake. */
    IDLE("idle"),

    /** Mic is live — either listening for voice start or user is speaking. */
    LISTENING("listening"),

    /** HA is processing (STT → intent → TTS synth). */
    THINKING("thinking"),

    /** TTS audio is playing back to the user. */
    TALKING("talking"),

    /** Last pipeline ended in error. Sticky until next real transition. */
    ERROR("error"),

    /** Satellite explicitly paused (`pause-satellite`). */
    PAUSED("paused");
}
