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

    /** Separates the channel name from the sender id inside one entry. */
    private const val KEY_SEPARATOR = '|'

    /** Separates entries in the persisted string. */
    private const val ENTRY_SEPARATOR = ","

    /** Guards the one-shot legacy migration; see [purgeLegacyEntries]. */
    @Volatile
    private var legacyPurgeDone = false

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
    fun getWhitelist(): Map<Channel, Set<String>> {
        val raw = storage.getString(KEY_CHANNEL_WHITELIST, "")
        if (raw.isEmpty()) return emptyMap()
        val out = LinkedHashMap<Channel, MutableSet<String>>()
        for (entry in raw.split(ENTRY_SEPARATOR)) {
            val parsed = parseEntry(entry.trim()) ?: continue
            out.getOrPut(parsed.first) { LinkedHashSet() }.add(parsed.second)
        }
        return out
    }

    /**
     * Add a sender ID to whitelist.
     */
    @Synchronized
    fun addSenderToWhitelist(channel: Channel, senderId: String) {
        if (senderId.isBlank()) return
        val current = getWhitelist().toMutableMap()
        val existing = current[channel]?.toMutableSet() ?: LinkedHashSet()
        existing.add(senderId.trim())
        current[channel] = existing
        persist(current)
        XLog.i(TAG, "Added sender ${redactId(senderId)} to whitelist on ${channel.name}")
    }

    /**
     * Remove a sender ID from whitelist.
     */
    @Synchronized
    fun removeSenderFromWhitelist(channel: Channel, senderId: String) {
        val current = getWhitelist().toMutableMap()
        val existing = current[channel]?.toMutableSet() ?: return
        if (existing.remove(senderId.trim())) {
            current[channel] = existing
            persist(current)
            XLog.i(TAG, "Removed sender ${redactId(senderId)} from whitelist on ${channel.name}")
        }
    }

    /**
     * Check whether a sender ID is whitelisted.
     */
    fun isSenderWhitelisted(channel: Channel, senderId: String): Boolean {
        if (senderId.isBlank()) return false
        return getWhitelist()[channel]?.contains(senderId.trim()) == true
    }

    /** Raw `CHANNEL|senderId` entries, for display and migration checks. */
    @Synchronized
    fun getWhitelistEntries(): Set<String> = getWhitelist()
        .flatMap { (channel, ids) -> ids.map { entryKey(channel, it) } }
        .toSet()

    private fun persist(map: Map<Channel, Set<String>>) {
        val flat = map.entries
            .filter { it.value.isNotEmpty() }
            .flatMap { (channel, ids) -> ids.map { entryKey(channel, it) } }
            .toMutableList()
        if (flat.isEmpty()) {
            storage.remove(KEY_CHANNEL_WHITELIST)
        } else {
            storage.putString(KEY_CHANNEL_WHITELIST, flat.joinToString(ENTRY_SEPARATOR))
        }
    }

    /**
     * Drop entries written before the whitelist was scoped per channel.
     *
     * They cannot be attributed to a channel, so keeping them would either
     * preserve the cross-channel collision this change fixes, or guess. Guessing
     * is the worse failure, so they are discarded and the affected sender must
     * send /bind again. Runs at most once per process.
     */
    @Synchronized
    fun purgeLegacyEntries() {
        if (legacyPurgeDone) return
        legacyPurgeDone = true
        val raw = storage.getString(KEY_CHANNEL_WHITELIST, "")
        if (raw.isEmpty()) return
        val entries = raw.split(ENTRY_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
        val kept = entries.filter { parseEntry(it) != null }
        if (kept.size == entries.size) return
        if (kept.isEmpty()) storage.remove(KEY_CHANNEL_WHITELIST)
        else storage.putString(KEY_CHANNEL_WHITELIST, kept.joinToString(ENTRY_SEPARATOR))
        XLog.w(
            TAG,
            "Discarded ${entries.size - kept.size} unscoped whitelist entr(ies) written before " +
                "per-channel authorisation. Affected senders must send /bind again. Kept ${kept.size}."
        )
    }

    /** Never log a full external id. */
    private fun redactId(senderId: String): String =
        if (senderId.length <= 4) "***" else "…${senderId.takeLast(4)}"

    /**
     * Test hook. [legacyPurgeDone] is process-global because the migration runs
     * once per launch, so tests need a way to replay it against fresh storage.
     */
    internal fun resetLegacyPurgeForTesting() {
        legacyPurgeDone = false
    }

    /**
     * Key format for a whitelist entry: `<CHANNEL><SEP><senderId>`.
     *
     * Scoping by channel matters because sender ids are only unique *within* a
     * channel. Telegram user ids, QQ open ids and Douyin open ids are all
     * numeric, so a single flat set let an id authorised on one platform
     * authorise the same id on another. Entries written before this was scoped
     * carry no channel and are dropped by [purgeLegacyEntries].
     */
    private fun entryKey(channel: Channel, senderId: String): String =
        "${channel.name}$KEY_SEPARATOR${senderId.trim()}"

    /** Inverse of [entryKey]; null when the entry is not in scoped form. */
    private fun parseEntry(entry: String): Pair<Channel, String>? {
        val sep = entry.indexOf(KEY_SEPARATOR)
        if (sep <= 0) return null
        val channel = runCatching { Channel.valueOf(entry.substring(0, sep)) }.getOrNull()
            ?: return null
        val senderId = entry.substring(sep + 1).trim()
        if (senderId.isEmpty()) return null
        return channel to senderId
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

        purgeLegacyEntries()

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
                addSenderToWhitelist(channel, trimmedSenderId)
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

        // 4. Check whitelist, scoped to the channel the message arrived on
        if (isSenderWhitelisted(channel, trimmedSenderId)) {
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
