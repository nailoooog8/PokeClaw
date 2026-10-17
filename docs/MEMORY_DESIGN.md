# 记忆库设计决策 —— 社区调研与取舍

> 调研日期：2026-10-17。记录的是**取舍与理由**，不是功能说明。
> 当前实现（机制、字段、算法、文件格式）见 [`MEMORY_BANK.md`](MEMORY_BANK.md)，本文不重复。

---

## 0. 为什么有这份文档

`MEMORY_BANK.md` 回答「记忆库现在是怎么工作的」。本文回答另外两个问题：

1. 社区在记忆 / 向量检索 / 记忆沉淀上收敛到了什么方案？
2. 我们**不**跟哪些，以及为什么？

写下来是为了避免以后重复调研，以及避免被「该上向量库了」这类直觉反复推动。

---

## 1. 社区现状（2026-10）

2026 年的 agent memory 已经从 prompt 技巧变成独立赛道。四条主流路线：

| 方案 | 核心抽象 | 量级 | 最适合 | 对我们 |
|---|---|---|---|---|
| **Mem0** | 抽取流水线逐轮决定 ADD/UPDATE/DELETE | ~48–55k star | 快速个人化、多租户、通用 agent | ❌ 面向云端 / 向量库 |
| **Zep / Graphiti** | 时序知识图谱，事实带**有效期区间** | ~24–27k star | 事实会随时间失效、需审计 | 🟡 部分对 |
| **Letta / MemGPT** | OS 式分层记忆，agent 自管 | ~15–23k star | 长跑自主 agent | ❌ 需要常驻 runtime |
| **LangMem** | episodic / semantic / procedural 三分 | LangChain 内置 | 已在 LangGraph 栈内 | ❌ 我们不用 LangGraph |

**市场本身不稳定**：有横评直接预测 2027 Q1 之前这四家至少两家合并或转向，理由是「碎片化到养不起四套互相竞争的方案」。
→ **不要把架构押在任何一家的 API 形状上。**

### ⚠️ 1.1 benchmark 数字不能横向比较

流传最广的几个数字**测的不是同一件事**，引用它们当目标会得出错误结论：

| 数字 | 出处 |
|---|---|
| mem0 「准确率 +26%」 | 相对 OpenAI 内置记忆，非绝对分 |
| LangMem ~58% (LOCOMO) | 且 **p95 延迟 ~60s** |
| Graphiti 63.8% / Zep | GPT-4o 条件下 |
| Letta 83.2% | 另一套 harness |

横评文章自己在开头就承认双方对「对方用了多少上下文」各执一词。
**结论：这些数字只用于判断量级，不能用于自测验收。**

### ⚠️ 1.2 社区最想要的能力，恰是商业化最重的部分

- Mem0 的知识图谱（实体解析、多跳查询）**锁在 $249/mo Pro 层**，免费/OSS 只有向量检索
- Zep **已弃用 Community Edition**

**对我们的含义**：市面上的免费开源方案，没有一个提供我们已有的东西——
「**删除后禁止 agent 写回**」（抑制指纹）、「**矛盾显式暴露给用户裁决**」、「**淡出但永不自动删除**」（保留度曲线）。
这三项是规则算出来的、可单元测试的、不依赖任何云服务。这是真差异点，不是「还没升级到向量库」。

---

## 2. 决策：暂不引入向量检索

社区的端侧向量方案**技术上已经可行**，有实测数据：

| 指标 | 实测值 |
|---|---|
| 全链路 p95 延迟 | ~140ms（Pixel 7a） |
| 总体积 | 38MB（含 INT8 embedding 模型） |
| 向量检索（1 万条，暴力） | 2–8ms |
| sqlite-vec 编译体积 | ~100KB |

`litertlm-android` 我们已经依赖，ONNX Runtime 那条路是通的。**但仍然不做**，三条理由按权重排序：

