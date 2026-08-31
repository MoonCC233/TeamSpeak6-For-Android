package com.mooncc.teamspeak6.screenshare.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.WindowManager
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/** Capture geometry, derived from the screen size and a target height. */
data class CaptureFormat(val width: Int, val height: Int, val fps: Int)

/**
 * Wraps [ScreenCapturerAndroid] into a single reusable video track.
 *
 * The [MediaProjection] permission intent must already have been granted and a
 * foreground service with type `mediaProjection` must be running before [start]
 * — on API 29+ the capture fails otherwise.
 */
class ScreenCaptureSource(
    private val context: Context,
    private val core: WebRtcCore,
) {

    private var capturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var helper: SurfaceTextureHelper? = null
    private var track: VideoTrack? = null

    val isCapturing: Boolean get() = track != null

    /**
     * @param permissionIntent the result data from the MediaProjection consent dialog
     * @param onStopped invoked when the system or user revokes the projection
     * @return the local screen track, or null when the capturer could not start
     */
    fun start(
        permissionIntent: Intent,
        format: CaptureFormat,
        onStopped: () -> Unit,
    ): VideoTrack? {
        if (track != null) return track

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                onStopped()
            }
        }

        val screenCapturer = ScreenCapturerAndroid(permissionIntent, callback)
        val textureHelper = SurfaceTextureHelper.create("ScreenCapture", core.eglBase.eglBaseContext)
        val source = core.factory.createVideoSource(/* isScreencast = */ true)

        return runCatching {
            screenCapturer.initialize(textureHelper, context, source.capturerObserver)
            screenCapturer.startCapture(format.width, format.height, format.fps)
            val videoTrack = core.factory.createVideoTrack(WebRtcCore.VIDEO_TRACK_ID, source)
            capturer = screenCapturer
            helper = textureHelper
            videoSource = source
            track = videoTrack
            videoTrack
        }.getOrElse {
            runCatching { screenCapturer.dispose() }
            runCatching { textureHelper.dispose() }
            runCatching { source.dispose() }
            null
        }
    }

    /** Changes resolution / frame rate without renegotiating; viewers adapt. */
    fun changeFormat(format: CaptureFormat) {
        capturer?.changeCaptureFormat(format.width, format.height, format.fps)
    }

    fun stop() {
        runCatching { capturer?.stopCapture() }
        runCatching { track?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { capturer?.dispose() }
        runCatching { helper?.dispose() }
        track = null
        videoSource = null
        capturer = null
        helper = null
    }

    /**
     * Scales the physical screen down to [targetHeight] preserving aspect ratio.
     *
     * Dimensions are rounded to even numbers because H.264 requires it, and a
     * target taller than the screen is clamped so we never upscale.
     */
    fun formatFor(targetHeight: Int, fps: Int): CaptureFormat {
        val metrics = DisplayMetrics()
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val screenWidth = metrics.widthPixels.coerceAtLeast(1)
        val screenHeight = metrics.heightPixels.coerceAtLeast(1)
        val height = targetHeight.coerceIn(1, screenHeight)
        val width = (screenWidth.toLong() * height / screenHeight).toInt().coerceAtLeast(1)

        return CaptureFormat(width = width.makeEven(), height = height.makeEven(), fps = fps)
    }

    private fun Int.makeEven(): Int = if (this % 2 == 0) this else this - 1
}
