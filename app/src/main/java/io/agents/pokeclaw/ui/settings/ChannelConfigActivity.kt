// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import io.agents.pokeclaw.R
import io.agents.pokeclaw.base.BaseActivity
import io.agents.pokeclaw.channel.ChannelManager
import io.agents.pokeclaw.server.ConfigServerManager
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.widget.CommonToolbar
import io.agents.pokeclaw.widget.KButton

/**
 * Channel config screen (Discord/Telegram bot token configuration)
 */
class ChannelConfigActivity : BaseActivity() {

    private lateinit var etInput1: EditText
    private lateinit var etInput2: EditText
    private var isPasswordVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_channel_config)

        val channelType = intent.getSerializableExtra(EXTRA_CHANNEL_TYPE) as? ChannelType
            ?: run {
                finish()
                return
            }

        initToolbar(channelType)
        initViews(channelType)
    }

    private fun initToolbar(channelType: ChannelType) {
        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle(when (channelType) {
                ChannelType.DISCORD -> getString(R.string.channel_config_discord_title)
                ChannelType.TELEGRAM -> getString(R.string.channel_config_telegram_title)
                ChannelType.WECHAT -> getString(R.string.channel_config_wechat_title)
                ChannelType.QQ -> getString(R.string.channel_config_qq_title)
                ChannelType.DOUYIN -> getString(R.string.channel_config_douyin_title)
            })
            showBackButton(true) { finish() }
        }
    }

    private fun initViews(channelType: ChannelType) {
        val tvTip = findViewById<TextView>(R.id.tvTip)
        val tvLabel1 = findViewById<TextView>(R.id.tvLabel1)
        val tvLabel2 = findViewById<TextView>(R.id.tvLabel2)
        etInput1 = findViewById(R.id.etInput1)
        etInput2 = findViewById(R.id.etInput2)
        val ivToggle = findViewById<ImageView>(R.id.ivTogglePassword)

        when (channelType) {
            ChannelType.DISCORD -> {
                tvTip.text = getString(R.string.channel_config_discord_tip)
                tvLabel1.text = "Bot Token"
                tvLabel2.visibility = View.GONE
                (etInput2.parent.parent as View).visibility = View.GONE
                etInput1.hint = getString(R.string.channel_config_discord_hint1)
                etInput1.setText(KVUtils.getDiscordBotToken())
            }
            ChannelType.TELEGRAM -> {
                tvTip.text = getString(R.string.channel_config_telegram_tip)
                tvLabel1.text = "Bot Token"
                tvLabel2.visibility = View.GONE
                (etInput2.parent.parent as View).visibility = View.GONE
                etInput1.hint = getString(R.string.channel_config_telegram_hint1)
                etInput1.setText(KVUtils.getTelegramBotToken())
            }
            ChannelType.WECHAT -> {
                tvTip.text = getString(R.string.channel_config_wechat_manual_tip)
                tvLabel1.text = getString(R.string.channel_config_wechat_api_url_label)
                etInput1.hint = getString(R.string.channel_config_wechat_api_url_hint)
                etInput1.setText(KVUtils.getWechatApiBaseUrl())
                tvLabel2.visibility = View.VISIBLE
                tvLabel2.text = "Bot Token"
                (etInput2.parent.parent as View).visibility = View.VISIBLE
                etInput2.hint = getString(R.string.channel_config_wechat_token_hint)
                etInput2.setText(KVUtils.getWechatBotToken())
            }
            ChannelType.QQ -> {
                tvTip.text = getString(R.string.channel_config_qq_tip)
                tvLabel1.text = getString(R.string.channel_config_qq_ws_url_label)
                etInput1.hint = getString(R.string.channel_config_qq_ws_url_hint)
                etInput1.setText(KVUtils.getQqWsUrl())
                tvLabel2.visibility = View.VISIBLE
                tvLabel2.text = getString(R.string.channel_config_qq_token_label)
                (etInput2.parent.parent as View).visibility = View.VISIBLE
                etInput2.hint = getString(R.string.channel_config_qq_token_hint)
                etInput2.setText(KVUtils.getQqBotToken())
            }
            ChannelType.DOUYIN -> {
                tvTip.text = getString(R.string.channel_config_douyin_tip)
                tvLabel1.text = getString(R.string.channel_config_douyin_url_label)
                etInput1.hint = getString(R.string.channel_config_douyin_url_hint)
                etInput1.setText(KVUtils.getDouyinWebhookUrl())
                tvLabel2.visibility = View.VISIBLE
                tvLabel2.text = getString(R.string.channel_config_douyin_token_label)
                (etInput2.parent.parent as View).visibility = View.VISIBLE
                etInput2.hint = getString(R.string.channel_config_douyin_token_hint)
                etInput2.setText(KVUtils.getDouyinToken())
            }
        }

        // Toggle password visibility
        ivToggle.setOnClickListener {
            isPasswordVisible = !isPasswordVisible
            if (isPasswordVisible) {
                etInput2.transformationMethod = HideReturnsTransformationMethod.getInstance()
                ivToggle.setImageResource(R.drawable.ic_visibility_on)
            } else {
                etInput2.transformationMethod = PasswordTransformationMethod.getInstance()
                ivToggle.setImageResource(R.drawable.ic_visibility_off)
            }
            etInput2.setSelection(etInput2.text.length)
        }

        findViewById<KButton>(R.id.btnSave).setOnClickListener {
            onSaveClicked(channelType)
        }

        // LAN config hint
        val tvLanHint = findViewById<TextView>(R.id.tvLanConfigHint)
        val ivCopy = findViewById<ImageView>(R.id.ivCopyAddress)
        val address = ConfigServerManager.getAddress()
        if (ConfigServerManager.isRunning() && address != null) {
            val url = "http://$address"
            tvLanHint.text = getString(R.string.channel_config_lan_hint_running, url)
            ivCopy.visibility = View.VISIBLE
            ivCopy.setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("lan_config_url", url))
                Toast.makeText(this, R.string.channel_config_lan_copied, Toast.LENGTH_SHORT).show()
            }
        } else {
            tvLanHint.text = getString(R.string.channel_config_lan_hint)
            ivCopy.visibility = View.GONE
        }
    }

    private fun onSaveClicked(channelType: ChannelType) {
        val value1 = etInput1.text.toString().trim()
        val value2 = etInput2.text.toString().trim()
        var isConfigured = false

        when (channelType) {
            ChannelType.DISCORD -> {
                KVUtils.setDiscordBotToken(value1)
                ChannelManager.reinitDiscordFromStorage()
                isConfigured = value1.isNotEmpty()
            }
            ChannelType.TELEGRAM -> {
                KVUtils.setTelegramBotToken(value1)
                ChannelManager.reinitTelegramFromStorage()
                isConfigured = value1.isNotEmpty()
            }
            ChannelType.WECHAT -> {
                KVUtils.setWechatApiBaseUrl(value1)
                KVUtils.setWechatBotToken(value2)
                ChannelManager.reinitWeChatFromStorage()
                isConfigured = value1.isNotEmpty() || value2.isNotEmpty()
            }
            ChannelType.QQ -> {
                KVUtils.setQqWsUrl(value1)
                KVUtils.setQqBotToken(value2)
                ChannelManager.reinitQQFromStorage()
                isConfigured = value1.isNotEmpty()
            }
            ChannelType.DOUYIN -> {
                KVUtils.setDouyinWebhookUrl(value1)
                KVUtils.setDouyinToken(value2)
                ChannelManager.reinitDouyinFromStorage()
                isConfigured = value1.isNotEmpty() || value2.isNotEmpty()
            }
        }

        // Return config result
        val result = ChannelConfigResult(
            channelType = channelType,
            isConfigured = isConfigured
        )
        val intent = Intent().apply {
            putExtra(EXTRA_RESULT_CONFIG, result)
        }
        setResult(RESULT_OK, intent)
        Toast.makeText(this, R.string.channel_config_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    enum class ChannelType {
        DISCORD, TELEGRAM, WECHAT, QQ, DOUYIN
    }

    /**
     * Channel config result
     */
    data class ChannelConfigResult(
        val channelType: ChannelType,
        val isConfigured: Boolean
    ) : java.io.Serializable

    /**
     * ActivityResultContract for use with registerForActivityResult
     */
    class ChannelConfigContract : ActivityResultContract<ChannelType, ChannelConfigResult?>() {
        override fun createIntent(context: Context, input: ChannelType): Intent {
            return Intent(context, ChannelConfigActivity::class.java).apply {
                putExtra(EXTRA_CHANNEL_TYPE, input)
            }
        }

        override fun parseResult(resultCode: Int, intent: Intent?): ChannelConfigResult? {
            return if (resultCode == RESULT_OK && intent != null) {
                @Suppress("DEPRECATION")
                intent.getSerializableExtra(EXTRA_RESULT_CONFIG) as? ChannelConfigResult
            } else {
                null
            }
        }
    }

    companion object {
        private const val EXTRA_CHANNEL_TYPE = "extra_channel_type"
        private const val EXTRA_RESULT_CONFIG = "extra_result_config"

        /**
         * Register a channel config Activity Result Launcher
         * Usage:
         * ```
         * private val channelConfigLauncher = ChannelConfigActivity.registerLauncher(this) { result ->
         *     result?.let {
         *         // Handle config result
         *         println("Channel: ${it.channelType}, Configured: ${it.isConfigured}")
         *     }
         * }
         *
         * // Launch the config screen
         * channelConfigLauncher.launch(ChannelConfigActivity.ChannelType.DISCORD)
         * ```
         */
        fun registerLauncher(
            caller: ActivityResultCaller,
            onResult: (ChannelConfigResult?) -> Unit
        ): ActivityResultLauncher<ChannelType> {
            return caller.registerForActivityResult(ChannelConfigContract()) { result ->
                onResult(result)
            }
        }

        fun start(context: Context, channelType: ChannelType) {
            val intent = Intent(context, ChannelConfigActivity::class.java).apply {
                putExtra(EXTRA_CHANNEL_TYPE, channelType)
            }
            context.startActivity(intent)
        }
    }
}
