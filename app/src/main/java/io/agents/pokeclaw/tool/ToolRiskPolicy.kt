// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool

import io.agents.pokeclaw.channel.Channel

/**
 * Central risk policy for tool execution.
 *
 * PokeClaw can actuate the phone on the user's behalf: place calls, type into
 * whatever app is focused, press the unlock key, send messages. Before this
 * policy existed there was no gate between "the model decided to call it" and
 * "the phone did it" — every tool ran, always, for every task source.
 *
 * That matters because a task can arrive from a source the user is not
 * watching. A Telegram message, an automation broadcast from another app, or a
 * scheduled task all reach the same agent loop as a tap in the app, but they
 * have no human in front of the screen to abort on. Authorisation for those
 * sources is checked upstream in [io.agents.pokeclaw.channel.security.ChannelSecurityGuard];
 * this policy is the second, independent layer that assumes that check was
 * wrong, stale, or bypassed.
 *
 * Design rule: **fail closed**. A tool that is not explicitly classified is
 * treated as high impact. New tools therefore start blocked for remote
 * sources until someone classifies them, instead of silently inheriting
 * unrestricted access.
 *
 * Deliberately not doing here: a user-facing per-tool confirmation dialog.
 * A remote task has no foreground activity to show a dialog on, and adding one
 * only for the local path would create a false sense of coverage. See
 * `docs/SECURITY_AUDIT_R2.md` F2 for the longer discussion.
 */
object ToolRiskPolicy {

    /**
     * How much damage a tool can do if it runs without a human watching.
     */
    enum class Risk {
        /** Observes state. No outward effect; worst case is disclosure. */
        READ_ONLY,

        /** Acts on the world: sends, dials, types, unlocks, or changes stored behaviour. */
        HIGH_IMPACT,
    }

    /**
     * Tools classified as [Risk.HIGH_IMPACT]. Everything not listed here is
     * [Risk.READ_ONLY] — but see the fail-closed note on unknown tools below.
     */
    private val HIGH_IMPACT_TOOLS: Set<String> = setOf(
        // Outward-facing: another human receives the result.
        "make_call",
        "send_message",
        "send_file",
        "auto_reply",
        // Acts on whatever happens to be focused, including the lock screen.
        "input_text",
        "system_key",
        // Reads AND writes the clipboard; the read side alone is a live
        // secret (one-time codes, passwords just copied) so both are gated.
        "clipboard",
    )

    /**
     * Tools that are safe to call from any source.
     *
     * This is an allowlist, not a denylist, so that adding a new tool cannot
     * accidentally expose it to remote control.
     */
    private val READ_ONLY_TOOLS: Set<String> = setOf(
        "wait",
        "finish",
        "get_screen_info",
        "get_notifications",
        "find_node_info",
        "get_device_info",
        "get_installed_apps",
        "take_screenshot",
        "scroll_to_find",
        "find_and_tap",
        "search_app_in_store",
        "kb_read",
        "kb_search",
        "memory_recall",
    )

    /**
     * Risk class of [toolName]. Unknown tools are [Risk.HIGH_IMPACT].
     */
    fun riskOf(toolName: String): Risk {
        val name = toolName.trim()
        if (name in READ_ONLY_TOOLS) return Risk.READ_ONLY
        // High-impact names stay high impact even if also listed above.
        if (name in HIGH_IMPACT_TOOLS) return Risk.HIGH_IMPACT
        // Knowledge-base writes and memory writes mutate persistent state that
        // a later task will trust; treat as high impact explicitly.
        if (name.startsWith("mcp_")) return Risk.HIGH_IMPACT
        return Risk.HIGH_IMPACT
    }

    /**
     * True when [toolName] must not run for a task arriving over [channel].
     *
     * A `null` channel means the task is not associated with a remote source.
     * That happens for a task started from the app before any session is
     * recorded, so it is treated as interactive and allowed — refusing here
     * would break the app's own UI, which is the one place a human is present.
     */
    fun isBlockedForChannel(toolName: String, channel: Channel?): Boolean {
        if (channel == null || channel == Channel.LOCAL) return false
        return riskOf(toolName) == Risk.HIGH_IMPACT
    }

    /**
     * Message shown to the model and echoed to the requesting channel when a
     * tool is refused. Kept explicit so the failure is diagnosable from the
     * transcript instead of looking like a transient tool error.
     */
    fun blockReason(toolName: String, channel: Channel?): String {
        val source = channel?.displayName ?: "Local"
        return "Tool '$toolName' is blocked for tasks arriving from $source. " +
            "It can act on the phone irreversibly (send, dial, type, unlock), and a " +
            "task from $source has no one watching the screen to stop it. " +
            "Run this from the PokeClaw app instead."
    }
}
