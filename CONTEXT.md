# CONTEXT.md — OPPO Watch X2 小说阅读器

## 项目概述
极简本地 TXT / EPUB 小说阅读器，目标设备为 OPPO Watch X / X2 等智能手表（466x466 纯圆屏，Android / Wear OS 系统）。

## 技术栈
- 语言：Kotlin
- UI 框架：Jetpack Compose（标准版）+ 原生 AndroidView 混合架构
- 最低 SDK：API 27（Android 8.1）
- 目标 SDK：API 34（Android 14）
- 编译 SDK：API 34

## 核心约束
1. **圆屏适配**：阅读与操作区域基于内接区域计算 SafeArea，防止文字与操作栏被弧边裁切。
2. **表冠翻页与调速**：表冠硬件事件由 Activity 顶层管线拦截并经 CrownScrollHelper 极性归一化，支持平滑滚屏与强阻尼速度调节。
3. **多格式与大文件支持**：分块流式加载 + 章节索引，支持 TXT 智能编码检测与 EPUB 毫秒级双引擎解包。
4. **SAF 与局域网传书双导入**：支持系统文件选择器与局域网 Wi-Fi 网页免线无线传书。

## 关键决策
- [ADR-001] 使用标准 Compose 而非 Wear Compose（避免魔改系统兼容问题）。
- [ADR-002] 章节检测使用融合正则匹配，配合 `ChapterDiskCache` 二进制持久化索引。
- [ADR-003] 阅读位置与书架配置基于 DataStore 单事务原子持久化。
- [ADR-004] **实机安装与调试强制 Release 准则**：实机测试/安装必须一律编译并安装 Release 变体（`assembleRelease` -> `app-release.apk`），严禁使用 Debug 包。
- [ADR-005] **永久放弃内置 TTS 语音朗读特性**：明确不引入 TTS 语音合成功能。手表端常驻引擎耗电且音质受限，WatchReader 专注于极简、极致省电与纯粹的视觉阅读体验。
- [ADR-006] **局域网轻量无线传书架构**：内置低开销 NanoHTTPD 原生 Socket HTTP 服务，支持分块流式直存与 Multipart 协议，传输完毕单事务原子刷新 DataStore 书架。
- [ADR-007] **RSVP 闪读与硬件表冠强阻尼滤波**：表冠事件通过顶层分发管线拦截，配合 2.5 档物理门限与时间窗口节流算法，实现扎实沉稳的调速手感。
- [ADR-008] **EPUB 毫秒级双引擎与 LRU 缓存**：本地文件采用 ZipFile $O(1)$ 随机寻址读取目录与正文，动态流采用单趟索引，配合 32 章正文 LRU 缓存池实现 0ms 翻章直出。

## 屏幕规格
- 分辨率：466x466
- 圆屏内接正方形边长：约 329px（466 * cos(45°) ≈ 329）
- SafeArea padding：顶部 44~52dp，底部 56~64dp
