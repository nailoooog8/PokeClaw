# 自定义技能配方格式（Skills 二期）

用户自定义技能是 YAML 声明式配方：按顺序调用已注册工具（含 MCP 远程工具），
由 `SkillExecutor` 确定性执行，失败时把 `fallback_goal` 交给 LLM agent 循环兜底。
相比 agent 循环每步 3-10 秒推理，确定性技能零推理直接执行。

## 存放位置与加载

- 目录：`<外部存储>/Android/data/io.agents.pokeclaw/files/skills/*.yaml`（与 `mcp_servers.json` 同级，adb 推送兼容；无外部存储时回退内部 `filesDir/skills/`）
- 加载：app 启动时 `UserSkillLoader.loadAndRegister()`（`ClawApplication.onCreate`，在内置技能之后）
- 校验：步骤引用的工具必须在 `ToolRegistry` 中；`mcp_*` 前缀的远程工具允许（MCP 异步注册，可能晚于本加载器）；其余未知工具 → 整个文件拒载并记日志
- 生命周期：配方解析失败只跳过该文件，不影响其他技能；删除配方文件即卸载

## 触发方式

1. **语音/文本任务自动匹配**：`triggers` 里的正则对任务文本做 `containsMatchIn`，`{param}` 占位符转成 `(.+)` 捕获组；含 " and / then / after " 的复合任务跳过技能匹配直接走 agent 循环
2. **技能面板手动点**：`user_facing: true` 的技能出现在聊天页 Workflows 面板

## 字段

| 字段 | 必填 | 说明 |
|---|---|---|
| `id` | ✓ | 唯一 ID，同 ID 会覆盖已注册技能（含内置技能，慎用） |
| `name` | ✓ | 显示名 |
| `description` | | 给用户看，也会进 agent 的技能描述 |
| `category` | | `INPUT` / `NAVIGATION` / `MESSAGING` / `DISMISS` / `MEDIA` / `GENERAL`（默认 GENERAL） |
| `user_facing` | | true 才出现在技能面板 |
| `estimated_steps_saved` | | 面板展示用，默认 5 |
| `triggers` | | 正则列表，空列表 = 只能手动触发 |
| `parameters` | | `{name, type, required, description, default}`；步骤参数里 `{name}` 会被替换 |
| `steps` | ✓ | 见下 |
| `fallback_goal` | | 任一非 optional 步骤重试耗尽后的 agent 兜底目标（支持 `{param}` 替换） |

### step 字段

| 字段 | 默认 | 说明 |
|---|---|---|
| `tool` | （必填） | 工具名，同 `ToolRegistry`（`open_app` / `input_text` / `find_and_tap` / `system_key` / `swipe` / `wait` / `mcp_{服务器}_{工具}` …） |
| `params` | `{}` | 字符串键值对，值支持 `{参数名}` 占位符 |
| `description` | "" | 进度提示 |
| `optional` | false | 失败时跳过继续 |
| `retries` | 1 | 重试次数，间隔 500ms |

## "保存为技能"（捕获）

agent 循环内每次工具调用都被 `ToolCallRecorder` 记录（`ToolRegistry.executeTool` 钩子，
只在 agent 循环期间 armed；直连工具/技能执行路径不记录）。任务成功结束
（finish 工具或纯文本完成）时快照到 `ToolCallRecorder.lastCapture`。

入口：设置 → 工具 → **自定义技能** → "从上次任务创建"。
生成时自动剔除 `finish` / `get_screen_info` / `take_screenshot` 这类观察性/终止性调用，
生成的 YAML 可在对话框里自由编辑后保存，立即注册生效（无需重启）。

## 示例

见 `scripts/sample_skill.yaml`（打开系统设置并等待，纯内置工具，可直接 adb push 测试）。

## 端到端验证方法

```bash
# 1. 推配方
adb push scripts/sample_skill.yaml /sdcard/Android/data/io.agents.pokeclaw/files/skills/open_settings_demo.yaml
# 2. 重启 app，看启动日志
adb logcat -s SkillRegistry:* UserSkillLoader:*
#    预期：Registered skill: open_settings_demo (打开设置) / User skills: 1 loaded
# 3. 聊天页 Workflows 面板应出现"打开设置"卡片，点击即确定性执行
```
