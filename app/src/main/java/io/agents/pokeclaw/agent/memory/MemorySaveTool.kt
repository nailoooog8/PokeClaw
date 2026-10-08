// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.memory

import io.agents.pokeclaw.tool.BaseTool
import io.agents.pokeclaw.tool.ToolParameter
import io.agents.pokeclaw.tool.ToolResult

/**
 * Stores one durable fact into the long-term memory bank.
 *
 * The agent is expected to call this on its own (the system prompt briefing
 * spells out when), so the user never has to maintain the bank by hand.
 */
class MemorySaveTool : BaseTool() {

    override fun getName() = "memory_save"

    override fun getDescriptionEN() =
        "Remember one durable fact across sessions. Use it when you learn who the user is, how they " +
            "prefer things done, facts about their device/accounts, a mistake worth not repeating, or " +
            "the outcome of an important task. Store ONE fact per call, as a self-contained sentence. " +
            "Do NOT store transient state (what is on screen, today's to-dos, temporary errors). " +
            "Near-duplicates are merged automatically. If the fact contradicts something already " +
            "stored, or restates something the user asked you to forget — including the same claim " +
            "with a different value — the result says so. Report that to the user instead of retrying; " +
            "if they do want it back, they can restore it under Settings > Tools > Memory bank."

    override fun getDescriptionCN() =
        "把一条值得长期记住的事存入记忆库，跨会话保留。适用于：了解到的用户身份与习惯偏好、" +
            "设备/账号相关事实、踩过的坑、重要任务的结果。每次只存一条，写成能独立成立的短句。" +
            "不要存临时状态（当前屏幕内容、今天的待办、偶发报错）。相近的内容会自动合并。" +
            "若与已有记忆矛盾、或与用户让你忘掉的内容相似（包括同一说法换了值：次数变了、型号变了），" +
            "返回结果会明说——请转告用户，不要重试；用户确实想加回来的，可在 设置 → 工具 → 记忆库 " +
            "的「遗忘抑制」里自行恢复。"

    override fun getParameters() = listOf(
        ToolParameter(
            "content", "string",
            "The fact to remember, one self-contained sentence, e.g. '用户的通勤方式是地铁，单程约 40 分钟'",
            true
        ),
        ToolParameter(
            "kind", "string",
            "Category: profile (who the user is) | preference (how they like things) | fact (device/account/environment facts) | event (something that happened and matters later). Default: fact",
            false
        ),
        ToolParameter(
            "tags", "string",
            "Comma-separated labels, e.g. 'device,permissions'",
            false
        )
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        return try {
            if (!MemoryStore.isEnabled()) {
                return ToolResult.success(
                    "记忆库当前已关闭，本次没有保存。可在 设置 → 工具 → 记忆库 重新开启。"
                )
            }
            val content = requireString(params, "content")
            val kind = optionalString(params, "kind", MemoryKind.FACT)
            val tags = optionalString(params, "tags", "")
                .split(",").map { it.trim() }.filter { it.isNotEmpty() }

            val entry = MemoryStore.remember(content, kind, tags, fromAgent = true)
            val sb = StringBuilder()
            when (entry) {
                is MemoryStore.RememberResult.Suppressed -> {
                    return ToolResult.success(
                        "没有保存：这条内容和用户此前让你忘掉的内容太像（已删除的原文：「${entry.trace.text}」）。" +
                            "如果用户现在明确要求你重新记住，请告诉用户可以在设置 → 工具 → 记忆库里手动添加。"
                    )
                }

                is MemoryStore.RememberResult.Stored -> {
                    if (entry.merged) {
                        sb.append("已更新已有记忆 [${entry.entry.kindLabel}] ${entry.entry.content}")
                    } else {
                        sb.append("已记住 [${entry.entry.kindLabel}] ${entry.entry.content}")
                    }
                    if (entry.conflicts.isNotEmpty()) {
                        sb.append("\n⚠ 注意，这条与已有记忆矛盾：")
                        for (c in entry.conflicts) sb.append("\n  - ${c.content}")
                        sb.append("\n两条都已标记冲突，向用户确认哪个是对的，不要默默选一条。")
                    }
                    sb.append("\n（共 ${MemoryStore.count()} 条）")
                }
            }
            ToolResult.success(sb.toString())
        } catch (e: IllegalArgumentException) {
            ToolResult.error("memory_save: missing required param — ${e.message}")
        } catch (e: Exception) {
            ToolResult.error("memory_save error: ${e.message}")
        }
    }
}