1. **语料只有 200 条上限**（`MemoryStore.MAX_ENTRIES = 200`，[MemoryStore.kt:42](app/src/main/java/io/agents/pokeclaw/agent/memory/MemoryStore.kt#L42)）。对 200 条做暴力检索是**亚毫秒级**。用 22MB 模型换亚毫秒，净亏。

2. **embedding 出端即破定位**。社区自己讲得很直白：embedding「看起来是匿名数字，但仍包含含义」，可暴露主题、意图，甚至被再识别。这与本项目「数据不出手机」的核心定位直接冲突，也会与 `SECURITY_AUDIT` 的叙事自相矛盾。

3. **保留度曲线是向量库管不了的东西**。`MemoryRetention` 回答的是**治理**问题——被用得多所以留得久、淡出但不删除、删了不许写回。向量检索只解决「哪块文本相关」，与这三项正交。

### 2.1 该借鉴的是那个**分工判断**

社区最有价值的一条洞察不是技术，是分层：

> **RAG 知识库**（大、多用户、读多写少）适合托管服务；
> **私有记忆**（小、单用户、每轮读写）应该留在端上。
> 「agent memory 一直感觉没被解决，是因为它被悄悄塞进了错误的层。」

我们**已经在对的一侧**。不要因为别人往云上搬就跟着搬。

---

## 3. 决策：记忆写入**不需要**异步化（并记录一次误判）

> **本节是一次自我纠正的留档。** 最初的结论是「记忆沉淀必须挪出热路径」，理由是 LangMem 的 p95 ~60s。**该结论错误**，原因见下。

### 3.1 误判是怎么产生的

那 60 秒来自 LangMem 的**抽取流水线**：每轮额外调用一次 LLM 去决定「这轮该抽出什么记忆」。我们**没有这条链路**：

| 环节 | 我们的实现 | 成本 |
|---|---|---|
| 判断「要不要记」 | 模型在**它本来就要跑的工具循环里**决定调不调 `memory_save` | 无额外 LLM 调用 |
| 执行写入 | `MemorySaveTool.execute` → `MemoryStore.remember()`（[MemoryStore.kt:176](app/src/main/java/io/agents/pokeclaw/agent/memory/MemoryStore.kt#L176)）→ 纯本地 JSON 写盘 | 毫秒级，同步无阻塞 |
| 读入提示 | `MemoryStore.briefing()`（[DefaultAgentService.kt:485](app/src/main/java/io/agents/pokeclaw/agent/DefaultAgentService.kt#L485)）→ 纯本地打分 | 亚毫秒级 |

**抽取即决策、由同一轮生成顺带完成**——这正是我们相对 Mem0 / LangMem 的**优势**，不是需要修掉的缺陷。
引入异步队列只会增加「记忆什么时候落盘」的不确定性，而换不来延迟收益。

### 3.2 唯一真实的代价（量级很小，不必现在动）

模型决定调 `memory_save` 会在工具循环里多插一个 round-trip。代价约等于一次工具调用，远小于一次生成。
若日后实测成为问题，正确做法是**把沉淀移到任务结束后的收尾阶段**，而不是引入并发队列。**暂记，不作为待办。**

---

## 4. 待评估：矛盾的时间维度（Zep 的核心能力）

Zep 相对 Mem0 领先的那 ~15 个点（63.8% vs 49%），差距几乎全在时间维度。它给每条事实一个**有效期区间**而非覆盖写：

```
active(f, t) = 1   if  t_from ≤ t < t_to
               1   if  t_from ≤ t 且 t_to 未知
               0   otherwise
```

并且明确区分**有效期**（事实何时为真）与**录入时间**（系统何时知道）——「合同今天上传，但一月份就生效了」。

### 我们的现状

`conflictsWith` 双向互标 + 提示词规则 2「⚠ 的条目不能只凭其中一条回答，应当直接问用户」。
即：**我们做了检测，把裁决成本推给了用户**；Zep 是**自己判断哪个仍在有效期内**，判不了才问。

### 为什么暂不做

自动裁决的失败模式是**不诚实的失败模式**：猜错时用户看到的是「它把我以前说的话忘了」，
这比现在多问一句糟糕得多。现有做法（标 ⚠ + 问用户）是**诚实的失败模式**。

除非明确接受偶尔的错误静默，否则维持现状。
若要做，只借「有效期」这一半即可，**不要**引入 Zep 那套双时态图索引——那是为共享知识图谱设计的，我们是单人单机。

---

## 5. 确认的实现缺口

以下是**代码事实**，不是调研意见。

### 5.1 导出无法分享（潜伏 bug）

`file_paths.xml` 只声明两个 root：

````xml
<external-files-path name="downloads" path="Download" />
<cache-path name="cache" path="." />
````

而 `MemoryStore.storageDir()`（[MemoryStore.kt:562](app/src/main/java/io/agents/pokeclaw/agent/memory/MemoryStore.kt#L562)）写入的是 `getExternalFilesDir(null)` **根目录**，
即 `memories-export.md` 落在 `<ext-files>/`，**既不在 `Download` 下、也不在 cacheDir 下** → 两个 root 都不覆盖。
另外 `storageDir()` 会 fallback 到 `ctx.filesDir`（内部目录），而 `file_paths.xml` **完全没有 `<files-path>`**。

Debug 报告之所以没暴露这个问题：它写进 `cacheDir/debug_reports/`（[DebugTaskReceiver.kt:312](app/src/main/java/io/agents/pokeclaw/debug/DebugTaskReceiver.kt#L312)），正好被 `cache-path` 覆盖。

**当前状态**：尚未崩溃——导出只弹一个 Toast 显示路径，没有走 `FileProvider`。
**将来谁照着 `SettingsActivity.shareReportFile()` 加分享按钮，`getUriForFile` 会直接抛 `IllegalArgumentException`。**
修法：补 `<external-files-path path="." />` + `<files-path path="." />`。

### 5.2 导入完全缺失

全包 grep 无 `import` / `merge` / `loadFrom` / `ingest`。只有导出，没有回灌。

导入**必须走 `remember()` / `restoreSuppression()`，不能直接写文件**，否则会静默破坏三条不变量：

| 字段 / 行为 | 导入时怎么处理 | 理由 |
|---|---|---|
| `hits` / `lastUsedAt` | **清零** | 这是**设备本地**的强化信号。跨设备继承会伪造熟悉度，直接架空保留度曲线——「谁被召回过」会变成「谁被导入过」 |
| `pinned` | **带走** | 用户的显式决定，跨设备应一致 |
| 抑制指纹 | **必须同行** | 否则 A 上删掉的事实会在 B 上被重新学回来 |
| 与本地已有冲突 | 复用现有矛盾检测 | 不重写 |

调用时 `fromAgent = false`，理由与 `restoreSuppression()` 内的注释一致：**用户显式导入，不是 agent 自己要学**。
另需设条数上限防呆（参考 `deleteByContent` 的 `q.length < 3` 教训：别让外来文件冲爆 200 条上限）。

### 5.3 `MemoryStore` 本身零单测

`agent/memory/` 下只有 `MemoryRetentionTest`、`MemorySimilarityTest`。**最复杂的 `MemoryStore` 没有任何测试**，
而它恰好包含几条顺序敏感的不变量：

- 抑制检查**必须**在合并判定**之前**（否则被忘的事实被合并回来，正是抑制要防的唯一结果）
- `conflictsWith` 必须**双向**维护，删除时还要修复对端
- `evictLocked`（[MemoryStore.kt:618](app/src/main/java/io/agents/pokeclaw/agent/memory/MemoryStore.kt#L618)）**刻意不留指纹**——容量淘汰不是用户不要了
- `id` 由 normalized content 哈希而来 → 改内容即换 id

均为纯逻辑、零 Android 依赖，符合 CLAUDE.md 的「纯逻辑用单测」例外，且 `build.yml` 已有 `test` job 兜底。

---

## 6. 一个反面参照

[`electricpipelines/barq`](https://github.com/electricpipelines/barq)（产品名 Dabarqus）在本轮被作为对照对象看过。
它是本地 RAG 引擎，**GitHub 仓库内无任何源码**（`contents` 只有 `LICENSE` / `README.md` / `examples` / `.github`，`language: null`，
许可为 `NOASSERTION`），靠闭源二进制分发。

它有两个值得记的对比：

- **它没有的东西，我们有**：矛盾暴露、抑制指纹、保留度曲线，且全部有单测。
- **它的 `examples/` 有真 bug**：`while ingestions["status"] != "complete"` 无 sleep 无超时，服务端挂了就死循环；
  `app.py` 把异常吞成 `[None]` / `["Default"]`。这是「示例当营销素材维护」的必然结果。

**教训**：本项目的安全审计与测试是 fork 的叙事资产。**不要为了跟进度而把代码收敛成不可审计的形状**——
审计不可验证，等于审计不存在。

---

## 7. 资料来源

| 来源 | 用途 |
|---|---|
| https://github.com/electricpipelines/barq | 对照对象（第 6 节） |
| https://github.com/letta-ai/letta · mem0ai/mem0 · getzep/graphiti · langchain-ai/langmem | 四条路线的能力边界 |
| https://adityakarnam.com/awesome-agentic-memory | star 量级与框架定位横评 |
| https://vectorize.io/articles/mem0-vs-zep | 时效建模与 LongMemEval 差异 |
| https://dreaming.press/posts/on-device-vector-search-agent-memory.html | 端侧向量检索分层判断 |
| https://mvpfactory.io/blog/building-a-local-rag-pipeline-on-mobile-vector-search-with-sqlite-on-device | 端侧实测延迟与体积 |
| https://fewshotacademy.com/blog/agent-memory-first-class | LangMem p95 延迟数据的出处 |
| https://maidul-haque.vercel.app/blog/agent-memory-architectures-2026 | 2027 合并预测 |

> 星数与定价随时变化，引用前请复核。
