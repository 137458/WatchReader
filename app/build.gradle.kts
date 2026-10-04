plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.watchreader"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.watchreader"
        minSdk = 27          // Android 8.1，覆盖绝大多数手表
        targetSdk = 34       // 编译与目标 SDK 34
        versionCode = 3
        versionName = "1.2.0"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    testOptions {
        // JVM 单测桩：framework 方法（如 SystemClock）返回默认值而非抛异常，
        // 使表冠分发等含 Android 时间源的纯逻辑可测（振感经探针观测）
        unitTests.isReturnDefaultValues = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Compose BOM — 统一管理 Compose 版本（升级至 Kotlin 2.4 同期稳定线，满足 miuix 基线）
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)

    // 核心 Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-text")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Activity Compose
    implementation("androidx.activity:activity-compose:1.13.0")

    // Lifecycle（与 miuix 0.9.4 传递基线对齐）
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")

    // DocumentFile
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Preferences DataStore — 线程安全且异步协程响应式配置持久化
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // ART ProfileInstaller — 预编译 Compose 与启动关键路径，将冷启动提升至极致
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")

    // ZXing Core — 超轻量二维码生成引擎（纯 Java，R8 裁剪后体积极小）
    implementation("com.google.zxing:core:3.5.3")

    // 调试工具
    debugImplementation("androidx.compose.ui:ui-tooling")

    // 单元测试
    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation("org.json:json:20240303")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
