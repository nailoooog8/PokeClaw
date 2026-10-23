# PokeClaw Backlog

Items go in, get prioritized, get done, get crossed out. Simple.

Priority: `P0` = blocks users, fix now. `P1` = next up. `P2` = when we get to it. `P3` = nice to have.

---

## Bugs

- [x] **P1 — FIXED 2026-10-22.** Tool calls vanished from a restored transcript. `ChatHistoryManager.save()` writes a tool group as one markdown line per call (`- ✓ name → summary`), but `load()` never parsed that back: the whole block landed in `ChatMessage.content` and `toolSteps` stayed null. Two surfaces, two symptoms — the **chat screen's `ToolGroup()` renders `toolSteps` and nothing else, so a restored group drew an empty box** (the tool call was simply gone), and the **web preview flattened it into one `- ✓ 截屏 → …` paragraph** (the card lost its dot, its kind label and its accent border). The fix parses the lines back into `toolSteps` — icon `✓`/`○` decides success, split on the **first** `→` because a summary can contain one — and restores the same shape the live message has (`content` empty), so the web surface does not render a card *and* a paragraph. An unrecognised layout is kept verbatim rather than guessed at. New `ChatHistoryManagerLoadTest` (4 cases). Verified on device with a transcript written by the **previous** build, so old files need no migration. Found while running QA H14 (three open/close cycles rebuild the Activity, which is exactly what a normal user does) — see `QA_CHECKLIST.md` H26.
  - ⚠️ **The lesson is about the asymmetry**: `save()` and `load()` were written at different times and never round-tripped. A markdown transcript is a *format*, and the format's tool-group section had no reader.
- [x] **P2 — RESOLVED 2026-10-22.** Claude UI preview: a tool card on the real device appeared to render `设备截图` as its kind label and `正在截取屏幕…` as its summary. **Neither string exists in any commit in this repo** (`git log -S`, all refs) nor in the unpacked `assets/claude-ui/cds.js` / `index.html`, and the packaged `cds.js` hardcodes `.tool-kind` to `工具调用` — no code path can emit them. Settled as an **OCR misread of 11 px CJK**, and on 2026-10-22 re-derived as *provably* impossible rather than merely doubted: the live renderer hardcodes `工具调用` (`mockup/claude-ui/cds.js:451`), the static samples say `无障碍 · 点击` / `无障碍 · 启动应用` (`cds.js:485`, `350e2f7`), and `git log -S "正在截取"` over all history hits only `QA_CHECKLIST.md` / `BACKLOG.md` themselves — so **no build, and therefore no cached page either, could have carried those strings**. The "cached another page" hypothesis is dead, and the DevTools-socket line (`webview_devtools_remote_28400`, `adb forward` + HTTP gave nothing) needs no further chasing: the page did not contain the string, so reading the DOM could not have produced it. Filed 2026-10-20 while running QA H24.
  - A *real* adjacent hazard was fixed anyway: the preview page's asset URL never changes, so the WebView cache could serve the previous build's page and a reviewer would judge a build that is not installed. `ClaudeUiPreviewActivity` now does `clearCache(true)` plus a `?v=BuildConfig.VERSION_CODE` query (same origin, so the localStorage theme survives).
- [~] **P1** AppCapabilityCoordinator process-young grace (partial fix shipped 2026-05-28): `bindingState` now returns `CONNECTING` for the first 30s after process start regardless of stale `lastHealthyAt` in MMKV. Addresses the case where OS keeps the AccessibilityService enabled across an app process restart and rebinds within seconds — previously the in-app coordinator would see `running=false`+`lastHealthyAt` from minutes ago and immediately return `DEGRADED`, forcing the user back to Settings. Limitation: cannot help the Pixel-specific behavior where `force-stop` revokes the secure setting `accessibility_enabled` (true `DISABLED` state, requires user re-enable). Production behavior on Xiaomi/Samsung task-killers may or may not preserve the secure setting — needs telemetry to confirm. Runtime QA blocked on Pixel by the force-stop auto-disable; code-review verified. **2026-05-28 commit `pending` adds 30s process-start grace.**
- [x] ~~**P1** The markdown transcript format has no escaping~~ ✅ **2026-10-23.** A model answer containing a line that starts with `## User` / `## 🦞 Assistant` / `## System` / `## Tools` was read back as a **message header**, and a `---` line toggled the frontmatter and swallowed everything after it. `save()` wrote content raw and `load()` decided what a line *is* from the line itself, so the content — which is model-generated — was read as structure: one answer split into two messages and shifted the rest of the transcript, or truncated it entirely.
  - **格式决策：按需围栏，不做全局转义。** 只在正文含结构行时才写一对 `<<< pokeclaw-body` / `>>> pokeclaw-body` 标记，围栏内按**逐字**读回。选它而不是「给每行加转义前缀」的理由有两条：转义是不可逆的猜（合法以 `\` 开头的正文会被误改，而老文件没有转义，读者无法区分「本来就带反斜杠」和「被转义过」）；围栏则是**无歧义**的——围栏内没有任何一行会被解释。选「按需」而不是「总是」的理由：普通对话的文件保持原样，人打开还是干净的 markdown，且不带围栏的读取路径每天都被走到，不会变成一条只在新文件上才跑的冷路。
  - **标记冲突**：若正文自身含标记行，则改用编号变体（`-1`、`-2` …）——否则围栏会被正文自己的文字提前闭合。有单测钉住。
  - **向后兼容**：老文件没有标记，读者照旧全盘接受（包括它们那些危险行——那是既有行为，不是新回归）。写入侧也无需迁移。
  - **顺带修掉同一族的 frontmatter 伪造**：`title:` 的值是**首条用户消息**，原样写入时一个换行就能续写 frontmatter——正文里塞一行 `id: forged` 会被读成会话 id，而预览页正是靠 id 找回自己的历史（`restoreChat()` 按 id 过滤），伪造成功即历史**静默不再恢复**。现在 frontmatter 的每个值都收敛为单行。
  - 单测 `ChatHistoryManagerBodyFenceTest`（8 例）：`---` 不再吞掉后续、`## User` 不再拆分会话、伪造时间戳行不被采信、标记冲突、**老文件（无围栏）行为不变**、frontmatter 不可伪造。全部走真实 `load()` 往返——这个缺陷只在读写两侧不一致时才存在。
  - ⚠️ **残留风险（记录在案）**：读取侧只在「正文起始处、且尚未累积任何正文」时才认标记行。因此**老文件**里若某条正文的第一行恰好就是 `<<< pokeclaw-body`，会被当成围栏起始。概率极低，且只会让该条消息少一段文字，不会污染别的消息。要根治需要格式版本号，那是另一次范围。
