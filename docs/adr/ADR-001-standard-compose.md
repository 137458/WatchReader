# ADR-001: 使用标准 Jetpack Compose 而非 Wear Compose

## 上下文
OPPO Watch X2 运行魔改的 Android 11 (Watch16 系统)，不是标准 Wear OS。Wear Compose 库依赖 Wear OS 特有的服务和服务端框架，在魔改系统上可能报错。

## 决策
使用标准 Jetpack Compose，不依赖 `androidx.wear.compose` 系列库。

## 后果
- 正面：兼容性更好，不会因缺少 Wear OS 服务而崩溃
- 负面：需要手动处理圆屏适配、表冠事件等 Wear 特有功能
- 负面：无法使用 Wear Compose 的现成组件（如 ScalingLazyColumn、SwipeToDismissBox）
