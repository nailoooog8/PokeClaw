// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool

import io.agents.pokeclaw.channel.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the second defence layer added in SECURITY_AUDIT_R2 F2: tools that act
 * on the world are refused for tasks that arrive from a channel, where nobody
 * is watching the screen to abort.
 */
class ToolRiskPolicyTest {

    // ---- Risk classification ----

    @Test
    fun `outward facing tools are high impact`() {
        val highImpact = listOf(
            "make_call",
            "send_message",
            "send_file",
            "auto_reply",
            "input_text",
            "system_key",
            "clipboard",
        )
        for (name in highImpact) {
            assertEquals(
                "$name should be HIGH_IMPACT",
                ToolRiskPolicy.Risk.HIGH_IMPACT,
                ToolRiskPolicy.riskOf(name),
            )
        }
    }

    @Test
    fun `observation tools are read only`() {
        val readOnly = listOf(
            "wait",
            "finish",
            "get_screen_info",
            "get_notifications",
            "get_device_info",
            "get_installed_apps",
            "take_screenshot",
            "kb_read",
            "kb_search",
            "memory_recall",
        )
        for (name in readOnly) {
            assertEquals(
                "$name should be READ_ONLY",
                ToolRiskPolicy.Risk.READ_ONLY,
                ToolRiskPolicy.riskOf(name),
            )
        }
    }

    @Test
    fun `an unclassified tool defaults to high impact`() {
        // Fail closed: a tool nobody classified must not become remotely
        // reachable just by being added to the registry.
        assertEquals(
            ToolRiskPolicy.Risk.HIGH_IMPACT,
            ToolRiskPolicy.riskOf("some_future_tool"),
        )
    }

    @Test
    fun `remote mcp tools are treated as high impact`() {
        // MCP servers are third parties; their tools can do anything the
        // server decides, so they never get a remote-source exemption.
        assertEquals(
            ToolRiskPolicy.Risk.HIGH_IMPACT,
            ToolRiskPolicy.riskOf("mcp_my_server_do_anything"),
        )
    }

    @Test
    fun `blank tool name is high impact`() {
        assertEquals(ToolRiskPolicy.Risk.HIGH_IMPACT, ToolRiskPolicy.riskOf(""))
        assertEquals(ToolRiskPolicy.Risk.HIGH_IMPACT, ToolRiskPolicy.riskOf("   "))
    }

    // ---- Source gating ----

    @Test
    fun `high impact tools are blocked for every remote channel`() {
        val remote = listOf(
            Channel.TELEGRAM,
            Channel.DISCORD,
            Channel.WECHAT,
            Channel.QQ,
            Channel.DOUYIN,
        )
        for (channel in remote) {
            assertTrue(
                "make_call must be blocked on $channel",
                ToolRiskPolicy.isBlockedForChannel("make_call", channel),
            )
            assertTrue(
                "system_key must be blocked on $channel",
                ToolRiskPolicy.isBlockedForChannel("system_key", channel),
            )
        }
    }

    @Test
    fun `read only tools stay available to remote channels`() {
        assertFalse(ToolRiskPolicy.isBlockedForChannel("get_screen_info", Channel.TELEGRAM))
        assertFalse(ToolRiskPolicy.isBlockedForChannel("get_notifications", Channel.QQ))
        assertFalse(ToolRiskPolicy.isBlockedForChannel("memory_recall", Channel.DISCORD))
    }

    @Test
    fun `local tasks keep every tool`() {
        // The app's own UI is the one place a human is present, so the gate
        // must not narrow what the user can ask for by hand.
        assertFalse(ToolRiskPolicy.isBlockedForChannel("make_call", Channel.LOCAL))
        assertFalse(ToolRiskPolicy.isBlockedForChannel("input_text", Channel.LOCAL))
        assertFalse(ToolRiskPolicy.isBlockedForChannel("system_key", Channel.LOCAL))
    }

    @Test
    fun `a null source channel is treated as local`() {
        // Happens before any task session is recorded; refusing here would
        // break the app's own launch path.
        assertFalse(ToolRiskPolicy.isBlockedForChannel("make_call", null))
        assertFalse(ToolRiskPolicy.isBlockedForChannel("send_message", null))
    }

    @Test
    fun `block reason names the tool and the source`() {
        val reason = ToolRiskPolicy.blockReason("make_call", Channel.TELEGRAM)
        assertTrue(reason.contains("make_call"))
        assertTrue(reason.contains("Telegram"))
    }
}
