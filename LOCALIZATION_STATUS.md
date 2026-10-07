# 汉化补丁状态（交接文档）

> 2026-10-07 更新。装机+权限+MCP e2e+MCP 管理 UI 已全部完成（见下），剩余：Skills 二期。
> 扫描脚本 `PokeClaw/.tmp_scan_ui.py` 可重跑（列出所有硬编码 UI 英文串的文件+行号+内容）。
> MCP 测试服务器已入库：`PokeClaw/scripts/mcp_test_server.py`（python 直接跑，端口 8765，工具 echo/server_uptime）。

## 已完成（2026-10-07 实测验证）

- **图标修复**：foreground/monochrome 两层 inset 56/72dp → 21dp，图标正常显示
- **装机**：构建 + `pm uninstall -k` 保数据 + shell 全新安装（受限设置标志随之清除），数据完整保留（mmkv/API Key 都在，无需重填）
- **无障碍**：`ClawAccessibilityService` 已绑定（capabilities=161，稳定不回弹）——注意 adb 全路线已证伪（settings put 秒回写、appops set 静默丢弃），必须 UI 手动拨开关
- **通知监听**：`ClawNotificationListener` 已启用
- **MCP e2e 全链路**：测试服务器(8765) → `adb reverse tcp:8765 tcp:8765` → `mcp_servers.json` 推到 `/sdcard/Android/data/io.agents.pokeclaw/files/` → 重启 app → logcat 实锤 `Registered 2 MCP tools from 'e2e' (registry size: 30)`；**agent 循环内实调 echo 工具成功**（服务器日志见 tools/call，云端走 OpenAI 格式 + 中转站 GLM/DeepSeek）——2026-10-07 全部实测通过，USB 隧道不依赖 WiFi

## 已完成（编译通过，APK 已产出）

- **LlmConfigActivity.kt**：31 处全部汉化 ✓（API Key 输入、模型选择、下载状态、云端连接状态、存储统计）
- **SettingsActivity.kt**：主设置菜单 24 项中的 24 处已替换（外部自动化、通知使用权、任务预算、主题、管理工具、Telegram、Web 控制台、报告问题等）——其中最后一批编辑因会话劣化存在不确定性，编译已通过（语法/语义有效），建议装机后目视复核
- 另有少量多行对话框正文（reportBug 的 AlertDialog message）未汉化

## 待办（新会话接手）

1. 剩余 13 处低频字符串：
   - `ui/chat/TaskFlowController.kt`（5 处：无障碍服务错误提示 ×4 + 请先配置 LLM）
   - `ui/chat/ChatScreen.kt`（4 处：Stop / Stop All / Monitor Messages / Send Message）
   - `ui/settings/ChannelConfigActivity.kt`（2 处："Bot Token"）
   - `ui/settings/ThemeActivity.kt`（1 处："Current: $label"）
   - `ui/chat/ComposeChatActivity.kt`（1 处："Image upload coming soon"）
2. ~~MCP tools/call 实调验证~~ ✅ 2026-10-07 完成（agent 循环实调 echo 成功）
3. ~~MCP 服务器管理 UI~~ ✅ 2026-10-07 完成（`ui/settings/McpServersActivity`：设置页 Tools 组入口，列表/添加/编辑/长按删除/启停，实时连接状态 StateFlow；`McpConnectionManager` 扩展 saveConfigs/connectOne/removeServer + 状态跟踪；`ToolRegistry` 加 unregister/unregisterByPrefix。装机实测：列表显示 "e2e · 2 tools"，编辑保存→落盘→重连→重注册全通。注意：系统 AlertDialog 白底，表单文字用固定深色而非主题色）
4. Skills 层二期（YAML recipe，见 MCP_INTEGRATION_PLAN.md 第 5 步）

## 相关文件

- `MCP_INTEGRATION_PLAN.md` — MCP/Skills 集成设计（已完成编码部分见此文档）
- fork 位置：本地仓库（agents-io/PokeClaw @ 0.7.1 + MCP 四件套 + 汉化补丁），工作分支 `fork/mcp-extension`
- 构建：JDK17 在 `../tools/jdk17`，代理在用户级 `~/.gradle/gradle.properties`
