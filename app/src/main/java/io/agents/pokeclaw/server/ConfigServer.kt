// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.server

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD
import io.agents.pokeclaw.BuildConfig
import io.agents.pokeclaw.channel.ChannelManager
import io.agents.pokeclaw.channel.security.ChannelSecurityGuard
import io.agents.pokeclaw.utils.BruteForceGuard
import io.agents.pokeclaw.tool.ToolRegistry
import io.agents.pokeclaw.tool.ToolResult
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import java.net.InetAddress
import java.net.URI

/**
 * LAN HTTP configuration server.
 * Hardened for security:
 * - Removed wildcard CORS (Access-Control-Allow-Origin: *)
 * - Validates Origin and Host headers to prevent DNS rebinding and CSRF
 * - Mandatory PIN / Token authentication for API endpoints
 * - Release builds strictly prohibit debug endpoints (/api/debug/execute)
 * - Debug builds enforce Localhost-only restriction for debug endpoints
 */
class ConfigServer(
    private val context: Context,
    port: Int = PORT
) : NanoHTTPD(null, port) { // Binds to all interfaces (0.0.0.0) for LAN access

    companion object {
        private const val TAG = "ConfigServer"
        const val PORT = 9527
        private const val MIME_HTML = "text/html"
        private const val MIME_JSON = "application/json"
        private const val HEADER_AUTH = "authorization"
        private const val HEADER_CONFIG_PIN = "x-config-pin"
        private const val HEADER_ORIGIN = "origin"
        private const val HEADER_HOST = "host"
        private const val MASK_PLACEHOLDER = "********"
    }

    private val gson = Gson()

    override fun serve(session: IHTTPSession): Response {
        val host = session.headers[HEADER_HOST]
        val method = session.method
        val uri = session.uri

        // 1. Host header validation to prevent DNS Rebinding attacks
        if (!isValidHost(host)) {
            XLog.w(TAG, "Rejected request with suspicious Host header: $host from ${session.remoteIpAddress}")
            return newFixedLengthResponse(
                Response.Status.FORBIDDEN, MIME_JSON,
                """{"code":403,"message":"Invalid Host header"}"""
            )
        }

        // 2. Handle CORS OPTIONS preflight
        if (method == Method.OPTIONS) {
            val response = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
            return applyCorsHeaders(response, session)
        }

        // 3. Static page delivery (HTML assets)
        if ((uri == "/" || uri == "/index.html") && method == Method.GET) {
            return applyCorsHeaders(serveHtml(), session)
        }

        // 4. Debug HTML asset delivery (strictly disabled in release builds)
        if (uri == "/debug.html" && method == Method.GET) {
            if (!BuildConfig.DEBUG) {
                return applyCorsHeaders(
                    newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_JSON, """{"code":403,"message":"Debug mode disabled"}"""),
                    session
                )
            }
            if (!isLocalhost(session)) {
                return applyCorsHeaders(
                    newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_JSON, """{"code":403,"message":"Debug page only accessible via localhost"}"""),
                    session
                )
            }
            return applyCorsHeaders(serveDebugHtml(), session)
        }

        // 5. Authentication check for all /api/* endpoints
        if (uri.startsWith("/api/")) {
            if (!authenticate(session)) {
                XLog.w(TAG, "Unauthorized API access attempt to $uri from ${session.remoteIpAddress}")
                return applyCorsHeaders(
                    newFixedLengthResponse(
                        Response.Status.UNAUTHORIZED, MIME_JSON,
                        """{"code":401,"message":"Unauthorized: Valid PIN/Token required"}"""
                    ),
                    session
                )
            }
        }

        return try {
            val response = when {
                uri == "/api/channels" && method == Method.GET -> handleGetChannels()
                uri == "/api/channels" && method == Method.POST -> handlePostChannels(session)
                uri == "/api/llm" && method == Method.GET -> handleGetLlm()
                uri == "/api/llm" && method == Method.POST -> handlePostLlm(session)

                // Debug endpoints: Release build strictly forbids execution
                uri == "/api/debug/tools" && method == Method.GET -> {
                    if (!BuildConfig.DEBUG) forbiddenReleaseResponse()
                    else if (!isLocalhost(session)) forbiddenLocalhostOnlyResponse()
                    else handleGetTools()
                }
                uri == "/api/debug/execute" && method == Method.POST -> {
                    if (!BuildConfig.DEBUG) forbiddenReleaseResponse()
                    else if (!isLocalhost(session)) forbiddenLocalhostOnlyResponse()
                    else handleExecuteTool(session)
                }
                uri == "/api/debug/screen-full" && method == Method.GET -> {
                    if (!BuildConfig.DEBUG) forbiddenReleaseResponse()
                    else if (!isLocalhost(session)) forbiddenLocalhostOnlyResponse()
                    else handleGetScreenFull()
                }
                uri.startsWith("/api/debug/file") && method == Method.GET -> {
                    if (!BuildConfig.DEBUG) forbiddenReleaseResponse()
                    else if (!isLocalhost(session)) forbiddenLocalhostOnlyResponse()
                    else handleServeFile(session)
                }

                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND, MIME_JSON,
                    """{"code":-1,"message":"not found"}"""
                )
            }
            applyCorsHeaders(response, session)
        } catch (e: Exception) {
            XLog.e(TAG, "Server error on $uri: ${e.message}", e)
            applyCorsHeaders(
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, MIME_JSON,
                    """{"code":-1,"message":"${e.message}"}"""
                ),
                session
            )
        }
    }

    // ==================== Security Validation Methods ====================

    /**
     * Authenticates request using X-Config-PIN header, Authorization header, or pin query parameter.
     */
    private fun authenticate(session: IHTTPSession): Boolean {
        val expectedPin = ChannelSecurityGuard.getPairPin().trim()
        if (expectedPin.isEmpty()) return true

        // The console PIN is the same 6-digit channel pairing PIN, so 10^6
        // guesses is enough for anything on the same Wi-Fi. Rate-limit per
        // remote IP and back off hard once someone starts guessing, otherwise
        // this endpoint is just an unauthenticated credential dump.
        val guardKey = "configserver:${session.remoteIpAddress ?: "unknown"}"
        if (BruteForceGuard.isLocked(guardKey)) {
            XLog.w(TAG, "Auth attempt from locked client ${session.remoteIpAddress}")
            return false
        }

        val candidates = mutableListOf<String>()
        session.headers[HEADER_CONFIG_PIN]?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { candidates.add(it) }
        session.headers[HEADER_AUTH]?.trim()?.takeIf { it.isNotEmpty() }?.let {
            candidates.add(it.removePrefix("Bearer ").removePrefix("bearer ").trim())
        }
        session.parms["pin"]?.trim()?.takeIf { it.isNotEmpty() }?.let { candidates.add(it) }

        val matched = candidates.any { BruteForceGuard.constantTimeEquals(it, expectedPin) }
        if (matched) {
            BruteForceGuard.onSuccess(guardKey)
            return true
        }

        val lockoutMs = BruteForceGuard.onFailure(guardKey)
        XLog.w(
            TAG,
            "Failed console auth from ${session.remoteIpAddress} lockoutMs=$lockoutMs"
        )
        return false
    }

    /**
     * Prevents DNS Rebinding attacks by validating the Host header.
     * Host must be localhost or a valid private IP address (LAN).
     */
    private fun isValidHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return true
        val hostWithoutPort = host.substringBefore(":")
        if (hostWithoutPort == "localhost" || hostWithoutPort == "127.0.0.1" || hostWithoutPort == "::1") {
            return true
        }
        return isPrivateIp(hostWithoutPort)
    }

    /**
     * Checks if an IP string is a private IPv4/IPv6 address.
     * Rejects domain names upfront to prevent DNS Rebinding attacks via InetAddress DNS resolution.
     */
    private fun isPrivateIp(ipStr: String): Boolean {
        if (!isNumericIpLiteral(ipStr)) {
            return false
        }
        return try {
            val addr = InetAddress.getByName(ipStr)
            addr.isSiteLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress
        } catch (_: Exception) {
            false
        }
    }

    private fun isNumericIpLiteral(str: String): Boolean {
        val ipv4Parts = str.split(".")
        if (ipv4Parts.size == 4) {
            return ipv4Parts.all { part ->
                part.toIntOrNull()?.let { it in 0..255 } == true
            }
        }
        if (str.contains(":") && str.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' }) {
            return true
        }
        return false
    }

    /**
     * Checks whether the request originated from localhost (127.0.0.1 or ::1).
     */
    private fun isLocalhost(session: IHTTPSession): Boolean {
        val remoteIp = session.remoteIpAddress ?: ""
        return remoteIp == "127.0.0.1" || remoteIp == "::1" || remoteIp == "0:0:0:0:0:0:0:1" || remoteIp == "localhost"
    }

    /**
     * Secure CORS headers application:
     * Removes wildcard '*' and only reflects valid origins (same host or LAN origin).
     */
    private fun applyCorsHeaders(response: Response, session: IHTTPSession): Response {
        val origin = session.headers[HEADER_ORIGIN]
        if (!origin.isNullOrEmpty() && isAllowedOrigin(origin, session)) {
            response.addHeader("Access-Control-Allow-Origin", origin)
            response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Config-PIN, X-Requested-With")
            response.addHeader("Access-Control-Allow-Credentials", "true")
            response.addHeader("Vary", "Origin")
        }
        return response
    }

    /**
     * Verifies whether an Origin is legitimate (local or LAN).
     */
    private fun isAllowedOrigin(origin: String, session: IHTTPSession): Boolean {
        return try {
            val uri = URI(origin)
            val host = uri.host ?: return false
            if (host == "localhost" || host == "127.0.0.1" || host == "::1") return true
            val requestHost = session.headers[HEADER_HOST]?.substringBefore(":")
            if (requestHost != null && host.equals(requestHost, ignoreCase = true)) return true
            isPrivateIp(host)
        } catch (_: Exception) {
            false
        }
    }

    private fun forbiddenReleaseResponse(): Response {
        return newFixedLengthResponse(
            Response.Status.FORBIDDEN, MIME_JSON,
            """{"code":403,"message":"Debug endpoints are strictly disabled in release builds"}"""
        )
    }

    private fun forbiddenLocalhostOnlyResponse(): Response {
        return newFixedLengthResponse(
            Response.Status.FORBIDDEN, MIME_JSON,
            """{"code":403,"message":"Debug execution is restricted to localhost only"}"""
        )
    }

    // ==================== Endpoint Handlers ====================

    private fun serveHtml(): Response {
        val inputStream = context.assets.open("web/index.html")
        val html = inputStream.bufferedReader().use { it.readText() }
        return newFixedLengthResponse(Response.Status.OK, MIME_HTML, html)
    }

    private fun serveDebugHtml(): Response {
        val inputStream = context.assets.open("web/debug.html")
        val html = inputStream.bufferedReader().use { it.readText() }
        return newFixedLengthResponse(Response.Status.OK, MIME_HTML, html)
    }

    /**
     * Secrets are never returned in plaintext over the LAN. The web console
     * round-trips these fields, and the POST handlers already ignore masked
     * values, so masking on read keeps the UI working without ever putting a
     * bot token or API key on the wire in response to a GET.
     */
    private fun maskIfPresent(value: String?): String {
        val v = value.orEmpty()
        if (v.isEmpty()) return ""
        return MASK_PLACEHOLDER
    }

    private fun handleGetChannels(): Response {
        val data = JsonObject().apply {
            addProperty("discordBotToken", maskIfPresent(KVUtils.getDiscordBotToken()))
            addProperty("telegramBotToken", maskIfPresent(KVUtils.getTelegramBotToken()))
            addProperty("wechatBotToken", maskIfPresent(KVUtils.getWechatBotToken()))
            addProperty("wechatApiBaseUrl", KVUtils.getWechatApiBaseUrl())
            addProperty("qqBotWsUrl", KVUtils.getQqWsUrl())
            addProperty("qqBotToken", maskIfPresent(KVUtils.getQqBotToken()))
            addProperty("douyinToken", maskIfPresent(KVUtils.getDouyinToken()))
            addProperty("douyinWebhookUrl", KVUtils.getDouyinWebhookUrl())
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
            addProperty("message", "ok")
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString())
    }

    private fun handlePostChannels(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""

        val json = try {
            gson.fromJson(body, JsonObject::class.java)
        } catch (e: Exception) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"invalid json"}"""
            )
        }

        var reinitDiscord = false
        var reinitTelegram = false
        var reinitWeChat = false
        var reinitQq = false
        var reinitDouyin = false

        if (json.has("discordBotToken")) {
            val v = json.get("discordBotToken").asString
            if (!isMaskedValue(v)) { KVUtils.setDiscordBotToken(v); reinitDiscord = true }
        }
        if (json.has("telegramBotToken")) {
            val v = json.get("telegramBotToken").asString
            if (!isMaskedValue(v)) { KVUtils.setTelegramBotToken(v); reinitTelegram = true }
        }
        if (json.has("wechatBotToken")) {
            val v = json.get("wechatBotToken").asString
            if (!isMaskedValue(v)) { KVUtils.setWechatBotToken(v); reinitWeChat = true }
        }
        if (json.has("wechatApiBaseUrl")) {
            KVUtils.setWechatApiBaseUrl(json.get("wechatApiBaseUrl").asString)
            reinitWeChat = true
        }
        if (json.has("qqBotWsUrl")) {
            KVUtils.setQqWsUrl(json.get("qqBotWsUrl").asString)
            reinitQq = true
        }
        if (json.has("qqBotToken")) {
            val v = json.get("qqBotToken").asString
            if (!isMaskedValue(v)) { KVUtils.setQqBotToken(v); reinitQq = true }
        }
        if (json.has("douyinToken")) {
            val v = json.get("douyinToken").asString
            if (!isMaskedValue(v)) { KVUtils.setDouyinToken(v); reinitDouyin = true }
        }
        if (json.has("douyinWebhookUrl")) {
            KVUtils.setDouyinWebhookUrl(json.get("douyinWebhookUrl").asString)
            reinitDouyin = true
        }

        if (reinitDiscord) ChannelManager.reinitDiscordFromStorage()
        if (reinitTelegram) ChannelManager.reinitTelegramFromStorage()
        if (reinitWeChat) ChannelManager.reinitWeChatFromStorage()
        if (reinitQq) ChannelManager.reinitQQFromStorage()
        if (reinitDouyin) ChannelManager.reinitDouyinFromStorage()

        ConfigServerManager.notifyConfigChanged()

        val result = JsonObject().apply {
            addProperty("code", 0)
            addProperty("message", "ok")
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString())
    }

    private fun handleGetLlm(): Response {
        val data = JsonObject().apply {
            addProperty("llmApiKey", maskIfPresent(KVUtils.getLlmApiKey()))
            addProperty("llmBaseUrl", KVUtils.getLlmBaseUrl())
            addProperty("llmModelName", KVUtils.getLlmModelName())
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
            addProperty("message", "ok")
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString())
    }

    private fun handlePostLlm(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""

        val json = try {
            gson.fromJson(body, JsonObject::class.java)
        } catch (e: Exception) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"invalid json"}"""
            )
        }

        if (json.has("llmApiKey")) {
            val value = json.get("llmApiKey").asString
            if (!isMaskedValue(value)) {
                KVUtils.setLlmApiKey(value)
            }
        }
        if (json.has("llmBaseUrl")) {
            KVUtils.setLlmBaseUrl(json.get("llmBaseUrl").asString)
        }
        if (json.has("llmModelName")) {
            val value = json.get("llmModelName").asString.trim()
            KVUtils.setLlmModelName(if (value.isEmpty()) "" else value)
        }

        ConfigServerManager.notifyConfigChanged()

        val result = JsonObject().apply {
            addProperty("code", 0)
            addProperty("message", "ok")
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString())
    }

    private fun handleGetTools(): Response {
        val tools = ToolRegistry.getAllTools()
        val arr = JsonArray()
        for (tool in tools) {
            val obj = JsonObject().apply {
                addProperty("name", tool.getName())
                addProperty("displayName", tool.getDisplayName())
                addProperty("description", tool.getDescription())
                val params = JsonArray()
                for (p in tool.getParameters()) {
                    params.add(JsonObject().apply {
                        addProperty("name", p.name)
                        addProperty("type", p.type)
                        addProperty("description", p.description)
                        addProperty("required", p.isRequired)
                    })
                }
                add("parameters", params)
            }
            arr.add(obj)
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", arr)
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString())
    }

    private fun handleExecuteTool(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""

        val json = try {
            gson.fromJson(body, JsonObject::class.java)
        } catch (e: Exception) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"invalid json"}"""
            )
        }

        val toolName = json.get("tool")?.asString ?: return newFixedLengthResponse(
            Response.Status.BAD_REQUEST, MIME_JSON,
            """{"code":-1,"message":"missing tool name"}"""
        )

        val params = mutableMapOf<String, Any>()
        try {
            json.getAsJsonObject("params")?.entrySet()?.forEach { (key, value) ->
                when {
                    value.isJsonNull -> {}
                    !value.isJsonPrimitive -> params[key] = value.toString()
                    value.asJsonPrimitive.isNumber -> params[key] = value.asNumber
                    value.asJsonPrimitive.isBoolean -> params[key] = value.asBoolean
                    else -> params[key] = value.asString
                }
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Debug param parse error: ${e.message}")
        }

        XLog.d(TAG, "Debug execute: $toolName params=$params")

        val toolResult = try {
            ToolRegistry.executeTool(toolName, params)
        } catch (e: Exception) {
            XLog.e(TAG, "Debug execute error", e)
            ToolResult.error("Exception: ${e.message}")
        }

        val data = JsonObject().apply {
            addProperty("success", toolResult.isSuccess)
            addProperty("data", toolResult.data)
            addProperty("error", toolResult.error)
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString())
    }

    private fun handleGetScreenFull(): Response {
        val service = io.agents.pokeclaw.service.ClawAccessibilityService.getInstance()
            ?: return newFixedLengthResponse(
                Response.Status.OK, MIME_JSON,
                """{"code":-1,"message":"Accessibility service is not running"}"""
            )
        val tree = service.screenTreeFull
        val data = JsonObject().apply {
            addProperty("success", tree != null)
            addProperty("data", tree ?: "")
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString())
    }

    private fun handleServeFile(session: IHTTPSession): Response {
        val path = session.parms["path"] ?: return newFixedLengthResponse(
            Response.Status.BAD_REQUEST, MIME_JSON,
            """{"code":-1,"message":"missing path param"}"""
        )
        // Resolve to a real path first. A plain string prefix check on
        // absolutePath lets "<cache>/../../databases/app.db" through, because
        // absolutePath does not collapse "..".
        val cacheDir = context.cacheDir.canonicalFile
        val file = try {
            java.io.File(path).canonicalFile
        } catch (e: java.io.IOException) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND, MIME_JSON,
                """{"code":-1,"message":"file not found or access denied"}"""
            )
        }
        if (!file.exists() || !file.toPath().startsWith(cacheDir.toPath())) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND, MIME_JSON,
                """{"code":-1,"message":"file not found or access denied"}"""
            )
        }
        val mime = when (file.extension.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
        return newFixedLengthResponse(Response.Status.OK, mime, file.inputStream(), file.length())
    }

    private fun isMaskedValue(value: String): Boolean = value.contains("*")
}
