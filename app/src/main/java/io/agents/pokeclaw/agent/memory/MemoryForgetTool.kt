// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.memory

import io.agents.pokeclaw.tool.BaseTool
import io.agents.pokeclaw.tool.ToolParameter
import io.agents.pokeclaw.tool.ToolResult

/**
 * Removes memories that turned out to be wrong or obsolete.
 *
 * Deleting leaves a suppression fingerprint, so the same fact does not creep
 * back in a few conversations later — forgetting is inhibition, not just
 * deletion. Content matching is exact-substring on purpose: the model should not
 * be able to wipe the whole bank with a vague word, and the management UI
 * remains the blunt instrument.
 */
class MemoryForgetTool : BaseTool() {

    override fun getName() = "memory_forget"

    override fun getDescriptionEN() =
        "Delete memories that are wrong or no longer true. Match by exact id or by a phrase contained " +
            "in the remembered text. Deleting also records a suppression fingerprint, so similar " +
            "facts will no longer be saved on their own later."

    override fun getDescriptionCN() =
        "删除错误或已过时的记忆。可用能唯一命中记忆原文的短语，或用 id 精确删除。返回实际删除的条数。"

    override fun getParameters() = listOf(
        ToolParameter(
            "query", "string",
            "A phrase contained in the memory text to delete, or an exact memory id",
            true
        )
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        return try {
            val query = requireString(params, "query").trim()
            if (query.isEmpty()) return ToolResult.error("memory_forget: query is empty")

            if (MemoryStore.delete(query)) {
                return ToolResult.success(
                    "已按 id 删除 1 条记忆，剩余 ${MemoryStore.count()} 条。" +
                        "已记下抑制指纹：以后相似的内容不会被自动保存（用户可在记忆库清除）。"
                )
            }
            val removed = MemoryStore.deleteByContent(query)
            if (removed == 0) {
                ToolResult.success("没有匹配「$query」的记忆，未删除任何内容。")
            } else {
                ToolResult.success(
                    "已删除 $removed 条包含「$query」的记忆，剩余 ${MemoryStore.count()} 条。" +
                        "已记下抑制指纹：以后相似的内容不会被自动保存。"
                )
            }
        } catch (e: IllegalArgumentException) {
            ToolResult.error("memory_forget: missing required param — ${e.message}")
        } catch (e: Exception) {
            ToolResult.error("memory_forget error: ${e.message}")
        }
    }
}