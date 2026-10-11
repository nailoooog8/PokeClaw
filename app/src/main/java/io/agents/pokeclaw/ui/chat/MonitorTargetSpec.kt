// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

data class MonitorTargetSpec(
    val label: String,
    val app: String,
    val tone: String = "Casual",
) {
    val displayLabel: String
        get() = "$label on $app"

    companion object {
        val supportedApps = listOf("WhatsApp", "Telegram", "Messages", "LINE", "WeChat", "QQ", "Douyin")
    }
}

object MonitorTargetParser {
    fun fromTaskText(text: String): MonitorTargetSpec? {
        val app = extractApp(text)
        val label = extractLabel(text)
        if (label.isEmpty()) {
            return null
        }
        return MonitorTargetSpec(label = label, app = app)
    }

    private fun extractApp(text: String): String {
        val lower = text.lowercase()
        return when {
            Regex("""\btelegram\b""").containsMatchIn(lower) -> "Telegram"
            Regex("""\bwe\s*chat\b|微信""").containsMatchIn(lower) -> "WeChat"
            Regex("""\bqq\b""").containsMatchIn(lower) -> "QQ"
            Regex("""\b(douyin|tiktok)\b|抖音""").containsMatchIn(lower) -> "Douyin"
            Regex("""\bline\b""").containsMatchIn(lower) -> "LINE"
            Regex("""\b(messages|google messages|sms)\b|短信""").containsMatchIn(lower) -> "Messages"
            else -> "WhatsApp"
        }
    }

    private fun extractLabel(text: String): String {
        val lower = text.lowercase()
        var cleaned = lower
        val removeWords = listOf(
            "monitoring", "monitor", "auto-reply", "auto reply", "watching", "watch",
            "on whatsapp", "on telegram", "on messages", "on google messages", "on sms", "on wechat", "on line",
            "on qq", "on douyin", "on tiktok",
            "messages", "message", "'s", "'s", "for", "from",
            "please", "can you", "start", "enable", "begin", "help me",
            "监控", "监听", "自动回复", "回复", "在qq上", "在微信上", "在抖音上"
        )
        for (word in removeWords) {
            cleaned = cleaned.replace(word, " ")
        }
        cleaned = cleaned.replace(Regex("\\s+"), " ").trim()
        return if (cleaned.isNotEmpty()) cleaned.replaceFirstChar { it.uppercase() } else ""
    }
}
