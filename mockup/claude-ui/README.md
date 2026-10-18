# Claude UI 设计稿（PokeClaw）

这是一个**设计稿 / 视觉参考**，不是产品的一部分。

- 不进 APK —— `mockup/` 不在 `app/src/main/assets` 下，打包时不会被包含
- 不连任何后端 —— 会话内容是写死的演示数据
- 不接 `ConfigServer` —— 与局域网配置功能无关
- **未经真机验证**，因此不能直接搬进 Android UI（见下）

## 来源

设计令牌与交互物理抄自 **[Caho1/open-claude-ui](https://github.com/Caho1/open-claude-ui)**（MIT © Caho1）。

- `cds.css` 的令牌值（35 阶灰度 / 陶土橙标尺、表面 0-3 阶梯、圆角矩阵、贝塞尔曲线、
  15 阶按钮弹簧、高分屏发丝线补偿、CJK 字体回退链）**逐值复制**，以保证观感一致
- `cds.js` 是从该项目 React hook 行为**用原生 JS 重写**的（滚动钉住、自动增高、
  输入法合成守卫、模型切换 popover），无构建步骤、无依赖

## 交互逻辑抄了什么

对应参考项目的四个 hooks（`code-examples/`）：

| 参考实现 | 本目录 | 关键机制 |
|---|---|---|
| `useClaudeScrollPinning.ts` | `cds.js` 滚动钉住 | 40px 容差 / 150ms 用户输入冷却 / 程序滚动守卫 / >120px 显示「跳至最新」 |
| `useRateLimitBackoff.ts` | `cds.js` 限流退避 | `min(max(base,5000) × 2^(n-1) × jitter, 600000)`，±15% 抖动，3 次后强制手动 |
| `useClaudeRovingFocus.ts` | `cds.js` 漫游焦点 | 方向键 / Home / End / PageUp / PageDown，`loop` 回绕，**Tab 立即交还**全局焦点流 |
| `ChatComposer.tsx` | `cds.js` 输入框 | 自动增高（封顶 220px）、CJK 合成守卫（`isComposing` + `keyCode 229` 双判） |

### 抄交互时踩到的三个坑

**1. 悬浮按钮不写 `z-index` 就点不动。**
`.jump` 是 `position: absolute`，而 `.turn` 是常规流内元素且排在前面。
按钮**看得见**（`data-show="true"` 打开了 `pointer-events`），
但点击被 `.turn` / `<pre>` 截走 —— Playwright 直接报
`subtree intercepts pointer events`。必须显式 `z-index: 10`。

**2. 只监听 `scroll` 会误判。**
`scroll` 事件滞后一帧，用户快速滚轮时锁定的释放是迟的。
参考项目额外监听 `wheel` / `touchstart` / `keydown`，用户一动手就立即解锁。

**3. 限流退避写完是死代码。**
`triggerBackoff()` 实现了但没人调用，测的时候才发现根本触发不了。
现已接到 `window.__cds.rateLimit()`，并在首次打开时演示一次。

### 调试入口

```js
__cds.rateLimit()        // 手动触发一次 429
__cds.resetRateLimit()   // 清空退避计数
__cds.state()            // { pinned, showJump, backoff }
```

## 抄的过程中发现的两个问题

**1. 参考主题用了自己没定义的变量。**
`cds-theme.css` 引用了 `--cds-bg-neutral`、`--cds-bg-neutral-hover`、`--cds-pad-lg/md`、
`--cds-dur-fast/slow`、`--cds-text-tint-blue`、`--cds-radius`，但 `:root` 里一个都没声明，
在那边全部落空。`--cds-radius` 落空尤其隐蔽：`.bubble` 的圆角公式
`calc(var(--cds-radius) + …)` 拿不到初值，退化成 `0 + 4px` 而非预期的 `8 + 4px`。
本目录 §2 已把它们的值补齐。

**2. 纵向 flex 容器会把消息压扁而不是撑出滚动条。**
`.feed` 是 `display:flex; flex-direction:column`，子项默认 `flex-shrink:1`。
而 `.main` 作为 grid item 默认 `min-height:auto`，会被内容撑高而不是收缩，
于是 `.feed` 的 `overflow-y:auto` 根本不触发，输入框被顶出视口。
修法是 `.main { min-height: 0 }` + `.turn { flex: none }`。

## 想把它变成真的 UI 的话

本目录**不能**直接落地。Android 端是 Kotlin/Compose，拿不到 `.tsx`，
也没有 npm/webpack 工具链。可行的两条路：

1. **只搬令牌**（推荐）—— 把 `cds.css` 的颜色、圆角、时长曲线翻译成
   `res/values/colors.xml` + Compose `MaterialTheme`。这是纯 Kotlin 内部工作，
   但属于「UI 改动」，按 `CLAUDE.md` 必须连真机跑 E2E（当前 **没有设备连接**）
2. **搬到 WebView** —— 只有 `app/src/main/assets/web/index.html` 一个位置，
   但那是局域网配置页，不是聊天页，语义不匹配

在接上真机之前，上面两条都属于未验证改动。

## 本地预览

直接用浏览器打开 `index.html`（无需构建、无需服务器）。
深浅色跟随系统，也可用左下角按钮切换。
