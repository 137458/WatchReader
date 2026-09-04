# 架构决策记录 (Architecture Decision Records)

本文档归档 WatchReader 的关键技术决策、演进背景与架构权衡。

| 编号 | 决策主题 | 状态 | 核心要点 |
| :--- | :--- | :--- | :--- |
| [ADR-001](ADR-001-standard-compose.md) | 使用标准 Jetpack Compose 而非 Wear Compose | 已采纳 | 规避魔改穿戴系统对 Wear OS 特有框架的依赖缺失 |
| [ADR-002](ADR-002-chapter-detection.md) | 章节检测使用融合正则匹配与二进制磁盘索引 | 已采纳 | 演进为 `ChapterDiskCache` 二进制持久化，实现超大书秒开 |
| [ADR-003](ADR-003-reading-position.md) | 阅读位置、书签与书架配置基于 DataStore 单事务原子持久化 | 已采纳 | 彻底淘汰旧 SharedPreferences，支持单事务原子异步存取 |
| [ADR-004](ADR-004-mandatory-release-build.md) | 实机安装与调试强制 Release 准则 | 已采纳 | 消除 Debug 变体在穿戴小核 CPU 上的卡顿假象与功耗虚高 |
| [ADR-005](ADR-005-reject-tts-engine.md) | 明确放弃手表端常驻内置 TTS 语音朗读特性 | 已采纳 | 维持 <1MB 极致精简定位，杜绝电量雪崩与常驻后台锁 |
| [ADR-006](ADR-006-lan-wifi-transfer-server.md) | 局域网轻量无线传书与流式直存架构 | 已采纳 | 内置轻量 HTTP 网页传书服务，免数据线与配套 App |
| [ADR-007](ADR-007-rsvp-rotary-damping.md) | RSVP 闪读与硬件表冠强阻尼自适应滤波算法 | 已采纳 | ORP 黄金注视焦点、标点加权节奏与表冠防抖调速 |
| [ADR-008](ADR-008-epub-dual-engine-and-cache.md) | EPUB 毫秒级双引擎架构与正文 LRU 缓存池 | 已采纳 | 随机条目寻址与单趟流式索引，32 篇正文预热秒翻 |
| [ADR-009](ADR-009-curved-tangent-status-bar.md) | 圆屏外轮廓相切弧形状态栏与两端对齐排版 | 已采纳 | 外轮廓相切消除反向畸变，圆屏内接安全区规避边缘截字 |
