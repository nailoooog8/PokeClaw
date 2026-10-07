// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.mcp

import com.google.gson.JsonObject
import io.agents.pokeclaw.tool.BaseTool
import io.agents.pokeclaw.tool.ToolParameter
import io.agents.pokeclaw.tool.ToolResult

/**
 * A remote MCP tool wrapped as a PokeClaw BaseTool so it participates in
 * ToolRegistry scheduling exactly like built-in tools.
 */
class McpTool(
    private val server: McpServerConfig,
    private val remote: McpStreamableHttpClient.McpRemoteTool,
    private val client: McpStreamableHttpClient
) : BaseTool() {

    companion object {
        /** JSON Schema type -> PokeClaw ToolParameter type */
        private fun mapType(schemaType: String?): String = when (schemaType) {
            "integer" -> "integer"
            "number" -> "number"
            "boolean" -> "boolean"
            else -> "string" // string, arrays and objects are passed as serialized strings
        }

        private fun schemaToParameters(schema: JsonObject?): List<ToolParameter> {
            if (schema == null) return emptyList()
            val properties = schema.getAsJsonObject("properties") ?: return emptyList()
            val required: Set<String> = schema.getAsJsonArray("required")
                ?.mapNotNull { (it as? com.google.gson.JsonPrimitive)?.takeIf { p -> p.isString }?.asString }
                ?.toSet() ?: emptySet()
            val out = ArrayList<ToolParameter>(properties.size())
            for (entry in properties.entrySet()) {
                val paramName = entry.key
                val def = entry.value.asJsonObject
                out.add(
                    ToolParameter(
                        name = paramName,
                        type = mapType(def.get("type")?.takeIf { !it.isJsonNull }?.asString),
                        description = def.get("description")?.takeIf { !it.isJsonNull }?.asString ?: "",
                        isRequired = paramName in required
                    )
                )
            }
            return out
        }
    }

    private val registeredName: String = "mcp_${server.safeName()}_${remote.name.replace(Regex("[^a-zA-Z0-9_]"), "_")}"
    private val mappedParams: List<ToolParameter> = schemaToParameters(remote.inputSchema)

    override fun getName(): String = registeredName

    override fun getParameters(): List<ToolParameter> = mappedParams

    override fun getDescriptionEN(): String =
        "[MCP:${server.name}] ${remote.description ?: "Remote tool ${remote.name}"}"

    override fun getDescriptionCN(): String =
        "［MCP:${server.name}］${remote.description ?: "远程工具 ${remote.name}"}（来自 MCP 服务器 ${server.name}）"

    override fun execute(params: Map<String, Any>): ToolResult {
        return try {
            ToolResult.success(client.callTool(remote.name, params))
        } catch (e: Exception) {
            ToolResult.error("MCP tool '${remote.name}' failed: ${e.message}")
        }
    }
}
