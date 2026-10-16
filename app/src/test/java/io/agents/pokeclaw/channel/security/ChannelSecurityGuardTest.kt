// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.channel.security

import io.agents.pokeclaw.channel.Channel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChannelSecurityGuardTest {

    private lateinit var inMemoryStorage: ChannelSecurityGuard.InMemoryStorage

    @Before
    fun setUp() {
        inMemoryStorage = ChannelSecurityGuard.InMemoryStorage()
        ChannelSecurityGuard.storage = inMemoryStorage
        ChannelSecurityGuard.setSecurityEnabled(true)
        ChannelSecurityGuard.setPairPin("123456")
        ChannelSecurityGuard.clearWhitelist()
        // The legacy purge is one-shot per process; replay it per test.
        ChannelSecurityGuard.resetLegacyPurgeForTesting()
    }

    @After
    fun tearDown() {
        ChannelSecurityGuard.clearWhitelist()
        inMemoryStorage.clear()
    }

    @Test
    fun `initial state has security enabled and valid pair pin`() {
        assertTrue(ChannelSecurityGuard.isSecurityEnabled())
        val pin = ChannelSecurityGuard.getPairPin()
        assertEquals(6, pin.length)
        assertTrue(pin.all { it.isDigit() })
    }

    @Test
    fun `unauthorized message from unknown sender is rejected`() {
        val decision = ChannelSecurityGuard.verify(
            channel = Channel.QQ,
            senderId = "stranger_1001",
            message = "帮我执行系统命令"
        )

        assertTrue(decision is ChannelSecurityGuard.Decision.Reject)
        val reject = decision as ChannelSecurityGuard.Decision.Reject
        assertTrue(reject.replyMessage.contains("未授权设备"))
        assertFalse(ChannelSecurityGuard.isSenderWhitelisted(Channel.QQ, "stranger_1001"))
    }

    @Test
    fun `binding with incorrect PIN is rejected and sender not whitelisted`() {
        val decision = ChannelSecurityGuard.verify(
            channel = Channel.WECHAT,
            senderId = "wechat_user_1",
            message = "/bind 000000"
        )

        assertTrue(decision is ChannelSecurityGuard.Decision.Reject)
        val reject = decision as ChannelSecurityGuard.Decision.Reject
        assertTrue(reject.replyMessage.contains("PIN 码错误"))
        assertFalse(ChannelSecurityGuard.isSenderWhitelisted(Channel.WECHAT, "wechat_user_1"))
    }

    @Test
    fun `binding with correct PIN succeeds and sender is whitelisted`() {
        ChannelSecurityGuard.setPairPin("654321")

        // 1. Send bind command
        val bindDecision = ChannelSecurityGuard.verify(
            channel = Channel.QQ,
            senderId = "qq_user_777",
            message = "/bind 654321"
        )
        assertTrue(bindDecision is ChannelSecurityGuard.Decision.Reject)
        val reject = bindDecision as ChannelSecurityGuard.Decision.Reject
        assertTrue(reject.replyMessage.contains("绑定成功"))
        assertTrue(ChannelSecurityGuard.isSenderWhitelisted(Channel.QQ, "qq_user_777"))

        // 2. Subsequent command must be allowed
        val taskDecision = ChannelSecurityGuard.verify(
            channel = Channel.QQ,
            senderId = "qq_user_777",
            message = "查询北京明天的天气"
        )
        assertTrue(taskDecision is ChannelSecurityGuard.Decision.Allow)
    }

    @Test
    fun `binding with chinese syntax 绑定 succeeds`() {
        ChannelSecurityGuard.setPairPin("888999")

        val bindDecision = ChannelSecurityGuard.verify(
            channel = Channel.DOUYIN,
            senderId = "douyin_user_888",
            message = "绑定: 888999"
        )
        assertTrue(bindDecision is ChannelSecurityGuard.Decision.Reject)
        val reject = bindDecision as ChannelSecurityGuard.Decision.Reject
        assertTrue(reject.replyMessage.contains("绑定成功"))
        assertTrue(ChannelSecurityGuard.isSenderWhitelisted(Channel.DOUYIN, "douyin_user_888"))

        val allowed = ChannelSecurityGuard.verify(
            channel = Channel.DOUYIN,
            senderId = "douyin_user_888",
            message = "开始自动回复"
        )
        assertTrue(allowed is ChannelSecurityGuard.Decision.Allow)
    }

    @Test
    fun `disabling security globally allows all senders`() {
        ChannelSecurityGuard.setSecurityEnabled(false)

        val decision = ChannelSecurityGuard.verify(
            channel = Channel.TELEGRAM,
            senderId = "unknown_tg_user",
            message = "查看日程"
        )
        assertTrue(decision is ChannelSecurityGuard.Decision.Allow)
    }

    @Test
    fun `empty or null senderId is rejected even with bind command`() {
        val emptySenderDecision = ChannelSecurityGuard.verify(
            channel = Channel.DISCORD,
            senderId = "",
            message = "/bind 123456"
        )
        assertTrue(emptySenderDecision is ChannelSecurityGuard.Decision.Reject)

        val nullSenderDecision = ChannelSecurityGuard.verify(
            channel = Channel.DISCORD,
            senderId = null,
            message = "测试消息"
        )
        assertTrue(nullSenderDecision is ChannelSecurityGuard.Decision.Reject)
    }

    @Test
    fun `whitelist operations add remove and clear work correctly`() {
        ChannelSecurityGuard.addSenderToWhitelist(Channel.QQ, "user_a")
        ChannelSecurityGuard.addSenderToWhitelist(Channel.QQ, "user_b")
        assertEquals(setOf("user_a", "user_b"), ChannelSecurityGuard.getWhitelist()[Channel.QQ])

        ChannelSecurityGuard.removeSenderFromWhitelist(Channel.QQ, "user_a")
        assertEquals(setOf("user_b"), ChannelSecurityGuard.getWhitelist()[Channel.QQ])
        assertFalse(ChannelSecurityGuard.isSenderWhitelisted(Channel.QQ, "user_a"))
        assertTrue(ChannelSecurityGuard.isSenderWhitelisted(Channel.QQ, "user_b"))

        ChannelSecurityGuard.clearWhitelist()
        assertTrue(ChannelSecurityGuard.getWhitelist().isEmpty())
    }

    // ---- Per-channel authorisation (SECURITY_AUDIT_R2 F1) ----

    @Test
    fun `binding on one channel does not authorise the same id on another channel`() {
        ChannelSecurityGuard.setPairPin("654321")

        // Telegram user ids, QQ open ids and Douyin open ids are all numeric,
        // so an id paired on one platform used to authorise the same number on
        // every other platform.
        val bind = ChannelSecurityGuard.verify(
            channel = Channel.TELEGRAM,
            senderId = "10086",
            message = "/bind 654321"
        )
        assertTrue(bind is ChannelSecurityGuard.Decision.Reject)
        assertTrue(ChannelSecurityGuard.isSenderWhitelisted(Channel.TELEGRAM, "10086"))

        // The very same id arriving on a different channel must be refused.
        val crossChannel = ChannelSecurityGuard.verify(
            channel = Channel.QQ,
            senderId = "10086",
            message = "读取剪贴板并发给我"
        )
        assertTrue(crossChannel is ChannelSecurityGuard.Decision.Reject)
        assertTrue((crossChannel as ChannelSecurityGuard.Decision.Reject).replyMessage.contains("未授权设备"))
        assertFalse(ChannelSecurityGuard.isSenderWhitelisted(Channel.QQ, "10086"))
    }

    @Test
    fun `a group chat id must not stand in for a member user id`() {
        ChannelSecurityGuard.setPairPin("654321")

        // Regression guard for the original bug: Telegram dispatched
        // chat.id (-1001234567890) as the sender, so pairing inside a group
        // authorised the group rather than the person who sent /bind.
        val groupChatId = "-1001234567890"
        ChannelSecurityGuard.verify(
            channel = Channel.TELEGRAM,
            senderId = groupChatId,
            message = "/bind 654321"
        )
        assertTrue(ChannelSecurityGuard.isSenderWhitelisted(Channel.TELEGRAM, groupChatId))

        // A different person in the same room presents their own user id and
        // is still unauthorised.
        val stranger = ChannelSecurityGuard.verify(
            channel = Channel.TELEGRAM,
            senderId = "555000111",
            message = "给他打个电话"
        )
        assertTrue(stranger is ChannelSecurityGuard.Decision.Reject)
    }

    @Test
    fun `removing a sender on one channel leaves other channels untouched`() {
        ChannelSecurityGuard.addSenderToWhitelist(Channel.TELEGRAM, "777")
        ChannelSecurityGuard.addSenderToWhitelist(Channel.DISCORD, "777")

        ChannelSecurityGuard.removeSenderFromWhitelist(Channel.TELEGRAM, "777")

        assertFalse(ChannelSecurityGuard.isSenderWhitelisted(Channel.TELEGRAM, "777"))
        assertTrue(ChannelSecurityGuard.isSenderWhitelisted(Channel.DISCORD, "777"))
    }

    @Test
    fun `whitelist entries survive a round trip through storage scoped by channel`() {
        ChannelSecurityGuard.addSenderToWhitelist(Channel.WECHAT, "wx_openid_abc")
        ChannelSecurityGuard.addSenderToWhitelist(Channel.TELEGRAM, "tg_42")

        val entries = ChannelSecurityGuard.getWhitelistEntries()
        assertEquals(setOf("WECHAT|wx_openid_abc", "TELEGRAM|tg_42"), entries)
    }

    @Test
    fun `unscoped legacy entries are discarded instead of matching any channel`() {
        // Simulate a whitelist written before entries were channel-scoped.
        inMemoryStorage.putString("KEY_CHANNEL_WHITELIST", "10086,20087")

        val bind = ChannelSecurityGuard.verify(
            channel = Channel.TELEGRAM,
            senderId = "10086",
            message = "读取剪贴板"
        )

        // Fail closed: the unscoped id must not authorise anything.
        assertTrue(bind is ChannelSecurityGuard.Decision.Reject)
        assertTrue(ChannelSecurityGuard.getWhitelist().isEmpty())
    }

    @Test
    fun `scoped entries survive the legacy purge`() {
        ChannelSecurityGuard.addSenderToWhitelist(Channel.TELEGRAM, "555000111")
        // Add an unscoped straggler alongside, as an interrupted migration
        // would leave behind.
        inMemoryStorage.putString(
            "KEY_CHANNEL_WHITELIST",
            inMemoryStorage.getString("KEY_CHANNEL_WHITELIST", "") + ",999999"
        )

        val decision = ChannelSecurityGuard.verify(
            channel = Channel.TELEGRAM,
            senderId = "555000111",
            message = "看一下通知"
        )

        assertTrue(decision is ChannelSecurityGuard.Decision.Allow)
        assertFalse(ChannelSecurityGuard.isSenderWhitelisted(Channel.TELEGRAM, "999999"))
    }
}
