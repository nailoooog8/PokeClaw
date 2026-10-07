// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.mcp

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.agents.pokeclaw.utils.XLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimal MCP (Model Context Protocol) client for the Streamable HTTP transport.
 *
 * Implements just enough of the protocol to discover and call remote tools:
 *   initialize -> notifications/initialized -> tools/list -> tools/call
 *
 * Responses may arrive as a plain application/json body or as a
 * text/event-stream; both are handled. Session affinity uses the
 * Mcp-Session-Id response header, as required by the Streamable HTTP spec.
 */
class McpStreamableHttpClient(private val server: McpServerConfig) {

    companion object {
        private const val TAG = "McpClient"
        private const val PROTOCOL_VERSION = "2025-03-26"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val ID_SEQ = AtomicLong(1)
    }

    private val gson = Gson()
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var sessionId: String? = null
    private var negotiatedProtocol: String = PROTOCOL_VERSION

    /** Remote tool description returned by tools/list */
    data class McpRemoteTool(
        val name: String,
        val description: String?,
        val inputSchema: JsonObject?
    )

    /**
     * Performs the initialize handshake. Must be called once before listTools/callTool.
     * Throws IOException/IllegalStateException on failure.
     */
    fun initialize() {
        val params = JsonObject().apply {
            addProperty("protocolVersion", PROTOCOL_VERSION)
            add("capabilities", JsonObject())
            add("clientInfo", JsonObject().apply {
                addProperty("name", "PokeClaw")
                addProperty("version", "0.7.1")
            })
        }
        val resp = postRpc("initialize", params, expectResult = true)
        val result = resp?.getAsJsonObject("result")
        val ver = result?.get("protocolVersion")?.asString
        if (!ver.isNullOrBlank()) negotiatedProtocol = ver
        // Spec: after initialize the client sends an initialized notification
        postRpc("notifications/initialized", null, expectResult = false)
        XLog.i(TAG, "Initialized MCP server '${server.name}', protocol=$negotiatedProtocol, session=${sessionId ?: "none"}")
    }

    /** Lists remote tools. Returns an empty list when the server exposes none. */
    fun listTools(): List<McpRemoteTool> {
        val resp = postRpc("tools/list", JsonObject(), expectResult = true)
            ?: throw IllegalStateException("No response for tools/list from '${server.name}'")
        val result = resp.getAsJsonObject("result")
            ?: throw IllegalStateException("tools/list returned no result from '${server.name}'")
        val tools = result.getAsJsonArray("tools") ?: JsonArray()
        val out = ArrayList<McpRemoteTool>(tools.size())
        for (t in tools) {
            val o = t.asJsonObject
            val name = o.get("name")?.asString ?: continue
            out.add(
                McpRemoteTool(
                    name = name,
                    description = o.get("description")?.takeIf { !it.isJsonNull }?.asString,
                    inputSchema = o.get("inputSchema")?.takeIf { it.isJsonObject }?.asJsonObject
                )
            )
        }
        return out
    }

    /**
     * Calls a remote tool. Returns the concatenated text content of the result,
     * or throws with the server-provided message on failure.
     */
    fun callTool(toolName: String, arguments: Map<String, Any>): String {
        val params = JsonObject().apply {
            addProperty("name", toolName)
            add("arguments", gson.toJsonTree(arguments))
        }
        val resp = postRpc("tools/call", params, expectResult = true)
            ?: throw IllegalStateException("No response for tools/call '$toolName' from '${server.name}'")
        val result = resp.getAsJsonObject("result")
            ?: throw IllegalStateException("tools/call returned no result for '$toolName'")
        val isError = result.get("isError")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
        val text = extractText(result)
        if (isError) throw IllegalStateException(text.ifBlank { "MCP tool '$toolName' reported an error" })
        return text
    }

    private fun extractText(result: JsonObject): String {
        val content = result.getAsJsonArray("content") ?: return ""
        val sb = StringBuilder()
        for (c in content) {
            val o = c.asJsonObject
            val type = o.get("type")?.asString
            when (type) {
                "text" -> sb.append(o.get("text")?.asString ?: "").append('\n')
                else -> sb.append("[${type ?: "unknown"} content item]\n")
            }
        }
        return sb.toString().trim()
    }

    /**
     * Sends a JSON-RPC request (or notification when expectResult=false).
     * Returns the parsed response object, or null for notifications.
     */
    private fun postRpc(method: String, params: JsonObject?, expectResult: Boolean): JsonObject? {
        val id = if (expectResult) ID_SEQ.getAndIncrement() else null
        val body = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            if (id != null) addProperty("id", id)
            addProperty("method", method)
            if (params != null) add("params", params)
        }
        val req = requestBuilder()
            .url(server.url)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json, text/event-stream")
            .build()

        http.newCall(req).execute().use { response ->
            if (!expectResult) {
                // Notification: 2xx is enough, body may be empty
                if (!response.isSuccessful) {
                    XLog.w(TAG, "Notification '$method' to '${server.name}' returned ${response.code}")
                }
                return null
            }
            if (!response.isSuccessful) {
                throw java.io.IOException("HTTP ${response.code} for '$method' from '${server.name}'")
            }
            response.header("Mcp-Session-Id")?.let { sessionId = it }

            val contentType = response.header("Content-Type") ?: ""
            val respBody = response.body ?: throw java.io.IOException("Empty body for '$method'")
            val parsed: JsonObject = if (contentType.contains("text/event-stream")) {
                readSseResponse(respBody, id)
            } else {
                JsonParser.parseString(respBody.string()).asJsonObject
            }
            validateAndReturn(parsed, id, method)
            return parsed
        }
    }

    /** Reads an SSE stream until the JSON-RPC response carrying our id arrives. */
    private fun readSseResponse(body: okhttp3.ResponseBody, id: Long?): JsonObject {
        val source = body.source()
        val dataBuf = StringBuilder()
        while (true) {
            val line = source.readUtf8Line() ?: break
            when {
                line.startsWith("data:") -> dataBuf.append(line.removePrefix("data:").trimStart())
                line.isBlank() && dataBuf.isNotEmpty() -> {
                    val candidate = JsonParser.parseString(dataBuf.toString()).asJsonObject
                    val hasId = candidate.get("id")
                    val matches = id == null || (hasId != null && !hasId.isJsonNull && hasId.asLong == id)
                    if (matches) return candidate
                    dataBuf.setLength(0)
                }
            }
        }
        throw java.io.IOException("SSE stream ended without a response (id=$id)")
    }

    private fun validateAndReturn(parsed: JsonObject, id: Long?, method: String) {
        parsed.get("error")?.takeIf { it.isJsonObject }?.let { err ->
            val msg = err.asJsonObject.get("message")?.asString ?: "unknown error"
            throw java.io.IOException("JSON-RPC error for '$method': $msg")
        }
        if (id != null) {
            val hasId = parsed.get("id")
            if (hasId == null || hasId.isJsonNull) {
                // Some servers batch notifications before the response; tolerate but note
                XLog.w(TAG, "Response for '$method' missing id")
            }
        }
    }

    private fun requestBuilder(): Request.Builder {
        val b = Request.Builder()
        server.headerName?.takeIf { it.isNotBlank() }?.let { name ->
            server.headerValue?.takeIf { it.isNotBlank() }?.let { value ->
                b.header(name, value)
            }
        }
        sessionId?.let { b.header("Mcp-Session-Id", it) }
        b.header("MCP-Protocol-Version", negotiatedProtocol)
        return b
    }
}
