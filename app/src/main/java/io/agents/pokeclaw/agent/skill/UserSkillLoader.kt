// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.skill

import android.content.Context
import io.agents.pokeclaw.tool.ToolRegistry
import io.agents.pokeclaw.utils.XLog
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Loads user-defined skill recipes (YAML) into [SkillRegistry].
 *
 * Location: "skills" directory under the app's external files dir (same
 * strategy as mcp_servers.json — external dir preferred so adb push works,
 * internal fallback), one *.yaml recipe per file.
 *
 * Schema (snake_case keys, mirrors the Skill data class):
 *
 * ```yaml
 * id: my_skill            # required, unique
 * name: 我的技能           # required
 * description: 干什么用的
 * category: GENERAL       # INPUT/NAVIGATION/MESSAGING/DISMISS/MEDIA/GENERAL
 * user_facing: true       # show in the Task skills panel
 * estimated_steps_saved: 5
 * triggers:               # regex against the task text, {param} → (.+)
 *   - "打开{app}并搜索{query}"
 * parameters:
 *   - name: query
 *     type: string
 *     required: true
 *     description: 要搜索的内容
 *     default: ""
 * steps:
 *   - tool: open_app      # must exist in ToolRegistry (mcp_* allowed, may register later)
 *     description: 打开应用
 *     optional: false
 *     retries: 1
 *     params:
 *       app_name: "{app}"
 * fallback_goal: 自然语言目标，步骤失败后交给 agent 循环
 * ```
 */
object UserSkillLoader {

    private const val TAG = "UserSkillLoader"
    private const val DIR_NAME = "skills"

    fun skillsDir(context: Context): File =
        context.getExternalFilesDir(null)?.let { File(it, DIR_NAME) }
            ?: File(context.filesDir, DIR_NAME)

