package dev.tsdroid.pad

import android.app.Application
import dev.tsdroid.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PadApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // The Pad has no floating overlay. :core's connection service reads this
        // setting when the app goes to the background; turning it off keeps the
        // service from attempting an overlay it has no permission for.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { SettingsStore(this@PadApp).setEnableFloatingWindow(false) }
        }
    }

    companion object {
        init {
            System.loadLibrary("tslib_jni")
        }
    }
}
