// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.memory

import io.agents.pokeclaw.tool.BaseTool
import io.agents.pokeclaw.tool.ToolParameter
import io.agents.pokeclaw.tool.ToolResult

/**
 * Searches the memory bank by topic — the "what do you remember about X"
 * tool. Recalled entries are reinforced, so the ones that keep proving useful
 * stay near the top of future briefings.
 */
class MemoryRecallTool : BaseTool() {

    override fun getName() = "memory_recall"

    override fun getDescriptionEN() =
        "Search the long-term memory bank by topic or keyword. Returns the most relevant remembered " +
            "facts (identity, preferences, device/account facts, past outcomes). " +
            "A blank query lists the most relevant recent and pinned memories."

    override fun getDescriptionCN() =
        "按主题或关键词检索长期记忆库，返回最相关的已记住事实（身份、偏好、设备/账号信息、" +
            "过往结果）。query 留空则返回最近与置顶的记忆。"

    override fun getParameters() = listOf(
        ToolParameter(
            "query", "string",
            "Topic or keyword to search for, e.g. '悬浮窗 权限' or '用户的偏好'. Blank = recent + pinned.",
            false
        ),
        ToolParameter(
            "limit", "integer",
            "Maximum number of memories to return (1-25, default 5)",
            false
        ),
        ToolParameter(
            "kind", "string",
            "Filter by category: profile | preference | fact | event (default: no filter)",
            false
        )
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        return try {
            val query = optionalString(params, "query", "")
            val limit = optionalInt(params, "limit", 5)
            val kind = optionalString(params, "kind", "").takeIf { it.isNotBlank() }

            val total = MemoryStore.count()
            if (total == 0) {
                return ToolResult.success("记忆库还是空的（0 条）。")
            }

            val results = MemoryStore.recall(query, limit, kind)
            if (results.isEmpty()) {
                return ToolResult.success(
                    "记忆库里没有匹配「${query.ifBlank { "全部" }}」的内容（共 $total 条）。"
                )
            }
            val body = results.joinToString("\n") { r ->
                "- [${r.entry.kindLabel}] ${r.entry.content}  (命中 ${r.entry.hits + 1} 次)"
            }
            ToolResult.success("记忆检索「${query.ifBlank { "全部" }}」：\n$body")
        } catch (e: Exception) {
            ToolResult.error("memory_recall error: ${e.message}")
        }
    }
}