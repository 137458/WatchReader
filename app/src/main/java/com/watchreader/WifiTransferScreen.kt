package com.watchreader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 腕上无线传书界面 — 扫码直传极简排版
 *
 * 组件口径：页面外壳沿用 WatchScreenScaffold 圆屏安全区；面板内部改用 Wear M3 官方件
 * （CircularProgressIndicator / LinearProgressIndicator / OutlinedCard / Text），
 * 底部弧形操作条与顶部弧形标题沿用既有实现。
 *
 * 性能要点：
 * 1. 二维码位图在 Default 线程异步生成（produceState），入场首帧不再被同步编码阻塞掉帧；
 * 2. 传输进度以 lambda 传给官方进度组件，由组件内部按帧读取，百分比数字经 derivedStateOf 去重，
 *    浮点进度逐帧推进时仅整数百分比变化才重组。
 *
 * 排版要点（466px 圆屏）：
 * 内容整体垂直居中并控制总高，二维码、地址胶囊、状态行与操作按钮全部落于圆屏黄金安全区，
 * 不再被顶部锚定的长列挤出屏幕底部。
 *
 * 段组件与文案口径见 WifiTransferSections.kt（进度块 / 二维码面板 / 边沿进度环 / 离线段 / 操作条接线）。
 */
@Composable
fun WifiTransferScreen(
    state: TransferUiState,
    onToggleServer: () -> Unit,
    onBack: () -> Unit
) {
    // 传书态是独立于书架/阅读态的型（TransferUiState），整页只有一个入参：
    // 上传进度变化只会重组本页，不会带着书架的整份列表一起重组
    val ipAddress = state.ipAddress
    val port = state.port
    val accessToken = state.token
    val uploadedCount = state.uploadedCount
    val isServerRunning = state.isRunning
    val isTransferring = state.isTransferring
    val transferProgress = state.progress
    val transferFileName = state.fileName

    val colorScheme = MaterialTheme.colorScheme
    val tick = rememberTickHaptic()

    // 传输中返回两步确认（与书架删书同款 3.2s 节奏）：右滑退出在传输中已被禁用，
    // 返回键若不加防护会成为"一按就断传"的破坏性陷阱
    var pendingExit by remember { mutableStateOf(false) }
    LaunchedEffect(pendingExit) {
        if (pendingExit) {
            delay(3200L)
            pendingExit = false
        }
    }
    BackHandler(onBack = {
        if (isTransferring && !pendingExit) {
            pendingExit = true
            tick()
        } else {
            onBack()
        }
    })

    // 就绪入场轻振：告知用户服务页已可用
    LaunchedEffect(Unit) {
        tick()
    }

    // 表冠焦点轮选：页面无可滚动内容，复刻系统设置「对齐模式」——单事件钳 ±79px、
    // 累积满 120px 焦点移动一格并给一次 302 齿轮微振
    val focusCount = if (isServerRunning) 1 else 2
    var focusedIndex by remember { mutableIntStateOf(0) }
    val focusStepper = remember { CrownFocusStepper(scrollFactorPxPerUnit = CrownScrollHelper.scrollFactorPxPerUnit) }
    rememberCrownScrollTarget(Unit) { delta ->
        val step = focusStepper.feed(delta)
        if (step != 0) {
            focusedIndex = (focusedIndex + step).mod(focusCount)
            tick()
        }
        true
    }

    // 细致平滑进度过渡
    val animatedProgress by animateFloatAsState(
        targetValue = TransferPanelText.targetProgress(isTransferring, transferProgress),
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f),
        label = "transferProgress"
    )

    // 百分比整数去重：spring 逐帧重定向时仅在数字变化的那一帧重组
    val progressPercent by remember { derivedStateOf { TransferPanelText.percentOf(animatedProgress) } }

    WatchReaderWearTheme {
        WatchScreenScaffold(
            swipeEnabled = !isTransferring,
            onDismiss = {
                tick()
                onBack()
            },
            // 传书页无滚动词表，遮罩与操作条都不参与
            fades = WatchScreenFades.None,
            contentAlignment = Alignment.Center
        ) {
            // 1. 视图 A：环形进度（官方 CircularProgressIndicator）+ 极简传输进度展示
            AnimatedVisibility(
                visible = isTransferring,
                enter = fadeIn(animationSpec = tween(WatchMotion.DUR_FADE)),
                exit = fadeOut(animationSpec = tween(WatchMotion.DUR_FADE_OUT))
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    EdgeProgressRing(
                        progress = animatedProgress,
                        color = colorScheme.primary,
                        trackColor = colorScheme.outlineVariant
                    )
                    TransferProgressPanel(
                        progressPercent = progressPercent,
                        transferFileName = transferFileName,
                        pendingExit = pendingExit
                    )
                }
            }

            // 2. 视图 B：扫码就绪 / 离线提示
            AnimatedVisibility(
                visible = !isTransferring,
                enter = fadeIn(animationSpec = tween(WatchMotion.DUR_FADE)),
                exit = fadeOut(animationSpec = tween(WatchMotion.DUR_FADE_OUT))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 30.dp, vertical = 4.dp)
                        .padding(top = 34.dp, bottom = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (TransferPanelText.serverReady(isServerRunning, ipAddress)) {
                        QrCodePanel(
                            ipAddress = ipAddress!!,
                            port = port,
                            accessToken = accessToken,
                            uploadedCount = uploadedCount
                        )
                    } else {
                        OfflinePanel()
                    }
                }
            }

            // 底部弧形贴边操作条：扇带处处贴合圆屏下缘；表冠轮选焦点高亮，点击确认
            if (!isTransferring) {
                EdgeActionBar(
                    *transferBarSpecs(
                        isServerRunning = isServerRunning,
                        focusedIndex = focusedIndex,
                        focusCount = focusCount,
                        onToggleServer = onToggleServer,
                        onBack = onBack
                    ).toTypedArray()
                )
            }

            // 顶部弧形标题
            CurvedChapterHeader(
                title = "无线传书",
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }
}
