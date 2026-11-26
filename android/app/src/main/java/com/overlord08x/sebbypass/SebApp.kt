package com.overlord08x.sebbypass

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension
import java.io.File

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
    private var isExtensionRegistered = false

    var currentStatus: SebStatus = SebStatus()
        private set

    var onStatusChanged: ((SebStatus) -> Unit)? = null
    var onBridgeReady: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        val processName = resolveProcessName()
        SebCrashReporter.install(this, processName)

        // GeckoView child processes (:tab0, :gpu, :socket, etc.) also instantiate Application.
        // Initializing GeckoRuntime inside a child process breaks or crashes child spawning.
        if (processName != packageName) {
            return
        }

        ensureRuntimeInitialized()
    }

    @Synchronized
    fun ensureRuntimeInitialized(): GeckoRuntime {
        if (!::runtime.isInitialized) {
            runtime = GeckoRuntime.create(this)
            runtime.delegate = GeckoRuntime.Delegate {
                Log.e(TAG, "GeckoRuntime shut down unexpectedly")
                mainHandler.post {
                    activePort = null
                    currentStatus = currentStatus.copy(
                        errorMsg = "Browser engine stopped unexpectedly. Reopen app if pages fail to load."
                    )
                    onStatusChanged?.invoke(currentStatus)
                }
            }
        }
        if (!isExtensionRegistered) {
            isExtensionRegistered = true
            installBuiltInExtension()
        }
        return runtime
    }

    private fun resolveProcessName(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return getProcessName()
        }
        return try {
            File("/proc/self/cmdline").readText().trim('\u0000', ' ', '\n', '\r')
        } catch (_: Throwable) {
            packageName
        }
    }

    private fun installBuiltInExtension() {
        val portDelegate = object : WebExtension.PortDelegate {
            override fun onPortMessage(message: Any, port: WebExtension.Port) {
                try {
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
                } catch (err: Throwable) {
                    Log.e(TAG, "Failed to parse extension message", err)
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
                // Attach delegate synchronously on UiThread so early messages never hit null delegate.
                activePort = port
                port.setDelegate(portDelegate)

                mainHandler.post {
                    val queued = pendingCommand
                    if (queued != null) {
                        pendingCommand = null
                        sendCommand(queued)
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
        if (port == null) {
            pendingCommand = payload
            return
        }
        try {
            port.postMessage(payload)
        } catch (err: Throwable) {
            Log.e(TAG, "Failed to post message to SEB extension", err)
            activePort = null
            pendingCommand = payload
            currentStatus = currentStatus.copy(
                errorMsg = "Extension bridge disconnected, retrying: ${err.message ?: err.javaClass.simpleName}"
            )
            onStatusChanged?.invoke(currentStatus)
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