- [ ] **P1** Historical upgrade gap: users on the older public debug signing path still need a one-time uninstall + reinstall because the original public signing key is already lost
- [ ] **P2** K3-a: Auto-return fires on every service connect, not just user-initiated permission enable
- [ ] **P2** B2-a: No auto-return to PokeClaw after task completes in another app (e.g., stuck in YouTube)
- [ ] **P1** Investigate MediaTek/Samsung local-engine bring-up failures that still report OpenCL/LiteRT engine creation errors on some devices even after GPU→CPU fallback
- [ ] **P2** Settings screen: active model row breaks layout when the model name is long; keep the label/value aligned and truncate or wrap cleanly without shoving the left label into a narrow column

### Security — round 2 (2026-10-16, `docs/SECURITY_AUDIT_R2.md`)

- [x] **F1 (Critical) — FIXED 2026-10-16.** Telegram now dispatches `message.from.id` instead of `chat.id`, and the whitelist is keyed `CHANNEL|senderId`. `ChannelSecurityGuard` whitelist API gained a `channel` parameter and `getWhitelist()` returns `Map<Channel, Set<String>>` (**breaking**). `getLastSenderId()` intentionally still returns the chat id — it is the reply/scheduled-push routing target, not the authorising identity; sending to `from.id` would break push to users who never `/start`ed the bot. Unscoped legacy entries are dropped by `purgeLegacyEntries()` rather than guessed; **upgraded installs must re-`/bind`**. Tests 8 → 14.
- [x] **F2 (High, architectural) — FIXED 2026-10-16.** New `ToolRiskPolicy` central gate in `ToolRegistry.executeTool`. High-impact tools (`make_call`, `send_message`, `send_file`, `auto_reply`, `input_text`, `system_key`, `clipboard`) are refused for tasks arriving on any remote channel; local and pre-session (`null`) sources keep full access. Unclassified and `mcp_*` tools default to HIGH_IMPACT (fail closed). Chose a source gate over a per-call confirm dialog because remote tasks have no foreground activity to show a dialog on — see the audit for why that split matters. `get_notifications` is deliberately left readable; tightening it is a separate decision. Tests: `ToolRiskPolicyTest` 10.
- [ ] **P1** `ChannelSecurityGuard` has no UI for the whitelist — a user cannot see or revoke paired senders without clearing all of them. Worth a settings list, and it must handle the "re-bind after upgrade" state introduced by the F1 migration.
- [ ] **P1** Consider whether remote `get_notifications` (OTPs, bank alerts) should be gated like `clipboard`; left readable in F2 as a deliberate scope call.
- [ ] **P1** **F3 (High)** `ExternalAutomationActivity` / `ExternalAutomationReceiver` are `exported="true"` with no permission; `isTargetedToPokeClaw()` is an addressing check, not authorization (any app can `setPackage()`). Mitigated today by `isExternalAutomationEnabled()` defaulting to false. Also `return_action` is caller-controlled → confused-deputy `sendBroadcast`. Fix: `protectionLevel="signature"` permission + caller signature check, and map callback IDs server-side instead of accepting raw actions.
- [ ] **P1** **F4 (High)** `MemoryRecallTool.execute` returns `entry.content` raw, bypassing the `<memory>` fence that `MemoryStore.briefing()` applies — persistent indirect prompt injection (ATLAS AML.T0051.001). Fix: hoist fencing into a shared `MemoryStore.fenceForContext()` used by briefing, recall, and search.
- [ ] **P1** **F5 (High)** LAN console masks credentials on GET but `POST /api/channels` and `/api/llm` still ship real bot tokens and the LLM API key over cleartext HTTP (`cleartextTrafficPermitted="true"` globally). Same-Wi-Fi passive sniffing harvests them. PIN is also accepted as a `?pin=` query param (logs/history/Referer). Fix: reject plaintext on those routes or add pinned self-signed TLS; drop the query-param PIN channel.
- [ ] **P2** **F6 (Medium)** `KVUtils.putEncryptedString` does `SecretBox.encrypt(value) ?: value`, silently writing plaintext when the Keystore refuses — directly contradicting SecretBox's own documented invariant. Fix: fail closed and surface the error.
- [ ] **P2** **F7 (Medium)** Skill `triggers` are recompiled as `Regex` on every route attempt (`SkillRegistry.kt:55-64`, `PipelineRouter.kt:118-121`) from YAML in the external files dir → ReDoS/ANR plus per-call compile cost. Fix: precompile at load, cap pattern length, reject nested quantifiers.
- [ ] **P2** **F8 (Medium)** `XLog.e`/`XLog.i` write to `AppLogStore` unconditionally in release, and `ToolRegistry.executeTool` logs full tool params (typed text, message bodies, phone numbers). `DebugReportManager` bundles those logs plus 400 lines of logcat into a user-shareable ZIP. Latent amplifier: `FileLoggingInterceptor.writeToFile` writes request headers **unmasked** (the mask only exists in the curl string). Currently dormant (`FILE_LOGGING_ENABLED` is always false). Fix: redact by param key, scrub the report, and reuse the curl masking for the header block.
- [ ] **P3** **F9 (Low)** `ConfigServer.isValidHost` returns `true` on a blank Host header, failing open on the DNS-rebinding guard. Should reject, with a loopback carve-out if local curl compatibility is needed.
- [ ] **P3** **F10 (Info)** `WebActivity` enables JS with no scheme allowlist and loads any URL. Currently **unreachable** (zero callers) — record so a future wiring change does not silently activate it.

