package dev.tsdroid.data

import android.os.Process
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Log feed for the in-app log viewer (设置 → 更多 → 日志).
 *
 * This reads the process's *own* logcat buffer rather than mirroring calls into
 * a ring buffer. Two reasons:
 *
 *  - nothing has to be changed at the ~200 existing `android.util.Log` call
 *    sites (a mirror only shows what is explicitly routed into it, so a viewer
 *    built that way starts out empty and stays misleading until every file is
 *    converted);
 *  - it also picks up native output. `libtslib_jni` logs from Rust, and audio
 *    problems are usually diagnosed exactly there.
 *
 * Android only exposes log entries belonging to the calling UID, so `logcat -d`
 * from inside the app returns our own process (plus a little system noise,
 * which is filtered out by PID below). Refresh is explicit — the viewer calls
 * [refresh] when it opens and when the user asks — so nothing runs in the
 * background and there is no cost while the screen is closed.
 */
object AppLog {

    private const val TAG = "AppLog"

    /** How many raw logcat lines to pull before filtering to our own PID. */
    private const val LOGCAT_TAIL = 4000

    /** Upper bound on what the viewer keeps, so a chatty session cannot grow without limit. */
    private const val MAX_LINES = 2000

    /** Snapshot-able content — Compose observes this list directly. */
    val lines = mutableStateListOf<String>()

    /** True when the last [refresh] managed to read anything. */
    var lastReadFailed: Boolean = false
        private set

    fun clear() = lines.clear()

    /**
     * Re-reads the process's logcat tail. Safe to call from a coroutine on any
     * dispatcher; the process spawn happens on IO.
     */
    suspend fun refresh() {
        val snapshot = readOwnLogcat()
        lastReadFailed = snapshot == null
        val effective = snapshot ?: return
        lines.clear()
        lines.addAll(effective.takeLast(MAX_LINES))
    }

    private suspend fun readOwnLogcat(): List<String>? = withContext(Dispatchers.IO) {
        try {
            val pid = Process.myPid()
            val process = ProcessBuilder(
                "logcat",
                "-d",
                "-v",
                "threadtime",
                "-t",
                LOGCAT_TAIL.toString(),
            ).redirectErrorStream(true).start()

            val output = process.inputStream.bufferedReader().use { it.readLines() }
            process.waitFor()

            output.filter { isOwnProcessLine(it, pid) }
        } catch (t: Throwable) {
            // A device that forbids reading logcat simply shows an empty viewer.
            Log.w(TAG, "Could not read logcat", t)
            null
        }
    }

    /**
     * `-v threadtime` lines look like
     * `01-02 03:04:05.678  1234  5678 I TAG     : message`
     * so the PID is the third whitespace-separated field. Banner lines such as
     * `--------- beginning of main` have too few fields and are dropped.
     */
    private fun isOwnProcessLine(line: String, pid: Int): Boolean {
        val parts = line.split(' ')
        var seen = 0
        var pidField: String? = null
        for (part in parts) {
            if (part.isEmpty()) continue
            if (seen == 2) {
                pidField = part
                break
            }
            seen++
        }
        return pidField?.toIntOrNull() == pid
    }
}
