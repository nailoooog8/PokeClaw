// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.settings

import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import io.agents.pokeclaw.R
import io.agents.pokeclaw.agent.memory.MemoryEntry
import io.agents.pokeclaw.agent.memory.MemoryKind
import io.agents.pokeclaw.agent.memory.MemoryStore
import io.agents.pokeclaw.base.BaseActivity
import io.agents.pokeclaw.ui.chat.ThemeManager
import io.agents.pokeclaw.widget.CommonToolbar
import io.agents.pokeclaw.widget.ConfirmDialog
import io.agents.pokeclaw.widget.MenuGroup

/**
 * Memory bank manager: browse, search, edit, pin and delete what the agent
 * remembers between sessions.
 *
 * The agent writes here by itself (memory_save), so this screen is mostly for
 * review and correction — the tool a user reaches for when a memory is wrong,
 * too broad, or missing something they would rather state once.
 */
class MemoryActivity : BaseActivity() {

    private lateinit var memoryGroup: MenuGroup
    private lateinit var actionsGroup: MenuGroup
    private var searchQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MemoryStore.init(this)

        val tc = ThemeManager.getColors()
        window.statusBarColor = tc.toolbarBg
        window.decorView.setBackgroundColor(tc.bg)

        setContentView(R.layout.activity_memory)

