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
import io.agents.pokeclaw.agent.mcp.McpAuthType
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

        label("\n鉴权方式")
        val authTypes = listOf(
            "无鉴权 (None)" to McpAuthType.NONE,
            "Bearer Token (推荐)" to McpAuthType.BEARER,
            "Basic 凭证 (用户名:密码)" to McpAuthType.BASIC,
            "自定义 Header" to McpAuthType.CUSTOM,
        )
        val initialType = McpAuthType.detect(existing?.headerName, existing?.headerValue)
        val authSpinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@McpServersActivity,
                android.R.layout.simple_spinner_dropdown_item,
                authTypes.map { it.first }
            )
            setSelection(authTypes.indexOfFirst { it.second == initialType }.coerceAtLeast(0))
        }
        layout.addView(authSpinner)

        val headerNameContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 0)
        }
        val headerNameLabel = TextView(this).apply {
            text = "Header 名"
            setTextColor(fieldColor)
            setTypeface(typeface, Typeface.BOLD)
        }
        val headerNameInput = field("X-API-Key", existing?.headerName)
        headerNameContainer.addView(headerNameLabel)
        headerNameContainer.addView(headerNameInput)
        layout.addView(headerNameContainer)

        val headerValueContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 0)
        }
        val headerValueLabel = TextView(this).apply {
            text = "Token / 密钥"
            setTextColor(fieldColor)
            setTypeface(typeface, Typeface.BOLD)
        }
        val initialDisplayCred = existing?.displayCredential() ?: ""
        val headerValueInput = field("填入密钥", initialDisplayCred, password = true)
        headerValueContainer.addView(headerValueLabel)
        headerValueContainer.addView(headerValueInput)
        layout.addView(headerValueContainer)

        fun updateAuthFieldVisibility(type: McpAuthType) {
            when (type) {
                McpAuthType.NONE -> {
                    headerNameContainer.visibility = android.view.View.GONE
                    headerValueContainer.visibility = android.view.View.GONE
                }
                McpAuthType.BEARER -> {
                    headerNameContainer.visibility = android.view.View.GONE
                    headerValueContainer.visibility = android.view.View.VISIBLE
                    headerValueLabel.text = "Bearer Token（填入密钥，系统自动补全 Bearer 前缀）"
                    headerValueInput.hint = "sk-…"
                }
                McpAuthType.BASIC -> {
                    headerNameContainer.visibility = android.view.View.GONE
                    headerValueContainer.visibility = android.view.View.VISIBLE
                    headerValueLabel.text = "Basic 凭证（用户名:密码 或 Base64）"
                    headerValueInput.hint = "user:password"
                }
                McpAuthType.CUSTOM -> {
                    headerNameContainer.visibility = android.view.View.VISIBLE
                    headerValueContainer.visibility = android.view.View.VISIBLE
                    headerNameLabel.text = "Header 名（如 X-API-Key）"
                    headerNameInput.hint = "X-API-Key"
                    headerValueLabel.text = "Header 值"
                    headerValueInput.hint = "值"
                }
            }
        }
        updateAuthFieldVisibility(initialType)

        authSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                updateAuthFieldVisibility(authTypes.getOrNull(position)?.second ?: McpAuthType.NONE)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        val enabledCheck = CheckBox(this).apply {
            text = "启用（保存后立即连接并发现工具）"
            isChecked = existing?.enabled ?: true
            setTextColor(fieldColor)
            setPadding(0, 16, 0, 0)
        }
        layout.addView(enabledCheck)

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(if (existing == null) "添加 MCP 服务器" else "编辑 ${existing.name}")
            .setView(layout)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                val url = urlInput.text.toString().trim()

                val selectedType = authTypes.getOrNull(authSpinner.selectedItemPosition)?.second ?: McpAuthType.BEARER
                val (headerName, headerValue) = when (selectedType) {
                    McpAuthType.NONE -> null to null
                    McpAuthType.BEARER -> {
                        val raw = headerValueInput.text.toString().trim()
                        if (raw.isEmpty()) null to null
                        else {
                            val formatted = if (raw.startsWith("Bearer ", ignoreCase = true)) raw else "Bearer $raw"
                            "Authorization" to formatted
                        }
                    }
                    McpAuthType.BASIC -> {
                        val raw = headerValueInput.text.toString().trim()
                        if (raw.isEmpty()) null to null
                        else {
                            val formatted = if (raw.startsWith("Basic ", ignoreCase = true)) raw else "Basic $raw"
                            "Authorization" to formatted
                        }
                    }
                    McpAuthType.CUSTOM -> {
                        val hName = headerNameInput.text.toString().trim().ifEmpty { null }
                        val hVal = headerValueInput.text.toString().trim().ifEmpty { null }
                        hName to hVal
                    }
                }

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
