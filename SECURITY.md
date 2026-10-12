# 安全策略 / Security Policy

> English: Please report security vulnerabilities through GitHub's private
> vulnerability reporting (see below). **Do not open a public issue.**
> 中文为主，下同。

## 报告漏洞

**请不要用公开 issue 报告安全问题。** 公开 issue 会立刻让漏洞细节对所有人可见，
包括在修复落地之前。

请使用 GitHub 的**私有漏洞上报**通道：

> **Security → Report a vulnerability** — https://github.com/nailoooog8/PokeClaw/security

你的报告会**私密地**送到维护者手上，只有你能看到，别人看不到。修复就绪后
才会公开，并以 GitHub Security Advisory 的形式发布。

> ⚠️ **如果你在 Security 标签页看不到「Report a vulnerability」按钮**，
> 说明该仓库的私有漏洞上报尚未启用。请先在仓库简介页的「Report a vulnerability」
> 链接处提交，或等待该功能启用后再提交。

## 响应预期

本项目由单人维护，以下为**尽力而为**的目标，不是服务承诺：

| 阶段 | 预期 |
|------|------|
| 收到确认 | 通常 7 天内 |
| 评估 | 确认严重级别与影响范围；若判定不构成漏洞，会说明理由 |
| 修复 | 在 fork 分支修复，并发布新的版本标签 |
| 披露 | 发布 Security Advisory；**默认署名感谢报告者**，除非你要求匿名 |

若 7 天内没有收到确认，请在同一通道跟催一次。

## 适用范围

**在本 fork 范围内：**

- **远程控制渠道**（QQ / 微信 / Telegram / Discord / 抖音）——凭据处理、消息路由、PIN 配对
- **局域网配置服务**（`ConfigServer`）——网络暴露面、鉴权、来源校验
- **MCP 工具接入**——工具描述清洗、工具投毒防护
- **密钥与本地存储**（`SecretBox`、`local.properties` 解析）
- **发布链路**——签名密钥、更新检查

**不在范围内，请转向上游：**

- 上游 [agents-io/PokeClaw](https://github.com/agents-io/PokeClaw) 存在、且本 fork 未改动引入的漏洞
- 需要物理接触一台**已解锁**设备才能利用的问题
- 使用用户自己的凭据发起的拒绝服务
- 第三方渠道服务（QQ / 微信 / Telegram / Discord / 抖音）自身的漏洞

## 已发布的安全审计

本 fork 对自身改动引入的安全边界做过一轮审计，覆盖 PIN 暴力破解、凭据明文泄漏、
路径穿越与 MCP 工具投毒四类问题：

→ [`docs/SECURITY_AUDIT.md`](docs/SECURITY_AUDIT.md)

该文档同时记录了**哪些验证已执行、哪些尚未覆盖**，欢迎针对其中的空白项提出补充。

## 不算漏洞的情况

- 未经 `adb` / 无障碍授权就期待自动化失效 —— PokeClaw 的设计前提就是已授权设备上的自动化
- 通过 Xposed / root / 重打包绕过完整性校验后的行为
- 社会工程与诱骗（例如诱导用户自行授权）