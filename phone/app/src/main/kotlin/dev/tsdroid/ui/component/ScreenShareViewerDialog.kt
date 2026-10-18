package dev.tsdroid.ui.component

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.tsdroid.han.R
import dev.tsdroid.screen.ScreenShareController
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * 屏幕共享观看窗口（A4）。
 *
 * 视频渲染由 WebRTC 的 [SurfaceViewRenderer] 负责，经 `AndroidView` 承载；
 * 进入组合时向 [ScreenShareController] 注册观看并绑定渲染器，
 * 退出（或流结束）时注销观看并释放渲染资源。
 */
@Composable
fun ScreenShareViewerDialog(
    streamId: String,
    sourceClientId: Int,
    name: String,
    onDismiss: () -> Unit,
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

    DisposableEffect(streamId) {
        ScreenShareController.joinStream(streamId, sourceClientId, renderer)
        onDispose {
            ScreenShareController.leaveStream(streamId)
            renderer.release()
            eglBase.release()
        }
    }

    // 观看期间保持屏幕常亮（被动观看场景下息屏会中断体验）
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.screen_share_close))
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    AndroidView(
                        factory = { renderer },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/** 向上遍历 ContextWrapper 找到宿主 Activity（用于窗口标志操作） */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
