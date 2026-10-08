# 记忆库（Memory Bank）

让 PokeClaw 跨会话记住用户：身份、偏好、设备与账号事实、踩过的坑、重要任务的结果。

记忆库和知识库（`kb_*` 工具 / `vault/` markdown 金库）是两套东西：

| | 知识库 KB | 记忆库 Memory Bank |
|---|---|---|
| 内容 | 用户自己写的文档、笔记、待办 | 模型自己判断值得长期记住的一句话 |
| 工具 | `kb_write` / `kb_read` / `kb_search` / `kb_append` / `kb_add_todo` | `memory_save` / `memory_recall` / `memory_forget` |
| 存储 | `files/vault/**/*.md` | `files/memories.json` |
| 谁调用 | 用户主动让 Agent 记东西 | Agent 自主调用 + 用户在管理页校对 |
| 进入上下文 | 模型自己 `kb_read` 时 | 每个任务自动注入系统提示 |

## 自动注入

`DefaultAgentService.runAgentLoop` 在拼系统提示时追加 `MemoryStore.briefing(当前任务文本)`：
按与当前任务的相关性挑最多 12 条（总长约 1400 字符），置顶条目必进。注入的小节同时把三条
记忆工具的用法讲给模型听，所以记忆库为空时模型也知道该记什么。

排序不用向量，纯本地打分：

```
score = 类型权重(身份1.2 / 偏好1.0 / 事实0.7 / 事件0.3)
      + 置顶 3.0
      + min(2.0, 0.3 × 命中次数)
      + 与当前任务词元重合 +2.0（标签命中再加）
      + max(0, 1.5 − 0.04 × 距上次使用天数)   // 慢慢衰减
```

中文靠二元组（bigram）切词，不需要分词器；英文按 ≥2 字符的词。`memory_recall` 命中的条目
`hits+1` 并刷新 `lastUsedAt`，越常用越靠前。

## 去重合并

同一件事会在不同会话里被重新学到，只是措辞略有差别。不合并的话简报会被近义重复塞满，
模型开始自相矛盾。所以 `remember()` 先归一化（去空白、转小写），若新旧互为子串就合并：
取更长的一条、标签取并集、`hits+1`、置顶取或。

## 矛盾暴露

新事实如果"框架不变、只换了值"，不会被静默地堆在旧事实旁边——两条都会被标记，工具
返回值和管理页都会带 ⚠，注入简报时也会提示模型"不能只凭一条回答，应直接问用户"。

判定三条规则，宁可漏报也不误报（误报会让 Agent 拿无冲突的事盘问用户，比漏报更糟）：

| 规则 | 例子 |
|---|---|
| 骨架相同、数字/日期/引号内容不同 | 用户每周跑3次 → 每周跑5次 |
| 骨架相近、极性翻转（一边有否定词） | 用户喜欢用微信 → 用户不喜欢用微信 |
| 长前缀相同、末尾短槽位被换掉（且不是"延长"） | 用户在杭州出差 → 用户在上海出差 |

用的是 `MemorySimilarity.contradicts()`，纯词法：CJK 二元组 + 数字/引号提取，没有模型，
`MemorySimilarityTest`（20 个用例）锁住行为，包括"必须不触发"的那几条。

**已知边界**：改述式矛盾（"不喜欢被工作打扰" vs "不希望晚上被工作消息找"）召不出来，
因为词法匹配看不见同义。

## 遗忘抑制（指纹）

删除一条记忆时，除了删掉条目，还把它写进 `memory_suppressions.json` 留一枚指纹。
之后 `memory_save` 写入的内容若与任意指纹的 bigram Dice ≥ 0.7，直接拒绝并把被删除的
原文回给模型——**遗忘是抑制，不是删除**。否则同一件事会在几轮对话后被重新学回来，
用户根本没要求过。

两条豁免：

- 用户在管理页手动添加记忆**不受抑制**——明确的人为动作高于指纹（否则"删错了想加回来"会变成死路）。
- 管理页「遗忘抑制」里可以整体清除指纹。

## 分类

| kind | 中文 | 什么时候用 |
|---|---|---|
| `profile` | 身份 | 姓名、学校、专业、长期身份 |
| `preference` | 偏好 | 习惯、禁忌、语言与风格要求 |
| `fact` | 事实 | 设备、账号、环境上的稳定事实 |
| `event` | 事件 | 发生过、以后还会影响判断的事 |

## 文件格式

`memories.json`，外部文件目录，可用 `adb push` 直接播种或手改：

```json
[
  {
    "id": "seed0000001",
    "content": "用户是示例大学计算机专业的大二学生。",
    "kind": "profile",
    "tags": ["用户", "身份", "学校"],
    "pinned": true,
    "createdAt": 1788000000000,
    "updatedAt": 1788000000000,
    "hits": 0,
    "lastUsedAt": 0
  }
]
```

解析逐字段容错：缺字段、类型写错、缺 `content` 的条目都会被丢掉或退回默认值，不会整库报废。
上限 200 条，超了按「非置顶、命中少、久未更新」依次淘汰。

```bash
adb push memories.example.json \
  /sdcard/Android/data/io.agents.pokeclaw/files/memories.json
adb shell am force-stop io.agents.pokeclaw   # 重启后重新读取
```

## 管理界面

设置 → 工具 → 记忆库（`ui/settings/MemoryActivity`）：搜索、按类排序、点击编辑、长按删除、
手动添加、导出 Markdown、总开关、清除遗忘抑制。带 ⚠ 的条目是互相矛盾的一对。

**总开关关掉后**：不再向模型注入任何记忆，`memory_save` 也会明确回复"没有保存"，但库里已有的
条目保留，随时可以再打开。

## 代码位置

- `agent/memory/MemoryStore.kt` — 存储、合并、排序、简报生成、抑制指纹
- `agent/memory/MemorySimilarity.kt` — 二元组 Dice、矛盾判定、指纹解析
- `agent/memory/MemoryEntry.kt` — 数据类、分类定义、容错解析
- `agent/memory/MemorySaveTool.kt` / `MemoryRecallTool.kt` / `MemoryForgetTool.kt`
- `ui/settings/MemoryActivity.kt` — 管理界面
- `app/src/test/.../MemorySimilarityTest.kt` — 矛盾判定与相似度的回归测试