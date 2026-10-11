// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.channel.douyin

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.agents.pokeclaw.channel.Channel
import io.agents.pokeclaw.channel.ChannelHandler
import io.agents.pokeclaw.channel.ChannelManager
import io.agents.pokeclaw.channel.security.ChannelSecurityGuard
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Douyin Channel Handler for Douyin Open Platform direct messaging.
 * Supports:
 * - Gateway WebSocket / Long-polling bridge for receiving user DM events
 * - Parsing open_id, msg_id, and message payload
 * - First-line security verification via ChannelSecurityGuard
 * - Outbound replies via Douyin Open API / Gateway endpoint
 */
class DouyinChannelHandler(
    private val scope: CoroutineScope,
    private var gatewayUrl: String = "",
    private var botToken: String = ""
) : ChannelHandler {

    override val channel = Channel.DOUYIN

    companion object {
        private const val TAG = "DouyinChannelHandler"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
    }

    private val gson = Gson()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var isConnected = false

    @Volatile
    private var isStopped = false

    @Volatile
    private var lastOpenId: String? = null

    private var reconnectJob: Job? = null

    override fun isConnected(): Boolean = isConnected

    override fun init() {
        if (gatewayUrl.isBlank()) {
            gatewayUrl = KVUtils.getDouyinWebhookUrl()
        }
        if (botToken.isBlank()) {
            botToken = KVUtils.getDouyinToken()
        }

        if (gatewayUrl.isBlank() && botToken.isBlank()) {
            XLog.w(TAG, "Douyin gateway URL or Bot Token not configured, Douyin channel disabled")
            return
        }

        isStopped = false
        if (gatewayUrl.startsWith("ws://") || gatewayUrl.startsWith("wss://")) {
            connectGatewayWebSocket()
        } else {
            // HTTP Webhook/Polling mode
            isConnected = true
            XLog.i(TAG, "Douyin channel initialized in HTTP Webhook mode")
        }
    }

    override fun disconnect() {
        isStopped = true
        reconnectJob?.cancel()
        reconnectJob = null
        try {
            webSocket?.close(1000, "Client stop")
        } catch (_: Exception) {}
        webSocket = null
        isConnected = false
        XLog.i(TAG, "Douyin channel disconnected")
    }

    override fun reinitFromStorage() {
        disconnect()
        gatewayUrl = KVUtils.getDouyinWebhookUrl()
        botToken = KVUtils.getDouyinToken()
        init()
    }

    private fun connectGatewayWebSocket() {
        if (isStopped || gatewayUrl.isBlank()) return

        val request = Request.Builder()
            .url(gatewayUrl)
            .apply {
                if (botToken.isNotBlank()) addHeader("Authorization", "Bearer $botToken")
            }
            .build()

        XLog.i(TAG, "Connecting to Douyin Gateway: $gatewayUrl")
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                XLog.i(TAG, "Douyin Gateway WebSocket connected!")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleInboundEvent(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (isStopped) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(5000L)
            if (!isStopped && !isConnected && gatewayUrl.startsWith("ws")) {
                connectGatewayWebSocket()
            }
        }
    }

    /**
     * Entry point for incoming Douyin events (from WebSocket or HTTP Webhook).
     */
    fun handleInboundEvent(eventPayload: String) {
        try {
            val json = gson.fromJson(eventPayload, JsonObject::class.java) ?: return

            // Handle ping/pong heartbeat
            val eventType = json.get("event")?.asString ?: json.get("event_type")?.asString
            if (eventType == "ping" || eventType == "heartbeat") {
                webSocket?.send("""{"event":"pong"}""")
                return
            }

            // Extract sender open_id
            val openId = json.get("from_user_id")?.asString
                ?: json.get("open_id")?.asString
                ?: json.getAsJsonObject("data")?.get("from_user_id")?.asString
                ?: return

            val msgId = json.get("msg_id")?.asString
                ?: json.getAsJsonObject("data")?.get("msg_id")?.asString
                ?: System.currentTimeMillis().toString()

            // Extract text content
            var text = json.get("text")?.asString
                ?: json.get("content")?.asString
                ?: ""

            if (text.startsWith("{") && text.endsWith("}")) {
                try {
                    val contentObj = gson.fromJson(text, JsonObject::class.java)
                    text = contentObj.get("text")?.asString ?: text
                } catch (_: Exception) {}
            }

            if (text.isBlank()) return

            lastOpenId = openId
            XLog.i(TAG, "Douyin message received: from=$openId, msgId=$msgId, text=${text.take(60)}")

            // Verify with ChannelSecurityGuard
            val decision = ChannelSecurityGuard.verify(channel, openId, text)
            if (decision is ChannelSecurityGuard.Decision.Reject) {
                XLog.w(TAG, "Douyin message intercepted by ChannelSecurityGuard: ${decision.replyMessage}")
                sendMessage(decision.replyMessage, msgId)
                return
            }

            ChannelManager.dispatchMessage(channel, text, msgId, openId)

        } catch (e: Exception) {
            XLog.e(TAG, "Failed to parse Douyin event: ${e.message}", e)
        }
    }

    // ==================== Outbound ChannelHandler Implementation ====================

    override fun sendMessage(content: String, messageID: String) {
        val targetOpenId = lastOpenId
        if (targetOpenId.isNullOrEmpty()) {
            XLog.w(TAG, "Douyin reply failed: no target open_id")
            return
        }

        scope.launch {
            try {
                // If WebSocket connection is active, send via WebSocket action
                if (isConnected && webSocket != null && gatewayUrl.startsWith("ws")) {
                    val frame = JsonObject().apply {
                        addProperty("action", "send_msg")
                        addProperty("to_user_id", targetOpenId)
                        addProperty("content", content)
                    }.toString()
                    webSocket?.send(frame)
                    XLog.i(TAG, "Douyin reply sent via Gateway WS to $targetOpenId")
                    return@launch
                }

                // Otherwise post via HTTP Gateway or Douyin OpenAPI
                withContext(Dispatchers.IO) {
                    val targetUrl = if (gatewayUrl.startsWith("http")) "$gatewayUrl/api/send"
                    else "https://open.douyin.com/im/send/msg/"

                    val reqBody = JsonObject().apply {
                        addProperty("to_user_id", targetOpenId)
                        addProperty("content", content)
                    }.toString()

                    val request = Request.Builder()
                        .url(targetUrl)
                        .post(reqBody.toRequestBody(JSON_MEDIA_TYPE))
                        .apply {
                            if (botToken.isNotBlank()) addHeader("access-token", botToken)
                        }
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        XLog.i(TAG, "Douyin reply sent via HTTP, code=${response.code}")
                    }
                }
            } catch (e: Exception) {
                XLog.e(TAG, "Douyin send message failed: ${e.message}", e)
            }
        }
    }

    override fun sendImage(imageBytes: ByteArray, messageID: String) {
        sendMessage("[图片消息]", messageID)
    }

    override fun sendFile(file: File, messageID: String) {
        sendMessage("[文件: ${file.name}]", messageID)
    }

    override fun getLastSenderId(): String? = lastOpenId

    override fun restoreRoutingContext(targetUserId: String) {
        if (targetUserId.isNotBlank()) lastOpenId = targetUserId
    }

    override fun sendMessageToUser(userId: String, content: String) {
        lastOpenId = userId
        sendMessage(content, "")
    }
}
