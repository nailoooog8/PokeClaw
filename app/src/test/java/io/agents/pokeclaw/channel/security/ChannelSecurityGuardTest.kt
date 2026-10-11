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
        assertFalse(ChannelSecurityGuard.isSenderWhitelisted("stranger_1001"))
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
        assertFalse(ChannelSecurityGuard.isSenderWhitelisted("wechat_user_1"))
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
        assertTrue(ChannelSecurityGuard.isSenderWhitelisted("qq_user_777"))

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
        assertTrue(ChannelSecurityGuard.isSenderWhitelisted("douyin_user_888"))

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
        ChannelSecurityGuard.addSenderToWhitelist("user_a")
        ChannelSecurityGuard.addSenderToWhitelist("user_b")
        assertEquals(setOf("user_a", "user_b"), ChannelSecurityGuard.getWhitelist())

        ChannelSecurityGuard.removeSenderFromWhitelist("user_a")
        assertEquals(setOf("user_b"), ChannelSecurityGuard.getWhitelist())
        assertFalse(ChannelSecurityGuard.isSenderWhitelisted("user_a"))
        assertTrue(ChannelSecurityGuard.isSenderWhitelisted("user_b"))

        ChannelSecurityGuard.clearWhitelist()
        assertTrue(ChannelSecurityGuard.getWhitelist().isEmpty())
    }
}
