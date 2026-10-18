// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.agents.pokeclaw.R
import io.agents.pokeclaw.base.BaseActivity
import io.agents.pokeclaw.ui.web.ClaudeUiPreviewActivity
import io.agents.pokeclaw.widget.AlertDialog
import io.agents.pokeclaw.widget.ConfirmDialog
import io.agents.pokeclaw.widget.CommonToolbar
import io.agents.pokeclaw.widget.InputDialog
import io.agents.pokeclaw.widget.MenuGroup
import io.agents.pokeclaw.widget.MenuItem
import io.agents.pokeclaw.widget.QRCodeDialog
import io.agents.pokeclaw.AppCapabilityCoordinator
import io.agents.pokeclaw.AppRequirement
import io.agents.pokeclaw.appViewModel
import io.agents.pokeclaw.server.ConfigServerManager
import io.agents.pokeclaw.service.ForegroundService
import io.agents.pokeclaw.support.DebugReportManager
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings screen
 */
class SettingsActivity : BaseActivity() {

    /** Number of built-in tools advertised on the "管理工具" row. */
    private companion object {
        const val TOOLS_ENABLED_COUNT = 12

        /**
         * Bundled copy of docs/sponsor-qr.jpg. Keep both in sync when rotating
         * the payment code -- the repo copy is what FUNDING.yml links to, this
         * one is what the app renders offline.
         */
        const val SPONSOR_QR_ASSET = "sponsor-qr.jpg"
    }

    // Poll permissions every second (same as original HomeActivity)
    private val handler = Handler(Looper.getMainLooper())
    private val permPoller = object : Runnable {
        override fun run() {
            refreshPermissions()
            handler.postDelayed(this, 1000)
        }
    }

    // Permission menu items — kept for onResume refresh
    private var permAccessibility: io.agents.pokeclaw.widget.MenuItem? = null
    private var permNotification: io.agents.pokeclaw.widget.MenuItem? = null
    private var permNotifAccess: io.agents.pokeclaw.widget.MenuItem? = null
    private var permOverlay: io.agents.pokeclaw.widget.MenuItem? = null
    private var permBattery: io.agents.pokeclaw.widget.MenuItem? = null
    private var permStorage: io.agents.pokeclaw.widget.MenuItem? = null
    private var externalAutomationItem: io.agents.pokeclaw.widget.MenuItem? = null
    private var globalPromptItem: io.agents.pokeclaw.widget.MenuItem? = null
    private var customModelUrlItem: io.agents.pokeclaw.widget.MenuItem? = null
    private var mcpServersItem: io.agents.pokeclaw.widget.MenuItem? = null
    private var keepAliveItem: io.agents.pokeclaw.widget.MenuItem? = null

    private val viewModel by lazy {
        ViewModelProvider(this)[SettingsViewModel::class.java]
    }

    // Keep MenuItem references for dynamic updates
    private val menuItems = mutableMapOf<String, MenuItem>()

