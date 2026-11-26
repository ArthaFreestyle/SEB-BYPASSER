package com.overlord08x.sebbypass

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension

data class SebStatus(
    val enabled: Boolean = false,
    val sebStartUrl: String = "",
    val configHash: String = "",
    val rqUrlsFilter: List<String> = listOf("<all_urls>"),
    val userAgent: String = "",
    val errorMsg: String = ""
)

class SebApp : Application() {
    lateinit var runtime: GeckoRuntime
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private var activePort: WebExtension.Port? = null
    private var pendingCommand: JSONObject? = null

    var currentStatus: SebStatus = SebStatus()
        private set

    var onStatusChanged: ((SebStatus) -> Unit)? = null
    var onBridgeReady: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        runtime = GeckoRuntime.create(this)
        installBuiltInExtension()
    }

    private fun installBuiltInExtension() {
        val portDelegate = object : WebExtension.PortDelegate {
            override fun onPortMessage(message: Any, port: WebExtension.Port) {
                val obj = message as? JSONObject ?: return
                if (obj.optString("action") != "status") return

                val filters = mutableListOf<String>()
                val arr = obj.optJSONArray("rqUrlsFilter")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        filters.add(arr.optString(i))
                    }
                }
                if (filters.isEmpty()) {
                    filters.add("<all_urls>")
                }

                val status = SebStatus(
                    enabled = obj.optBoolean("enabled", false),
                    sebStartUrl = obj.optString("sebStartUrl", ""),
                    configHash = obj.optString("configHash", ""),
                    rqUrlsFilter = filters,
                    userAgent = obj.optString("userAgent", ""),
                    errorMsg = obj.optString("errorMsg", "")
                )

                mainHandler.post {
                    currentStatus = status
                    onStatusChanged?.invoke(status)
                }
            }

            override fun onDisconnect(port: WebExtension.Port) {
                if (activePort === port) {
                    activePort = null
                }
            }
        }

        val messageDelegate = object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
                mainHandler.post {
                    activePort = port
                    port.setDelegate(portDelegate)
                    val queued = pendingCommand
                    if (queued != null) {
                        pendingCommand = null
                        port.postMessage(queued)
                    }
                    onBridgeReady?.invoke()
                }
            }

            override fun onMessage(
                nativeApp: String,
                message: Any,
                sender: WebExtension.MessageSender
            ): GeckoResult<Any>? = null
        }

        runtime.webExtensionController
            .ensureBuiltIn(EXTENSION_URI, EXTENSION_ID)
            .accept(
                { ext ->
                    if (ext != null) {
                        ext.setMessageDelegate(messageDelegate, NATIVE_APP_ID)
                        Log.i(TAG, "Built-in SEB extension installed: ${ext.id}")
                    }
                },
                { err ->
                    Log.e(TAG, "Failed to install SEB extension", err)
                    mainHandler.post {
                        currentStatus = currentStatus.copy(
                            errorMsg = "Extension init failed: ${err?.message ?: "unknown"}"
                        )
                        onStatusChanged?.invoke(currentStatus)
                    }
                }
            )
    }

    fun sendCommand(payload: JSONObject) {
        val port = activePort
        if (port != null) {
            port.postMessage(payload)
        } else {
            pendingCommand = payload
        }
    }

    fun enableSeb(xml: String, urlsFilter: List<String> = listOf("<all_urls>")) {
        val payload = JSONObject().apply {
            put("action", "enable")
            put("sebXML", xml)
            put("rqUrlsFilter", JSONArray(urlsFilter))
        }
        sendCommand(payload)
    }

    fun disableSeb() {
        val payload = JSONObject().apply {
            put("action", "disable")
        }
        sendCommand(payload)
    }

    fun updateFilter(urlsFilter: List<String>) {
        val payload = JSONObject().apply {
            put("action", "updateRqUrlsFilter")
            put("rqUrlsFilter", JSONArray(urlsFilter))
        }
        sendCommand(payload)
    }

    companion object {
        private const val TAG = "SebApp"
        private const val EXTENSION_URI = "resource://android/assets/seb-extension/"
        private const val EXTENSION_ID = "seb-bypass@overlord08x.dev"
        private const val NATIVE_APP_ID = "seb_bypass"
    }
}
