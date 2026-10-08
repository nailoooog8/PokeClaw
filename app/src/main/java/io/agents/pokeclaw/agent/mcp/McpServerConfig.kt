// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.mcp

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName

/**
 * Configuration for one remote MCP (Model Context Protocol) server
 * exposing a Streamable HTTP endpoint.
 *
 * Persisted as a JSON array at: <external files dir>/mcp_servers.json
 * (editable via adb push or any file manager; the app reads it on startup
 * and whenever McpConnectionManager.refresh() is called).
 *
 * [headerValue] is the one field that must not sit on disk in the clear:
 * McpConnectionManager encrypts it with a Keystore-backed key before writing
 * and decrypts it on load, so in-memory instances always carry the real token
 * while the file carries `enc:v1:`-tagged ciphertext. A hand-written plaintext
 * value is still accepted on load and is re-encrypted the next time the file
 * is saved.
 */
data class McpServerConfig(
    /** Short identifier used to prefix registered tool names (mcp_{name}_{tool}) */
    val name: String,
    /** Streamable HTTP endpoint, e.g. https://example.com/mcp */
    val url: String,
    /** Optional auth header name (e.g. Authorization) */
    @SerializedName("headerName") val headerName: String? = null,
    /** Optional auth header value (e.g. Bearer sk-...) */
    @SerializedName("headerValue") val headerValue: String? = null,
    /** Disabled servers are skipped at load time */
    val enabled: Boolean = true
) {
    /** Sanitized token used in registered tool names */
    fun safeName(): String = name.replace(Regex("[^a-zA-Z0-9_]"), "_").lowercase()

    companion object {
        private val GSON = Gson()

        fun parseList(json: String): List<McpServerConfig> {
            if (json.isBlank()) return emptyList()
            return try {
                val arr = GSON.fromJson(json, Array<McpServerConfig>::class.java) ?: return emptyList()
                arr.filter { it.name.isNotBlank() && it.url.isNotBlank() }
            } catch (_: JsonSyntaxException) {
                emptyList()
            } catch (_: IllegalStateException) {
                // File may contain a single object instead of an array
                try {
                    val one = GSON.fromJson(json, McpServerConfig::class.java)
                    if (one != null && one.name.isNotBlank() && one.url.isNotBlank()) listOf(one) else emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }
    }
}
