# WatchReader — Agent 约束入口

OPPO Watch X / X2 等 Android / Wear OS 圆屏手表的极简本地 TXT / EPUB 阅读器，纯 Jetpack Compose + DataStore。

## 构建与验证

- 环境：JDK 17+、Android SDK 34、Gradle 8.2+
- Release APK：`./gradlew assembleRelease`（产物 `app/build/outputs/apk/release/app-release.apk`）
- 实机安装：`./gradlew installRelease`
- 完成标准 = 编译通过。

## 项目约定（硬约束）

- **[ADR-004] 实机测试/安装一律使用 Release 变体**（`assembleRelease` → `app-release.apk`），严禁 Debug 包。
- **[ADR-005] 永久放弃内置 TTS 语音朗读**，不引入语音合成功能。
- 圆屏适配：阅读与操作区域基于内接区域计算 SafeArea（466x466，内接正方形边长约 329px，顶部 44~52dp / 底部 56~64dp）。
- 表冠事件由 Activity 顶层管线拦截，经 `CrownScrollHelper` 极性归一化 + 强阻尼滤波（2.5 档物理门限 + 时间窗口节流）。
- 架构决策记录在 `docs/adr/`（ADR-001~009）：改动触及既有决策时先读对应 ADR，并新增或更新 ADR。

## 文档索引

| 何时读 | 文档 |
|---|---|
| 领域约束与决策摘要 | `CONTEXT.md` |
| 架构决策全文 | `docs/adr/` |
| Agent 协作配置（issue tracker / triage / domain docs） | `docs/agents/` |
| 性能优化提案 | `docs/OPTIMIZATION_PROPOSALS.md` |
| 更新日志 | `CHANGELOG.md` |
