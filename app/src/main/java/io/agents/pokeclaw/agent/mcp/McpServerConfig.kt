// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.mcp

import com.google.gson.Gson
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

    /**
     * Resolves the effective normalized header value.
     * When headerName is "Authorization" and headerValue lacks an explicit scheme (e.g. bare token like "sk-..."),
     * it automatically prepends "Bearer " to prevent 401 Unauthorized errors with standard MCP servers.
     */
    fun normalizedHeaderValue(): String? {
        val name = headerName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val value = headerValue?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (name.equals("Authorization", ignoreCase = true)) {
            val hasExplicitScheme = KNOWN_AUTH_SCHEMES.any { value.startsWith(it, ignoreCase = true) }
            if (!hasExplicitScheme) {
                return "Bearer $value"
            }
        }
        return value
    }

    /**
     * Extracts the raw token/credential without scheme if headerName is Authorization.
     * Useful for UI editing so users don't see double "Bearer Bearer".
     */
    fun displayCredential(): String? {
        val value = headerValue?.trim() ?: return null
        if (headerName?.equals("Authorization", ignoreCase = true) == true) {
            if (value.startsWith("Bearer ", ignoreCase = true)) {
                return value.substring(7).trim()
            }
            if (value.startsWith("Basic ", ignoreCase = true)) {
                return value.substring(6).trim()
            }
        }
        return value
    }

    companion object {
        private val GSON = Gson()
        internal val KNOWN_AUTH_SCHEMES = listOf("Bearer ", "Basic ", "Digest ", "Token ", "OAuth ")

        fun parseList(json: String): List<McpServerConfig> {
            val trimmed = json.trim()
            if (trimmed.isEmpty()) return emptyList()
            if (trimmed.startsWith("{")) {
                return try {
                    val one = GSON.fromJson(trimmed, McpServerConfig::class.java)
                    if (one != null && one.name.isNotBlank() && one.url.isNotBlank()) listOf(one) else emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
            }
            return try {
                val arr = GSON.fromJson(trimmed, Array<McpServerConfig>::class.java) ?: return emptyList()
                arr.filter { it.name.isNotBlank() && it.url.isNotBlank() }
            } catch (_: Exception) {
                try {
                    val one = GSON.fromJson(trimmed, McpServerConfig::class.java)
                    if (one != null && one.name.isNotBlank() && one.url.isNotBlank()) listOf(one) else emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }
    }
}

/**
 * Authentication type helper for MCP server configuration.
 */
enum class McpAuthType {
    NONE,
    BEARER,
    BASIC,
    CUSTOM;

    companion object {
        fun detect(headerName: String?, headerValue: String?): McpAuthType {
            if (headerName.isNullOrBlank() || headerValue.isNullOrBlank()) return NONE
            if (headerName.equals("Authorization", ignoreCase = true)) {
                val trimmed = headerValue.trim()
                return when {
                    trimmed.startsWith("Bearer ", ignoreCase = true) -> BEARER
                    trimmed.startsWith("Basic ", ignoreCase = true) -> BASIC
                    McpServerConfig.KNOWN_AUTH_SCHEMES.any { trimmed.startsWith(it, ignoreCase = true) } -> CUSTOM
                    else -> BEARER
                }
            }
            return CUSTOM
        }
    }
}
