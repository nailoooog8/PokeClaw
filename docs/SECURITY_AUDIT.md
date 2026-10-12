# 安全审计报告 — PokeClaw Fork

> 对应版本：v0.8.0 · 审计日期：2026-10-11 · 范围：`fork/personal-fork-release` 相对 `origin/main`（`69fd2bf`）的改动，以及上游遗留的高暴露面代码。
>
> 方法：OWASP MASTG / Android IPC 边界 / 间接提示注入（indirect prompt injection）三个角度的静态红队走查 + 单测回归。
> E2E 用例见 [`QA_CHECKLIST.md`](../QA_CHECKLIST.md) 的 `SEC1`–`SEC11`。

---

## 1. 结论摘要

| ID | 等级 | 问题 | 修复 | 状态 |
|---|---|---|---|---|
| r1 | **Critical** | 6 位 PIN 零限速，渠道配对与 LAN 控制台两处比较均可爆破 | [`BruteForceGuard`](../app/src/main/java/io/agents/pokeclaw/utils/BruteForceGuard.kt) | 已修复 |
| r2 | **Critical** | `GET /api/channels`、`GET /api/llm` 明文返回全部 bot token 与 LLM API Key | [`ConfigServer.maskIfPresent`](../app/src/main/java/io/agents/pokeclaw/server/ConfigServer.kt) | 已修复 |
| r3 | **Medium** | `handleServeFile` 用字符串前缀校验路径，不折叠 `..` | 同上文件 `handleServeFile` | 已修复 |
| r4 | **High** | 远程 MCP 服务器的 `description` 原样进入 system prompt | [`McpDescriptionSanitizer`](../app/src/main/java/io/agents/pokeclaw/agent/mcp/McpDescriptionSanitizer.kt) | 已修复 |

**必须诚实说明：以上「已修复」指的是代码层已修复且有单测覆盖。SEC1–SEC11 的真机 E2E 验证全部尚未执行**（见第 4 节），不要把本文当作「已通过安全测试」的证明。

---

## 2. 修复细节

### r1 — PIN 暴力破解（Critical）

**问题**：`ChannelSecurityGuard.verify()` 与 `ConfigServer.authenticate()` 都对 6 位 PIN 做直接比较，无失败计数、无锁定、无延迟。6 位十进制即 10⁶ 组合空间，局域网内可自动化穷举。

**修复**：新增 `BruteForceGuard`，两个入口统一接入。行为：

- 连续失败 5 次 → 锁定约 30 秒
- 再次失败 → 锁定时长约 2 倍递增（`strikes` 计数）
- 成功后 `onSuccess()` 清空记录
- PIN 比较使用**常量时间**比较，避免时序侧信道

**回归**：`BruteForceGuardTest`（6 用例）、`ChannelSecurityGuardTest`（8 用例）。

### r2 — 凭据明文泄漏（Critical）

**问题**：LAN 控制台的读接口直接把存储的 bot token 与 API Key 原样返回。任何通过 PIN 认证的客户端（以及任何能绕过认证的路径）都能拿到全部渠道凭据。

**修复**：`maskIfPresent()` 在读路径返回掩码占位符。写路径的 POST handler 已能识别掩码值并忽略，因此「页面带着掩码回存」不会把真实凭据清空（`isMaskedValue`）。

**回归**：E2E 用例 SEC1 / SEC2，**尚未执行**。

### r3 — 路径穿越（Medium）

**问题**：`absolutePath.startsWith(cacheDir)` 是字符串前缀检查，而 `absolutePath` 不折叠 `..`，因此 `<cacheDir>/../../databases/app.db` 可以通过校验。

**修复**：两侧都取 `canonicalFile`（折叠 `..` 与符号链接）后再用 `Path.startsWith(Path)` 做逐段比较：

```kotlin
val cacheDir = context.cacheDir.canonicalFile
val file = java.io.File(path).canonicalFile      // ".." 在此已被解析
if (!file.exists() || !file.toPath().startsWith(cacheDir.toPath())) { /* 404 */ }
```

**回归**：E2E 用例 SEC7，**尚未执行**。

### r4 — MCP 工具投毒（High）

**问题**：`McpTool` 把第三方 MCP 服务器提供的 `description` 直接拼进 system prompt。一个能控制自身工具描述的恶意服务器，等于能对一个「会点击、会输入、会发消息」的模型下达指令。

**修复**：`McpDescriptionSanitizer` 对远程描述做围栏包裹（`[UNTRUSTED SERVER TEXT …]`）、剥离伪造的闭合围栏、清除零宽字符。

**回归**：`McpDescriptionSanitizerTest`（8 用例）+ E2E 用例 SEC9，**E2E 尚未执行**。

---

## 3. 已证伪的假设

以下三条在初筛时是疑点，经实测后**排除**，不再重复排查：

| 疑点 | 证伪依据 |
|---|---|
| SnakeYAML 反序列化 RCE | 实测 SnakeYAML 2.2 的 `Yaml()` 已禁用全局标签，反序列化 `!!javax.script.ScriptEngineManager` 抛 `ComposerException: Global tag is not allowed`。探针保留在 `scripts/YamlProbe.java`。 |
| 导出的 Debug 接收器可被外部触发 | `DebugTaskReceiver` 与 `TaskTriggerReceiver` 虽为 `exported=true`，但入口均有 `!BuildConfig.DEBUG` 早退，release 构建下惰性。回归用例 SEC10。 |
| 记忆库召回可注入 system prompt | 记忆召回已有 `<memory>` 围栏，并主动剥离内容中的反围栏序列。 |

---

## 4. 验证状态：诚实边界

**SEC1–SEC11 全部未执行。** 状态是准确的，没有被标成通过。

阻塞原因是环境而非代码：`ConfigServerManager.start()` 硬性要求 `TRANSPORT_WIFI` 且只能从设置页开关触发（无开机自启、无 adb 入口、无 broadcast 入口）；校园网 Captive Portal 会反复抢占前台。

> ⚠️ **一条历史假结果的警告**：此前 A1–A4 探测返回的 `502` / `RemoteDisconnected` 来自本机 Clash VPN 劫持了对 `172.22.249.38:9527` 的请求，**不是 PokeClaw 的响应**。这些结果既不算 PASS 也不算 FAIL。重跑前请先关闭 Clash。

**跑通条件**：手机切到自己的热点（非校园网、无 Captive Portal）→ 设置中开启「局域网配置」→ 关闭 Clash → 从 PC 直连 `172.22.249.38:9527` 执行 `scripts/security_probe_configserver.py`。

### 已执行的部分：单元测试

| 测试 | 用例数 | 覆盖 |
|---|---|---|
| `BruteForceGuardTest` | 6 | r1 锁定与递增逻辑 |
| `McpDescriptionSanitizerTest` | 8 | r4 围栏与剥离 |
| `ChannelSecurityGuardTest` | 8 | 渠道侧 PIN 校验 |
| `McpServerConfigTest` | 8 | `safeName` / `normalizedHeaderValue` / `parseList` / `detect` |

**合计 30 个单测全部通过。** 注意：这些单测目前**未接入 CI**，`build.yml` 只跑 `assembleDebug`，因此它们只在本地生效。

---

## 5. 尚未覆盖的建议

- 渠道 WebSocket 传输层的鉴权强度（当前为应用层 PIN）
- 记忆库内容与模型输出的交叉污染面（已有围栏，缺对抗性测试语料）
- `skills/` YAML 配方的加载沙箱边界
