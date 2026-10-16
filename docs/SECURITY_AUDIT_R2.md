# 安全审计报告 · 第二轮（红队）— PokeClaw Fork

> 版本基线：v0.8.1（`versionCode 31`），`main` @ `aabaa00`
> 审计日期：2026-10-16
> 方法论：`Anthropic-Cybersecurity-Skills-1.3.0` 中的
> [conducting-mobile-app-penetration-test]（OWASP MASTG/MASVS）、
> [testing-android-intents-for-vulnerabilities]、
> [testing-mobile-api-authentication]、
> [exploiting-insecure-data-storage-in-mobile]、
> [testing-websocket-api-security]、
> [performing-api-rate-limiting-bypass]、
> [performing-directory-traversal-testing]、
> [performing-cryptographic-audit-of-application]、
> [detecting-indirect-prompt-injection]、
> [testing-prompt-injection-in-rag-pipelines]、
> [testing-for-system-prompt-leakage]、
> [performing-blind-ssrf-exploitation]。
>
> 上一轮见 [`SECURITY_AUDIT.md`](SECURITY_AUDIT.md)。本文**只记录本轮新增发现**，
> 并明确标注哪些是**静态走查已确认**、哪些**尚未动态验证**。

---

## 0. 与上一轮的关系

上一轮的 r1–r4 修复**经本轮复核依然成立**，未发现回归：

| 上一轮项 | 本轮复核结论 |
|---|---|
| r1 PIN 限速 | `BruteForceGuard` 确实接入两个入口，锁定递增到 30 分钟上限，常量时间比较 ✅ |
| r2 凭据明文 | GET 读路径确实掩码 ✅（**但见 F5：写路径仍是明文**） |
| r3 路径穿越 | `canonicalFile` + `Path.startsWith(Path)`，逻辑正确 ✅ |
| r4 MCP 描述投毒 | `McpDescriptionSanitizer` 确在 `McpTool` 构造时调用 ✅（**但见 F4：工具返回值未处理**） |

**上一轮最大的盲区**：只检查了「入口有没有认证」，没有检查「认证后的身份粒度对不对」
（→ F1），也没有检查「认证之后凭据走的是明文还是密文」（→ F5）。
这两类都是典型的**红队第二层**问题——第一层（有没有门）过了，第二层（门的钥匙开多大）没过。

---

## 1. 结论摘要

| ID | 等级 | 问题 | 状态 |
|---|---|---|---|
| **F1** | **Critical** | Telegram 白名单绑定到 `chat.id`（会话）而非用户，且白名单**全局跨渠道**不隔离 | ✅ **已修复 2026-10-16** |
| **F2** | **High** | 全部高危工具（拨号/发消息/输入/解锁/自动回复）**无任何人工确认闸门** | ✅ **已修复 2026-10-16**（风险分级 + 远程来源闸门） |
| **F3** | **High** | 导出的自动化组件**无调用方认证**，且 `return_action` 造成混淆代理广播 | 部分缓解（默认关闭） |
| **F4** | **High** | `memory_recall` 工具返回值**绕过记忆围栏**，构成持久化间接注入 | 未修复 |
| **F5** | **High** | LAN 控制台**明文 HTTP 传输真实凭据**；PIN 走 URL query | 未修复 |
| **F6** | **Medium** | `SecretBox` 加密失败时**静默降级为明文**，与其自身设计声明相悖 | 未修复 |
| **F7** | **Medium** | 用户技能 `triggers` 正则**每次调用重新编译**且可触发 ReDoS | 未修复 |
| **F8** | **Medium** | 工具参数**无条件落盘**到日志，并被打包进**用户可分享的调试报告 ZIP** | 未修复 |
| **F9** | **Low** | `isValidHost` 对空 Host **fail-open** | 未修复 |
| **F10** | **Info** | `WebActivity` 开启 JS 且无 scheme 白名单——当前**无调用方**（死代码） | 潜在风险 |

> F1 / F2 作为同一变更集落地（2026-10-16）。单测 **165 用例 / 23 套件 / 0 失败**（原 149 / 22）。
> `./gradlew testDebugUnitTest` 首次接入 CI（[`build.yml`](../../.github/workflows/build.yml) 新增 `test` job）。

---

## 2. 详细发现

