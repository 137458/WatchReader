package com.watchreader

import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * MainActivity — 遵循 Android / ColorOS Watch 官方规范的入口 Activity
 *
 * 只承担四件在 Activity 期才有意义的事：SAF 启动器注册与表冠管线装配、setContent 与状态收集、
 * Window 级副作用（亮度 / DecorView 底色 / 暗化遮罩）、生命周期钩子。
 * 页面装配见 [WatchAppContent]，SAF 回调体见 [SafResultHandlers]，表冠分派见 [CrownEventDispatch]。
 */
class MainActivity : ComponentActivity() {

    private val viewModel: ReaderViewModel by viewModels()

    // SAF 回调体：流生命周期与编码在 SafResultHandlers 里，本类只负责注册
    private val safHandlers by lazy {
        SafResultHandlers(contentResolver, lifecycleScope, viewModel)
    }

    // 启动器必须在 Activity 期注册
    private val openFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> safHandlers.onOpenDocumentResult(uri) }

    // 备份导出（SAF 建文件）与恢复（SAF 选文件）
    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? -> safHandlers.onExportBackupResult(uri) }

    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> safHandlers.onImportBackupResult(uri) }

    // 页面只拿到三个启动请求，不再接触启动器字段
    private val safLaunchRequests by lazy {
        SafLaunchRequests(
            openDocument = { openFileLauncher.launch(SafRequests.openDocumentMimeTypes()) },
            exportBackup = { exportBackupLauncher.launch(SafRequests.defaultBackupFileName()) },
            importBackup = { importBackupLauncher.launch(SafRequests.importBackupMimeTypes()) }
        )
    }

    // 表冠路由：分派顺序在 CrownRoutes 里声明一次，本类只做装配与原生下发适配
    private val crownDispatch by lazy {
        CrownEventDispatch(
            onRsvpRotary = { delta -> viewModel.handleRotaryScroll(delta) },
            pageTarget = { CrownScrollTargetRegistry.active },
            dispatchDefaultChain = { event -> dispatchDefaultChain(event) },
            dispatchToFocused = { event -> currentFocus?.dispatchGenericMotionEvent(event) == true },
            rootView = { window.decorView as? ViewGroup }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化 ViewModel 状态（从 DataStore 异步读取书架、字号、深色模式、亮度、滚速）
        viewModel.init()

        // 异步预热 OPPO 官方 Linearmotor 线性马达引擎
        lifecycleScope.launch(Dispatchers.Default) {
            RotaryHapticManager.initOplusLinearmotor(applicationContext)
        }

        // 读取系统表冠滚动系数与振感门限（与系统设置 / 桌面的列表内核同源）
        CrownScrollHelper.ensureSystemScrollFactor(applicationContext)
        CrownScrollHelper.ensureSystemHapticThreshold(applicationContext)

        // 全屏沉浸式
        @Suppress("DEPRECATION")
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        setContent {
            val uiState by viewModel.uiState.collectAsState()
            val transferState by viewModel.transferState.collectAsState()
            val searchState by viewModel.searchState.collectAsState()
            val colorScheme = watchColorSchemeOf(ThemeMode.fromValue(uiState.themeMode))

            // 动态同步 Window 底层 DecorView 背景色与硬件独立屏幕亮度。
            // 底色必须带变更守卫：无守卫时每次重组（搜索逐键 / 亮度步进 / 阅读时长 tick）
            // 都会令 DecorView 全窗口失效重绘，抵消全部逐帧绘制优化
            val lastDecorBackground = remember { intArrayOf(Int.MIN_VALUE) }
            SideEffect {
                BrightnessManager.applyToWindow(this@MainActivity, uiState.appBrightness)
                val bgArgb = colorScheme.background.toArgb()
                if (bgArgb != lastDecorBackground[0]) {
                    lastDecorBackground[0] = bgArgb
                    window.decorView.setBackgroundColor(bgArgb)
                }
            }

            // 主题根节点：应用自持 ThemeMode（DataStore 持久化），显式注入 MD3 色板、
            // 腕上排版与 shape scale，不走系统动态取色管线
            MaterialTheme(
                colorScheme = colorScheme,
                typography = WatchTypography,
                shapes = WatchShapesM3
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    WatchAppContent(
                        uiState = uiState,
                        transferState = transferState,
                        searchState = searchState,
                        viewModel = viewModel,
                        requests = safLaunchRequests,
                        onExitApp = { finish() }
                    )

                    // 恢复备份确认浮层：SAF 选完文件先亮出将载入的书目数，确认才覆盖。
                    // 出口三条：取消按钮 / 返回键 / 右滑，后两者一律走「取消」而非覆盖，
                    // 与浮层外点击被拦截的强制二选一语义一致（侧滑绝不等于确认）。
                    // 退出动画期 pendingRestoreCount 已置空，故留存最后一次书目数供离场渲染。
                    val restoreCount = uiState.pendingRestoreCount
                    var lastRestoreCount by remember { mutableStateOf(0) }
                    if (restoreCount != null) lastRestoreCount = restoreCount
                    AnimatedVisibility(
                        visible = restoreCount != null,
                        enter = WatchOverlay.Enter,
                        exit = WatchOverlay.Exit
                    ) {
                        val blockInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        BackHandler { viewModel.cancelBackupRestore() }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.6f))
                                .swipeDismissBack { viewModel.cancelBackupRestore() }
                                // 拦截浮层外的一切点击，强制走 取消/确认 二选一
                                .clickable(
                                    interactionSource = blockInteraction,
                                    indication = null
                                ) {},
                            contentAlignment = androidx.compose.ui.Alignment.Center
                        ) {
                            SurfaceCard(modifier = Modifier.padding(horizontal = 28.dp)) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = "恢复备份？",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "将载入 $lastRestoreCount 本书的书架快照，并覆盖当前书架与全部阅读设置。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                    Row(
                                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                                    ) {
                                        PillButton("取消", Modifier.weight(1f)) {
                                            viewModel.cancelBackupRestore()
                                        }
                                        PillButton(
                                            "确认恢复",
                                            Modifier.weight(1f),
                                            emphasis = PillEmphasis.Primary
                                        ) {
                                            lifecycleScope.launch { viewModel.confirmBackupRestore() }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 极暗纯黑 Alpha 硬件加速遮罩（仅当亮度低于 12% 时激活，不拦截手势）
                    val overlayAlpha = BrightnessManager.calculateDarkOverlayAlpha(uiState.appBrightness)
                    if (overlayAlpha > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = overlayAlpha))
                        )
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.flushReadingPosition()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 释放表冠微振后台线程（shutdown 幂等，且有振感请求时会按需重建）
        RotaryHapticManager.shutdown()
    }

    /**
     * 表冠事件唯一入口：表冠事件交给 [CrownEventDispatch] 一次分派；其余通用运动事件保持原生链语义
     *
     * 收口前这里串行试探了 5 条路径，谁消费取决于书写顺序；现在顺序由 CrownRoutes 声明，
     * 原生链 / 焦点视图 / 兜底遍历退化为第 3~5 条策略，实现见 [CrownEventDispatch]。
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        crownDispatch.onGenericMotionEvent(event)

    /** super 调用不能写进 lambda / 内部类，单独包一层供路由策略回调 */
    private fun dispatchDefaultChain(event: MotionEvent): Boolean = super.dispatchGenericMotionEvent(event)
}