        val contentFrame = findViewById<ViewGroup>(android.R.id.content)
        contentFrame?.setBackgroundColor(tc.bg)
        (contentFrame?.getChildAt(0) as? ViewGroup)?.setBackgroundColor(tc.bg)

        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle("记忆库")
            setTitleColor(tc.aiText)
            setBackgroundColor(tc.toolbarBg)
            showBackButton(true) { finish() }
            findViewById<android.widget.ImageView>(R.id.ivBack)?.setColorFilter(tc.aiText)
        }

        findViewById<EditText>(R.id.etSearch).apply {
            setTextColor(tc.aiText)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    searchQuery = s?.toString().orEmpty().trim()
                    renderMemories()
                }
            })
        }

        memoryGroup = findViewById(R.id.memoryGroup)
        memoryGroup.setTitleColor(tc.aiText)
        memoryGroup.setCardBackgroundColor(tc.toolbarBg)

        actionsGroup = findViewById(R.id.actionsGroup)
        actionsGroup.setTitleColor(tc.aiText)
        actionsGroup.setCardBackgroundColor(tc.toolbarBg)

        findViewById<TextView>(R.id.tvFooter).apply {
            setTextColor(tc.aiText)
        }
    }

    override fun onResume() {
        super.onResume()
        renderAll()
    }

    private fun renderAll() {
        renderMemories()
        renderActions()
    }

    // ── Memory list ───────────────────────────────────────────────────────────

    private fun filtered(): List<MemoryEntry> {
        val all = MemoryStore.all()
        if (searchQuery.isEmpty()) return all
        val q = searchQuery.lowercase()
        return all.filter {
            it.content.lowercase().contains(q) ||
                it.kindLabel.contains(searchQuery) ||
                it.tags.any { tag -> tag.contains(q) }
        }
    }

    private fun renderMemories() {
        val tc = ThemeManager.getColors()
        memoryGroup.clearMenuItems()

        val entries = filtered()
        memoryGroup.setTitle("记忆（${entries.size}）")

        if (entries.isEmpty()) {
            memoryGroup.addMenuItem(
                leadingIcon = android.R.drawable.ic_menu_info_details,
                title = if (searchQuery.isEmpty()) "还没有记忆。对话中值得长期记住的事会被自动记下来。" else "没有匹配的记忆",
                onClick = {},
                showDivider = false
            ).apply {
                setTitleColor(tc.aiText)
                setLeadingIconColor(tc.aiText)
                setShowTrailingIcon(false)
            }
            return
        }

        for (entry in entries) {
            val conflicted = entry.conflictsWith.isNotEmpty()
            memoryGroup.addMenuItem(
                leadingIcon = leadingIconFor(entry),
                title = (if (entry.pinned) "📌 " else "") + (if (conflicted) "⚠ " else "") + entry.content,
                onClick = { showEditDialog(entry) },
                showDivider = true
            ).apply {
                setTitleColor(tc.aiText)
                setTrailingText(
                    buildString {
                        append(entry.kindLabel)
                        if (conflicted) append(" · 矛盾")
                        if (entry.hits > 0) append(" · ${entry.hits}次")
                    }
                )
                setTrailingTextColor(tc.aiText)
                setLeadingIconColor(tc.aiText)
                setTrailingIconColor(tc.aiText)
                setOnLongClickListener {
                    showDeleteDialog(entry)
                    true
                }
            }
        }

        findViewById<TextView>(R.id.tvFooter).text = footerText()
    }

    private fun leadingIconFor(entry: MemoryEntry): Int = when (entry.kind) {
        MemoryKind.PROFILE -> android.R.drawable.ic_menu_myplaces
        MemoryKind.PREFERENCE -> android.R.drawable.ic_menu_preferences
        MemoryKind.EVENT -> android.R.drawable.ic_menu_recent_history
        else -> android.R.drawable.ic_menu_agenda
    }

    private fun footerText(): String {
        val total = MemoryStore.count()
        val all = MemoryStore.all()
        val pinned = all.count { it.pinned }
        val conflicted = all.count { it.conflictsWith.isNotEmpty() }
        val suppressed = MemoryStore.suppressionCount()
        val state = if (MemoryStore.isEnabled()) "已开启" else "已关闭"
        return "记忆库$state · 共 $total 条（置顶 $pinned 条" +
            (if (conflicted > 0) " · 矛盾 $conflicted 条" else "") +
            "）· 上限 ${MemoryStore.MAX_ENTRIES} 条\n" +
            "存储于应用外部目录 memories.json，可直接用 adb 推送编辑。\n" +
            (if (suppressed > 0) "删除过的 $suppressed 条记忆留有抑制指纹，Agent 不会自动写回相似内容。\n" else "") +
            "点击编辑，长按删除。"
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private fun renderActions() {
        val tc = ThemeManager.getColors()
        actionsGroup.clearMenuItems()
        actionsGroup.setTitle("管理")

        val enabled = MemoryStore.isEnabled()
        actionsGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_manage,
            title = "记忆库总开关",
            onClick = {
                MemoryStore.setEnabled(!enabled)
                Toast.makeText(
                    this,
                    if (enabled) "已关闭：不再向模型注入记忆" else "已开启",
                    Toast.LENGTH_SHORT
                ).show()
                renderAll()
            },
            showDivider = true
        ).apply {
            setTitleColor(tc.aiText)
            setTrailingText(if (enabled) "开启" else "关闭")
            setTrailingTextColor(if (enabled) tc.sendColor else tc.aiText)
            setLeadingIconColor(tc.aiText)
            setTrailingIconColor(tc.aiText)
        }

        actionsGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_add,
            title = "＋ 添加记忆",
            onClick = { showEditDialog(null) },
            showDivider = true
        ).apply {
            setTitleColor(tc.aiText)
            setLeadingIconColor(tc.aiText)
            setTrailingIconColor(tc.aiText)
        }

        actionsGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_save,
            title = "导出为 Markdown",
            onClick = {
                val file = MemoryStore.exportMarkdown()
                Toast.makeText(
                    this,
                    if (file == null) "没有可导出的记忆" else "已导出到 ${file.absolutePath}",
                    Toast.LENGTH_LONG
                ).show()
            },
            showDivider = true
        ).apply {
            setTitleColor(tc.aiText)
            setLeadingIconColor(tc.aiText)
            setTrailingIconColor(tc.aiText)
        }

        actionsGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_delete,
            title = "清空记忆库",
            onClick = { showClearDialog() },
            showDivider = true
        ).apply {
            setTitleColor(tc.aiText)
            setLeadingIconColor(tc.aiText)
            setTrailingIconColor(tc.aiText)
        }

        // Forgetting leaves a fingerprint so the agent cannot re-learn the fact.
        // This is the way back if a deletion was a mistake.
        val suppressed = MemoryStore.suppressionCount()
        actionsGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_close_clear_cancel,
            title = "遗忘抑制（$suppressed）",
            onClick = { showClearSuppressionDialog() },
            showDivider = false
        ).apply {
            setTitleColor(tc.aiText)
            setLeadingIconColor(tc.aiText)
            setTrailingIconColor(tc.aiText)
        }
    }

    private fun showClearSuppressionDialog() {
        val n = MemoryStore.suppressionCount()
        if (n == 0) {
            Toast.makeText(this, "当前没有抑制记录", Toast.LENGTH_SHORT).show()
            return
        }
        ConfirmDialog.showWarm(
            context = this,
            title = "清除遗忘抑制？",
            message = "将清除 $n 条抑制指纹。清除后，Agent 可以重新自动记住你之前删掉的内容。",
            actionTitle = "清除",
            cancelTitle = getString(R.string.common_cancel),
            onAction = {
                val cleared = MemoryStore.clearSuppressions()
                Toast.makeText(this, "已清除 $cleared 条抑制指纹", Toast.LENGTH_SHORT).show()
                renderAll()
            }
        )
    }

    private fun showDeleteDialog(entry: MemoryEntry) {
        ConfirmDialog.showWarm(
            context = this,
            title = "删除这条记忆？",
            message = entry.content +
                "\n\n删除后会留下抑制指纹，Agent 之后不会自动写回相似内容" +
                "（可在「遗忘抑制」里清除）。",
            actionTitle = "删除",
            cancelTitle = getString(R.string.common_cancel),
            onAction = {
                MemoryStore.delete(entry.id)
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                renderAll()
            }
        )
    }

    private fun showClearDialog() {
        ConfirmDialog.showWarm(
            context = this,
            title = "清空记忆库？",
            message = "将删除全部 ${MemoryStore.count()} 条记忆，Agent 会从零开始重新认识你。",
            actionTitle = "清空",
            cancelTitle = getString(R.string.common_cancel),
            onAction = {
                val n = MemoryStore.clear()
                Toast.makeText(this, "已清空 $n 条", Toast.LENGTH_SHORT).show()
                renderAll()
            }
        )
    }

    private fun showEditDialog(existing: MemoryEntry?) {
        // System AlertDialog is light-themed: fixed dark text, not theme colors
        val fieldColor = 0xFF333333.toInt()
        val labelColor = 0xFF666666.toInt()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        layout.addView(TextView(this).apply {
            text = "记忆内容（一条一个事实，写成能独立成立的短句）"
            setTextColor(fieldColor)
            setTypeface(typeface, Typeface.BOLD)
        })
        val contentInput = EditText(this).apply {
            hint = "例：用户的通勤方式是地铁，单程约 40 分钟"
            setText(existing?.content ?: "")
            minLines = 2
            maxLines = 4
            setTextColor(fieldColor)
            setHintTextColor(labelColor)
        }
        layout.addView(contentInput)

        layout.addView(TextView(this).apply {
            text = "\n分类"
            setTextColor(fieldColor)
            setTypeface(typeface, Typeface.BOLD)
        })
        val kindGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val kindButtons = HashMap<String, RadioButton>()
        for (kind in MemoryKind.all) {
            val button = RadioButton(this).apply {
                text = MemoryKind.labelOf(kind)
                isChecked = (existing?.kind ?: MemoryKind.FACT) == kind
                setTextColor(fieldColor)
                textSize = 14f
            }
            kindButtons[kind] = button
            kindGroup.addView(button)
        }
        layout.addView(kindGroup)

        layout.addView(TextView(this).apply {
            text = "\n标签（逗号分隔，可选）"
            setTextColor(fieldColor)
            setTypeface(typeface, Typeface.BOLD)
        })
        val tagsInput = EditText(this).apply {
            hint = "device,permissions"
            setText(existing?.tags?.joinToString(",") ?: "")
            setSingleLine(true)
            setTextColor(fieldColor)
            setHintTextColor(labelColor)
        }
        layout.addView(tagsInput)

        val pinnedCheck = CheckBox(this).apply {
            text = "置顶（每次都注入，不参与衰减）"
            isChecked = existing?.pinned ?: false
            setTextColor(fieldColor)
        }
        layout.addView(pinnedCheck)

        android.app.AlertDialog.Builder(this)
            .setTitle(if (existing == null) "添加记忆" else "编辑记忆")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val content = contentInput.text.toString().trim()
                if (content.isEmpty()) {
                    Toast.makeText(this, "内容不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val kind = kindButtons.entries.firstOrNull { it.value.isChecked }?.key
                    ?: MemoryKind.FACT
                val tags = tagsInput.text.toString()
                    .split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val saved = MemoryStore.remember(
                    content = content,
                    kind = kind,
                    tags = tags,
                    pinned = pinnedCheck.isChecked,
                    id = existing?.id
                )
                val message = when (saved) {
                    is MemoryStore.RememberResult.Suppressed ->
                        "与被删除过的记忆太像，已作为手动添加保存"

                    is MemoryStore.RememberResult.Stored -> when {
                        saved.merged -> "已合并到另一条记忆（共 ${MemoryStore.count()} 条）"
                        saved.conflicts.isNotEmpty() ->
                            "已保存，但与 ${saved.conflicts.size} 条已有记忆矛盾（列表里有 ⚠ 标记）"

                        else -> "已保存，共 ${MemoryStore.count()} 条"
                    }
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                renderAll()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}