## Memory bank (see `docs/MEMORY_DESIGN.md` for the rationale behind each call)

- [x] ~~**P1** Memory export cannot be shared off-device~~ ✅ 2026-10-17. `file_paths.xml` now declares `<external-files-path path="." />` + `<files-path path="." />`, and the export action routes through `FileProvider` + `ACTION_SEND`. Verified on device (QA AA1/AA2/AA3/AA5): share sheet opens, empty bank neither crashes nor writes a stray file, cancelling returns to `MemoryActivity`. `MemoryStore` has an injectable `clock`, so the suggested unit tests below are straightforward. Menu retitled 导出为 Markdown → 导出并分享. Still open from the original item: a lossless `exportJson()` — Markdown is for humans, machine re-import needs JSON.
- [x] ~~**P1** ~~`MemoryStore` has zero unit tests~~~~ → **已完成 2026-10-17**，且原描述的前提是错的。记忆库并非「零单测」：`MemorySimilarityTest`（31 例）与 `MemoryRetentionTest`（22 例）早已存在。真正缺口分两块，均已补齐：
  - `MemoryEntryTest`（18 例）：`parseList` 的防御性解析 + `MemoryKind` 权重/标签契约
  - `MemoryStoreTest`（27 例）：四个顺序敏感不变量 —— 抑制检查**先于**近似去重合并、`conflictsWith` 双向维护且删除时修存活侧、`evictLocked()` **不留**抑制指纹、`deleteByContent` 拒绝 <3 字符查询

  为此给 `MemoryStore` 加了两个 `@VisibleForTesting` 接缝：`storageDirOverride: File?` 与 `clock: () -> Long`（5 处 `System.currentTimeMillis()` + 1 处目录分支改走接缝）。**两者默认值即生产路径，未重写任何逻辑**，真机已回归验证。全量 **209 / 25 suites / 0 失败**。

  > 过程中我自己写错 4 条断言，均由测试而非阅读抓出，已全部改为描述真实契约（详见 `QA_CHECKLIST.md` 同日两条记录）。
  >
  > **设计决策留档**：选的是「两个可覆盖读取点」而非接口/子类/`Robolectric`。理由是接缝越小越不可能被误用，且两处默认值即生产路径，无需在测试外证明等价；`Robolectric` 方案虽然零生产改动，但要新增依赖且 MMKV 这类 native 库在 JVM 上是否可用未经验证，风险最高。
- [x] ~~**P1** ~~记忆之间没有「联想」——只有去重合并和矛盾标记~~ → **已完成 2026-10-17**。新增 `MemoryEntry.relatedIds`（单向），由模型在 `memory_save` 时通过 `related_ids` 参数产出。为了让模型能引用，简报与 `memory_recall` 每条结果现在都带 `#编号`。

  **不是相似度算出来的，这是实测逼出来的**：一次性探针测 bigram Dice，相关对落在 0.11–0.42（摄影/Lightroom、Anki/背单词），矛盾对落在 0.50–0.71（Pixel/小米、杭州/上海、橘猫/黑猫）——**矛盾比相关还相似**，任何阈值或加权都分不开，「矛盾 ⟂ 关联」是正交不是程度。社区侧（mem0 v3）同样用「让模型填 `linked_memory_ids`」而非算相似度，取舍与来源见 `docs/MEMORY_DESIGN.md` §7。

  写入时三条硬约束，任一不满足就丢这条关联（宁可没有，不要错的）：编号不存在 / 指向自己 / **两条矛盾**。三条删除路径（`delete` / `deleteByContent` / 淘汰）统一走 `pruneDanglingLocked` 剪掉悬垂引用。手动编辑正文后关联**重新判定**而非盲信。新增 `MemoryLinkTest`（16 例），全量 **225 / 26 suites / 0 失败**。

  > **顺带修掉一个现存缺陷**：`deleteByContent()` 与 `evictLocked()` 此前**不修** `conflictsWith`，只在 `delete()` 里修——即批量删除和容量淘汰会留下指向已删记忆的悬垂冲突。新增关联若不统一处理，同一个洞会翻倍，故三条路径合并到一个 pruner。
  >
  > **过程中我又写错 5 条断言**（累计第 7–11 条）。其中一条是**重复了 §15.3 的同一个教训**：我断言数字型 `relatedIds` 会被丢弃，实际 GSON `asString()` 对 `7` 返回 `"7"`、保留为文本。另外 4 条分别把单向链接的方向写反、用 2 字符查询撞上 `deleteByContent` 的 `< 3` 守卫、用递增编号做填充数据被前缀包含合并掉。全部已改为描述真实契约。
  >
  > **未做**：管理页不展示关联（用户选的是「写入时自动建关联」，UI 是另一次范围）；导出 Markdown 丢关联（与 `conflictsWith` 同样丢，归入下方无损导出那条）。
