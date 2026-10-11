// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.channel.security

import com.tencent.mmkv.MMKV
import io.agents.pokeclaw.channel.Channel
import io.agents.pokeclaw.utils.BruteForceGuard
import io.agents.pokeclaw.utils.XLog
import java.util.regex.Pattern

/**
 * Channel security guard: First line of defense against unauthenticated RCE via external channels.
 * Verifies message senders against a whitelist and handles PIN pairing (/bind <PIN> / 绑定 <PIN>).
 */
object ChannelSecurityGuard {

    private const val TAG = "ChannelSecurityGuard"

    private const val KEY_CHANNEL_SECURITY_ENABLED = "KEY_CHANNEL_SECURITY_ENABLED"
    private const val KEY_CHANNEL_PAIR_PIN = "KEY_CHANNEL_PAIR_PIN"
    private const val KEY_CHANNEL_WHITELIST = "KEY_CHANNEL_WHITELIST"

    interface Storage {
        fun getBool(key: String, defaultValue: Boolean): Boolean
        fun putBool(key: String, value: Boolean)
        fun getString(key: String, defaultValue: String): String
        fun putString(key: String, value: String)
        fun remove(key: String)
    }

    class MmkvStorage : Storage {
        private val mmkv: MMKV by lazy { MMKV.defaultMMKV() }
        override fun getBool(key: String, defaultValue: Boolean): Boolean = mmkv.decodeBool(key, defaultValue)
        override fun putBool(key: String, value: Boolean) { mmkv.encode(key, value) }
        override fun getString(key: String, defaultValue: String): String = mmkv.decodeString(key, defaultValue) ?: defaultValue
        override fun putString(key: String, value: String) { mmkv.encode(key, value) }
        override fun remove(key: String) { mmkv.removeValueForKey(key) }
    }

    class InMemoryStorage : Storage {
        private val map = mutableMapOf<String, Any>()
        override fun getBool(key: String, defaultValue: Boolean): Boolean = (map[key] as? Boolean) ?: defaultValue
        override fun putBool(key: String, value: Boolean) { map[key] = value }
        override fun getString(key: String, defaultValue: String): String = (map[key] as? String) ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        fun clear() { map.clear() }
    }

    @Volatile
    var storage: Storage = MmkvStorage()

    private val BIND_PATTERN = Pattern.compile(
        """^(?:/bind|绑定)\s*[:：]?\s*(\S+)$""",
        Pattern.CASE_INSENSITIVE
    )

    sealed class Decision {
        object Allow : Decision()
        data class Reject(val replyMessage: String) : Decision()
    }

    /**
     * Check whether global channel security verification is enabled (default: true).
     */
    fun isSecurityEnabled(): Boolean {
        return storage.getBool(KEY_CHANNEL_SECURITY_ENABLED, true)
    }

    fun setSecurityEnabled(enabled: Boolean) {
        storage.putBool(KEY_CHANNEL_SECURITY_ENABLED, enabled)
    }

    /**
     * Get or generate the 6-digit channel pairing PIN.
     */
    @Synchronized
    fun getPairPin(): String {
        var pin = storage.getString(KEY_CHANNEL_PAIR_PIN, "")
        if (pin.isEmpty()) {
            pin = (100000..999999).random().toString()
            storage.putString(KEY_CHANNEL_PAIR_PIN, pin)
        }
        return pin
    }

    @Synchronized
    fun setPairPin(pin: String) {
        storage.putString(KEY_CHANNEL_PAIR_PIN, pin.trim())
    }

