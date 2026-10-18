package dev.tsdroid

import android.app.Application
import android.content.Context
import android.util.Log
import dev.tsdroid.data.SettingsStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class TsDroidApp : Application() {

    companion object {
        const val CHANNEL_ID_CONNECTION = "ts_connection"
        const val CHANNEL_ID_POKE = "ts_poke"

        /** Activities currently started — pokes only notify when none exist. */
        private var startedActivities = 0
        val isAppForeground: Boolean get() = startedActivities > 0

        init {
            System.loadLibrary("tslib_jni")
        }

        /**
         * The language the process is starting in, read while it is still coming up.
         *
         * `attachBaseContext` needs it *before* anything can be inflated, which used to mean blocking
         * the main thread on the first DataStore read of the process as its very first act — before
         * the activity, the theme, or the first frame existed. The read is started here instead; an
         * activity only blocks on it if it somehow loses the race.
         */
        @Volatile
        private var startupLanguage: String? = null

        fun preloadLanguage(context: Context) {
            val appContext = context.applicationContext
            // Process-lifetime scope: nothing to cancel, and losing this read would only mean the
            // activity falls back to reading it itself.
            CoroutineScope(Dispatchers.IO).launch {
                startupLanguage = SettingsStore(appContext).language.first()
            }
        }

        /** What [preloadLanguage] found, or null while that read is still running. */
        fun preloadedLanguage(): String? = startupLanguage
    }

    override fun onCreate() {
        // 崩溃陷阱：未捕获异常的堆栈写入 filesDir/last_crash.txt，下次启动在
        // 界面上展示并可一键复制（无 adb 环境时也能拿到闪退原因）。
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                File(filesDir, "last_crash.txt").writeText(
                    buildString {
                        appendLine("thread=${thread.name}")
                        appendLine(Log.getStackTraceString(throwable))
                    },
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
        super.onCreate()
        preloadLanguage(this)
    }
}
