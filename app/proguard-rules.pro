# 通用混淆规则
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# 保留 Compose 相关
-dontwarn androidx.compose.**

# 保留数据模型
-keep class com.watchreader.BookItem { *; }
-keep class com.watchreader.Chapter { *; }
-keep class com.watchreader.ChapterContent { *; }
-keep class com.watchreader.ReaderUiState { *; }

# 保留 DataStore Preferences
-keepclassmembers class * extends androidx.datastore.preferences.core.Preferences { *; }

# 保留 OPPO 私有线性马达反射相关
-dontwarn android.os.linearmotorvibrator.**
-keep class android.os.linearmotorvibrator.** { *; }

