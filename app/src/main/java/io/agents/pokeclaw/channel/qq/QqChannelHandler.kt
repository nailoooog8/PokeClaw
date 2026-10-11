// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.channel.qq

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.agents.pokeclaw.channel.Channel
import io.agents.pokeclaw.channel.ChannelHandler
import io.agents.pokeclaw.channel.ChannelManager
import io.agents.pokeclaw.channel.security.ChannelSecurityGuard
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.*
import okio.ByteString
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/**
 * QQ Channel Handler implementing OneBot v11 protocol over WebSocket.
 * Compatible with LLOneBot, NapCatQQ, Lagrange, Go-CQHttp, Shamrock, etc.
 *
 * Features:
 * - Robust OkHttp WebSocket client
 * - Heartbeat tracking & exponential backoff reconnect
 * - Private & Group message parsing and text extraction (CQ code cleaning)
 * - ChannelSecurityGuard verification on all incoming messages
 * - Outbound message/image delivery via OneBot JSON RPC actions
 */
class QqChannelHandler(
    private val scope: CoroutineScope,
    private var wsUrl: String = "",
    private var accessToken: String = ""
) : ChannelHandler {

    override val channel = Channel.QQ

    companion object {
        private const val TAG = "QqChannelHandler"
        private const val MIN_RECONNECT_DELAY_MS = 2_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L

        private val CQ_AT_PATTERN = Pattern.compile("""\[CQ:at,qq=\d+\]""")
    }

    private val gson = Gson()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var isConnected = false

    @Volatile
    private var isStopped = false

    private val reconnectAttempts = AtomicInteger(0)
    private var reconnectJob: Job? = null

    // Routing context for replies
    @Volatile
    private var lastMessageType: String? = null // "private" or "group"
    @Volatile
    private var lastUserId: Long? = null
    @Volatile
    private var lastGroupId: Long? = null

    override fun isConnected(): Boolean = isConnected

    override fun init() {
        if (wsUrl.isBlank()) {
            wsUrl = KVUtils.getQqWsUrl()
        }
        if (accessToken.isBlank()) {
            accessToken = KVUtils.getQqBotToken()
        }

        if (wsUrl.isBlank()) {
            XLog.w(TAG, "QQ Bot WebSocket URL not configured, QQ channel unavailable")
            return
        }

        isStopped = false
        connectWebSocket()
    }

    override fun disconnect() {
        isStopped = true
        reconnectJob?.cancel()
        reconnectJob = null
        try {
            webSocket?.close(1000, "Client disconnected")
        } catch (e: Exception) {
            XLog.w(TAG, "Error closing WebSocket: ${e.message}")
        }
        webSocket = null
        isConnected = false
        XLog.i(TAG, "QQ Channel disconnected")
    }

    override fun reinitFromStorage() {
        disconnect()
        wsUrl = KVUtils.getQqWsUrl()
        accessToken = KVUtils.getQqBotToken()
        init()
    }

    private fun connectWebSocket() {
        if (isStopped || wsUrl.isBlank()) return

        val requestBuilder = Request.Builder().url(wsUrl)
        if (accessToken.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $accessToken")
        }

        val request = requestBuilder.build()
        XLog.i(TAG, "Connecting to OneBot WebSocket: $wsUrl (attempt=${reconnectAttempts.get()})")

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                reconnectAttempts.set(0)
                XLog.i(TAG, "OneBot WebSocket successfully connected!")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleInboundMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                XLog.d(TAG, "Binary frame received: length=${bytes.size}")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                XLog.w(TAG, "OneBot WebSocket closing: code=$code, reason=$reason")
                isConnected = false
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                XLog.w(TAG, "OneBot WebSocket closed: code=$code, reason=$reason")
                isConnected = false
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                XLog.e(TAG, "OneBot WebSocket failure: ${t.message}")
                isConnected = false
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (isStopped) return
        val attempt = reconnectAttempts.incrementAndGet()
        val delayMs = (MIN_RECONNECT_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(5)))
            .coerceAtMost(MAX_RECONNECT_DELAY_MS)

        XLog.i(TAG, "Scheduling OneBot WebSocket reconnect in ${delayMs}ms (attempt $attempt)")
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!isStopped && !isConnected) {
                connectWebSocket()
            }
        }
    }

    /**
     * Parses incoming JSON frames from OneBot v11.
     */
    private fun handleInboundMessage(jsonStr: String) {
        try {
            val json = gson.fromJson(jsonStr, JsonObject::class.java) ?: return

            // Handle Meta Events (heartbeat, lifecycle)
            val postType = json.get("post_type")?.asString
            if (postType == "meta_event") {
                val metaEventType = json.get("meta_event_type")?.asString
                if (metaEventType == "heartbeat") {
                    XLog.d(TAG, "OneBot heartbeat received")
                }
                return
            }

            // Only process message events
            if (postType != "message") {
                return
            }

            val messageType = json.get("message_type")?.asString ?: return // "private" or "group"
            val userId = json.get("user_id")?.asLong ?: return
            val messageId = json.get("message_id")?.asLong ?: System.currentTimeMillis()
            val groupId = if (messageType == "group") json.get("group_id")?.asLong else null

            // Update routing context
            lastMessageType = messageType
            lastUserId = userId
            lastGroupId = groupId

            // Extract plain text
            val rawMessage = json.get("raw_message")?.asString ?: ""
            var cleanText = CQ_AT_PATTERN.matcher(rawMessage).replaceAll("").trim()

            if (cleanText.isEmpty() && json.has("message")) {
                cleanText = extractTextFromMessageElement(json.get("message"))
            }

            if (cleanText.isBlank()) {
                XLog.d(TAG, "Message without text content received from user=$userId")
                return
            }

            val senderId = userId.toString()
            XLog.i(TAG, "QQ message received: type=$messageType, sender=$senderId, group=$groupId, text=${cleanText.take(60)}")

            // First line of defense: ChannelSecurityGuard
            val decision = ChannelSecurityGuard.verify(channel, senderId, cleanText)
            if (decision is ChannelSecurityGuard.Decision.Reject) {
                XLog.w(TAG, "QQ message intercepted by ChannelSecurityGuard: ${decision.replyMessage}")
                sendMessage(decision.replyMessage, messageId.toString())
                return
            }

            // Dispatched to ChannelManager -> TaskOrchestrator
            ChannelManager.dispatchMessage(channel, cleanText, messageId.toString(), senderId)

        } catch (e: Exception) {
            XLog.e(TAG, "Failed to parse OneBot inbound message: ${e.message}", e)
        }
    }

    private fun extractTextFromMessageElement(msgElem: com.google.gson.JsonElement?): String {
        if (msgElem == null) return ""
        if (msgElem.isJsonPrimitive) return msgElem.asString
        if (msgElem.isJsonArray) {
            val sb = StringBuilder()
            for (item in msgElem.asJsonArray) {
                if (item.isJsonObject) {
                    val obj = item.asJsonObject
                    if (obj.get("type")?.asString == "text") {
                        val text = obj.getAsJsonObject("data")?.get("text")?.asString ?: ""
                        sb.append(text)
                    }
                }
            }
            return sb.toString().trim()
        }
        return ""
    }

    // ==================== Outbound ChannelHandler Implementation ====================

    override fun sendMessage(content: String, messageID: String) {
        if (!isConnected || webSocket == null) {
            XLog.w(TAG, "QQ reply failed: WebSocket not connected")
            return
        }

        val type = lastMessageType
        val userId = lastUserId
        val groupId = lastGroupId

        val actionJson = JsonObject()
        val params = JsonObject()

        if (type == "group" && groupId != null) {
            actionJson.addProperty("action", "send_group_msg")
            params.addProperty("group_id", groupId)
            params.addProperty("message", content)
        } else if (userId != null) {
            actionJson.addProperty("action", "send_private_msg")
            params.addProperty("user_id", userId)
            params.addProperty("message", content)
        } else {
            XLog.w(TAG, "QQ reply failed: no active target (userId/groupId is null)")
            return
        }

        actionJson.add("params", params)
        actionJson.addProperty("echo", "msg_${System.currentTimeMillis()}")

        val frame = actionJson.toString()
        val sent = webSocket?.send(frame) ?: false
        if (sent) {
            XLog.i(TAG, "QQ reply sent successfully: target=${groupId ?: userId}, text=${content.take(60)}")
        } else {
            XLog.e(TAG, "QQ reply failed to send over WebSocket")
        }
    }

    override fun sendImage(imageBytes: ByteArray, messageID: String) {
        if (!isConnected || webSocket == null) return

        val b64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        val cqImage = "[CQ:image,file=base64://$b64]"

        sendMessage(cqImage, messageID)
    }

    override fun sendFile(file: File, messageID: String) {
        if (!isConnected || webSocket == null || !file.exists()) return

        val type = lastMessageType
        val userId = lastUserId
        val groupId = lastGroupId

        val actionJson = JsonObject()
        val params = JsonObject()

        if (type == "group" && groupId != null) {
            actionJson.addProperty("action", "upload_group_file")
            params.addProperty("group_id", groupId)
            params.addProperty("file", file.absolutePath)
            params.addProperty("name", file.name)
        } else if (userId != null) {
            actionJson.addProperty("action", "upload_private_file")
            params.addProperty("user_id", userId)
            params.addProperty("file", file.absolutePath)
            params.addProperty("name", file.name)
        } else {
            return
        }

        actionJson.add("params", params)
        webSocket?.send(actionJson.toString())
    }

    override fun getLastSenderId(): String? = lastUserId?.toString()

    override fun restoreRoutingContext(targetUserId: String) {
        targetUserId.toLongOrNull()?.let {
            lastUserId = it
            lastMessageType = "private"
        }
    }

    override fun sendMessageToUser(userId: String, content: String) {
        val targetUid = userId.toLongOrNull() ?: return
        if (!isConnected || webSocket == null) return

        val actionJson = JsonObject().apply {
            addProperty("action", "send_private_msg")
            add("params", JsonObject().apply {
                addProperty("user_id", targetUid)
                addProperty("message", content)
            })
            addProperty("echo", "proactive_${System.currentTimeMillis()}")
        }
        webSocket?.send(actionJson.toString())
    }
}
