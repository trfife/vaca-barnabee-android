package com.msp1974.vacompanion.wyoming

import android.content.Context
import com.msp1974.vacompanion.utils.DeviceCapabilitiesData
import com.msp1974.vacompanion.utils.DeviceCapabilitiesManager
import com.msp1974.vacompanion.utils.Logger
import kotlinx.serialization.json.JsonObject
import java.net.ServerSocket
import kotlin.concurrent.thread

enum class SatelliteState { STOPPED, RUNNING, STARTING, STOPPING}
enum class PipelineStatus { INACTIVE, LISTENING, STREAMING }

interface WyomingCallback {
    fun onSatelliteStarted()
    fun onSatelliteStopped()
    fun onRequestInputAudioStream()
    fun onReleaseInputAudioStream()
}

class WyomingTCPServer (val context: Context, val port: Int, val cbCallback: WyomingCallback){
    var log = Logger()
    var runServer: Boolean = true
    var pipelineClient: ClientHandler? = null
    lateinit var server: ServerSocket

    var deviceInfo: DeviceCapabilitiesData = DeviceCapabilitiesManager(context).getDeviceInfo()

    fun start() {
        try {
            server = ServerSocket(port)
            log.d("Wyoming server is running on port ${server.localPort}")

            while (runServer) {
                val client = server.accept()
                // Run client in it's own thread.
                if (runServer) {
                    thread(name = "ClientHandler-${client.port}") {
                        ClientHandler(
                            context,
                            this,
                            client
                        ).run()
                    }
                } else {
                    client.close()
                }
            }
            if (!server.isClosed) {
                server.close()
            }
        } catch (e: Exception) {
            log.e("Server exception: $e")
        }

    }

    fun stop() {
        log.d("Stopping server")
        runServer = false
        if (pipelineClient != null) {
            pipelineClient?.stop()
        }
        if (!server.isClosed) {
            server.close()
        }
    }

    fun sendAudio(audio: ByteArray) {
        if (pipelineClient != null) {
            pipelineClient?.sendAudio(audio)
        }
    }

    fun sendWakeScore(wakeWord: String, score: Float, threshold: Float) {
        pipelineClient?.sendWakeScore(wakeWord, score, threshold)
    }

    fun sendStatus(data: JsonObject) {
        if (pipelineClient != null) {
            pipelineClient?.sendStatus(data)
        }
    }

    fun sendSetting(name: String, value: Any) {
        if (pipelineClient != null) {
            if (value is Boolean) {
                pipelineClient?.sendSettingChange(name, value)
            } else if (value is Int)
                pipelineClient?.sendSettingChange(name, value)
            else if (value is String) {
                pipelineClient?.sendSettingChange(name, value)
            }
        }
    }

    fun requestInputAudioStream() {
        cbCallback.onRequestInputAudioStream()
    }

    fun releaseInputAudioStream() {
        cbCallback.onReleaseInputAudioStream()
    }

    fun satelliteStarted() {
        cbCallback.onSatelliteStarted()
    }

    fun satelliteStopped() {
        cbCallback.onSatelliteStopped()
    }

}