- [ ] **P2** No import path for the memory bank. `grep import|merge|loadFrom|ingest` over `agent/memory/` returns nothing. Import **must** go through `remember()` / `restoreSuppression()` and never write `memories.json` directly, or it silently breaks three invariants: `hits`/`lastUsedAt` must be **reset** (they are device-local reinforcement — inheriting them fakes familiarity and hollows out the retention curve, turning "what got recalled" into "what got imported"); `pinned` **travels** (it is an explicit user decision); **suppression fingerprints must travel too** (otherwise facts deleted on device A get re-learned on device B). Pass `fromAgent = false`, matching the reasoning already documented in `restoreSuppression()`. Cap the entry count defensively — see the `deleteByContent` `q.length < 3` lesson about stray input doing irreversible damage. **`relatedIds` / `conflictsWith` must be remapped on import**: a content-hash id does not survive a rewrite of the imported text, so links copied verbatim become dangling ids.
- [ ] **P1** Lossless `exportJson()` for the memory bank — `exportMarkdown()` writes only content/tags/pinned, so it drops `hits`/`lastUsedAt` (retention state), `conflictsWith` and `relatedIds`. It is a human-readable share format, not a round-trip format, and should not be presented as one. Markdown export stays as-is.
- [ ] **P3** Optional: contrast-bound evaluation for the bank. Community benchmark numbers are not comparable across frameworks (see `docs/MEMORY_DESIGN.md` §1.1), so a small local set of (query → expected entry) pairs would be the only honest way to judge a ranking change. Not blocking; useful before any tuning of the retention curve.

## Temporary surfaces — DELETE BEFORE ANY PUBLIC RELEASE

Anything here is reachable by users right now. If a release is being cut and this
section is not empty, the release is not ready.

> **2026-10-18 变更**：Claude UI 预览页**已不再是「临时面」** —— 它接上了真实任务流
> （`WebChatSink` → `TaskFlowController`），是有产品功能的入口。原先「发布前必删」
> 的判据随之作废。下面剩下的条目才仍适用这条规则。

> **2026-10-22 复核：本节当前为空** —— 没有待删条目。唯一的候选（Claude UI 预览页）
> 已于 2026-10-18 转正（见下方「Promote」条），同日 `QA_CHECKLIST.md` 里那条
> `[RELEASE-BLOCKER] … must not ship` 的说明也已一并删掉：它会告诉下一个人删掉一个
> 能用的功能。**本节留在这里是给未来的临时面用的** —— 新增临时面必须登记在此，
> 否则发布时没人知道该删什么。
>
> 转正之后，它的验收用例也归入正典：2026-10-22 补完了 H13 / H14 / H15 / H19 四条
> （H14 的泄漏项第一次拿到真数据），并因此**发现并修掉一个真实缺陷**：工具卡在历史恢复
> 后会消失 —— 聊天页里那次工具调用整个不见，Web 端被拍平成一行字。见 `QA_CHECKLIST.md`
> H26 与下方「Bugs」条。

- [x] ~~Claude UI design-review surface~~ **已转为功能入口**。原先记为「无产品价值、纯设计评审、
  不得随发布」，但 B 方案落地后它驱动的是真实任务流（见下方「Promote」条）。
  唯一残留的临时痕迹是 `index.html` 里的静态示例 turn（工具卡 / MCP 卡 / 记忆 chips），
  它们在 `toolSteps` 喂上数据前应视为示例而非缺陷。