### F1 — Critical — Telegram 白名单绑定到会话而非用户 ✅ 已修复

> **修复记录（2026-10-16）**
>
> | 改动 | 位置 |
> |---|---|
> | 改传 `message.from.id`；频道贴文无 `from` 时回退 `chat.id` | [`TelegramChannelHandler.kt`](../../app/src/main/java/io/agents/pokeclaw/channel/telegram/TelegramChannelHandler.kt) |
> | 新增 `lastFromUserId` 字段，**与 `lastChatId` 明确分离** | 同上 |
> | 白名单条目改为 `CHANNEL\|senderId` 复合键 | [`ChannelSecurityGuard.kt`](../../app/src/main/java/io/agents/pokeclaw/channel/security/ChannelSecurityGuard.kt) |
> | `getWhitelist()` 返回 `Map<Channel, Set<String>>`（**破坏性 API 变更**） | 同上 |
> | `addSenderToWhitelist` / `removeSenderFromWhitelist` / `isSenderWhitelisted` 全部增加 `channel` 参数 | 同上 |
> | `purgeLegacyEntries()` 一次性丢弃无渠道前缀的旧条目 | 同上 |
> | `ChannelHandler.getLastSenderId()` KDoc 写明「这是路由目标，**不是**鉴权身份」 | [`ChannelHandler.kt`](../../app/src/main/java/io/agents/pokeclaw/channel/ChannelHandler.kt) |
>
> **两个刻意的设计决定**：
>
> 1. **`getLastSenderId()` 仍返回 `chatId`，不改成 `from.id`。**
>    它的用途是回复路由与定时任务推送，Telegram bot 无法主动私聊未 `/start` 它的用户；
>    改成 `from.id` 会让「群内配对 + 定时推送」直接失效（403）。鉴权走 `dispatchMessage`
>    单独传的 `senderId`，两条路径互不干扰。
> 2. **旧白名单条目直接丢弃，不做猜测迁移。**
>    无渠道前缀的条目无法归属渠道——保留就等于保留跨渠道撞号，猜就是引入新的越权。
>    代价是**升级后需要重新 `/bind` 一次**，已在 `XLog.w` 中明确告知。
>
> **回归测试**：`ChannelSecurityGuardTest` 8 → 14 用例，新增 6 条覆盖跨渠道隔离、
> 群 chat.id 冒充用户 id、按渠道独立增删、存储往返、旧条目 fail-closed。

