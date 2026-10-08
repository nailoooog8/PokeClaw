// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.settings

import android.graphics.Typeface
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.agents.pokeclaw.R
import io.agents.pokeclaw.agent.skill.SkillRegistry
import io.agents.pokeclaw.agent.skill.ToolCallRecorder
import io.agents.pokeclaw.agent.skill.UserSkillLoader
import io.agents.pokeclaw.base.BaseActivity
import io.agents.pokeclaw.widget.ConfirmDialog
import io.agents.pokeclaw.widget.CommonToolbar
import io.agents.pokeclaw.widget.MenuGroup
import java.io.File

/**
 * User skill manager: lists YAML skill recipes, lets the user create new ones
 * from the last captured agent task, edit the YAML by hand, and delete them.
 *
 * Persisted as *.yaml files in the app's external files dir (/skills/), same
 * location strategy as mcp_servers.json — adb push workflows still work.
 */
class UserSkillsActivity : BaseActivity() {

    private lateinit var skillsGroup: MenuGroup
    private var files: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val tc = io.agents.pokeclaw.ui.chat.ThemeManager.getColors()
        window.statusBarColor = tc.toolbarBg
        window.decorView.setBackgroundColor(tc.bg)

        setContentView(R.layout.activity_user_skills)

        val contentFrame = findViewById<android.view.ViewGroup>(android.R.id.content)
        contentFrame?.setBackgroundColor(tc.bg)
        (contentFrame?.getChildAt(0) as? android.view.View)?.setBackgroundColor(tc.bg)

        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle("自定义技能")
            setTitleColor(tc.aiText)
            setBackgroundColor(tc.toolbarBg)
            showBackButton(true) { finish() }
            findViewById<android.widget.ImageView>(R.id.ivBack)?.setColorFilter(tc.aiText)
        }

        skillsGroup = findViewById(R.id.skillsGroup)
        skillsGroup.setTitleColor(tc.aiText)
        skillsGroup.setCardBackgroundColor(tc.toolbarBg)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        files = UserSkillLoader.listFiles(this)
        renderItems()
    }

    private fun renderItems() {
        val tc = io.agents.pokeclaw.ui.chat.ThemeManager.getColors()
        skillsGroup.clearMenuItems()
        skillsGroup.setTitle("技能配方（${files.size}）")

        for (file in files) {
            val skill = try {
                UserSkillLoader.parse(file.readText())
            } catch (_: Exception) {
                null
            }
            val title = skill?.name ?: file.name
            val trailing = when {
                skill == null -> "解析失败"
                else -> "${skill.steps.size} 步"
            }
            skillsGroup.addMenuItem(
                leadingIcon = android.R.drawable.ic_menu_agenda,
                title = title,
                onClick = { showEditDialog(file) },
                showDivider = true
            ).apply {
                setTitle(title)
                setTrailingText(trailing)
                setTitleColor(tc.aiText)
                setTrailingTextColor(if (skill == null) tc.sendColor else tc.aiText)
                setLeadingIconColor(tc.aiText)
                setTrailingIconColor(tc.aiText)
                setOnLongClickListener {
                    showDeleteDialog(file, title)
                    true
                }
            }
        }

        // Create from last captured task
        val capture = ToolCallRecorder.lastCapture
        val captureTitle = if (capture == null) "＋ 从上次任务创建（暂无捕获）" else "＋ 从上次任务创建"
        skillsGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_camera,
            title = captureTitle,
            onClick = {
                if (capture == null) {
                    Toast.makeText(this, "还没有捕获：完成一个任务后再来（agent 循环执行成功后会记录工具序列）", Toast.LENGTH_LONG).show()
                } else {
                    showCreateFromCaptureDialog(capture)
                }
            },
            showDivider = false
        ).apply {
            setTitleColor(if (capture == null) tc.aiText else tc.sendColor)
            setLeadingIconColor(if (capture == null) tc.aiText else tc.sendColor)
            setShowTrailingIcon(false)
        }

        // Manual blank recipe
        skillsGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_add,
            title = "＋ 手动新建配方",
            onClick = { showEditDialog(null) },
            showDivider = false
        ).apply {
            setTitleColor(tc.sendColor)
            setLeadingIconColor(tc.sendColor)
            setShowTrailingIcon(false)
        }

        findViewById<TextView>(R.id.tvCaptureHint)?.apply {
            text = "技能配方存于应用外部目录 /skills/（*.yaml），与 adb 推送方式兼容，重启后自动加载。\n" +
                "点击配方可查看/编辑 YAML，长按删除。\n" +
                "配方引用的工具须在工具注册表中（mcp_* 远程工具允许后注册）。"
            setTextColor(tc.aiText)
        }
    }

    private fun showDeleteDialog(file: File, title: String) {
        ConfirmDialog.showWarm(
            context = this,
            title = "删除技能？",
            message = "将删除 \"${file.name}\"（$title），下次启动不再加载。",
            actionTitle = "删除",
            cancelTitle = getString(R.string.common_cancel),
            onAction = {
                val ok = UserSkillLoader.delete(this, file)
                if (!ok) Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show()
                reload()
            }
        )
    }

    /** Create-from-capture: ask for id/name, then open the YAML editor prefilled. */
    private fun showCreateFromCaptureDialog(capture: ToolCallRecorder.Capture) {
        val fieldColor = 0xFF333333.toInt()
        val labelColor = 0xFF666666.toInt()

        fun field(hint: String, preset: String): EditText {
            return EditText(this).apply {
                this.hint = hint
                setText(preset)
                setSingleLine(true)
                setTextColor(fieldColor)
                setHintTextColor(labelColor)
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

        val suggestedId = "captured_" + (System.currentTimeMillis() / 1000)
        label("技能 ID（文件名，英文/数字/下划线）")
        val idInput = field("my_skill", suggestedId)
        layout.addView(idInput)

        label("\n技能名称")
        val nameInput = field("我的技能", capture.task.take(20))
        layout.addView(nameInput)

        label("\n描述（可留空）")
        val descInput = field("", "")
        layout.addView(descInput)

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("从上次任务创建技能")
            .setMessage("已捕获 ${capture.calls.size} 次工具调用（任务：${capture.task.take(60)}）")
            .setView(layout)
            // Validate on click, not in the builder callback: the builder
            // callback runs after dismissal, so a rejected ID threw away the
            // name and description the user had already typed.
            .setPositiveButton("生成配方", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val id = idInput.text.toString().trim()
                val name = nameInput.text.toString().trim()
                if (id.isEmpty() || !id.matches(Regex("[a-zA-Z0-9_]+"))) {
                    Toast.makeText(this, "ID 只能含英文/数字/下划线", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (name.isEmpty()) {
                    Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val yaml = UserSkillLoader.captureToYaml(
                    capture, id, name, descInput.text.toString().trim()
                )
                if (yaml.isEmpty()) {
                    Toast.makeText(this, "捕获中没有可用的动作步骤（只截屏/finish 的任务无法成技能）", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                showEditor(id, yaml, isNew = true)
            }
        }
        dialog.show()
    }

    /** YAML editor: new file (null) / existing file / prefilled capture text. */
    private fun showEditDialog(file: File?) {
        val presetId = file?.nameWithoutExtension ?: ""
        val presetYaml = try {
            file?.readText() ?: TEMPLATE_YAML
        } catch (_: Exception) {
            TEMPLATE_YAML
        }
        showEditor(presetId, presetYaml, isNew = file == null, editingFile = file)
    }

    private fun showEditor(
        presetId: String,
        presetYaml: String,
        isNew: Boolean,
        editingFile: File? = null
    ) {
        val fieldColor = 0xFF333333.toInt()
        val labelColor = 0xFF666666.toInt()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        layout.addView(TextView(this).apply {
            text = "技能 ID"
            setTextColor(fieldColor)
            setTypeface(typeface, Typeface.BOLD)
        })
        val idInput = EditText(this).apply {
            setText(presetId)
            setSingleLine(true)
            setTextColor(fieldColor)
            setHintTextColor(labelColor)
            // Editing an existing file: id fixed to the filename
            isEnabled = isNew || editingFile == null
        }
        layout.addView(idInput)

        layout.addView(TextView(this).apply {
            text = "YAML 配方"
            setTextColor(fieldColor)
            setTypeface(typeface, Typeface.BOLD)
        })
        val yamlInput = EditText(this).apply {
            setText(presetYaml)
            setSingleLine(false)
            minLines = 8
            maxLines = 20
            setTextColor(fieldColor)
            setTypeface(Typeface.MONOSPACE)
            textSize = 12f
        }
        layout.addView(yamlInput)

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(if (isNew) "保存技能配方" else "编辑配方")
            .setView(layout)
            // Validate on click: a YAML error in the builder callback fired
            // after dismissal and discarded the whole recipe the user typed.
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val id = idInput.text.toString().trim()
                val yaml = yamlInput.text.toString()
                if (id.isEmpty() || !id.matches(Regex("[a-zA-Z0-9_]+"))) {
                    Toast.makeText(this, "ID 只能含英文/数字/下划线", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val parsed = try {
                    UserSkillLoader.parse(yaml)
                } catch (e: Exception) {
                    null
                }
                if (parsed == null) {
                    Toast.makeText(this, "YAML 解析失败或引用未知工具，未保存", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (parsed.id != id) {
                    Toast.makeText(this, "YAML 里的 id（${parsed.id}）须与上方 ID 一致", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                // Renaming an existing file: remove the old one
                if (editingFile != null && editingFile.name != "$id.yaml") {
                    editingFile.delete()
                }
                val saved = UserSkillLoader.save(this, id, yaml)
                if (saved == null) {
                    Toast.makeText(this, "写入失败", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                SkillRegistry.register(parsed)
                Toast.makeText(this, "已保存并启用：${parsed.name}（${parsed.steps.size} 步）", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                reload()
            }
        }
        dialog.show()
    }

    private companion object {
        const val TEMPLATE_YAML = """id: my_skill
name: 我的技能
description: 这个技能做什么
category: GENERAL
user_facing: true
estimated_steps_saved: 3
triggers: []
parameters: []
steps:
  - tool: open_app
    description: 打开应用
    optional: false
    retries: 1
    params:
      app_name: "设置"
  - tool: wait
    description: 等待加载
    optional: false
    retries: 1
    params:
      duration_ms: "2000"
fallback_goal: 完成上述操作
"""
    }
}
