# ADR-003: 阅读位置使用 SharedPreferences 持久化

## 上下文
用户需要关闭 App 后再次打开时恢复到上次阅读位置。

## 决策
使用 SharedPreferences 存储 URI + 滚动偏移量（字符索引），而非字节偏移量。

## 后果
- 正面：简单可靠，无需数据库依赖
- 负面：仅支持单一书签，不支持多书签
- 负面：URI 权限可能过期（SAF 的 takePersistableUriPermission 可缓解）
