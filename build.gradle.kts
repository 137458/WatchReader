// 顶层构建文件 — 所有子项目/模块的公共配置
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    // Compose 编译器随 Kotlin 2.x 起改由官方 Gradle 插件分发，版本必须与 Kotlin 完全一致
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
