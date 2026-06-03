package com.vibecode.tvremote

import android.util.Base64
import android.util.Log
import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.*
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.*
import java.util.concurrent.TimeoutException

class SamsungTvClient(
    private val appName: String = "VibeRemote",
    private val onStateChanged: (State) -> Unit,
    private val onTokenReceived: (String) -> Unit
) {
    companion object {
        private const val TAG = "SamsungTvClient"
        private const val PORT_SECURE = 8002
    }

    enum class State {
        DISCONNECTED,
        CONNECTING,
        PAIRING, // Waiting for user to click Allow on TV
        CONNECTED,
        ERROR
    }

    enum class KeyCommand(val wireName: String) {
        CLICK("Click"),
        PRESS("Press"),
        RELEASE("Release")
    }

    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private var currentIp: String? = null
    private var savedToken: String? = null
    private var pendingResponse: ((JsonObject) -> Unit)? = null
    private var pendingFailure: ((Throwable) -> Unit)? = null
    private var pendingTimeoutRunnable: Runnable? = null
    var currentState: State = State.DISCONNECTED
        private set(value) {
            field = value
            onStateChanged(value)
        }

    init {
        client = createUnsafeOkHttpClient()
    }

    fun connect(ip: String, token: String? = null) {
        if (currentState == State.CONNECTING || currentState == State.CONNECTED) {
            disconnect()
        }

        currentIp = ip
        savedToken = token
        currentState = State.CONNECTING
        val encodedAppName = Base64.encodeToString(appName.toByteArray(), Base64.NO_WRAP)
        val url = StringBuilder("wss://$ip:$PORT_SECURE/api/v2/channels/samsung.remote.control?name=$encodedAppName")
        if (!token.isNullOrEmpty()) {
            url.append("&token=$token")
        }

        val request = Request.Builder()
            .url(url.toString())
            .build()

        webSocket = client?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened")
                currentState = State.PAIRING
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Message received: $text")
                try {
                    val map = gson.fromJson(text, Map::class.java)
                    val event = map["event"] as? String
                    if (event == "ms.channel.connect") {
                        val data = map["data"] as? Map<*, *>
                        val receivedToken = data?.get("token") as? String
                        if (receivedToken != null) {
                            savedToken = receivedToken
                            onTokenReceived(receivedToken)
                        }
                        currentState = State.CONNECTED
                    } else if (event == "ms.channel.unauthorized") {
                        currentState = State.ERROR
                        failPendingRequest(IllegalStateException("TV rejected the connection"))
                    } else {
                        val pending = pendingResponse
                        if (pending != null) {
                            val jsonObject = gson.fromJson(text, JsonObject::class.java)
                            clearPendingRequest()
                            mainHandler.post { pending.invoke(jsonObject) }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing message", e)
                    failPendingRequest(e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code / $reason")
                currentState = State.DISCONNECTED
                failPendingRequest(IllegalStateException("Connection closed"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed")
                currentState = State.DISCONNECTED
                failPendingRequest(IllegalStateException("Connection closed"))
                reconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                currentState = State.ERROR
                failPendingRequest(t)
                reconnect()
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "App request disconnect")
        webSocket = null
        currentState = State.DISCONNECTED
    }

    fun reconnect() {
        if (currentState == State.CONNECTING || currentState == State.CONNECTED) return
        val ip = currentIp ?: return
        Log.d(TAG, "Reconnecting to $ip")
        connect(ip, savedToken)
    }

    fun sendKey(key: String) {
        sendKey(key, KeyCommand.CLICK)
    }

    fun pressKey(key: String) {
        sendKey(key, KeyCommand.PRESS)
    }

    fun releaseKey(key: String) {
        sendKey(key, KeyCommand.RELEASE)
    }

    private fun sendKey(key: String, command: KeyCommand) {
        if (currentState != State.CONNECTED) {
            Log.w(TAG, "Cannot send key: Client not connected")
            return
        }

        val payload = """
            {
                "method": "ms.remote.control",
                "params": {
                    "Cmd": "${command.wireName}",
                    "DataOfCmd": "$key",
                    "Option": "false",
                    "TypeOfRemote": "SendRemoteKey"
                }
            }
        """.trimIndent()
        webSocket?.send(payload)
    }

    fun sendText(text: String) {
        if (currentState != State.CONNECTED) return
        val encodedText = Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)
        val payload = """
            {
                "method": "ms.remote.control",
                "params": {
                    "Cmd": "$encodedText",
                    "DataOfCmd": "base64",
                    "Option": "false",
                    "TypeOfRemote": "SendInputString"
                }
            }
        """.trimIndent()
        webSocket?.send(payload)
    }

    fun launchApp(appId: String) {
        if (currentState != State.CONNECTED) return
        val payload = """
            {
                "method": "ms.channel.emit",
                "params": {
                    "event": "ed.apps.launch",
                    "to": "host",
                    "data": {
                        "action_type": "DEEP_LINK",
                        "appId": "$appId"
                    }
                }
            }
        """.trimIndent()
        webSocket?.send(payload)
    }

    fun launchApp(appId: String, appType: Int?) {
        if (currentState != State.CONNECTED) return
        val actionType = if (appType == 2) "DEEP_LINK" else "NATIVE_LAUNCH"
        val payload = """
            {
                "method": "ms.channel.emit",
                "params": {
                    "event": "ed.apps.launch",
                    "to": "host",
                    "data": {
                        "action_type": "$actionType",
                        "appId": "$appId"
                    }
                }
            }
        """.trimIndent()
        webSocket?.send(payload)
    }

    fun launchApp(app: SamsungTvApp) {
        launchApp(app.appId, app.appType)
    }

    fun getInstalledApps(
        onSuccess: (List<SamsungTvApp>) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        sendJsonRequest(
            payload = """
                {
                    "method": "ms.channel.emit",
                    "params": {
                        "data": "",
                        "event": "ed.installedApp.get",
                        "to": "host"
                    }
                }
            """.trimIndent(),
            onSuccess = { response ->
                onSuccess(parseSamsungTvApps(response))
            },
            onError = onError,
        )
    }

    fun getAppIcon(
        iconPath: String,
        onSuccess: (JsonObject) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        sendJsonRequest(
            payload = """
                {
                    "method": "ms.channel.emit",
                    "params": {
                        "data": {
                            "iconPath": "$iconPath"
                        },
                        "event": "ed.apps.icon",
                        "to": "host"
                    }
                }
            """.trimIndent(),
            onSuccess = onSuccess,
            onError = onError,
        )
    }

    private fun sendJsonRequest(
        payload: String,
        onSuccess: (JsonObject) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        if (currentState != State.CONNECTED) {
            onError(IllegalStateException("TV is not connected"))
            return
        }
        if (pendingResponse != null) {
            onError(IllegalStateException("Another TV request is already in flight"))
            return
        }

        pendingResponse = onSuccess
        pendingFailure = onError
        pendingTimeoutRunnable = Runnable {
            failPendingRequest(TimeoutException("Timed out waiting for Samsung TV response"))
        }
        mainHandler.postDelayed(pendingTimeoutRunnable!!, 8000L)

        val sent = webSocket?.send(payload) == true
        if (!sent) {
            failPendingRequest(IllegalStateException("Failed to send request to TV"))
        }
    }

    private fun clearPendingRequest() {
        pendingTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingTimeoutRunnable = null
        pendingResponse = null
        pendingFailure = null
    }

    private fun failPendingRequest(error: Throwable) {
        val failure = pendingFailure
        clearPendingRequest()
        if (failure != null) {
            mainHandler.post { failure.invoke(error) }
        }
    }

    private fun createUnsafeOkHttpClient(): OkHttpClient {
        return try {
            val trustAllCerts = arrayOf<TrustManager>(
                object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }
            )

            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, SecureRandom())
            val sslSocketFactory = sslContext.socketFactory

            OkHttpClient.Builder()
                .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .build()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }
}
