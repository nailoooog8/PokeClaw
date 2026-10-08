// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.settings

import android.graphics.Typeface
import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.agents.pokeclaw.R
import io.agents.pokeclaw.agent.mcp.McpConnectionManager
import io.agents.pokeclaw.agent.mcp.McpServerConfig
import io.agents.pokeclaw.base.BaseActivity
import io.agents.pokeclaw.widget.ConfirmDialog
import io.agents.pokeclaw.widget.CommonToolbar
import io.agents.pokeclaw.widget.MenuGroup
import kotlinx.coroutines.launch

/**
 * MCP server manager: lists configured remote MCP servers, lets the user
 * add / edit / delete / enable-disable them and shows live connection status.
 *
 * Persisted through McpConnectionManager.saveConfigs (mcp_servers.json in the
 * app's external files dir, same file adb workflows used before this UI).
 */
class McpServersActivity : BaseActivity() {

    private lateinit var serversGroup: MenuGroup
    private var configs: List<McpServerConfig> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val tc = io.agents.pokeclaw.ui.chat.ThemeManager.getColors()
        window.statusBarColor = tc.toolbarBg
        window.decorView.setBackgroundColor(tc.bg)

        setContentView(R.layout.activity_mcp_servers)

        val contentFrame = findViewById<android.view.ViewGroup>(android.R.id.content)
        contentFrame?.setBackgroundColor(tc.bg)
        (contentFrame?.getChildAt(0) as? android.view.View)?.setBackgroundColor(tc.bg)

        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle("MCP 服务器")
            setTitleColor(tc.aiText)
            setBackgroundColor(tc.toolbarBg)
            showBackButton(true) { finish() }
            findViewById<android.widget.ImageView>(R.id.ivBack)?.setColorFilter(tc.aiText)
        }

        serversGroup = findViewById(R.id.serversGroup)
        serversGroup.setTitleColor(tc.aiText)
        serversGroup.setCardBackgroundColor(tc.toolbarBg)

        findViewById<TextView>(R.id.tvConfigPath)?.apply {
            text = "配置存储于 mcp_servers.json（应用外部目录），与 adb 推送方式兼容。\n" +
                "鉴权 Header 值以 enc:v1: 开头的密文保存，请在应用内填写。\n" +
                "点击服务器编辑，长按删除。"
            setTextColor(tc.aiText)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                McpConnectionManager.statuses.collect {
                    renderServerItems()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        reloadConfigs()
    }

    private fun reloadConfigs() {
        configs = McpConnectionManager.loadConfigs(this)
        renderServerItems()
    }

    private fun statusText(name: String): String {
        val s = McpConnectionManager.statuses.value[name] ?: return "未连接"
        return when (s.state) {
            McpConnectionManager.ServerState.CONNECTED -> "${s.toolCount} tools"
            McpConnectionManager.ServerState.CONNECTING -> "Connecting…"
            McpConnectionManager.ServerState.FAILED -> "Failed: ${s.error?.take(40) ?: "unknown"}"
            McpConnectionManager.ServerState.DISABLED -> "Disabled"
        }
    }

    private fun renderServerItems() {
        val tc = io.agents.pokeclaw.ui.chat.ThemeManager.getColors()
        serversGroup.clearMenuItems()
        serversGroup.setTitle("Servers（${configs.size}）")

        for (config in configs) {
            serversGroup.addMenuItem(
                leadingIcon = android.R.drawable.ic_menu_manage,
                title = config.name,
                onClick = { showEditDialog(config) },
                showDivider = true
            ).apply {
                setTitle(if (config.enabled) config.name else config.name + "（已停用）")
                setTrailingText(statusText(config.name))
                setTitleColor(tc.aiText)
                setTrailingTextColor(if (config.enabled) tc.sendColor else tc.aiText)
                setLeadingIconColor(tc.aiText)
                setTrailingIconColor(tc.aiText)
                setOnLongClickListener {
                    showDeleteDialog(config)
                    true
                }
            }
        }

        // Add entry
        serversGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_add,
            title = "＋ 添加服务器",
            onClick = { showEditDialog(null) },
            showDivider = false
        ).apply {
            setTitleColor(tc.sendColor)
            setLeadingIconColor(tc.sendColor)
            setShowTrailingIcon(false)
        }
    }

    private fun showDeleteDialog(config: McpServerConfig) {
        ConfirmDialog.showWarm(
            context = this,
            title = "删除 MCP 服务器？",
            message = "将移除 \"${config.name}\" 的配置，并从工具注册表中注销其全部远程工具。",
            actionTitle = "删除",
            cancelTitle = getString(R.string.common_cancel),
            onAction = {
                val updated = configs.filterNot { it.name == config.name }
                McpConnectionManager.saveConfigs(this, updated)
                McpConnectionManager.removeServer(this, config)
                reloadConfigs()
                Toast.makeText(this, "已删除 ${config.name}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun showEditDialog(existing: McpServerConfig?) {
        // System AlertDialog is light-themed: use fixed dark text, not theme colors
        val fieldColor = 0xFF333333.toInt()
        val labelColor = 0xFF666666.toInt()

        fun field(hint: String, preset: String?, singleLine: Boolean = true, password: Boolean = false): EditText {
            return EditText(this).apply {
                this.hint = hint
                setText(preset ?: "")
                setSingleLine(singleLine)
                setTextColor(fieldColor)
                setHintTextColor(labelColor)
                if (password) {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        fun label(text: String) {
            layout.addView(TextView(this).apply {
                this.text = text
                setTextColor(fieldColor)
                setTypeface(typeface, Typeface.BOLD)
            })
        }

        label("名称（用于工具前缀 mcp_{名称}_…）")
        val nameInput = field("my-server", existing?.name)
        layout.addView(nameInput)

        label("\nStreamable HTTP 端点")
        val urlInput = field("https://example.com/mcp", existing?.url)
        layout.addView(urlInput)

        label("\n鉴权 Header 名（可选，如 Authorization）")
        val headerNameInput = field("留空 = 不带鉴权", existing?.headerName)
        layout.addView(headerNameInput)

        label("\n鉴权 Header 值（可选，如 Bearer sk-…）")
        val headerValueInput = field("", existing?.headerValue, password = true)
        layout.addView(headerValueInput)

        val enabledCheck = CheckBox(this).apply {
            text = "启用（保存后立即连接并发现工具）"
            isChecked = existing?.enabled ?: true
            setTextColor(fieldColor)
        }
        layout.addView(enabledCheck)

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(if (existing == null) "添加 MCP 服务器" else "编辑 ${existing.name}")
            .setView(layout)
            // Validate on click instead of in the builder callback: a builder
            // callback runs after the dialog is already dismissed, so a rejected
            // input threw away everything the user had typed.
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                val url = urlInput.text.toString().trim()
                val headerName = headerNameInput.text.toString().trim().ifEmpty { null }
                val headerValue = headerValueInput.text.toString().trim().ifEmpty { null }

                if (name.isEmpty()) {
                    Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val lower = url.lowercase()
                if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                    Toast.makeText(this, "URL 必须以 http:// 或 https:// 开头", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                val fresh = McpServerConfig(
                    name = name,
                    url = url,
                    headerName = headerName,
                    headerValue = headerValue,
                    enabled = enabledCheck.isChecked
                )
                var updated = configs.filterNot { it.name == name } + fresh
                if (existing != null && existing.name != name) {
                    // Renamed: drop the entry under the old name too
                    updated = updated.filterNot { it.name == existing.name }
                }
                if (!McpConnectionManager.saveConfigs(this, updated)) {
                    Toast.makeText(this, "配置写入失败", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                configs = updated
                if (existing != null && existing.name != name) {
                    // Unregister the old server, otherwise its mcp_<oldName>_* tools
                    // stay registered and callable after the rename.
                    McpConnectionManager.removeServer(this, existing)
                }
                McpConnectionManager.connectOne(this, fresh)
                renderServerItems()
                Toast.makeText(this, "已保存，正在连接 ${fresh.name}…", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
