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

**必须诚实说明：以上「已修复」指的是代码层已修复且有单测覆盖。其中 r1 / r2 / r3 的 HTTP 层行为已于 2026-10-17 在真机上动态验证通过（SEC1 / SEC2 / SEC3 / SEC6），其余仍为静态走查**（见第 4 节），不要把本文当作「已通过完整安全测试」的证明。

---

## 2. 修复细节

### r1 — PIN 暴力破解（Critical）

**问题**：`ChannelSecurityGuard.verify()` 与 `ConfigServer.authenticate()` 都对 6 位 PIN 做直接比较，无失败计数、无锁定、无延迟。6 位十进制即 10⁶ 组合空间，局域网内可自动化穷举。

**修复**：新增 `BruteForceGuard`，两个入口统一接入。行为：

- 连续失败 5 次 → 锁定约 30 秒
- 再次失败 → 锁定时长约 2 倍递增（`strikes` 计数）
- 成功后 `onSuccess()` 清空记录
- PIN 比较使用**常量时间**比较，避免时序侧信道

**回归**：`BruteForceGuardTest`（6 用例）、`ChannelSecurityGuardTest`（8 用例）、
**外加 2026-10-17 真机 A2 动态验证**（见第 4 节）。

### r2 — 凭据明文泄漏（Critical）

**问题**：LAN 控制台的读接口直接把存储的 bot token 与 API Key 原样返回。任何通过 PIN 认证的客户端（以及任何能绕过认证的路径）都能拿到全部渠道凭据。

**修复**：`maskIfPresent()` 在读路径返回掩码占位符。写路径的 POST handler 已能识别掩码值并忽略，因此「页面带着掩码回存」不会把真实凭据清空（`isMaskedValue`）。

**回归**：E2E 用例 SEC1 / SEC2，**已于 2026-10-17 真机验证通过**（见第 4 节）。

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

### 4.1 已真机执行：SEC1 / SEC2 / SEC3 / SEC6（2026-10-17）

首次动态验证，设备 `10AG4J1THX00524` / V2502DA / Android 16 (API 36)，v0.8.3 release 构建：

| 用例 | 结果 | 实测证据 |
|---|---|---|
| SEC1 未认证凭据收集（A1） | ✅ PASS | `GET /api/channels` 与 `GET /api/llm` 均 `401 Unauthorized: Valid PIN/Token required` |
| SEC2 PIN 限速（A2） | ✅ PASS | 5 次错误猜测后，**正确 PIN 亦被拒**；服务端停止比对 |
| SEC3 DNS rebinding（A3） | ✅ PASS | `Host: evil.example.com` / `attacker.com` / `127.0.0.1.evil.com` 全部 `403` |
| SEC6 调试端点（A4） | ✅ PASS | `/api/debug/file?path=/etc/passwd` 等返回 `401`，release 构建正确拒绝 |

r2 的掩码在带凭据的真实数据上得到确认：`GET /api/channels` 返回
`"wechatBotToken":"********"`（真值存在但被掩码），其余未配置项为空串。

**§4 的阻塞条件已作废**：USB 调试可用后，改用 `adb forward tcp:9527 tcp:9527`
让 PC 经 USB 隧道访问手机的 `ConfigServer`，**数据不经过 Wi-Fi、也不经过 Clash**。
`ConfigServer` 绑定 `0.0.0.0`，因此手机只需连上**任意** Wi-Fi（含有 Captive Portal
的校园网）以满足 `isWifiConnected()` 这道硬门即可，无需任何外网。

### 4.2 仍未执行：SEC4 / SEC5 / SEC7–SEC11

| 用例 | 阻塞原因 |
|---|---|
| SEC7 路径穿越 | `/api/debug/file` 是 debug-only 端点，release 构建不可达（A4 已确认）。需装 debug 构建复测 |
| SEC9 MCP 描述投毒 | 需要配置一个可控 `description` 的恶意 MCP 服务器 |
| SEC10 调试接收器 | 需 debug 构建才能触达入口 |
| SEC4 / SEC5 / SEC8 / SEC11 | 需第三方渠道（Discord / Telegram / QQ / 抖音）凭据或双设备 |

### 4.3 ⚠️ 探测脚本曾产生假 FAIL（已修）

`scripts/security_probe_configserver.py` 的 A2 曾报 `FAIL: no effective lockout`，
**这是脚本缺陷，不是产品漏洞**。两处原因叠加：

1. **口径错**：脚本把 `429/403` 当作锁定信号，但 `ConfigServer.authenticate()`
   锁定期返回的是与普通鉴权失败**完全相同的 `401`**（无 `Retry-After`）。
2. **探测自毁**：即使改成「锁定期正确 PIN 也应被拒」，只要每次错误猜测后都插一次
   正确 PIN 探测，成功认证会调用 `BruteForceGuard.onSuccess()` **删除整个条目**，
   失败计数永远回到 0，锁定阈值不可达。

修法：按 `MAX_FAILURES`(5) 分批打错误猜测，**每批结束后**才探一次正确 PIN；
并接受 PIN 作为 `argv[2]` 传入。修正后 A2 如实 PASS。

> **教训**：安全测试断言不能只看状态码。本例中「锁定生效」的表现与「鉴权失败」
> 在 HTTP 层不可区分，必须用「已知正确凭据在锁定期被拒」这一语义信号来判定。

### 4.4 一条历史假结果的警告

此前 A1–A4 探测返回的 `502` / `RemoteDisconnected` 来自本机 Clash VPN 劫持了对
`172.22.249.38:9527` 的请求，**不是 PokeClaw 的响应**。这些结果既不算 PASS 也不算 FAIL。
改走 `adb forward` 隧道后该问题不再出现。

### 4.5 复现步骤

```powershell
adb forward tcp:9527 tcp:9527
# 手机端：Wi-Fi 连任意 AP → 设置 → 局域网配置 → 开启（记下 6 位 PIN）
python scripts/security_probe_configserver.py http://127.0.0.1:9527 <PIN>
```

> A2 结束后该客户端会处于锁定退避中（30s << strikes，封顶 30min）。状态仅存内存，
> `adb shell am force-stop io.agents.pokeclaw` 可清空，之后需重新开启局域网配置。

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
