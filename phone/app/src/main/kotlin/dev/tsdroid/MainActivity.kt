package dev.tsdroid

import android.os.Bundle
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tsdroid.data.SettingsStore
import dev.tsdroid.ui.theme.TsDroidTheme
import dev.tsdroid.ui.component.GlassOverlayHost
import dev.tsdroid.ui.component.LocalGlassOverlayHost
import dev.tsdroid.ui.component.rememberGlassOverlayHostState
import dev.tsdroid.ui.screen.AppNavigation
import dev.tsdroid.ui.screen.SplashScreen
import dev.tsdroid.ui.component.AnimeWallpaperState
import dev.tsdroid.ui.component.CrashReportOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import dev.tsdroid.service.TsConnectionService

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        // The language has to be settled before anything is inflated, so it is the first thing the
        // activity needs. It is normally already read by now — TsDroidApp starts that read on a
        // background thread while the process comes up — and blocking here is only the fallback for
        // having lost that race.
        val languageTag = TsDroidApp.preloadedLanguage()
            ?: runBlocking(Dispatchers.IO) { SettingsStore(newBase).language.first() }
        val locale = java.util.Locale.forLanguageTag(languageTag)
        java.util.Locale.setDefault(locale)
        val config = newBase.resources.configuration
        config.setLocale(locale)
        config.setLocales(android.os.LocaleList(locale))
        val updatedContext = newBase.createConfigurationContext(config)
        super.attachBaseContext(updatedContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val settingsStore = remember { SettingsStore(context) }

            // Read once, here, on the way in: the splash decides from this whether it is waiting for
            // a wallpaper at all, and the app it hands over to starts from the same answer. Letting
            // the screens read it themselves meant their first frame assumed "no wallpaper" and
            // painted an empty background until DataStore replied — one more frame of the very
            // flicker the splash exists to rule out.
            val animeBackground by settingsStore.animeBackground
                .collectAsStateWithLifecycle(initialValue = null)

            var showSplash by remember { mutableStateOf(true) }
            val seedColor = AnimeWallpaperState.dominantColor.value

            TsDroidTheme(seedColor = if (showSplash) null else seedColor) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        val anime = animeBackground
                        if (showSplash || anime == null) {
                            SplashScreen(animeBackground = anime, onReady = { showSplash = false })
                        } else {
                            // One overlay slot for the whole app: a GlassDialog declared anywhere inside
                            // the navigation graph lands here, at the root, so it can blur the wallpaper
                            // and cover the floating bars instead of being clipped by whatever declared
                            // it. See GlassDialog.kt.
                            val overlayHost = rememberGlassOverlayHostState()
                            CompositionLocalProvider(LocalGlassOverlayHost provides overlayHost) {
                                AppNavigation(initialAnimeBackground = anime)
                            }
                            GlassOverlayHost(overlayHost)
                        }
                    }
                    // Sits above everything, including the splash: an uncaught exception
                    // from the previous run is written by TsDroidApp and surfaced here so
                    // users without adb can still report the stack.
                    CrashReportOverlay()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The floating window exists for the time the app is *not* on screen: coming back hides it,
        // and the service brings it back the next time the app goes away. The service decides on its
        // own whether that is wanted (setting, permission, a live connection).
        TsConnectionService.instance?.setAppInForeground(true)
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) return
        TsConnectionService.instance?.setAppInForeground(false)
    }
}
