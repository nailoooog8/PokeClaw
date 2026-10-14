# Contributing to PokeClaw

本 fork 欢迎 PR。任何形式的贡献都受欢迎：bug 修复、新 skill / 示例任务、翻译、文档改进。

## 在你动手前

1. 读 [`README.md`](README.md) 顶部，了解本 fork 的定位与上游关系
2. 读 [`CLAUDE.md`](CLAUDE.md)，了解项目规则
3. 看 [`BACKLOG.md`](BACKLOG.md) 找能做的事；或自己提一个 issue 讨论
4. 带 `good first issue` 标签的 issue 适合首次贡献

## 本地构建

```bash
git clone -b fork/personal-fork-release https://github.com/nailoooog8/PokeClaw.git
cd PokeClaw
./gradlew assembleDebug
```

构建环境与已知坑见 `MCP_INTEGRATION_PLAN.md` 末尾的「构建环境备忘」。

## 提 PR 之前

- **一件事一个 PR**，不夹带不相关改动
- 跑 `./gradlew test` 通过
- 用户可见行为变更：在 [`QA_CHECKLIST.md`](QA_CHECKLIST.md) 对应章节加一条 E2E 用例
- commit message 英文，PR 标题中英都行
- 涉及安全漏洞：见 [`SECURITY.md`](SECURITY.md)，**不要**走公开 issue

## 问题反馈

- 提 issue 请提在**本仓库**，不要去 `agents-io/PokeClaw`（上游自 2026-06-02 起停更）
- 重复 issue 前先搜索
- 已知在 [`BACKLOG.md`](BACKLOG.md) 里的项目直接引用，不要另开 issue
