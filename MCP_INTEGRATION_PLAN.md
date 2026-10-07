# MCP / Skills 集成设计（PokeClaw fork 扩展计划）

> 状态：设计定稿，待基线验证后实施。基于 0.7.1 源码实测（非推测）。

## 已验证的架构事实

- **工具契约**（`app/src/main/java/io/agents/pokeclaw/tool/`）：
  - `BaseTool`（抽象类）：`getName()` / `getParameters(): List<ToolParameter>` / `execute(params): ToolResult` + `getDescriptionEN/CN()` 双语描述 + `executeWithWaitAfter()` 包装器（自动处理 wait_after 参数）
  - `ToolParameter(name, type, description, isRequired)` — data class
  - `ToolResult` — `success(data: String)` / `error(msg)`，数据为字符串
  - `ToolRegistry` — 单例 object，`LinkedHashMap<String, BaseTool>`，`register()` 公开；`registerAllTools(deviceType)` 按 TV/MOBILE 分组注册；`executeTool(name, params)` 统一入口
- **LLM 层已内嵌 LangChain4j**：`agent/langchain/LangChain4jToolBridge` 将 BaseTool 转为 `dev.langchain4j.agent.tool.ToolSpecification`（JsonObjectSchema 等），云端/本地（LiteRT-LM Gemma）共用
- **感知回路**：截图作为图像 content 直喂云端多模态模型（这是本 fork 的核心资产，不动）
- 许可证：Apache-2.0；MCP 客户端实现不得引入 GPL 代码（BAIC2 的实现仅作设计参考，不复制代码）

## 路线决策：langchain4j-mcp 桥接（路线 1）

两条候选路线：
1. **langchain4j-mcp 桥接**（选定）：项目已依赖 langchain4j，官方 `langchain4j-mcp` 模块提供 `McpToolProvider`（Streamable HTTP + SSE 传输）。MCP 工具经 LangChain4j 桥直达 LLM 层，改动最小、依赖同源。
2. ~~官方 Kotlin SDK（modelcontextprotocol/kotlin-sdk）~~：需自建工具注册表适配层，与 LangChain4j 桥并行存在两套工具体系，弃。

## 实施步骤

### 第 1 步：依赖
`gradle/libs.versions.toml` + `app/build.gradle.kts` 增加 `dev.langchain4j:langchain4j-mcp`（版本对齐现有 langchain4j BOM，先验证与当前版本兼容）。

### 第 2 步：McpConnectionManager（新文件 `agent/mcp/McpConnectionManager.kt`）
- 管理 N 个远程 MCP 服务器连接（URL + 可选 header 鉴权），生命周期随 Application
- 启动时刷新（对齐 Baic2Application 的 "MCP refresh" 模式）：连接 → `listTools()` → 产出远程工具清单
- 断线重连 + 健康状态暴露给 UI

### 第 3 步：McpToolBridge（新文件 `agent/mcp/McpToolBridge.kt`）
- 远程 MCP 工具 → 包装为 `BaseTool` 子类 `McpTool`：
  - `getName()` = `mcp_{serverShortName}_{toolName}`（防撞名）
  - `getParameters()` = MCP inputSchema（JSON Schema）→ ToolParameter 映射（string/number/boolean/enum）
  - `execute()` = 调用远程 MCP `callTool`，结果 JSON 序列化为 ToolResult.success 字符串
- 注册：`ToolRegistry.registerAllTools()` 尾部追加 `McpToolBridge.registerAll(this)`

### 第 4 步：设置页 + 存储
- `ui/settings` 增"MCP 服务器"区块：增删服务器（URL/Header），存 DataStore
- 工具列表页展示 MCP 工具来源标记

### 第 5 步（Skills 层，二期）
- YAML recipe 技能：`skill/` 下加 `SkillLoader`（SnakeYAML 或 kotlinx.serialization + 自定义解析），声明式步骤引用已注册工具（含 MCP 工具）
- "保存为技能"：捕获一次成功的操作序列落盘

## 构建环境备忘（本机）

- JDK 17.0.13 Liberica：`ZCodeProject/tools/jdk17`（JAVA_HOME 指向此处）
- Android SDK：`%LOCALAPPDATA%/Android/Sdk`（android-36 就位），`local.properties` 已写（正斜杠路径）
- Gradle 9.3.1：wrapper 走腾讯镜像；依赖代理已固化在**用户级** `~/.gradle/gradle.properties`（`systemProp.http(s).proxyHost=127.0.0.1:7897`——命令行 `-D` 不会传给 Daemon JVM，必须用文件）
- 基线产物：`app/build/outputs/apk/debug/PokeClaw_v0.7.1_*.apk`（154MB，与官方 release 同源不同渠道，体积差异来自资产打包）