- [x] **P1** Promote the Claude UI mockup to a real screen. The user wants the design live, not just viewable. **Both seams are now extracted**: `ChatSink` (`ui/chat/ChatSink.kt`, commit `86e4719`) for the task flow, and `ChatSessionSink` (`ui/chat/ChatSessionSink.kt`) for the session path — a WebView surface implements them and forwards to `evaluateJavascript` instead of re-implementing either flow. Do not shortcut by duplicating the flow — that is exactly the anti-pattern these extractions exist to remove.
  - ⚠️ The two sinks are **deliberately separate types**. `replaceTypingIndicator` has opposite contracts (task flow no-ops when the indicator is gone; session appends so the user's reply is never swallowed). Do not merge them without changing one caller's behaviour on purpose — `TaskFlowUiStateTest` and `ChatSessionUiStateTest` will each break, which is the intended alarm.
  - ✅ 已验证：`QA_CHECKLIST.md` **H17** 于 2026-10-18 真机 PASS（单条助手气泡、模型状态行、无 FATAL）。**唯一例外**：token/费用计数的真实数值未取证 —— `XLog` 在 release 下只有 `e` 级进 logcat，计数走 `XLog.d`，而设备上是 release 签名、debug 包无法覆盖安装。
  - ✅ **2026-10-18 已实现第三层接线**：`WebChatSink`（`ui/web/WebChatSink.kt`）实现 `ChatSink`，`ClaudeUiPreviewActivity` 用它驱动 `TaskFlowController`；页面侧新增 `renderLive`/`renderLiveRaw` 按 role 重建 turn，composer 经 `PokeClawChat.send` 回调 Kotlin。**编排逻辑仍是 `TaskFlowController` 一处**，这是「一个控制器 + 三个 sink 实现」，不是 CLAUDE.md 说的 duplicate code path。
  - 配套：`WebChatSinkTest` 13 例；全量 **260 tests / 29 suites / 0 失败**。
  - ✅ **真机 PASS**（release 包 `..._171735.apk`）：`QA_CHECKLIST.md` **H18** 四项判据全通过 —— 用户气泡 → 「正在思考…」→ 真实回复替换 → **完成后仍停留在预览页**。`topResumedActivity` = `.ui.web.ClaudeUiPreviewActivity`，无 `FATAL` / 无 `ANR`。
  - ✅ **连带修掉一个真 bug**（commit `b93b9b4`）：`TaskOrchestrator.onComplete` 把回跳目标**硬编码**为聊天页 —— 任何非聊天页发起的任务都会被误送回聊天页。改为 `TaskSessionState.originActivity` 记录发起方类名（存字符串不存引用，任务可能比界面活得久）。
    - ⚠️ **硬编码回跳共四处，语义不同**：watchdog 超时是 `GLOBAL_ACTION_HOME`（回桌面）；`bringAppToForeground` 只挂在浮动球点击回调上（且其入口 `showFloatingCircle()` 零调用方）；通知 PendingIntent 应指主界面。**只有 `onComplete` 那一处是 bug**，其余故意不动。
    - ✅ 全量 **264 tests / 29 suites / 0 失败**（新增 4 例，含「release 后 origin 必须清空」防泄漏）。详见 **H20**。
  - ✅ **2026-10-19 工具卡已接真实数据**：`ChatSink` 新增 `appendToolStep()`，`handleTaskEvent` 在 `TaskEvent.ToolResult` 到达时写入 `TOOL_GROUP` 消息。**只在结果时落卡** —— `ToolAction` 与 `ToolResult` **并非严格成对**（`TaskOrchestrator:319` 会发一个永远没有结果的 `ToolAction("Retrying with AI agent")`），在 Action 时落卡会留下永久「运行中」的卡片。
    - 之前的判断「缺的是生产侧」是对的：`ChatScreen` 早有 `ToolGroup()` 渲染器、`cds.js` 早就能画 `.tool-card`，两边都在等一个不存在的字段。
    - 全量 **274 tests / 29 suites / 0 失败**（新增 5 例）。**2026-10-19 真机 PASS**，用户确认工具卡出现且工作正常。
  - ⚠️ **MCP 确认卡 / 记忆 chips 仍是静态示例**：两者都有渲染器、无数据源。MCP 确认按钮需一条 Kotlin → JS → Kotlin 的**决策回路**，记忆 chips 需接到记忆召回结果。属另一次范围决策。
  - ✅ **2026-10-19 收敛完成**（commits `5b4e68c` / `aa0a6ed`）：①修 `executor` 线程泄漏（我上一笔引入的缺陷，`ComposeChatActivity` 本来就有这行）；②用 `<html data-live="true">` 隐藏死控件，**保留纯浏览器里的完整设计稿**；③会话落盘（`saveChat()` 从空实现改为真实写入）。
    - ⚠️ **落盘刻意绕开 `ConversationStore`**：它的 `currentConversationId` 存在**全局 KV**，共用会让预览页与聊天页互相覆盖对方历史。改走无状态 `ChatHistoryManager` + 固定 ID `preview_claude_ui`。
    - ⚠️ **文件路径算不出来**：`save()` 用「日期 + 首条用户消息」命名，ID 只在 frontmatter 里。故按 ID 过滤 `listConversations` 取最新匹配。
  - ✅ **2026-10-19 真机确认「能用了」**。全量 **269 tests / 29 suites / 0 失败**。详见 `QA_CHECKLIST.md` **H21 / H22**。
  - ✅ **2026-10-19 入口改名**：「Claude UI 设计稿 / mockup」→「**Claude UI 实验版 / experimental**」（三语）。理由：它已接真实任务流，「设计稿」名不副实；而 MCP 卡 / 记忆 chips 仍是静态示例，「实验版」如实反映成熟度。
    - 顺带清掉五处「发布前必删」「不连后端」「计划移除」等**与现状相反**的表述（`SettingsActivity` 横幅、`mockup/claude-ui/README.md` 开篇、`NOTICE`、`BACKLOG` 删除清单、`scripts/ui_cycle_test.py` 的旧入口名）。最危险的是 `SettingsActivity` 那条横幅——它会告诉下一个人删掉一个能用的功能。

  Same class of leak as `.github/FUNDING.yml` shipping `github: ithiria894` (§10.2).

## Features

- [x] ~~**P1** MCP server editor: auth-type dropdown for the auth header~~ (2026-10-10). `McpServersActivity.showEditDialog` provides an auth-type selector (None / Bearer / Basic / Custom) that automatically formats and ensures the `Bearer ` or `Basic ` prefix, plus runtime `normalizedHeaderValue()` in `McpServerConfig` and `McpStreamableHttpClient` preventing 401s when bare tokens are provided. Unit tests added in `McpServerConfigTest.kt`.

- [~] ~~**P0** Missed-call auto follow-up~~ — **dropped 2026-05-26**. Missed-call response is the dedicated scope of the separate revenue product `missed-call-ai-chatbot-lab` (web prototype, Android port planned there). PokeClaw stays the generic mobile-agent harness per ARCHITECTURE_DECISIONS.md D2 — no per-vertical workflows in core. If a future need arises for a generic "phone state" tool (any task that depends on call state), add it as a tool primitive, NOT a workflow.
- [x] ~~**P0** Production external automation intent: promote the debug-only task/chat broadcast into a user-enabled production API for Tasker, MacroDroid, Locale, and ADB-style callers. It should accept explicit package/component broadcasts with `task` / `chat` / base64 extras, preserve harness safety rules, and optionally return a result callback intent.~~ — implemented 2026-04-30; callback contract exists, Tasker/MacroDroid callback E2E remains a QA gap
- [ ] **P1** Persistent global instructions: add a user-editable local instructions layer that applies to new tasks/conversations without becoming a prompt dump. It must be short, inspectable, removable, local-first, and separate from hard safety/tool rules.
- [ ] **P1** Scoped app/channel rules: support rules scoped to apps or channels such as WhatsApp, Telegram, Gmail, Browser, and Phone so the harness loads only relevant guidance instead of stuffing every rule into every local-model context.
- [ ] **P1** Explicit user-approved memory: add manual "remember this" style memory first, then optional suggested memories only after user approval. Memory must be deletable/exportable and must not store secrets, bot tokens, API keys, or recovery codes.
- [ ] **P1** Telegram remote-control channel hardening: treat the Telegram bot token path as a first-class remote-control channel with clear setup, token validation, polling status, and E2E QA gates. Current QA can configure a bot token, but live send-to-bot E2E is blocked on the QA Telegram account being frozen/read-only.
- [ ] **P2** Voice input: add a prompt microphone button as an input method, preferably using an available cloud transcription path when the user has a cloud API key and a local/on-device option later. Wake-word/background listening is a separate higher-risk permission/battery design, not the MVP.
- [ ] **P1** Local model import UX: keep shared-storage `.litertlm` import easy and explain clearly why other apps' `Android/data/...` sandboxes (for example Edge Gallery) are not directly readable
- [ ] **P1** More small local model options: add 1B / 1.5B-class local models so lower-RAM phones can still run a useful on-device agent
- [ ] **P1** Custom local model sources: let users point PokeClaw at user-defined model URLs / hosted downloads instead of only the built-in catalog
- [ ] **P2** Google AI Core integration research: evaluate Android's official on-device AI / system model APIs as an optional local runtime path
- [ ] **P1** Structured monitor identifiers: let monitor setup keep a user-facing nickname while using a more stable identifier where possible (phone number / app-stable id / aliases) so WhatsApp/Telegram display-name drift stops breaking setup
- [ ] **P2** Chat keyboard dismissal polish: tapping non-button chatroom space should reliably clear focus and hide IME in both empty and non-empty conversations
- [ ] **P1** Structure-first UI matching: remove remaining language-specific text heuristics where the platform exposes a stable structural hook first (dialog positive buttons, send affordances, standard action widgets)
- [ ] **P1** Tinder automation: auto swipe + monitor matches + auto-reply using same monitor architecture as WhatsApp
- [x] ~~**P1** NLP Playbooks (Layer 2): 5 playbooks in system prompt (Search in App, Navigate Settings, Compose Email, Read Screen, Read Notifications)~~ — done 2026-04-08
- [x] ~~**P1** In-chat task auto-return~~ — done 2026-04-08
- [x] ~~**P2** Monitor stays in app~~ — done 2026-04-08, removed GLOBAL_ACTION_HOME
- [ ] **P2** Unified task registry: monitor + agent tasks tracked in same system (top bar, floating button, etc.)
- [ ] **P3** Rename chat session (H6): pencil icon in sidebar → InputDialog → update title in DB + markdown
- [ ] **P3** Floating button: use PokeClaw icon instead of "AI" text
- [ ] **P3** ChatViewModel extraction: move business logic out of ComposeChatActivity god class
- [x] ~~**P1** Full i18n refactor for ChatScreen.kt~~ (2026-10-10) — 75+ user-facing hardcoded English strings (quick task templates, intro text, monitor labels, workflows, send/cancel dialogs, active task bar, token info, model picker) extracted to strings.xml + values-zh + values-ja translation. Requested by realme RMX3823 user (#50) for thorough Chinese localization.

## QA Gaps

- [ ] **P2** Emulator matrix red for **infrastructure** reasons is indistinguishable from a real regression at a glance. Two distinct classes seen in one day (2026-10-19/20):
  | Run | Job | Symptom | In our code? | Fix |
  |---|---|---|---|---|
  | 39240300241 | API 35 | all assertions passed, `adb logcat -d` hung → `set -e` killed the script | **yes** | ✅ fixed, QA CI1 + `smoke-harness-self-test` |
  | 39359436248 | API 34 | `sdkmanager` → `Error on ZipFile unknown archive`, emulator never booted, smoke script never ran | **no** | re-run only; went green on retry |
  - The API 34 case is upstream of us — a corrupt system-image download on the GitHub runner. Nothing in this repo can prevent it, and `emulator-smoke.sh` never executed, so it carries **zero signal about the app**. Next step if it recurs often enough to annoy: a retry wrapper around the `reactivecircus/android-emulator-runner` step, or pinning `force-avd-creation` / caching the system image so the download happens once.
  - Until then: **read the failing job's log before assuming a regression.** A red job whose script never ran is not a product signal.
- [ ] **P0** SEC1–SEC11 security E2E never executed. The red-team audit fixed 4 issues (PIN brute force, credential plaintext on read, MCP tool poisoning, path traversal), but **not one SEC case has been run on a real device**. Code-fixed is not security-tested — `docs/SECURITY_AUDIT.md` §4 states this boundary explicitly. Currently blocked by test-environment constraints, not by the code itself.
- [ ] **P0** E2E for the round-2 findings (F1–F10, `docs/SECURITY_AUDIT_R2.md`) is likewise entirely unexecuted. F1 especially needs a live Telegram group to demonstrate end-to-end. Static code paths are confirmed; exploitability is not.
- [ ] **P1** `./gradlew testDebugUnitTest` now runs in CI (`build.yml` `test` job) — verify the first green run on push and consider adding the same step to `emulator-matrix.yml` / `release.yml` so tag builds cannot ship without tests passing.
- [ ] **P1** Enable GitHub private vulnerability reporting so the `SECURITY.md` intake path is reachable by outside reporters. There is no REST endpoint for this setting; it must be toggled in the browser.
- [ ] **P1** Keep an offline backup of the release signing key. Without it, existing installs cannot be updated in place and users would have to uninstall and reinstall.

- [ ] **P0** Missed-call follow-up E2E: missed-call notification / phone-state trigger reaches PokeClaw, follow-up message is sent to the caller, and the result/status is visible in the same chatroom
- [ ] **P0** Production intent E2E: Tasker/MacroDroid-style explicit broadcast reaches PokeClaw in a release build, starts the requested task/chat, and never bypasses safety/global rules
- [ ] **P1** Production intent callback E2E: when an external automation request includes `request_id` and `return_action`, PokeClaw broadcasts a completion/failure result that Tasker/MacroDroid can consume
- [ ] **P1** Telegram bot channel E2E: token configured -> polling connected -> user sends `/start` and a task to the bot -> PokeClaw receives the update -> returns a visible bot reply. Current QA is blocked by the handset Telegram account being frozen/read-only.
- [ ] **P1** C2: Auto-reply trigger E2E — needs 2nd device to send WhatsApp message to Girlfriend
- [x] ~~**P1** Release QA: verify locally signed `0.5.1+` public APK can upgrade in-place over the next signed public build once the stable key is installed in GitHub Actions~~ — done 2026-05-26: v0.6.12 signed → v0.7.0 signed in-place upgrade PASS on Pixel 8 Pro (same keystore, no uninstall needed)
- [ ] **P0** PROCESS GATE — full QA on signed-release APK BEFORE pushing the version tag. v0.7.0 was tagged first and QA'd second; CLAUDE.md says full QA triggers on "before any release/version bump". Next release: pull the locally-built signed `assembleRelease` APK or the CI artifact from a draft release, run V/W/X/Y + J + K + P sections, only then push the tag.
- [ ] **P1** v0.7.x follow-up — re-run W7/W8 with a configured cloud LLM to verify PromptUtils.applyGlobalPrompt logcat trace fires (or AppLogStore captures it in debug-report.zip)
- [ ] **P1** v0.7.x follow-up — re-run V3/V4/V7-V9 with actual human voice to verify transcript appends to text field correctly on signed release
- [x] ~~**P2** Emulator Matrix CI workflow debug~~ — fixed 2026-05-26. ROOT CAUSE: `reactivecircus/android-emulator-runner@v2` runs each LINE of the `script:` block as a separate `sh -c <line>` call, breaking variable persistence and if/then/fi continuation. Fix: extract logic to `scripts/emulator-smoke.sh` and invoke as a single command. All 5 API levels (29/31/33/34/35) now GREEN. ClawApplication boot + ComposeChatActivity launch + zero FATAL verified per API.
- [x] ~~**P1** M1-M12 QA: Cloud LLM complex tasks~~ — done 2026-04-08, 10/12 PASS
- [x] ~~**P2** K6: Verify each Settings permission row leads to correct system settings page~~ — done 2026-05-26 on Pixel 8 Pro v0.7.0 signed: all 6 rows route to the correct system Activity (Accessibility, Task Notifications via permissioncontroller, Notification Access, System Window via SpaActivity, Battery Whitelist via RequestIgnoreBatteryOptimizations, File Access via SpaActivity). Recorded in QA Debug Changelog 2026-05-26.
- [ ] **P2** Settings layout QA: verify long local/cloud model names render cleanly on the Settings screen across Pixel/Samsung widths
- [ ] **P1** Bump GitHub Actions off `@v4` (Node 20 deprecation). All five workflows emit `Node.js 20 is deprecated ... being forced to run on Node.js 24` for `checkout` / `cache` / `setup-java` / `upload-artifact` / `download-artifact`. Verified real majors: checkout v7, cache v6, setup-java v6, upload-artifact v7, download-artifact v8. **Not** a clean bump — the artifact actions cross real breaking changes across v4→v7/v8 (v4 already made artifacts immutable; behaviour differs again in the v5+ line), so it needs its own change plus a verified green run, not a find-replace. Currently non-breaking: GitHub forces v4 onto Node 24 and the matrix passed 2026-10-13 with all warnings present. Blocked on nothing; just not worth risking a red CI inside an unrelated fix.
- [ ] **P2** Download free space check — done 2026-04-08 (StatFs before download)
- [ ] **P1** Local vague-task UX: in Local Task mode, prompt-only behavior is correct, but vague requests like `Copy that token to the clipboard` currently hang instead of failing fast with a clear request for the missing content/details

## Ideas / Research

- Monetization: two-tier (dev=free open source, consumer=China APK + premium features)
- YC application showcase
- Layer 2 NLP Playbooks as "App Cards" like DroidRun
- On-device LLM as competitive moat (first to ship with Gemma 4)
- Positioning: cloud/desktop-driven mobile-agent frameworks already exist; PokeClaw should own the phone-resident, local-first, model-slot harness that can run on a user's own Android device without a PC/cloud phone fleet.

---

## Done

_Move completed items here with date._

- [x] ~~2026-04-30: Production External Automation API~~ — user-enabled `RUN_TASK` / `RUN_CHAT` broadcasts, base64 extras, safety opt-in, and task terminal callback contract
- [x] ~~2026-04-08: Fix "Accessibility starting..." on every chat (A1-b)~~
- [x] ~~2026-04-08: Floating button IDLE→RUNNING in other apps (F3-b)~~
- [x] ~~2026-04-08: LiteRT-LM session conflict + GPU→CPU fallback (D1-a, D1-b)~~
- [x] ~~2026-04-08: Monitor permission check + auto-return after grant~~
- [x] ~~2026-04-08: Settings page: Notification Access row~~
- [x] ~~2026-04-08: Full QA pass 49/50 cases~~
- [x] ~~2026-04-08: Download free space check (StatFs before download)~~
- [x] ~~2026-04-08: Task detection keywords fix (check, compose, find, screen, notification, read my)~~
- [x] ~~2026-04-08: Compound task routing fix (skip Tier 1 for "and"/"then"/"after")~~
- [x] ~~2026-04-08: M1-M12 QA: 10/12 PASS, 2 PARTIAL (M9 camera, M12 system dialog)~~
- [x] ~~2026-04-08: NLP Playbooks Layer 2: 5 playbooks (Search, Settings, Email, Screen, Notifications)~~
- [x] ~~2026-04-08: Tinder research: UI structure documented, workflow designed, needs login~~
- [x] ~~2026-04-10: Chat bubble timestamps~~ — IG-style per-message footer landed for user + assistant bubbles, with hidden timestamp metadata persisted in markdown history so relaunch/reload keeps stable times
- [x] ~~2026-04-28: Release publishing stable signing path~~ — `v0.6.9` tag workflow produced a signed release APK and `SHA256SUMS.txt` through GitHub Actions
- [x] ~~2026-05-26: Voice Input (#44)~~ — system RecognizerIntent wired into chat composer between TextField and Send FAB. V1/V2/V5/V10 ADB-verified PASS on Pixel 8 Pro v0.7.0; V3/V4/V7/V8/V9 need human voice verify, code path structurally verified
- [x] ~~2026-05-26: Persistent Global Prompt (#45)~~ — MMKV-backed user-defined instructions, prepended to system prompt at ChatSessionController.buildConversationConfig and AgentConfig.Builder.build. Settings row under Model group, InputDialog editor, max 2000 chars, empty string = disabled. W1-W6 ADB-verified PASS Pixel 8 Pro v0.7.0 (MMKV persistence via run-as confirmed); W7/W8 logcat injection trace needs configured LLM to fire
- [x] ~~2026-05-26: Custom Local Model URL (#36)~~ — MMKV-backed advanced setting. Adds synthetic ModelInfo (isCustom=true) appended to LocalModelManager.catalog(); LlmConfigActivity now iterates catalog so custom row renders. Relaxed file validation (≥1MB) for unknown-size custom models. http(s) prefix validator + Android auto-cap normalization. X1-X8 ADB-verified PASS Pixel 8 Pro v0.7.0 (custom model appears in Available Models list with displayName "Custom: my-model.litertlm")
- [x] ~~2026-05-26: Floating button icon swap (BACKLOG P3)~~ — replaced "AI" text label with PokeClaw small icon in `layout_floating_circle.xml`'s `cardIdle` view. Layout compiles + inflates without error, content-desc localised in en/zh/ja. Tracked `tvFloatTextIdle` -> `ivFloatIconIdle`; old `floating_ai_label` string left in place but unused, can be cleaned up later
- [x] ~~2026-05-26: GPU/OpenCL diagnostics in debug-report (#41 + #14)~~ — DebugReportManager summary.txt now includes Supported ABIs, RAM, OpenCL library presence check (probes 6 well-known driver paths), and LocalBackendHealth.debugStateSummary() output. Y1-Y4 ADB-verified PASS Pixel 8 Pro (OpenCL found at /system/vendor/lib64/libOpenCL.so confirms why Pixel GPU fallback works; non-Pixel reporters will see "(none)" if drivers missing — enables instant community triage)
- [x] ~~2026-05-26: Rename chat session — pencil icon (BACKLOG P3)~~ — pencil edit icon added next to each conversation row in sidebar's Recent list, alongside the existing long-press menu (kept for delete + power users). Tap pencil → existing rename AlertDialog opens pre-filled with current title. Long-press still works for both Rename and Delete. ADB-installed cleanly on Pixel 8 Pro; structural verification only (need an actual saved conversation to dump the icon in uiautomator, deferred to human verify or LLM-enabled QA session)
- [x] ~~2026-10-10: Full i18n refactor for ChatScreen.kt (Ticket 44)~~ — extracted 75+ user-facing hardcoded English strings across top bar, model menu, token info, input composer, quick task templates, background monitor dialog, send message dialog, and active task bar into strings.xml with zh and ja translations. Unit tests pass.
- [x] ~~2026-10-11: WeChat, QQ, Douyin Channel Integration & Security Hardening~~:
  - Multi-channel support: QQ OneBot v11 reverse WebSocket (`QqChannelHandler`), Douyin Open Platform Webhook/Gateway (`DouyinChannelHandler`), WeChat manual Base URL + Token input (`WeChatChannelHandler`), with automatic background reconnection and sender ID extraction.
  - Channel Anti-RCE Defense: `ChannelSecurityGuard` interceptor acting as first line of defense; unauthenticated senders blocked from `TaskOrchestrator`; dynamic 6-digit PIN pairing (`/bind <PIN>` or `绑定 <PIN>`) with persistent whitelist.
  - Credential Keystore Encryption: `SecretBox` (AES-256-GCM via Android Keystore) integrated into `KVUtils` for all bot tokens (Discord, Telegram, WeChat, QQ, Douyin) and LLM API keys with transparent migration.
  - ConfigServer LAN Protection: Removed wildcard CORS; enforced Origin/Host verification against DNS rebinding; mandatory `X-Config-PIN` auth on `/api/*`; gated `/api/debug/execute` against release and remote execution.
  - UI & Accessibility: QQ, Douyin, and Channel PIN entries in Settings; `MonitorTargetSpec` and `AutoReplyManager` extended for QQ (`com.tencent.mobileqq`) and Douyin (`com.ss.android.ugc.aweme`); full en/zh/ja localization; unit tests added in `ChannelSecurityGuardTest`.