    // Register launcher to refresh after returning from LLM config screen
    private val llmConfigLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
        viewModel.refresh()
    }

    // Register channel config result callback
    private val channelConfigLauncher = ChannelConfigActivity.registerLauncher(this) { result ->
        result?.let {
            // Refresh settings after successful config (refresh "Bound"/"Unbound" status)
            viewModel.refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Force match theme from ThemeManager
        val themeColors = io.agents.pokeclaw.ui.chat.ThemeManager.getColors()
        window.statusBarColor = themeColors.toolbarBg
        window.decorView.setBackgroundColor(themeColors.bg)

        setContentView(R.layout.activity_settings)

        // Override XML backgrounds with ThemeManager colors
        val contentFrame = findViewById<android.view.ViewGroup>(android.R.id.content)
        contentFrame?.setBackgroundColor(themeColors.bg)
        // Root LinearLayout has android:background="@color/colorBgPrimary" — override it
        (contentFrame?.getChildAt(0) as? android.view.View)?.setBackgroundColor(themeColors.bg)

        initToolbar()
        initMenuGroups()
        applyThemeToGroups(themeColors)
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        refreshSettings()
        refreshPermissions()
        refreshExternalAutomation()
        mcpServersItem?.setTrailingText(refreshMcpServerCount())
        handler.removeCallbacks(permPoller)
        handler.postDelayed(permPoller, 1000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(permPoller)
    }

    /** Refreshes the trailing label on the MCP-servers row. */
    private fun refreshMcpServerCount(): String {
        val count = io.agents.pokeclaw.agent.mcp.McpConnectionManager.loadConfigs(this).size
        return if (count == 0) {
            getString(R.string.settings_status_not_configured)
        } else {
            getString(R.string.settings_count_configured, count)
        }
    }

    /** Trailing label for the memory-bank row: off state wins over the count. */
    private fun refreshMemoryCount(): String {
        io.agents.pokeclaw.agent.memory.MemoryStore.init(this)
        if (!io.agents.pokeclaw.agent.memory.MemoryStore.isEnabled()) return getString(R.string.settings_off)
        val count = io.agents.pokeclaw.agent.memory.MemoryStore.count()
        return if (count == 0) {
            getString(R.string.settings_count_empty)
        } else {
            getString(R.string.settings_count_items, count)
        }
    }

    /**
     * Display name for a theme id. The id itself is a storage key and must never
     * be translated - only the label shown next to the Theme row is localized.
     * Unknown ids fall back to the previous "Ember light"-style derivation so a
     * newly added theme still renders something readable.
     */
    private fun themeDisplayName(themeId: String): String {
        val resId = when (themeId) {
            "ember_dark" -> R.string.theme_label_ember_dark
            "ember_light" -> R.string.theme_label_ember_light
            "abyss_dark" -> R.string.theme_label_abyss_dark
            "abyss_light" -> R.string.theme_label_abyss_light
            else -> 0
        }
        return if (resId != 0) getString(resId)
        else themeId.replace("_", " ").replaceFirstChar { it.uppercase() }
    }

    private fun refreshPermissions() {
        val capabilities = AppCapabilityCoordinator.snapshot(this)
        permAccessibility?.setTrailingText(capabilities.accessibilityStatusLabel)
        permNotification?.setTrailingText(capabilities.notificationPermissionStatusLabel)
        permNotifAccess?.setTrailingText(capabilities.notificationAccessStatusLabel)
        permOverlay?.setTrailingText(if (capabilities.overlayGranted) "Enabled" else "Disabled")
        permBattery?.setTrailingText(if (capabilities.batteryOptimizationIgnored) "Unrestricted" else "Restricted")
        permStorage?.setTrailingText(if (capabilities.storageAccessGranted) "Enabled" else "Disabled")
    }

    private fun refreshExternalAutomation() {
        externalAutomationItem?.setTrailingText(
            if (KVUtils.isExternalAutomationEnabled()) "Enabled" else "Disabled"
        )
    }

    /** Refreshes the trailing label on the global-prompt row (#45). */
    private fun refreshGlobalPromptStatus() {
        val current = KVUtils.getGlobalPrompt()
        val label = if (current.isBlank()) {
            getString(R.string.global_prompt_not_set)
        } else {
            getString(R.string.global_prompt_set_status, current.length)
        }
        globalPromptItem?.setTrailingText(label)
    }

    /** Refreshes the trailing label on the custom-model-URL row (#36). */
    private fun refreshCustomModelUrlStatus() {
        val current = KVUtils.getCustomLocalModelUrl()
        val label = if (current.isBlank()) {
            getString(R.string.custom_local_model_url_not_set)
        } else {
            getString(R.string.custom_local_model_url_set)
        }
        customModelUrlItem?.setTrailingText(label)
    }

    private fun initToolbar() {
        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle(getString(R.string.settings_title))
            showBackButton(true) { finish() }
        }
    }

    private fun applyThemeToGroups(tc: io.agents.pokeclaw.ui.chat.ThemeManager.ChatColors) {
        val groups = listOf(
            R.id.permissionsGroup, R.id.channelGroup, R.id.modelGroup,
            R.id.appearanceGroup, R.id.toolsGroup, R.id.remoteGroup, R.id.aboutGroup
        )
        for (id in groups) {
            val g = findViewById<MenuGroup>(id) ?: continue
            g.setTitleColor(tc.aiText)
            g.setCardBackgroundColor(tc.toolbarBg)
            for (i in 0 until g.getMenuItemCount()) {
                g.getMenuItemAt(i)?.apply {
                    setTitleColor(tc.aiText)
                    setTrailingTextColor(tc.sendColor)
                    setLeadingIconColor(tc.aiText)
                    setTrailingIconColor(tc.aiText)
                }
            }
        }
        // Toolbar
        findViewById<CommonToolbar>(R.id.toolbar)?.apply {
            setBackgroundColor(tc.toolbarBg)
            setTitleColor(tc.aiText)
            findViewById<android.widget.ImageView>(R.id.ivBack)?.setColorFilter(tc.aiText)
        }
    }

    private fun refreshSettings() {
        viewModel.refresh()
    }

    private fun toggleExternalAutomation() {
        if (KVUtils.isExternalAutomationEnabled()) {
            KVUtils.setExternalAutomationEnabled(false)
            refreshExternalAutomation()
            Toast.makeText(this, "外部自动化已关闭", Toast.LENGTH_SHORT).show()
            return
        }

        ConfirmDialog.showWarm(
            context = this,
            title = "启用外部自动化？",
            message = "This lets trusted apps like Tasker, MacroDroid, or ADB start PokeClaw tasks with explicit Android intents. Keep it off unless you control the automation that will call it.",
            actionTitle = "Enable",
            cancelTitle = getString(R.string.common_cancel),
            onAction = {
                KVUtils.setExternalAutomationEnabled(true)
                refreshExternalAutomation()
                Toast.makeText(this, "外部自动化已开启", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun initMenuGroups() {
        // Permissions
        val permissionsGroup = findViewById<MenuGroup>(R.id.permissionsGroup)
        permissionsGroup.setTitle("Permissions")

        permAccessibility = permissionsGroup.addMenuItem(
            leadingIcon = R.drawable.ic_accessibility,
            title = getString(R.string.home_card_accessibility_title),
            onClick = {
                AppCapabilityCoordinator.openSystemSettings(this, AppRequirement.ACCESSIBILITY)
                Toast.makeText(this, R.string.home_enable_accessibility, Toast.LENGTH_LONG).show()
            },
            showDivider = true
        )

        permNotification = permissionsGroup.addMenuItem(
            leadingIcon = R.drawable.ic_notification,
            title = getString(R.string.home_card_notification_title),
            onClick = {
                if (!AppCapabilityCoordinator.isNotificationPermissionGranted(this@SettingsActivity)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
                    }
                } else {
                    Toast.makeText(this@SettingsActivity, R.string.home_notification_enabled, Toast.LENGTH_SHORT).show()
                }
            },
            showDivider = true
        ).apply {
            setTrailingText(
                if (AppCapabilityCoordinator.isNotificationPermissionGranted(this@SettingsActivity)) "Enabled" else "Disabled"
            )
        }

        permNotifAccess = permissionsGroup.addMenuItem(
            leadingIcon = R.drawable.ic_notification,
            title = "通知使用权",
            onClick = {
                AppCapabilityCoordinator.openSystemSettings(this, AppRequirement.NOTIFICATION_ACCESS)
            },
            showDivider = true
        )

        permOverlay = permissionsGroup.addMenuItem(
            leadingIcon = R.drawable.ic_window,
            title = getString(R.string.home_card_system_window_title),
            onClick = {
                if (AppCapabilityCoordinator.snapshot(this@SettingsActivity).overlayGranted) {
                    Toast.makeText(this@SettingsActivity, R.string.home_overlay_enabled, Toast.LENGTH_SHORT).show()
                } else {
                    AppCapabilityCoordinator.openSystemSettings(this@SettingsActivity, AppRequirement.OVERLAY)
                }
            },
            showDivider = true
        )

        permBattery = permissionsGroup.addMenuItem(
            leadingIcon = R.drawable.ic_battery,
            title = getString(R.string.home_card_battery_title),
            onClick = {
                if (AppCapabilityCoordinator.snapshot(this@SettingsActivity).batteryOptimizationIgnored) {
                    Toast.makeText(this@SettingsActivity, R.string.home_battery_ignored, Toast.LENGTH_SHORT).show()
                } else {
                    AppCapabilityCoordinator.openSystemSettings(this@SettingsActivity, AppRequirement.BATTERY_OPTIMIZATION)
                }
            },
            showDivider = true
        )

        permStorage = permissionsGroup.addMenuItem(
            leadingIcon = R.drawable.ic_storage,
            title = getString(R.string.home_card_storage_title),
            onClick = {
                if (AppCapabilityCoordinator.snapshot(this@SettingsActivity).storageAccessGranted) {
                    Toast.makeText(this@SettingsActivity, R.string.home_storage_enabled, Toast.LENGTH_SHORT).show()
                } else {
                    AppCapabilityCoordinator.openSystemSettings(this@SettingsActivity, AppRequirement.STORAGE)
                }
            },
            showDivider = false
        )

        // Channel (hidden)
        val channelGroup = findViewById<MenuGroup>(R.id.channelGroup)
        channelGroup.setTitle(getString(R.string.settings_group_channel))

        menuItems[SettingsViewModel.MenuAction.DISCORD.name] = channelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_channel_discord,
            title = getString(R.string.menu_discord),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.DISCORD) },
            showDivider = true
        )
        menuItems[SettingsViewModel.MenuAction.TELEGRAM.name] = channelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_channel_telegram,
            title = getString(R.string.menu_telegram),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.TELEGRAM) },
            showDivider = true
        )
        menuItems[SettingsViewModel.MenuAction.WECHAT.name] = channelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_channel_wechat,
            title = getString(R.string.menu_wechat),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.WECHAT) },
            showDivider = true
        )
        menuItems[SettingsViewModel.MenuAction.QQ.name] = channelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_channel_qq,
            title = getString(R.string.menu_qq),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.QQ) },
            showDivider = true
        )
        menuItems[SettingsViewModel.MenuAction.DOUYIN.name] = channelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_channel_douyin,
            title = getString(R.string.menu_douyin),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.DOUYIN) },
            showDivider = true
        )
        menuItems[SettingsViewModel.MenuAction.CHANNEL_PIN.name] = channelGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_lock_lock,
            title = getString(R.string.menu_channel_pin),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.CHANNEL_PIN) },
            showDivider = true
        )
        menuItems[SettingsViewModel.MenuAction.LAN_CONFIG.name] = channelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_lan_config,
            title = getString(R.string.menu_lan_config),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.LAN_CONFIG) },
            showDivider = false
        )
        menuItems[SettingsViewModel.MenuAction.LAN_CONFIG.name]?.setLeadingIconColor(getColor(R.color.colorTextPrimary))


        val modelGroup = findViewById<MenuGroup>(R.id.modelGroup)
        modelGroup.setTitle(getString(R.string.settings_group_model))

        menuItems[SettingsViewModel.MenuAction.LLM_CONFIG.name] = modelGroup.addMenuItem(
            leadingIcon = R.drawable.icon_current_model,
            title = getString(R.string.menu_llm_config),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.LLM_CONFIG) },
            showDivider = false
        )
        menuItems[SettingsViewModel.MenuAction.LLM_CONFIG.name]?.setLeadingIconColor(getColor(R.color.colorTextPrimary))

        // Task Budget (inline in model group)
        modelGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_recent_history,
            title = "任务预算",
            onClick = { showBudgetDialog() },
            showDivider = true
        ).apply {
            setTrailingText(io.agents.pokeclaw.agent.TaskBudget.describeCurrentBudget())
        }

        // Global Prompt (#45) — user-defined persistent instructions
        globalPromptItem = modelGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_edit,
            title = getString(R.string.global_prompt_title),
            onClick = {
                val current = KVUtils.getGlobalPrompt()
                XLog.i("SettingsActivity", "open global prompt dialog: current.len=${current.length}")
                InputDialog.show(
                    context = this@SettingsActivity,
                    title = getString(R.string.global_prompt_dialog_title),
                    presetText = current,
                    hint = getString(R.string.global_prompt_hint),
                    maxLength = 2000,
                ) { text ->
                    KVUtils.setGlobalPrompt(text)
                    XLog.i("SettingsActivity", "global prompt saved: new.len=${text.length}, hasPrompt=${KVUtils.hasGlobalPrompt()}")
                    refreshGlobalPromptStatus()
                }
            },
            showDivider = false
        )
        globalPromptItem?.setLeadingIconColor(getColor(R.color.colorTextPrimary))
        refreshGlobalPromptStatus()

        // Custom Local Model URL (#36) — advanced: lets users add their own model download URL
        customModelUrlItem = modelGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_share,
            title = getString(R.string.custom_local_model_url_title),
            onClick = {
                val current = KVUtils.getCustomLocalModelUrl()
                XLog.i("SettingsActivity", "open custom model url dialog: current.len=${current.length}")
                InputDialog.show(
                    context = this@SettingsActivity,
                    title = getString(R.string.custom_local_model_url_dialog_title),
                    presetText = current,
                    hint = getString(R.string.custom_local_model_url_hint),
                    maxLength = 1000,
                    inputValidate = { text ->
                        val lower = text.trim().lowercase()
                        if (lower.isEmpty()) {
                            // Empty = clear; allow
                            io.agents.pokeclaw.widget.InputDialog.ValidateResult(true, null)
                        } else if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                            io.agents.pokeclaw.widget.InputDialog.ValidateResult(
                                false,
                                getString(R.string.custom_local_model_url_invalid)
                            )
                        } else {
                            io.agents.pokeclaw.widget.InputDialog.ValidateResult(true, null)
                        }
                    },
                ) { text ->
                    // Normalize the protocol prefix to lowercase (Android keyboard auto-cap
                    // can produce "HTTPS://..."). Rest of the URL is case-preserved.
                    val trimmed = text.trim().let { raw ->
                        when {
                            raw.startsWith("HTTPS://", ignoreCase = false) -> "https://" + raw.substring(8)
                            raw.startsWith("HTTP://", ignoreCase = false) -> "http://" + raw.substring(7)
                            else -> raw
                        }
                    }
                    KVUtils.setCustomLocalModelUrl(trimmed)
                    XLog.i(
                        "SettingsActivity",
                        "custom local model url saved: new.len=${trimmed.length}, hasUrl=${KVUtils.hasCustomLocalModelUrl()}"
                    )
                    refreshCustomModelUrlStatus()
                }
            },
            showDivider = false
        )
        customModelUrlItem?.setLeadingIconColor(getColor(R.color.colorTextPrimary))
        refreshCustomModelUrlStatus()

        // Appearance
        val appearanceGroup = findViewById<MenuGroup>(R.id.appearanceGroup)
        appearanceGroup.setTitle(getString(R.string.settings_group_appearance))

        appearanceGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_slideshow,
            title = "主题",
            onClick = {
                startActivity(Intent(this, ThemeActivity::class.java))
            },
            showDivider = false
        ).apply {
            val themeId = KVUtils.getString("THEME_ID", "abyss_dark")
            setTrailingText(themeDisplayName(themeId))
        }

        // Tools
        val toolsGroup = findViewById<MenuGroup>(R.id.toolsGroup)
        toolsGroup.setTitle(getString(R.string.settings_group_tools))

        toolsGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_manage,
            title = "管理工具",
            onClick = {
                Toast.makeText(this, "已启用 12 个工具。工具管理即将上线。", Toast.LENGTH_SHORT).show()
            },
            showDivider = true
        ).apply {
            setTrailingText(getString(R.string.settings_count_enabled, TOOLS_ENABLED_COUNT))
        }

        // MCP server manager — in-app editor for mcp_servers.json
        mcpServersItem = toolsGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_share,
            title = "MCP 服务器",
            onClick = { startActivity(Intent(this, McpServersActivity::class.java)) },
            showDivider = false
        ).apply {
            setLeadingIconColor(getColor(R.color.colorTextPrimary))
            setTrailingText(refreshMcpServerCount())
        }

        // User skill recipes (YAML) — capture / edit / delete
        toolsGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_agenda,
            title = "自定义技能",
            onClick = { startActivity(Intent(this, UserSkillsActivity::class.java)) },
            showDivider = true
        ).apply {
            setLeadingIconColor(getColor(R.color.colorTextPrimary))
        }

        // Long-term memory bank — what the agent carries between sessions
        toolsGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_save,
            title = "记忆库",
            onClick = { startActivity(Intent(this, MemoryActivity::class.java)) },
            showDivider = true
        ).apply {
            setLeadingIconColor(getColor(R.color.colorTextPrimary))
            setTrailingText(refreshMemoryCount())
        }

        // Persistent background keep-alive — holds a foreground service so
        // vivo-style ROMs don't freeze the process when nothing else runs
        keepAliveItem = toolsGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_mylocation,
            title = "常驻后台",
            onClick = {
                val enabled = !ForegroundService.isPersistentKeepAliveEnabled()
                ForegroundService.setPersistentKeepAliveEnabled(enabled)
                keepAliveItem?.setTrailingText(
                    if (enabled) getString(R.string.settings_on) else getString(R.string.settings_off)
                )
                ForegroundService.syncToBackgroundState(this)
            },
            showDivider = false
        ).apply {
            setLeadingIconColor(getColor(R.color.colorTextPrimary))
            setTrailingText(
                if (ForegroundService.isPersistentKeepAliveEnabled()) getString(R.string.settings_on)
                else getString(R.string.settings_off)
            )
        }

        // Remote Control
        val remoteGroup = findViewById<MenuGroup>(R.id.remoteGroup)
        remoteGroup.setTitle(getString(R.string.settings_group_remote))

        remoteGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_send,
            title = "Telegram 机器人",
            onClick = {
                channelConfigLauncher.launch(ChannelConfigActivity.ChannelType.TELEGRAM)
            },
            showDivider = true
        ).apply {
            val token = KVUtils.getTelegramBotToken()
            setTrailingText(
                if (token.isNotEmpty()) getString(R.string.settings_status_connected)
                else getString(R.string.settings_status_not_connected)
            )
        }

        externalAutomationItem = remoteGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_share,
            title = "外部自动化",
            onClick = { toggleExternalAutomation() },
            showDivider = true
        ).apply {
            setTrailingText(
                if (KVUtils.isExternalAutomationEnabled()) getString(R.string.settings_status_enabled)
                else getString(R.string.settings_status_disabled)
            )
        }

        remoteGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_call,
            title = "WhatsApp",
            onClick = { },
            showDivider = true
        ).apply {
            setTrailingText(getString(R.string.settings_status_coming_soon))
        }

        remoteGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_myplaces,
            title = "Web 控制台",
            onClick = { },
            showDivider = false
        ).apply {
            setTrailingText(getString(R.string.settings_status_coming_soon))
        }

        // About
        val aboutGroup = findViewById<MenuGroup>(R.id.aboutGroup)
        aboutGroup.setTitle(getString(R.string.settings_group_about))

        aboutGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_info_details,
            title = "PokeClaw",
            onClick = { },
            showDivider = true
        ).apply {
            setTrailingText("v${io.agents.pokeclaw.BuildConfig.VERSION_NAME}")
        }

        aboutGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_send,
            title = getString(R.string.settings_about_report_bug),
            onClick = { reportBug() },
            showDivider = true
        ).apply {
            setTrailingText(getString(R.string.settings_about_report_bug_desc))
        }

        aboutGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_upload,
            title = getString(R.string.settings_about_share_debug),
            onClick = { shareDebugReport() },
            showDivider = true
        ).apply {
            setTrailingText(getString(R.string.settings_about_share_debug_desc))
        }

        aboutGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_share,
            title = "GitHub",
            onClick = {
                startActivity(Intent(Intent.ACTION_VIEW, io.agents.pokeclaw.BuildConfig.REPO_URL.toUri()))
            },
            showDivider = true
        ).apply {
            setTrailingText(io.agents.pokeclaw.BuildConfig.REPO_SLUG)
        }

        // Sponsorship. This replaces a row that used to read "开发者 / ithiria894"
        // and linked to the UPSTREAM author. On a fork build that is a misdirection:
        // people holding this APK were sent to a repository that stopped receiving
        // work, while the actual maintainer was one tap further away. Same class of
        // bug as .github/FUNDING.yml shipping `github: ithiria894` (CLAUDE.local.md
        // §10.2) — a reference inherited from upstream that silently routes people
        // to someone else.
        //
        // Upstream attribution is NOT lost: NOTICE carries "Copyright 2026 agents.io"
        // and the README has an explicit "上游状态" section. That is where
        // Apache-2.0 §4 wants it — a credits screen that points at a dead repo is
        // not attribution, it is a dead link.
        //
        // This used to hand off to SPONSOR_URL -> docs/SPONSOR.md on GitHub. That
        // required the user's browser to render GitHub-flavored Markdown AND GitHub
        // to rewrite the relative image path "sponsor-qr.jpg" into a blob/ URL. The
        // Comet browser did neither: it fetched the .md over the raw channel and
        // rendered the Markdown source, so the row opened a page full of code with a
        // literal "![微信收款码](sponsor-qr.jpg)" instead of the payment code. Any
        // second browser could fail the same two ways. Showing the bundled asset is
        // the only variant that needs neither a network nor a specific browser.
        aboutGroup.addMenuItem(
            leadingIcon = R.drawable.ic_heart,
            title = getString(R.string.settings_about_sponsor),
            onClick = { showSponsorQrCode() },
            showDivider = false
        ).apply {
            setTrailingText(getString(R.string.settings_about_sponsor_desc))
            // ic_heart ships white; without this it disappears in the light theme.
            setLeadingIconColor(getColor(R.color.colorTextPrimary))
        }

        // TEMPORARY — design-review entry for the Claude UI mockup. Tracked in
        // BACKLOG.md "Temporary surfaces"; delete before any public release.
        //
        // ClaudeUiPreviewActivity is exported="false" on purpose, so `adb shell
        // am start` cannot reach it either (SecurityException: not exported from
        // uid 10395). Loosening exported just to make QA convenient would turn a
        // design surface into something any app on the device can launch, so the
        // entry point stays inside the app instead.
        //
        // Deliberately NOT gated on BuildConfig.DEBUG: the device under QA has the
        // release-signed build installed, and a debug APK cannot overwrite it
        // (INSTALL_FAILED_UPDATE_INCOMPATIBLE — see CLAUDE.local.md §13.2). Gating
        // on DEBUG would hide this entry on the only build that can actually be
        // tested. The BACKLOG entry is the guard instead.
        aboutGroup.addMenuItem(
            leadingIcon = R.drawable.ic_heart,
            title = getString(R.string.settings_about_claude_ui_preview),
            onClick = {
                XLog.i("SettingsActivity", "opening Claude UI preview")
                startActivity(Intent(this, ClaudeUiPreviewActivity::class.java))
            },
            showDivider = false
        ).apply {
            setLeadingIconColor(getColor(R.color.colorTextPrimary))
        }
    }

    /**
     * Show the bundled WeChat payment code in-app.
     *
     * The image ships in assets/ rather than being fetched, so this works offline
     * and cannot be broken by a markdown renderer or a CDN. Decoding a ~95 KB JPEG
     * is not free, so it happens off the main thread; the dialog is only shown once
     * the bitmap exists because QRCodeDialog takes the bitmap eagerly.
     */
    private fun showSponsorQrCode() {
        XLog.i("SettingsActivity", "showSponsorQrCode: loading bundled asset")
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    assets.open(SPONSOR_QR_ASSET).use { BitmapFactory.decodeStream(it) }
                }.onFailure { error ->
                    XLog.e("SettingsActivity", "Failed to decode sponsor QR asset", error)
                }.getOrNull()
            }
            if (bitmap == null) {
                // Never fail silently (CLAUDE.md): tell the user what happened.
                Toast.makeText(
                    this@SettingsActivity,
                    R.string.sponsor_qr_load_failed,
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            XLog.i("SettingsActivity", "showSponsorQrCode: showing dialog ${bitmap.width}x${bitmap.height}")
            QRCodeDialog.show(
                context = this@SettingsActivity,
                title = getString(R.string.sponsor_qr_title),
                subtitle = getString(R.string.sponsor_qr_subtitle),
                qrBitmap = bitmap,
            )
        }
    }

    private fun reportBug() {
        buildSupportBundle(
            preparingToast = "正在生成问题报告…"
        ) { report ->
            AlertDialog.show(
                context = this@SettingsActivity,
                title = "问题报告已生成",
                message = """
                    ${report.name} is ready.

                    Open GitHub Issue to file the bug now.
                    If your browser or GitHub app makes attachment upload awkward, tap Share ZIP instead and send the report manually.
                """.trimIndent(),
                actionTitle = "打开 GitHub Issue",
                cancelTitle = "Share ZIP",
                onAction = { openGitHubIssue(report) },
                onCancel = {
                    shareReportFile(
                        report = report,
                        chooserTitle = "Share bug report ZIP",
                        subject = "PokeClaw bug report ${io.agents.pokeclaw.BuildConfig.VERSION_NAME}",
                        body = """
                            Attach this ZIP to your GitHub issue:
                            ${io.agents.pokeclaw.BuildConfig.ISSUES_URL}
                        """.trimIndent()
                    )
                }
            )
        }
    }

    private fun shareDebugReport() {
        buildSupportBundle(
            preparingToast = "Preparing debug report…",
        ) { report ->
            shareReportFile(
                report = report,
                chooserTitle = "Share debug report",
                subject = "PokeClaw debug report ${io.agents.pokeclaw.BuildConfig.VERSION_NAME}",
                body = "Attach this debug report when reporting a PokeClaw issue."
            )
        }
    }

    private fun buildSupportBundle(
        preparingToast: String,
        onReportReady: (java.io.File) -> Unit,
    ) {
        lifecycleScope.launch {
            Toast.makeText(this@SettingsActivity, preparingToast, Toast.LENGTH_SHORT).show()
            runCatching {
                withContext(Dispatchers.IO) {
                    DebugReportManager.buildReport(this@SettingsActivity)
                }
            }.onSuccess { report ->
                onReportReady(report)
            }.onFailure { error ->
                XLog.e("SettingsActivity", "Failed to build debug report", error)
                Toast.makeText(this@SettingsActivity, "Failed to build debug report", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openGitHubIssue(report: java.io.File) {
        val issueUri = io.agents.pokeclaw.BuildConfig.ISSUES_URL.toUri()
            .buildUpon()
            .appendQueryParameter(
                "title",
                "[Bug] ${Build.MANUFACTURER} ${Build.MODEL} - "
            )
            .appendQueryParameter("body", buildGitHubIssueBody(report))
            .build()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, issueUri))
            Toast.makeText(
                this,
                "Attach ${report.name} to the GitHub issue after the page opens",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "没有可打开 GitHub 的应用", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildGitHubIssueBody(report: java.io.File): String {
        return """
            ## What happened
            -

            ## What you expected
            -

            ## Exact steps to reproduce
            1.
            2.
            3.

            ## Device
            - Manufacturer: ${Build.MANUFACTURER}
            - Model: ${Build.MODEL}
            - Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})

            ## Attachments
            - Attach this ZIP from PokeClaw: `${report.name}`
            - If this looks device-specific and you have ADB available, also attach `adb logcat`

            Generated by PokeClaw ${io.agents.pokeclaw.BuildConfig.VERSION_NAME}.
        """.trimIndent()
    }

    private fun shareReportFile(
        report: java.io.File,
        chooserTitle: String,
        subject: String,
        body: String,
    ) {
        val uri = FileProvider.getUriForFile(
            this@SettingsActivity,
            "${packageName}.fileprovider",
            report
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, chooserTitle))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this@SettingsActivity, "没有可分享报告的应用", Toast.LENGTH_LONG).show()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe settings changes and dynamically update UI
                launch {
                    viewModel.settingItems.collect { items ->
                        items.forEach { (key, value) ->
                            when (value) {
                                is SettingsViewModel.SettingValue.Text -> {
                                    menuItems[key]?.setTrailingText(value.text)
                                }
                                is SettingsViewModel.SettingValue.Switch -> {
                                    // Update switch state here if needed
                                }
                            }
                        }
                    }
                }

                // Observe H5 config changes (includes LLM/channel), refresh UI and re-initialize Agent and channels
                launch {
                    ConfigServerManager.configChanged.collect {
                        viewModel.refresh()
                        appViewModel.initAgent()
                        appViewModel.afterInit()
                    }
                }

                // Observe menu click events
                launch {
                    viewModel.menuClickEvent.collect { action ->
                        when (action) {
                            SettingsViewModel.MenuAction.WECHAT -> {
                                if (viewModel.isWechatBound()) {
                                    showUnbindDialog(getString(R.string.channel_wechat)) {
                                        viewModel.unbindWeChat()
                                        Toast.makeText(this@SettingsActivity, R.string.common_unbound_success, Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    AlertDialog.show(
                                        context = this@SettingsActivity,
                                        title = getString(R.string.channel_config_wechat_title),
                                        message = getString(R.string.channel_config_wechat_choose_mode),
                                        actionTitle = getString(R.string.channel_config_wechat_qr_mode),
                                        cancelTitle = getString(R.string.channel_config_wechat_manual_mode),
                                        onAction = {
                                            viewModel.startWeChatQrLogin(this@SettingsActivity)
                                        },
                                        onCancel = {
                                            channelConfigLauncher.launch(ChannelConfigActivity.ChannelType.WECHAT)
                                        }
                                    )
                                }
                            }
                            SettingsViewModel.MenuAction.QQ -> {
                                if (viewModel.isQqBound()) {
                                    showUnbindDialog(getString(R.string.channel_qq)) {
                                        viewModel.unbindQq()
                                        Toast.makeText(this@SettingsActivity, R.string.common_unbound_success, Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    channelConfigLauncher.launch(ChannelConfigActivity.ChannelType.QQ)
                                }
                            }
                            SettingsViewModel.MenuAction.DOUYIN -> {
                                if (viewModel.isDouyinBound()) {
                                    showUnbindDialog(getString(R.string.channel_douyin)) {
                                        viewModel.unbindDouyin()
                                        Toast.makeText(this@SettingsActivity, R.string.common_unbound_success, Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    channelConfigLauncher.launch(ChannelConfigActivity.ChannelType.DOUYIN)
                                }
                            }
                            SettingsViewModel.MenuAction.CHANNEL_PIN -> {
                                showPairPinDialog()
                            }
                            SettingsViewModel.MenuAction.DISCORD -> {
                                if (viewModel.isDiscordBound()) {
                                    showUnbindDialog(getString(R.string.channel_discord)) {
                                        viewModel.unbindDiscord()
                                        Toast.makeText(this@SettingsActivity, R.string.common_unbound_success, Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    channelConfigLauncher.launch(ChannelConfigActivity.ChannelType.DISCORD)
                                }
                            }
                            SettingsViewModel.MenuAction.TELEGRAM -> {
                                if (viewModel.isTelegramBound()) {
                                    showUnbindDialog(getString(R.string.channel_telegram)) {
                                        viewModel.unbindTelegram()
                                        Toast.makeText(this@SettingsActivity, R.string.common_unbound_success, Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    channelConfigLauncher.launch(ChannelConfigActivity.ChannelType.TELEGRAM)
                                }
                            }
                            SettingsViewModel.MenuAction.LAN_CONFIG -> {
                                val result = viewModel.toggleConfigServer(this@SettingsActivity)
                                if (result == getString(R.string.lan_config_no_wifi)) {
                                    Toast.makeText(this@SettingsActivity, R.string.lan_config_no_wifi, Toast.LENGTH_SHORT).show()
                                }
                            }
                            SettingsViewModel.MenuAction.LLM_CONFIG -> {
                                llmConfigLauncher.launch(Intent(this@SettingsActivity, LlmConfigActivity::class.java))
                            }
                            null -> {}
                            else -> {}
                        }
                        viewModel.clearMenuClickEvent()
                    }
                }
            }
        }
    }

    /**
     * Show unbind confirmation dialog
     */
    private fun showUnbindDialog(channelName: String, onUnbind: () -> Unit) {
        AlertDialog.showWarm(
            context = this,
            title = getString(R.string.unbind_title),
            message = getString(R.string.unbind_message, channelName, channelName),
            actionTitle = getString(R.string.unbind_action),
            onAction = onUnbind
        )
    }

    private fun showPairPinDialog() {
        val currentPin = KVUtils.getChannelPairPin()
        InputDialog.show(
            context = this@SettingsActivity,
            title = getString(R.string.channel_pair_pin_title),
            presetText = currentPin,
            hint = getString(R.string.channel_pair_pin_hint),
            numberOnly = true,
            minLength = 6,
            maxLength = 6,
            confirmText = getString(R.string.common_save),
            inputValidate = { text ->
                val trimmed = text.trim()
                if (trimmed.length == 6 && trimmed.all { it.isDigit() }) {
                    InputDialog.ValidateResult(true, null)
                } else {
                    InputDialog.ValidateResult(false, getString(R.string.channel_pair_pin_invalid))
                }
            }
        ) { text ->
            val trimmed = text.trim()
            KVUtils.setChannelPairPin(trimmed)
            viewModel.refresh()
            Toast.makeText(this@SettingsActivity, R.string.channel_pair_pin_saved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showBudgetDialog() {
        val currentTokens = io.agents.pokeclaw.agent.TaskBudget.getConfiguredMaxTokens()
        val currentCost = io.agents.pokeclaw.agent.TaskBudget.getConfiguredMaxCost()

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val tokenLabel = android.widget.TextView(this).apply {
            text = "单任务最大 token 数"
            setTextColor(getColor(R.color.colorTextPrimary))
        }
        layout.addView(tokenLabel)

        val tokenOptions = arrayOf("Unlimited", "10K", "50K", "100K", "200K", "250K", "500K")
        val tokenValues = arrayOf<Int?>(null, 10_000, 50_000, 100_000, 200_000, 250_000, 500_000)
        val selectedTokenIndex = when (currentTokens) {
            null -> 0
            else -> tokenValues.indexOfFirst { it == currentTokens }.takeIf { it >= 0 }
                ?: tokenValues.indices
                    .filter { tokenValues[it] != null }
                    .minByOrNull { kotlin.math.abs((tokenValues[it] ?: 0) - currentTokens) }
                ?: 0
        }

        val tokenSpinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, tokenOptions)
            setSelection(selectedTokenIndex)
        }
        layout.addView(tokenSpinner)

        val costLabel = android.widget.TextView(this).apply {
            text = "\n单任务成本上限（美元）"
            setTextColor(getColor(R.color.colorTextPrimary))
        }
        layout.addView(costLabel)

        val costInput = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "留空 = 不设成本上限"
            setText(currentCost?.let { String.format("%.2f", it) } ?: "")
            setTextColor(getColor(R.color.colorTextPrimary))
        }
        layout.addView(costInput)

        android.app.AlertDialog.Builder(this)
            .setTitle("任务预算")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newTokens = tokenValues[tokenSpinner.selectedItemPosition]
                val newCost = costInput.text.toString().trim().toDoubleOrNull()

                when (newTokens) {
                    null -> io.agents.pokeclaw.agent.TaskBudget.clearMaxTokens()
                    else -> io.agents.pokeclaw.agent.TaskBudget.saveMaxTokens(newTokens)
                }
                when {
                    newCost == null || newCost <= 0.0 -> io.agents.pokeclaw.agent.TaskBudget.clearMaxCost()
                    else -> io.agents.pokeclaw.agent.TaskBudget.saveMaxCost(newCost)
                }

                val summary = io.agents.pokeclaw.agent.TaskBudget.describeCurrentBudget()
                Toast.makeText(this, "预算：$summary", Toast.LENGTH_SHORT).show()
                recreate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
