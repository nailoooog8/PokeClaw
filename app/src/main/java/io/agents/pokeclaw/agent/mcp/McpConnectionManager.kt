// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.mcp

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.agents.pokeclaw.tool.ToolRegistry
import io.agents.pokeclaw.utils.SecretBox
import io.agents.pokeclaw.utils.XLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.Executors

/**
 * Loads MCP server configs, connects to each Streamable HTTP endpoint,
 * and registers discovered remote tools into the ToolRegistry.
 *
 * Config file: mcp_servers.json (JSON array of McpServerConfig) located in
 * the app's external files dir (preferred, adb-editable) or internal files dir.
 * The in-app MCP server manager UI writes through [saveConfigs]; the auth
 * header value is encrypted at rest with a Keystore key, so an adb-editable
 * file never carries the token in the clear.
 *
 * All ToolRegistry mutations are posted to the main thread: registration
 * happens once at app startup (main) and after async discovery (main),
 * so registry access stays serialized with Application.onCreate.
 */
object McpConnectionManager {

    enum class ServerState { DISABLED, CONNECTING, CONNECTED, FAILED }

    data class ServerStatus(
        val name: String,
        val state: ServerState,
        val toolCount: Int = 0,
        val error: String? = null,
        val updatedAt: Long = System.currentTimeMillis()
    )

    private const val TAG = "McpConnectionManager"
    private const val CONFIG_FILE = "mcp_servers.json"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newCachedThreadPool { r -> Thread(r, "mcp-connect") }

    @Volatile
    private var registeredCount = 0

    private val statusMap = LinkedHashMap<String, ServerStatus>()
    private val _statuses = MutableStateFlow<Map<String, ServerStatus>>(emptyMap())

    /** Per-server connection status, keyed by config name. Observed by the MCP manager UI. */
    val statuses: StateFlow<Map<String, ServerStatus>> = _statuses.asStateFlow()

    /** Called from ClawApplication.onCreate (main thread). Config read is fast; network is async. */
    @JvmStatic
    fun loadAndRegister(context: Context) {
        val app = context.applicationContext
        val configs = readConfigs(app)
        if (configs.isEmpty()) {
            XLog.i(TAG, "No MCP server configs found (mcp_servers.json absent or empty)")
            return
        }
        XLog.i(TAG, "Found ${configs.size} MCP server config(s), connecting asynchronously")
        connectAll(app, configs)
    }

    /** Re-reads configs and (re)connects all enabled servers. */
    @JvmStatic
    fun refresh(context: Context) {
        val app = context.applicationContext
        connectAll(app, readConfigs(app))
    }

    /** Reads the persisted config list without connecting. Used by the MCP manager UI. */
    @JvmStatic
    fun loadConfigs(context: Context): List<McpServerConfig> = readConfigs(context.applicationContext)

    /**
     * Persists configs to mcp_servers.json (external files dir preferred, internal fallback).
     *
     * The auth header value is encrypted with a Keystore-backed key before it
     * hits the disk, so the file stays adb-editable for name/url/enabled without
     * leaking the token to anyone who can read it. If encryption fails the save
     * fails — writing the token in the clear is worse than not writing at all.
     */
    @JvmStatic
    fun saveConfigs(context: Context, configs: List<McpServerConfig>): Boolean {
        val app = context.applicationContext
        val file = configFile(app) ?: return false
        val redacted = ArrayList<McpServerConfig>(configs.size)
        for (c in configs) {
            val secret = c.headerValue
            if (secret.isNullOrEmpty() || SecretBox.isEncrypted(secret)) {
                redacted.add(c)
                continue
            }
            val enc = SecretBox.encrypt(secret)
            if (enc == null) {
                XLog.e(TAG, "Refusing to save '${c.name}' with an unencrypted auth header")
                return false
            }
            redacted.add(c.copy(headerValue = enc))
        }
        return try {
            val gson: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
            file.writeText(gson.toJson(redacted))
            XLog.i(TAG, "Saved ${redacted.size} MCP server config(s) to ${file.absolutePath}")
            true
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to save MCP configs", e)
            false
        }
    }

    /** Removes a server: unregisters its tools on the main thread and marks it removed in status. */
    @JvmStatic
    fun removeServer(context: Context, config: McpServerConfig) {
        mainHandler.post {
            val removed = ToolRegistry.getInstance().unregisterByPrefix(toolPrefix(config))
            registeredCount = maxOf(0, registeredCount - removed)
            if (removed > 0) {
                XLog.i(TAG, "Unregistered $removed MCP tool(s) of '${config.name}'")
            }
        }
        synchronized(statusMap) { statusMap.remove(config.name) }
        publishStatuses()
    }

