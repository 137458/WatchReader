plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.watchreader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.watchreader"
        minSdk = 27          // Android 8.1，覆盖绝大多数手表
        targetSdk = 34       // 编译与目标 SDK 34
        versionCode = 1
        versionName = "1.0.0"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

dependencies {
    // Compose BOM — 统一管理 Compose 版本
    val composeBom = platform("androidx.compose:compose-bom:2024.01.00")
    implementation(composeBom)

    // 核心 Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-text")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Activity Compose
    implementation("androidx.activity:activity-compose:1.8.2")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // DocumentFile
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Wear Compose
    implementation("androidx.wear.compose:compose-foundation:1.3.1")

    // Preferences DataStore — 线程安全且异步协程响应式配置持久化
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // ART ProfileInstaller — 预编译 Compose 与启动关键路径，将冷启动提升至极致
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")

    // 调试工具
    debugImplementation("androidx.compose.ui:ui-tooling")
}