**位置**：[`TelegramChannelHandler.kt:99-104`](../../app/src/main/java/io/agents/pokeclaw/channel/telegram/TelegramChannelHandler.kt#L99-L104)
→ [`ChannelSecurityGuard.kt:212-215`](../../app/src/main/java/io/agents/pokeclaw/channel/security/ChannelSecurityGuard.kt#L212-L215)
→ [`ChannelSecurityGuard.kt:102-110`](../../app/src/main/java/io/agents/pokeclaw/channel/security/ChannelSecurityGuard.kt#L102-L110)

**问题**。`ChannelSecurityGuard.verify()` 的白名单键是 `senderId`，但各渠道传入的语义不同：

```kotlin
// TelegramChannelHandler.kt:99
val chatId = message.getJSONObject("chat").getLong("id")
ChannelManager.dispatchMessage(channel, text, messageId.toString(), chatId.toString())
//                                                                   ^^^^^^^^^^^ 会话 ID，不是 message.from.id
```

对照本仓库其余两个渠道，**它们是正确的**：

| 渠道 | 传入的 senderId | 粒度 | 判定 |
|---|---|---|---|
| WeChat | `msg.fromUserId` | **用户** | ✅ 正确 |
| QQ | `userId.toString()` | **用户** | ✅ 正确 |
| **Telegram** | `chat.id` | **会话/群** | ❌ **错误** |

**叠加第二个缺陷**：白名单是**单份全局集合**，不按渠道分区。

```kotlin
private const val KEY_CHANNEL_WHITELIST = "KEY_CHANNEL_WHITELIST"   // 只有一份

fun isSenderWhitelisted(senderId: String): Boolean {
    if (senderId.isBlank()) return false
    return getWhitelist().contains(senderId.trim())   // 不带 channel 参数
}
```

`verify(channel, ...)` 的 `channel` 参数**只用于日志和限流键**，完全不参与鉴权判断。

**攻击链**：

1. 用户把自己的 Telegram bot 拉进一个**群聊**（或频道），在群里发送 `/bind 123456`
2. 系统把**群 `chat.id`**（例如 `-1001234567890`）永久写入全局白名单
3. 此后**群里任何人**——包括之后才加入的陌生人——发消息，`isSenderWhitelisted` 都返回 `true`
4. 该消息进入 `TaskOrchestrator`，LLM 拥有 `make_call` / `send_message` / `input_text` /
   `system_key(unlock_screen)` / `auto_reply` / `clipboard` / `get_notifications` 全套执行器
5. 攻击者可以让手机**给任意联系人发消息、拨打任意电话、在任意 App 里输入内容、解锁屏幕**

**放大因素**：
- 白名单**无过期、无自动轮换**，只能用户在设置里手动删
- Telegram 频道/超级群的 `chat.id` 格式固定且可预测；公共频道的 chat.id 属于公开信息
- 跨渠道 ID 命名空间共用：Telegram 群 id 与 QQ/抖音的数字用户 id 若数值撞车，会**跨渠道互相授权**

**评级依据**：CWE-863（Incorrect Authorization）/ OWASP API5:2023 Broken Function
Level Authorization / MITRE ATT&CK T1078（Valid Accounts）。这是一个**授权范围错误**，
不是认证绕过——上一轮只看「PIN 能不能爆破」，所以漏掉了。

**修复方向**：
- Telegram 改传 `message.getJSONObject("from").getLong("id")`；
- 白名单键改为 `"${channel.name}:$senderId"` 复合键，**同时保留 chat 维度作为额外约束**
  （建议要求 `from.id` 与 `chat.id` 双双在白名单内才放行，兼顾群内协作场景）；
- 增加白名单条目过期与「最近使用时间」，便于用户识别陈旧授权。

---

### F2 — High — 高危工具无人工确认闸门 ✅ 已修复（来源闸门）

> **修复记录（2026-10-16）**
>
> | 改动 | 位置 |
> |---|---|
> | 新增 `ToolRiskPolicy`：风险分类 + 按来源判定 | [`ToolRiskPolicy.kt`](../../app/src/main/java/io/agents/pokeclaw/tool/ToolRiskPolicy.kt) |
> | `ToolRegistry.executeTool` 统一拦截，`sourceChannelProvider` 可注入以便测试 | [`ToolRegistry.kt`](../../app/src/main/java/io/agents/pokeclaw/tool/ToolRegistry.kt) |
> | 新增 `ToolRiskPolicyTest` 10 用例 | [`ToolRiskPolicyTest.kt`](../../app/src/test/java/io/agents/pokeclaw/tool/ToolRiskPolicyTest.kt) |
>
> **采用的是「来源闸门」而非「逐次确认弹窗」**，理由：
>
> - 本项目**没有可用的确认 UI**。远程任务前台可能没有 Activity，
>   `ConfirmDialog` 根本弹不出来；只给本地路径加弹窗会制造「已覆盖」的错觉。
> - 因此把风险模型与确认交互**分开**：`ToolRiskPolicy` 决定「谁不能调」，
>   未来要加弹窗时在同一个位置扩展即可，不必重做分类。
>
> **判定规则**：
>
> | 任务来源 | 高危工具 | 读类工具 |
> |---|---|---|
> | `Channel.LOCAL`（App 内本人操作） | 允许 | 允许 |
> | `null`（会话未建立，如冷启动首条） | 允许 | 允许 |
> | Telegram / Discord / 微信 / QQ / 抖音 | **拒绝** | 允许 |
>
> **fail-closed 设计**：`riskOf()` 对**未分类的工具默认返回 `HIGH_IMPACT`**，
> `mcp_*` 第三方工具一律高危。新增工具不会因为「没来得及分类」而自动获得远程可达性。
>
> 高危集合：`make_call` / `send_message` / `send_file` / `auto_reply` / `input_text` /
> `system_key` / `clipboard`。
>
> > `clipboard` 的**读**侧也一并拦了——刚复制的一次性验证码和密码同样要命。
> > 如果产品上确实需要远程读剪贴板，应当做成显式开关，而不是默认放行。
>
> **未覆盖**：`get_notifications`（含验证码与银行通知）目前归为只读因而放行。
> 这是有意识的取舍——远程读屏同样是隐私面，但拦掉会显著影响 monitor/auto_reply 的实用性。
> 若要收紧，应作为单独一条发现处理，不要混进本次变更。

**位置**：[`ToolRegistry.kt:33-79`](../../app/src/main/java/io/agents/pokeclaw/tool/ToolRegistry.kt#L33-L79)

已注册工具中具备不可逆或高影响能力的有：

| 工具 | 能力 |
|---|---|
| `make_call` | 发起真实电话 |
| `send_message` | 向任意联系人发送消息 |
| `input_text` | 在当前焦点 App 任意输入（密码、支付、搜索） |
| `system_key` | `unlock_screen` / `lock_screen` / `enter` / `recent_apps` … |
| `auto_reply` | 关闭自动回复，或设置持续自动应答 |
| `clipboard` | 读**和写**剪贴板 |
| `send_file` | 发送文件到外部 |
| `get_notifications` | 读取全部通知（验证码、银行提醒） |
| `get_installed_apps` | 读取已安装 App 清单 |
| `take_screenshot` / `get_screen_info` | 读取屏幕 |

全仓检索 `ConfirmDialog` / `requireConfirm` / `userConfirm` / `needsConsent`：
命中**全部**在设置类界面（`SettingsActivity`、`McpServersActivity`、`MemoryActivity`、
`UserSkillsActivity`），**工具执行路径上一个都没有**。

**这意味着 F1、F3、F4 任何一条被利用，后果都是立即且不可逆的**——
没有「最后确认」这一层兜底。这是本项目最核心的结构性安全缺口，
且**无法靠给单个工具加 if 修补**，需要在 `TaskOrchestrator` / agent loop 层引入策略层。

**修复方向**：引入按风险分级的工具策略（如 `CONFIRM_REQUIRED` / `ALLOW` / `DENY`），
在 `executeTool` 统一拦截；对 F1 这类非交互来源（渠道/广播）默认对高危工具拒绝或降级为只读。

---

### F3 — High — 导出组件无调用方认证 + 混淆代理

**位置**：[`AndroidManifest.xml:107-134`](../../app/src/main/AndroidManifest.xml#L107-L134)
→ [`ExternalAutomationEntrypoint.kt:16-25, 74-80`](../../app/src/main/java/io/agents/pokeclaw/automation/ExternalAutomationEntrypoint.kt#L74-L80)

上一轮把 `DebugTaskReceiver` / `TaskTriggerReceiver` 判为「release 下惰性」——**该结论正确**，
本轮复核两处 `onReceive` 开头均有 `if (!io.agents.pokeclaw.BuildConfig.DEBUG) return;` ✅

但**同一份 manifest 里另有两个导出组件从未被审计过**：

```xml
<activity android:name=".automation.ExternalAutomationActivity"
          android:exported="true">        <!-- 无 android:permission -->
    <intent-filter>
        <action android:name="io.agents.pokeclaw.RUN_TASK" />
        <action android:name="io.agents.pokeclaw.RUN_CHAT" />
    </intent-filter>
</activity>
<receiver android:name=".automation.ExternalAutomationReceiver"
          android:exported="true" />      <!-- 同样无 permission -->
```

唯一的「检查」是 `isTargetedToPokeClaw()`：

```kotlin
private fun isTargetedToPokeClaw(context: Context, intent: Intent): Boolean {
    val packageName = context.packageName
    val component = intent.component
    return component?.packageName == packageName || intent.`package` == packageName
}
```

**这不是授权控制，是「这封信是不是寄给我的」检查。** 任何 App 都可以
`intent.setPackage("io.agents.pokeclaw")` 或 `setComponent(...)` 让它为真。
没有签名校验、没有自定义 `signature` 级 permission、没有调用方白名单。

**缓解因素（必须诚实标注）**：
`KVUtils.isExternalAutomationEnabled()` 默认 `false`
（[`KVUtils.kt:260`](../../app/src/main/java/io/agents/pokeclaw/utils/KVUtils.kt#L260)），
所以**默认状态下不可利用**。这是本条评为 High 而非 Critical 的原因。
但该功能是对外宣传的（文档注释里直接给了 `adb shell am broadcast` 示例），
用户一旦开启，**设备上任意 App 即可驱动 agent**。

**混淆代理（confused deputy）**：
`return_action` 完全由调用方控制，最终执行

```kotlin
val callback = Intent(returnAction).apply { ... }
context.sendBroadcast(callback)
```

攻击者可让 PokeClaw 向设备上**任意 action** 发送广播（附带 status/result/error 等 extra），
用于触发其他 App 的导出 receiver。extras 的键值集合是受控的，但「代播任意广播」这一能力本身
就是不必要的攻击面。

**修复方向**：
- 改用 `android:permission` + `protectionLevel="signature"`，或在代码内校验
  `packageManager.getPackageInfo(callingPackage, ...).signatures`
- `return_action` 改为**存储的回调 ID**，由 PokeClaw 侧查表映射到已注册 action，禁止调用方直接给 action
- 长期：把「开启外部自动化」从布尔开关升级为**逐调用方授权**（记录包名+签名）

---

### F4 — High — `memory_recall` 绕过记忆围栏（持久化间接注入）

**位置**：[`MemoryRecallTool.kt:59-66`](../../app/src/main/java/io/agents/pokeclaw/agent/memory/MemoryRecallTool.kt#L59-L66)
对照 [`MemoryStore.kt:508-520`](../../app/src/main/java/io/agents/pokeclaw/agent/memory/MemoryStore.kt#L508-L520)

上一轮把「记忆库召回注入」列为**已证伪**，依据是「已有 `<memory>` 围栏，并主动剥离反围栏」。
该结论**只对 briefing 路径成立**：

```kotlin
// MemoryStore.briefing() —— 有围栏、有反围栏剥离 ✅
val body = e.content.replace(OPEN_FENCE, "").replace(CLOSE_FENCE, "")
sb.append("- [${e.kindLabel}] $OPEN_FENCE$body$CLOSE_FENCE$tags$warn\n")
```

但**工具返回路径完全没有这层处理**：

```kotlin
// MemoryRecallTool.execute() —— 裸拼接，无围栏、无剥离 ❌
val body = results.joinToString("\n") { r ->
    "- [${r.entry.kindLabel}] ${r.entry.content}  (命中 ${r.entry.hits + 1} 次)"
}
```

**为什么这构成一条独立的注入链**：

1. `briefing()` 只注入 `BRIEFING_LIMIT = 12` 条、且受 `BRIEFING_CHAR_BUDGET = 1400` 限制——
   **绝大多数记忆不会每轮出现**
2. 攻击者让 agent 调一次 `memory_recall`（或攻击者本人就是被白名单放行的渠道发送者，
   诱导 agent 召回），被召回的条目**未经任何处理**进入上下文
3. `memory_save` 是 **agent 可写**的工具，`fromAgent = true`；
   一条被植入的记忆**跨会话持久存在**

也就是说：上一轮加固的是「记忆自动注入」这条路径，而**记忆作为工具结果返回**这条路径
（OWASP LLM01 / MITRE ATLAS **AML.T0051.001** 间接提示注入的标准交付渠道）当时是敞开的。

**修复方向**：把围栏与反围栏剥离**下沉到 `MemoryStore` 的读取出口**，
让 `briefing()` / `MemoryRecallTool` / `MemorySearchTool` 共用同一个
`MemoryStore.fenceForContext(content)`，杜绝「新增一个调用点就漏一层」。

---

### F5 — High — LAN 控制台明文传输凭据

**位置**：[`ConfigServer.kt:171-199`](../../app/src/main/java/io/agents/pokeclaw/server/ConfigServer.kt#L171-L199)
+ [`network_security_config.xml`](../../app/src/main/res/xml/network_security_config.xml)

r2 修复了**读方向**，但**写方向没动**：

```kotlin
// 读：已掩码 ✅
addProperty("discordBotToken", maskIfPresent(KVUtils.getDiscordBotToken()))

// 写：真实凭据原样放进 HTTP body，随后经明文 HTTP 发往局域网 ❌
if (json.has("discordBotToken")) {
    val v = json.get("discordBotToken").asString
    if (!isMaskedValue(v)) { KVUtils.setDiscordBotToken(v); reinitDiscord = true }
}
```

而传输层全局放行明文：

```xml
<base-config cleartextTrafficPermitted="true">
```

**后果**：同一 Wi-Fi 上的被动嗅探者（ promiscuous mode / ARP 欺骗 / 恶意 AP）
可以在用户每次保存配置时，直接捞走 Discord / Telegram / 微信 / QQ / 抖音的 bot token
和 LLM API key。**这些 token 值本身就是钱**（可被用于接管 bot、以受害者身份发消息）。

**叠加问题：PIN 走 URL query 参数**

```kotlin
session.parms["pin"]?.trim()?.takeIf { it.isNotEmpty() }?.let { candidates.add(it) }
```

PIN 以 `?pin=123456` 形式出现，会进入 HTTP 访问日志、浏览器历史、
中间代理日志，以及潜在的 `Referer` 头。控制台是浏览器页面，Referer 泄漏尤其现实。

**修复方向**：
- 至少对 `/api/llm` 与 `/api/channels` 的 POST **拒绝 `http://`**，或整体引入自签 TLS +
  首次配对时的证书指纹固定（pinning）
- 彻底移除 `?pin=` 通道，只保留 header 传参
- `cleartextTrafficPermitted` 收敛为按域名例外，而不是全局 true

---

### F6 — Medium — `SecretBox` 加密失败静默降级为明文

**位置**：[`KVUtils.kt:64-73`](../../app/src/main/java/io/agents/pokeclaw/utils/KVUtils.kt#L64-L73)
对照 [`SecretBox.kt:26-28`](../../app/src/main/java/io/agents/pokeclaw/utils/SecretBox.kt#L26-L28)

`SecretBox` 自己的文档注释写得很明确：

> Failures are reported, never swallowed into a plaintext fallback:
> writing the secret back in the clear would silently undo the reason this exists.

但调用方正是这么做的：

```kotlin
val encrypted = SecretBox.encrypt(value) ?: value   // ← 加密失败就把明文写进去
return putString(key, encrypted)
```

**触发条件**：Android Keystore 中 AES/GCM 不可用、keystore 损坏、密钥条目被系统清理、
或极少数 OEM 定制 ROM。发生概率不高，但**后果是全部 bot token 与 LLM API key 变明文**，
且用户在界面上完全看不出来——`getEncryptedString()` 对明文和密文一视同仁地返回。

**修复方向**：改为**写入失败即拒绝保存并向用户报错**（fail-closed），
绝不能 `?: value`；读取侧可保留明文兼容，但应加一次性迁移 + 提示。

---

### F7 — Medium — 技能触发正则 ReDoS + 每次调用重编译

**位置**：[`SkillRegistry.kt:55-64`](../../app/src/main/java/io/agents/pokeclaw/agent/skill/SkillRegistry.kt#L55-L64)
同型问题：[`PipelineRouter.kt:118-121`](../../app/src/main/java/io/agents/pokeclaw/agent/PipelineRouter.kt#L118-L121)

```kotlin
val regex = pattern.lowercase().replace(Regex("\\{\\w+\\}"), "(.+)")
Regex(regex).containsMatchIn(lower)     // 每次匹配都重新编译
```

配方来自 `UserSkillLoader.skillsDir()`，即 `getExternalFilesDir(null)/skills/*.yaml`
——**外部存储目录**，且本 App 自己持有 `MANAGE_EXTERNAL_STORAGE` 与 `WRITE_EXTERNAL_STORAGE`
（`minSdk = 28`，覆盖 Android 9/10 上外部存储可写的场景）。

**两个问题**：
1. **ReDoS**：配方作者可写 `(a+)+$` 一类触发灾难性回溯的表达式，
   在 UI 线程上匹配长文本 → ANR。同理 `{param}` 会被替换成 `(.+)`，与配方其余部分组合
   也极易产生嵌套量词。
2. **性能**：`findByTrigger` 在每次任务路由时对每条配方重新 `Regex(...)` 编译，
   配方多了就是明显的 CPU 浪费。

`try/catch` 只能挡住**语法错误**，挡不住**合法但病态**的正则。

**修复方向**：加载时一次性预编译并缓存；加超时/长度上限；
对来自外部存储的配方做模式白名单校验（拒绝嵌套量词）。

---

### F8 — Medium — 工具参数无条件落盘 + 打进可分享的调试报告

**位置**：[`ToolRegistry.kt:107-110`](../../app/src/main/java/io/agents/pokeclaw/tool/ToolRegistry.kt#L107-L110)
+ [`XLog.java:32-45`](../../app/src/main/java/io/agents/pokeclaw/utils/XLog.java#L32-L45)
+ [`DebugReportManager.kt:40-42, 192-206`](../../app/src/main/java/io/agents/pokeclaw/support/DebugReportManager.kt#L40-L42)

`XLog.e` / `XLog.i` **无视 DEBUG 标志恒写入 `AppLogStore`**：

```java
public static void e(String tag, String msg, Throwable tr) {
    AppLogStore.log("E", tag, msg, tr);   // 无 DEBUG 判断 —— release 也写
    Log.e(tag, msg, tr);
}
```

而 `ToolRegistry` 在**执行失败时把完整参数打进日志**：

```kotlin
XLog.e("ToolRegistry", "Tool '$name' execution failed with params=$params", e)
```

`params` 里可能包含 `input_text` 的**用户输入正文**、`send_message` 的**消息全文**、
`make_call` 的**电话号码**。release 构建同样落盘（`AppLogStore` 上限约 1 MB 滚动）。

**放大路径**：[`DebugReportManager`](../../app/src/main/java/io/agents/pokeclaw/support/DebugReportManager.kt)
把这些打包进一个**用户主动分享给支持方的 ZIP**：

```kotlin
collectLogcat().takeIf { it.isNotBlank() }?.let { addText(zip, "app-logcat.txt", it) }  // 400 行 logcat
addRecentAppLogs(zip, context)          // app_logs/*.log
addRecentHttpLogs(zip, context.cacheDir) // http_logs/*.log
```

**已核实的近失事件（latent）**：
[`FileLoggingInterceptor.writeToFile`](../../app/src/main/java/io/agents/pokeclaw/agent/langchain/http/FileLoggingInterceptor.java#L126-L131)
把**所有请求头原样写入**，**不脱敏**：

```java
for (int i = 0; i < request.headers().size(); i++) {
    writer.write(request.headers().name(i) + ": " + request.headers().value(i) + "\n");
}
```

脱敏只做在 `buildCurl()` 生成的**可复现命令字符串**里
（`if ("Authorization".equalsIgnoreCase(name)) value = value.substring(0,15)+"..."`），
真正的 header 块是裸的。一旦启用，**完整 LLM API key + 整段 system prompt + 全部对话历史**
都会进磁盘并进报告 ZIP。

**当前状态**：`DefaultAgentService.FILE_LOGGING_ENABLED` 默认 `false`，
且全仓无任何位置置为 `true`（仅 2 处出现：声明 + `LlmClientFactory` 读取）。
**因此这是潜在缺陷而非当前活跃漏洞**——但只要有人为排障把它打开，后果极重。

**修复方向**：
- 工具参数日志按键名脱敏（`text`/`message`/`content` 类参数不落盘）
- 报告打包时对 `Authorization`、bot token、API key 做正则清洗
- `FileLoggingInterceptor` 的 header 块复用 `buildCurl` 的脱敏逻辑

---

### F9 — Low — `isValidHost` 对空 Host fail-open

**位置**：[`ConfigServer.kt:206-215`](../../app/src/main/java/io/agents/pokeclaw/server/ConfigServer.kt#L206-L215)

```kotlin
private fun isValidHost(host: String?): Boolean {
    if (host.isNullOrBlank()) return true      // ← 空 Host 直接放行
```

Host 头校验的目的是防 DNS rebinding。放行空 Host 意味着**不发 Host 头的请求绕过全部校验**。
HTTP/1.1 要求 Host 必填，但 NanoHTTPD 的容忍度取决于版本与请求构造方式，不应依赖对端自律。

**修复方向**：空 Host 应当 `return false`；确需兼容本机 curl 时，单独放行 `session.remoteIpAddress` 为回环的情况。

---

### F10 — Info — `WebActivity` 死代码中的 WebView 风险

**位置**：[`WebActivity.kt:79-97`](../../app/src/main/java/io/agents/pokeclaw/ui/web/WebActivity.kt#L79-L97)

```kotlin
settings.apply {
    javaScriptEnabled = true
    domStorageEnabled = true
    ...
}
webViewClient = object : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        url?.let { view?.loadUrl(it) }   // 任何 url 都加载，无 scheme 白名单
        return true
    }
}
```

无 scheme 限制 + 全局明文放行 + JS 开启 = 若被 `file://` / `content://` / `intent://`
或任意 http 页面驱动，是典型的 WebView 提权面。

**本轮全仓检索 `WebActivity` 仅命中定义处自身，`WebActivity.start()` 无任何调用方**，
故当前**不可达**。记录为潜在风险，避免后续接线时无意引入。

---

## 3. 本轮**排除**的疑点（避免下个会话重复排查）

| 疑点 | 排除依据 |
|---|---|
| 调试广播可在 release 触发 | `DebugTaskReceiver.kt:39` 与 `TaskTriggerReceiver.java:31` 开头均有 `!BuildConfig.DEBUG` 早退，静态确认 |
| `WebActivity` 是活跃攻击面 | 全仓无调用方，死代码 |
| `FileLoggingInterceptor` 正在泄漏 API key | `FILE_LOGGING_ENABLED` 默认 false 且无处置 true |
| 凭据以明文存于 MMKV | `KVUtils.getXxx()` 均走 `getEncryptedString` → `SecretBox`（AES-256-GCM + Keystore）。**但 F6 是其失败路径的例外** |
| LAN 控制台无鉴权 | `authenticate()` 的 `if (expectedPin.isEmpty()) return true` 看似 fail-open，但 `ChannelSecurityGuard.getPairPin()` 会**自动生成** 6 位 PIN，该分支实际不可达 |
| MCP 工具返回值投毒 | 本轮确认 r4 只覆盖了 `description`，返回值**未**覆盖 → 已升级为 F4 |
| SnakeYAML 反序列化 RCE | 沿用上一轮实测结论（`scripts/YamlProbe.java`），本轮未推翻 |

---

## 4. 验证状态（诚实边界）

**本轮全部为静态走查。** 未执行任何动态验证，原因与上一轮相同且未改善：
`ConfigServerManager.start()` 硬性要求 `TRANSPORT_WIFI` 且只能从设置页开关触发；
真机为校园网 + Captive Portal；本机 Clash 会劫持到 `172.22.249.38:9527` 的请求产生假结果。

因此：

- F1–F10 的**代码路径与数据流**已逐行确认，可直接采信
- **可利用性**未经真机复现，尤其 F1 需要「真实 Telegram 群 + bot」才能端到端演示
- F2 属于架构缺失，不存在「复现」概念，是代码事实

建议的 E2E 用例（`SEC` 段续编）见
[`QA_CHECKLIST.md`](../QA_CHECKLIST.md)；F1 的验证脚本建议新增
`scripts/security_probe_telegram_authz.py`（静态断言 `from.id` 而非 `chat.id`）。

---

## 5. 建议修复顺序

| 优先级 | 项 | 理由 |
|---|---|---|
| ~~**P0**~~ ✅ | F1 | **已于 2026-10-16 修复** |
| ~~**P0**~~ ✅ | F2 | **已于 2026-10-16 修复**（来源闸门，见上） |
| **P1** | F4 | 加固已在隔壁房间，抽出公共函数即可闭合 |
| **P1** | F5 | 影响面是「所有用户的全部凭据」 |
| **P2** | F3 | 默认关闭，但对外宣传过，开关一开即全设备沦陷 |
| **P2** | F6, F7, F8 | 均为「某条已有防线在特定条件下失效」，需要触发条件 |
| **P3** | F9, F10 | 收敛项 |

> **F1 的破坏性变更提醒**：`ChannelSecurityGuard` 的白名单 API 全部增加了 `channel`
> 参数，`getWhitelist()` 返回类型从 `Set<String>` 变为 `Map<Channel, Set<String>>`。
> 全仓检索确认**无 UI 引用**这些方法（配对状态目前只能在日志里看到），
> 但若后续要加「已配对设备」列表，需要按渠道分组展示，并处理升级后需重新 `/bind` 的提示。

> **架构提醒**（按 `CLAUDE.md` 的 Architecture Before Features 规则）：
> F2 与 F1/F3/F4 不是同级问题。F1/F3/F4 是**具体漏洞**，
> F2 是**缺少一层防御**。只修 F1 而不碰 F2，等于把一道门换成更好的锁，
> 房间本身仍然没有墙。建议 F1 与 F2 作为一个变更集一起设计。