    /** (Re)connects one server: drops its previously registered tools first, then discovers anew. */
    @JvmStatic
    fun connectOne(context: Context, config: McpServerConfig) {
        val app = context.applicationContext
        mainHandler.post {
            val removed = ToolRegistry.getInstance().unregisterByPrefix(toolPrefix(config))
            if (removed > 0) registeredCount = maxOf(0, registeredCount - removed)
        }
        setStatus(ServerStatus(config.name, ServerState.CONNECTING))
        if (!config.enabled) {
            setStatus(ServerStatus(config.name, ServerState.DISABLED))
            return
        }
        executor.execute {
            connectAndRegister(app, config)
        }
    }

    private fun connectAll(app: Context, configs: List<McpServerConfig>) {
        for (config in configs) {
            setStatus(ServerStatus(config.name, if (config.enabled) ServerState.CONNECTING else ServerState.DISABLED))
            if (!config.enabled) {
                XLog.i(TAG, "MCP server '${config.name}' is disabled, skipping")
                continue
            }
            executor.execute {
                connectAndRegister(app, config)
            }
        }
    }

    private fun connectAndRegister(app: Context, config: McpServerConfig) {
        try {
            val client = McpStreamableHttpClient(config)
            client.initialize()
            val remoteTools = client.listTools()
            if (remoteTools.isEmpty()) {
                XLog.w(TAG, "MCP server '${config.name}' exposes no tools")
                setStatus(ServerStatus(config.name, ServerState.FAILED, 0, "Server exposes no tools"))
                return
            }
            mainHandler.post {
                var count = 0
                for (remote in remoteTools) {
                    try {
                        ToolRegistry.getInstance().register(McpTool(config, remote, client))
                        count++
                    } catch (e: Exception) {
                        XLog.e(TAG, "Failed to register MCP tool ${remote.name} from '${config.name}'", e)
                    }
                }
                registeredCount += count
                XLog.i(TAG, "Registered $count MCP tools from '${config.name}' (total MCP tools: $registeredCount, registry size: ${ToolRegistry.getInstance().getAllTools().size})")
                setStatus(ServerStatus(config.name, ServerState.CONNECTED, count))
            }
        } catch (e: Exception) {
            XLog.e(TAG, "MCP server '${config.name}' (${config.url}) connection failed: ${e.message}")
            setStatus(ServerStatus(config.name, ServerState.FAILED, 0, e.message ?: "Connection failed"))
        }
    }

    private fun toolPrefix(config: McpServerConfig): String = "mcp_${config.safeName()}_"

    private fun setStatus(status: ServerStatus) {
        synchronized(statusMap) { statusMap[status.name] = status }
        publishStatuses()
    }

    private fun publishStatuses() {
        val snapshot: Map<String, ServerStatus>
        synchronized(statusMap) { snapshot = LinkedHashMap(statusMap) }
        _statuses.value = snapshot
    }

    private fun configFile(app: Context): File? {
        return app.getExternalFilesDir(null)?.let { File(it, CONFIG_FILE) }
            ?: File(app.filesDir, CONFIG_FILE)
    }

    private fun readConfigs(app: Context): List<McpServerConfig> {
        val external = app.getExternalFilesDir(null)?.let { File(it, CONFIG_FILE) }
        val candidates = listOfNotNull(external, File(app.filesDir, CONFIG_FILE))
        for (f in candidates) {
            if (f.isFile && f.length() > 0) {
                val parsed = decryptSecrets(McpServerConfig.parseList(f.readText()))
                if (parsed.isNotEmpty()) {
                    XLog.i(TAG, "Loaded ${parsed.size} MCP server(s) from ${f.absolutePath}")
                    return parsed
                }
            }
        }
        return emptyList()
    }

    /**
     * Undoes the at-rest encryption from [saveConfigs]. Entries written by an
     * older build, or hand-edited into the file, carry a plaintext header and
     * pass through untouched. A server whose token cannot be decrypted is
     * dropped rather than kept with a ciphertext token that would only produce
     * a confusing 401 later.
     */
    private fun decryptSecrets(configs: List<McpServerConfig>): List<McpServerConfig> {
        val out = ArrayList<McpServerConfig>(configs.size)
        for (c in configs) {
            val stored = c.headerValue
            if (stored.isNullOrEmpty() || !SecretBox.isEncrypted(stored)) {
                out.add(c)
                continue
            }
            val plain = SecretBox.decrypt(stored)
            if (plain == null) {
                XLog.e(TAG, "Dropping MCP server '${c.name}': its auth header could not be decrypted")
                continue
            }
            out.add(c.copy(headerValue = plain))
        }
        return out
    }
}
