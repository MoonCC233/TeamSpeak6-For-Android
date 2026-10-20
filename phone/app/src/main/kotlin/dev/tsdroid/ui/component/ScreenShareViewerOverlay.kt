package dev.tsdroid.ui.component

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.ZoomInMap
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.tsdroid.han.R
import dev.tsdroid.screen.ScreenShareController
import dev.tsdroid.ui.icon.AppIcons
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * 屏幕共享全屏观看窗口（A4）——沉浸式。
 *
 * 会话由首页的实时预览卡片持有（[ScreenSharePreviewCard]），本窗口只往同一
 * 会话上增挂一个渲染器：关闭全屏后回到预览，不重新协商、不闪断。
 *
 * - 阶段提示：加入中 / 连接画面中 / 重连 / 已结束 / 被拒绝 / 失败；
 * - 沉浸全屏：隐藏系统栏（可滑动唤出）、保持屏幕常亮、退出时恢复；
 * - 旋转按钮：在「传感器横屏」与「跟随系统」之间切换，退出时恢复进入前的方向；
 * - 缩放按钮：在「适应（完整显示）」与「填充（裁边铺满）」之间切换；
 * - 单击画面可切换控制条显隐，观看中 3.5 秒无操作自动隐藏；
 * - 顶部提供连接信息入口；关闭按钮退出全屏回到预览，「退出观看」才会停止观看。
 */
@Composable
fun ScreenShareViewerOverlay(
    streamId: String,
    sourceClientId: Int,
    name: String,
    phase: ScreenShareController.ViewerPhase,
    stats: ScreenShareController.ViewerStats?,
    resumed: Boolean,
    onDismiss: () -> Unit,
    onInfo: () -> Unit,
    onStopWatching: () -> Unit,
) {
    val context = LocalContext.current
    val eglBase = remember { EglBase.create() }
    val renderer = remember {
        SurfaceViewRenderer(context).apply {
            init(eglBase.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            setEnableHardwareScaler(true)
        }
    }

    // 只增删自己的渲染器；会话生命周期属于首页预览卡片
    DisposableEffect(streamId, resumed) {
        if (resumed) {
            ScreenShareController.joinStream(streamId, sourceClientId, renderer)
        } else {
            ScreenShareController.leaveStream(streamId)
        }
        onDispose {
            ScreenShareController.removeStreamRenderer(streamId, renderer)
        }
    }
    // 窗口关闭时释放渲染器与 EGL 上下文（不随 resumed 变化释放）
    DisposableEffect(Unit) {
        onDispose {
            renderer.release()
            eglBase.release()
        }
    }

    val activity = remember(context) { context.findActivity() }

    // 沉浸式全屏：隐藏系统栏 + 常亮 + 退出时恢复方向与系统栏
    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val previousOrientation = activity?.requestedOrientation
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller?.let {
            it.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            it.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (activity != null && previousOrientation != null &&
                activity.requestedOrientation != previousOrientation
            ) {
                activity.requestedOrientation = previousOrientation
            }
        }
    }

    // 返回键 = 退出全屏（回到预览），不停止观看
    BackHandler(onBack = onDismiss)

    var fillMode by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }

    // 观看中：3.5 秒无操作自动隐藏控制条
    LaunchedEffect(controlsVisible, phase) {
        if (controlsVisible && phase == ScreenShareController.ViewerPhase.WATCHING) {
            delay(3_500)
            controlsVisible = false
        }
    }

    // 终态处理：显示提示；「已结束 / 被拒绝」短暂停留后自动收起
    LaunchedEffect(phase) {
        when (phase) {
            ScreenShareController.ViewerPhase.ENDED,
            ScreenShareController.ViewerPhase.REJECTED,
            -> {
                controlsVisible = true
                delay(3_000)
                onDismiss()
            }

            ScreenShareController.ViewerPhase.FAILED -> controlsVisible = true
            else -> Unit
        }
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusRequester) {
        runCatching { focusRequester.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.key == Key.Back && event.type == KeyEventType.KeyUp) {
                    onDismiss()
                    true
                } else {
                    false
                }
            },
    ) {
        // 视频画面：按视频宽高比布局，避免竖屏时被拉伸
        VideoSurface(
            renderer = renderer,
            stats = stats,
            fillMode = fillMode,
            modifier = Modifier.fillMaxSize(),
        )

        // 透明层：单击切换控制条显隐（位于视频之上、控制条之下）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { controlsVisible = !controlsVisible })
                },
        )

        // 阶段提示（非观看中时居中显示）
        if (phase != ScreenShareController.ViewerPhase.WATCHING) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                viewerStatusRes(phase)?.let { res ->
                    Text(
                        text = stringResource(res),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
                if (phase.isBusyPhase()) {
                    Spacer(Modifier.height(16.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        color = Color.White,
                        strokeWidth = 3.dp,
                    )
                }
                if (phase.isTerminalPhase()) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = stringResource(R.string.screen_share_close),
                            color = Color.White,
                        )
                    }
                }
            }
        }

        // 顶部控制条（渐隐背景 + 标题 + 连接信息 / 缩放 / 旋转 / 退出全屏 / 停止观看）
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent),
                        ),
                    )
                    .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    stats?.let { s ->
                        val quality = listOfNotNull(
                            formatQuality(s),
                            s.bitrateKbps?.let { "$it Kbps" },
                        ).joinToString(" · ")
                        if (quality.isNotEmpty()) {
                            Text(
                                text = quality,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.75f),
                                maxLines = 1,
                            )
                        }
                    }
                }
                IconButton(onClick = onInfo) {
                    Icon(
                        imageVector = AppIcons.Info,
                        contentDescription = stringResource(R.string.screen_share_connection_info),
                        tint = Color.White,
                    )
                }
                IconButton(onClick = { fillMode = !fillMode }) {
                    Icon(
                        imageVector = if (fillMode) Icons.Filled.ZoomInMap
                        else Icons.Filled.ZoomOutMap,
                        contentDescription = stringResource(
                            if (fillMode) R.string.screen_share_fit
                            else R.string.screen_share_fill,
                        ),
                        tint = Color.White,
                    )
                }
                IconButton(onClick = {
                    val act = activity ?: return@IconButton
                    val landscape = act.requestedOrientation ==
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    act.requestedOrientation = if (landscape) {
                        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                }) {
                    Icon(
                        imageVector = Icons.Filled.ScreenRotation,
                        contentDescription = stringResource(R.string.screen_share_rotate),
                        tint = Color.White,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.FullscreenExit,
                        contentDescription = stringResource(R.string.screen_share_exit_fullscreen),
                        tint = Color.White,
                    )
                }
                IconButton(onClick = onStopWatching) {
                    Icon(
                        imageVector = AppIcons.Exit,
                        contentDescription = stringResource(R.string.screen_share_stop_watching),
                        tint = Color(0xFFFF7A7A),
                    )
                }
            }
        }
    }
}