    /**
     * Get current whitelisted sender IDs.
     */
    @Synchronized
    fun getWhitelist(): Set<String> {
        val raw = storage.getString(KEY_CHANNEL_WHITELIST, "")
        if (raw.isEmpty()) return emptySet()
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    /**
     * Add a sender ID to whitelist.
     */
    @Synchronized
    fun addSenderToWhitelist(senderId: String) {
        if (senderId.isBlank()) return
        val current = getWhitelist().toMutableSet()
        current.add(senderId.trim())
        storage.putString(KEY_CHANNEL_WHITELIST, current.joinToString(","))
        XLog.i(TAG, "Added sender to whitelist: ${senderId.trim()}")
    }

    /**
     * Remove a sender ID from whitelist.
     */
    @Synchronized
    fun removeSenderFromWhitelist(senderId: String) {
        val current = getWhitelist().toMutableSet()
        val removed = current.remove(senderId.trim())
        if (removed) {
            storage.putString(KEY_CHANNEL_WHITELIST, current.joinToString(","))
            XLog.i(TAG, "Removed sender from whitelist: ${senderId.trim()}")
        }
    }

    /**
     * Check whether a sender ID is whitelisted.
     */
    fun isSenderWhitelisted(senderId: String): Boolean {
        if (senderId.isBlank()) return false
        return getWhitelist().contains(senderId.trim())
    }

    /**
     * Clear whitelist.
     */
    @Synchronized
    fun clearWhitelist() {
        storage.remove(KEY_CHANNEL_WHITELIST)
    }

    /**
     * Verify whether an incoming message from the specified channel and sender is authorized.
     *
     * @param channel The incoming channel (Telegram, Discord, WeChat, QQ, Douyin)
     * @param senderId The unique user identifier from the channel (e.g. Telegram chatId/userId, WeChat fromUserId, QQ userId)
     * @param message Content of the message
     * @return [Decision.Allow] if the message should proceed to TaskOrchestrator, or [Decision.Reject] with a reply message
     */
    fun verify(channel: Channel, senderId: String?, message: String): Decision {
        // 1. If global security verification is disabled, allow
        if (!isSecurityEnabled()) {
            XLog.d(TAG, "Channel security check is disabled globally, allowing request on ${channel.displayName}")
            return Decision.Allow
        }

        val trimmedSenderId = senderId?.trim() ?: ""
        val trimmedMessage = message.trim()

        // 2. Check for bind / pair command: /bind <PIN> or 绑定 <PIN>
        val bindMatcher = BIND_PATTERN.matcher(trimmedMessage)
        if (bindMatcher.matches()) {
            val inputPin = bindMatcher.group(1)?.trim() ?: ""
            val expectedPin = getPairPin().trim()

            if (trimmedSenderId.isEmpty()) {
                XLog.w(TAG, "Binding rejected: missing senderId on ${channel.displayName}")
                return Decision.Reject("绑定失败：无法识别发送者身份。")
            }

            // The pairing PIN is only 6 digits. Without a lockout an attacker
            // scripts 10^6 messages from any bot account and takes the device
            // over, so a sender that keeps guessing is cut off and backed off.
            val guardKey = "${channel.name}:$trimmedSenderId"
            if (BruteForceGuard.isLocked(guardKey)) {
                val seconds = BruteForceGuard.lockoutRemainingMs(guardKey) / 1000
                return Decision.Reject(
                    "配对尝试过于频繁，已临时锁定。请 $seconds 秒后再试，或在手机 PokeClaw 设置中重置配对码。"
                )
            }

            return if (inputPin.isNotEmpty() &&
                BruteForceGuard.constantTimeEquals(inputPin, expectedPin)
            ) {
                BruteForceGuard.onSuccess(guardKey)
                addSenderToWhitelist(trimmedSenderId)
                XLog.i(TAG, "Sender $trimmedSenderId successfully paired on ${channel.displayName}")
                Decision.Reject("绑定成功！设备已授权，可正常接收与执行指令。")
            } else {
                val lockoutMs = BruteForceGuard.onFailure(guardKey)
                XLog.w(TAG, "Sender pairing failed on ${channel.displayName} senderId=$trimmedSenderId lockoutMs=$lockoutMs")
                if (lockoutMs > 0) {
                    Decision.Reject(
                        "PIN 码错误次数过多，已临时锁定 ${lockoutMs / 1000} 秒。" +
                            "如非本人操作，请到手机 PokeClaw 设置中重置配对码。"
                    )
                } else {
                    Decision.Reject("PIN 码错误，绑定失败。请在手机 PokeClaw 设置中查看配对码后重试。")
                }
            }
        }

        // 3. Reject if senderId is empty
        if (trimmedSenderId.isEmpty()) {
            XLog.w(TAG, "Unauthorized request with empty senderId on ${channel.displayName}")
            return Decision.Reject("未授权设备。请在手机 PokeClaw 设置中查看配对码，并发送 /bind <配对码> 绑定")
        }

        // 4. Check whitelist
        if (isSenderWhitelisted(trimmedSenderId)) {
            return Decision.Allow
        }

        // 5. Unauthorized and not a bind command: reject, log warning, and resolutely DO NOT call TaskOrchestrator!
        XLog.w(
            TAG,
            "Security ALERT: Unauthorized message rejected from sender=$trimmedSenderId on ${channel.displayName}: ${trimmedMessage.take(60)}"
        )
        return Decision.Reject("未授权设备。请在手机 PokeClaw 设置中查看配对码，并发送 /bind <配对码> 绑定")
    }
}