    fun listFiles(context: Context): List<File> {
        val dir = skillsDir(context)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && (f.extension == "yaml" || f.extension == "yml") }
            ?.sortedBy { it.name } ?: emptyList()
    }

    /**
     * Load every recipe file and register it. Replaces previously registered
     * USER-source skills first, so this doubles as a reload.
     *
     * @return pair of (loaded count, per-file error messages)
     */
    fun loadAndRegister(context: Context): Pair<Int, List<String>> {
        SkillRegistry.unregisterBySource(SkillSource.USER)
        val errors = mutableListOf<String>()
        var loaded = 0
        for (file in listFiles(context)) {
            try {
                val skill = parse(file.readText())
                if (skill == null) {
                    errors.add("${file.name}: 配方无效（缺 id/name/steps 或引用未知工具）")
                    continue
                }
                SkillRegistry.register(skill)
                loaded++
            } catch (e: Exception) {
                XLog.w(TAG, "Failed to load skill recipe ${file.name}", e)
                errors.add("${file.name}: ${e.message ?: "解析失败"}")
            }
        }
        XLog.i(TAG, "User skills: $loaded loaded, ${errors.size} error(s) from ${skillsDir(context)}")
        return loaded to errors
    }

    /** Parse a YAML recipe. Returns null (with a log) if the recipe is invalid. */
    fun parse(yaml: String): Skill? {
        val root = Yaml().load(yaml) as? Map<*, *> ?: return null

        fun str(map: Map<*, *>, key: String): String = map[key]?.toString()?.trim() ?: ""

        val id = str(root, "id")
        val name = str(root, "name")
        if (id.isEmpty() || name.isEmpty()) {
            XLog.w(TAG, "Recipe rejected: id/name required")
            return null
        }

        val category = root["category"]?.toString()?.trim()?.uppercase()
            ?.let { c -> runCatching { SkillCategory.valueOf(c) }.getOrNull() }
            ?: SkillCategory.GENERAL
        val userFacing = root["user_facing"] as? Boolean ?: false
        val stepsSaved = (root["estimated_steps_saved"] as? Number)?.toInt() ?: 5
        val fallbackGoal = str(root, "fallback_goal")
        val triggers = (root["triggers"] as? List<*>)
            ?.map { it.toString().trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        val parameters = (root["parameters"] as? List<*>)?.mapNotNull { entry ->
            val p = entry as? Map<*, *> ?: return@mapNotNull null
            val pName = str(p, "name")
            if (pName.isEmpty()) return@mapNotNull null
            SkillParameter(
                name = pName,
                type = str(p, "type").ifEmpty { "string" },
                required = p["required"] as? Boolean ?: true,
                description = str(p, "description"),
                defaultValue = str(p, "default")
            )
        } ?: emptyList()

        val steps = (root["steps"] as? List<*>)?.mapNotNull { entry ->
            val s = entry as? Map<*, *> ?: return@mapNotNull null
            val tool = str(s, "tool")
            if (tool.isEmpty()) return@mapNotNull null
            val params = (s["params"] as? Map<*, *>)
                ?.entries?.associate { it.key.toString() to it.value.toString() }
                ?: emptyMap()
            SkillStep(
                toolName = tool,
                params = params,
                description = str(s, "description"),
                optional = s["optional"] as? Boolean ?: false,
                retries = (s["retries"] as? Number)?.toInt() ?: 1
            )
        } ?: emptyList()

        if (steps.isEmpty()) {
            XLog.w(TAG, "Recipe '$id' rejected: no steps")
            return null
        }

        // Validate tool names. mcp_* tools are allowed even when absent — MCP
        // registration is async and may complete after this loader runs.
        val registry = ToolRegistry.getInstance()
        val unknown = steps.map { it.toolName }.distinct().filter {
            registry.getTool(it) == null && !it.startsWith("mcp_")
        }
        if (unknown.isNotEmpty()) {
            XLog.w(TAG, "Recipe '$id' rejected: unknown tool(s) $unknown")
            return null
        }

        return Skill(
            id = id,
            name = name,
            description = str(root, "description"),
            category = category,
            estimatedStepsSaved = stepsSaved,
            steps = steps,
            parameters = parameters,
            triggerPatterns = triggers,
            fallbackGoal = fallbackGoal,
            userFacing = userFacing,
            source = SkillSource.USER
        )
    }

    /** Serialize a skill definition back to YAML (used by the capture editor). */
    fun toYaml(
        id: String,
        name: String,
        description: String,
        triggers: List<String>,
        steps: List<SkillStep>,
        fallbackGoal: String,
        userFacing: Boolean = true
    ): String {
        val root = LinkedHashMap<String, Any>()
        root["id"] = id
        root["name"] = name
        root["description"] = description
        root["category"] = "GENERAL"
        root["user_facing"] = userFacing
        root["estimated_steps_saved"] = steps.size
        root["triggers"] = triggers
        root["steps"] = steps.map { step ->
            LinkedHashMap<String, Any>().apply {
                put("tool", step.toolName)
                put("description", step.description)
                put("optional", step.optional)
                put("retries", step.retries)
                put("params", LinkedHashMap(step.params))
            }
        }
        root["fallback_goal"] = fallbackGoal
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
        }
        return Yaml(options).dump(root)
    }

    /** Build a recipe from a captured task ("save as skill"). */
    fun captureToYaml(
        capture: ToolCallRecorder.Capture,
        id: String,
        name: String,
        description: String
    ): String {
        // Observation-only and terminal tools are noise in a deterministic recipe.
        val skipped = setOf(
            "finish", "get_screen_info", "take_screenshot",
            // Memory calls are side effects, not device steps
            "memory_save", "memory_recall", "memory_forget"
        )
        val steps = capture.calls
            .filterNot { it.toolName in skipped }
            .map { call ->
                SkillStep(
                    toolName = call.toolName,
                    params = call.params.mapValues { it.value.toString() },
                    description = call.toolName
                )
            }
        if (steps.isEmpty()) return ""
        val header = "# 由任务捕获生成，可自由编辑。\n# 原始任务：${capture.task}\n"
        return header + toYaml(
            id = id,
            name = name,
            description = description.ifBlank { "捕获自任务：${capture.task}" },
            triggers = emptyList(),
            steps = steps,
            fallbackGoal = capture.task
        )
    }

    /** Write recipe text to <id>.yaml in the skills dir. Returns the file or null on failure. */
    fun save(context: Context, id: String, yamlText: String): File? {
        return try {
            val dir = skillsDir(context)
            if (!dir.isDirectory) dir.mkdirs()
            val file = File(dir, "$id.yaml")
            file.writeText(yamlText)
            XLog.i(TAG, "Saved skill recipe: ${file.absolutePath}")
            file
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to save skill recipe '$id'", e)
            null
        }
    }

    /** Delete a recipe file; unregisters the skill it defines (if loadable). */
    fun delete(context: Context, file: File): Boolean {
        val id = try {
            parse(file.readText())?.id
        } catch (_: Exception) {
            null
        }
        val deleted = file.delete()
        if (deleted && id != null) {
            SkillRegistry.unregister(id)
        }
        return deleted
    }
}