/** 各阶段的提示文案 */
internal fun viewerStatusRes(phase: ScreenShareController.ViewerPhase): Int? =
    when (phase) {
        ScreenShareController.ViewerPhase.JOINING -> R.string.viewer_status_joining
        ScreenShareController.ViewerPhase.CONNECTING -> R.string.viewer_status_connecting
        ScreenShareController.ViewerPhase.RECONNECTING -> R.string.viewer_status_reconnecting
        ScreenShareController.ViewerPhase.ENDED -> R.string.viewer_status_ended
        ScreenShareController.ViewerPhase.REJECTED -> R.string.viewer_status_rejected
        ScreenShareController.ViewerPhase.FAILED -> R.string.viewer_status_failed
        ScreenShareController.ViewerPhase.WATCHING -> null
    }

/** 需要转圈等待的阶段 */
internal fun ScreenShareController.ViewerPhase.isBusyPhase(): Boolean =
    this == ScreenShareController.ViewerPhase.JOINING ||
        this == ScreenShareController.ViewerPhase.CONNECTING ||
        this == ScreenShareController.ViewerPhase.RECONNECTING

/** 已终结、需要用户关闭（或自动收起）的阶段 */
internal fun ScreenShareController.ViewerPhase.isTerminalPhase(): Boolean =
    this == ScreenShareController.ViewerPhase.ENDED ||
        this == ScreenShareController.ViewerPhase.REJECTED ||
        this == ScreenShareController.ViewerPhase.FAILED

/** 向上遍历 ContextWrapper 找到宿主 Activity（用于窗口标志 / 方向操作） */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
