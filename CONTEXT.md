# CONTEXT.md — OPPO Watch X2 小说阅读器

## 项目概述
极简本地 TXT 小说阅读器，目标设备为 OPPO Watch X2（466x466 纯圆屏，Android 11 / Watch16 系统）。

## 技术栈
- 语言：Kotlin
- UI 框架：Jetpack Compose（标准版，不依赖 Wear OS 专属库）
- 最低 SDK：API 27（Android 8.1）
- 目标 SDK：API 30（Android 11）
- 编译 SDK：API 34

## 核心约束
1. **圆屏适配**：阅读区域必须基于内接正方形计算 SafeArea，防止文字被弧边裁切
2. **表冠翻页**：Watch16 表冠映射为 ACTION_SCROLL (AXIS_VSCROLL)，需在 Compose 中拦截并转换为平滑滚动
3. **大文件支持**：需分块加载 + 章节索引，支持 GBK/UTF-8 自动检测编码
4. **SAF 文件选择**：使用系统文件选择器，不依赖 Wear OS 适配

## 关键决策
- [ADR-001] 使用标准 Compose 而非 Wear Compose（避免魔改系统兼容问题）
- [ADR-002] 章节检测使用融合正则匹配，配合 `ChapterDiskCache` 二进制持久化索引
- [ADR-003] 阅读位置与书架配置基于 DataStore 单事务原子持久化
- [ADR-004] **实机安装与调试强制 Release 准则**：实机测试/安装必须一律编译并安装 Release 变体（`assembleRelease` -> `app-release.apk`），严禁使用 Debug 包。Compose Debug 包包含大量调试追踪且禁用 R8/AOT 优化，在手表低功耗芯片上性能衰减达 5~10 倍。
- [ADR-005] **永久放弃内置 TTS 语音朗读特性**：明确不引入 TTS（Text-To-Speech）语音朗读功能。手表端语音合成引擎常驻占用大量内存、耗电严重且发音音质受限，WatchReader 专注于极简、极致省电与纯粹的视觉阅读体验，未来亦不做支持。

## 屏幕规格
- 分辨率：466x466
- 圆屏内接正方形边长：约 329px（466 * cos(45°) ≈ 329）
- SafeArea padding：约 68px 每侧

