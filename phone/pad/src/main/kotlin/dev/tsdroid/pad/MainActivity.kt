package dev.tsdroid.pad

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tsdroid.data.ServerBookmark
import dev.tsdroid.pad.ui.JoinScreen
import dev.tsdroid.pad.ui.PadTheme
import dev.tsdroid.pad.ui.WorkspaceScreen
import dev.tsdroid.service.TsConnectionService
import dev.tsdroid.viewmodel.ConnectionViewModel
import dev.tsdroid.viewmodel.ServerViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            PadTheme {
                PadRoot()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Keeps the service's foreground/overlay bookkeeping honest. The Pad
        // disables the floating window, but a future screen-share overlay uses
        // the same signal.
        TsConnectionService.instance?.setAppInForeground(true)
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) return
        TsConnectionService.instance?.setAppInForeground(false)
    }
}

@Composable
private fun PadRoot() {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val connectionViewModel: ConnectionViewModel = viewModel()

    // The Pad has no floating overlay; the phone default stays untouched.
    connectionViewModel.requireOverlayPermission = false

    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    // Returning from the system app-settings page must re-check the grant,
    // otherwise the connect button stays disabled after the user enables it.
    LifecycleResumeEffect(Unit) {
        micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        onPauseOrDispose { }
    }

    val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.POST_NOTIFICATIONS
    } else {
        null
    }
    val requestAudioPermissions: () -> Unit = {
        permissionLauncher.launch(
            buildList {
                add(Manifest.permission.RECORD_AUDIO)
                notificationPermission?.let { add(it) }
            }.toTypedArray(),
        )
    }

    val openAppSettings: () -> Unit = {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ),
            )
        }
    }

    val notificationsGranted = notificationPermission == null ||
        ContextCompat.checkSelfPermission(context, notificationPermission) ==
        PackageManager.PERMISSION_GRANTED
    LaunchedEffect(Unit) {
        if (!micGranted || !notificationsGranted) requestAudioPermissions()
    }

    var sessionActive by remember { mutableStateOf(connectionViewModel.hasActiveConnectionNow()) }
    var sessionKey by remember { mutableIntStateOf(0) }
    var initialChannel by remember { mutableStateOf<String?>(null) }

    // A background-started session (the service outlives the Activity) is
    // resumed here. `hasActiveConnectionNow()` above covers UI recreation, so
    // this only needs to catch the case where the service became ready after
    // the first frame.
    LaunchedEffect(Unit) {
        if (!sessionActive) {
            connectionViewModel.resumeExistingConnection {
                initialChannel = null
                sessionKey++
                sessionActive = true
            }
        }
    }

    if (!sessionActive) {
        JoinScreen(
            viewModel = connectionViewModel,
            micGranted = micGranted,
            onRequestMic = requestAudioPermissions,
            onOpenAppSettings = openAppSettings,
            onConnected = {
                initialChannel = connectionViewModel.channel.value.trim().takeIf { it.isNotEmpty() }
                sessionKey++
                sessionActive = true
            },
        )
    } else {
        key(sessionKey) {
            PadSession(
                application = application,
                connectionViewModel = connectionViewModel,
                initialChannelName = initialChannel,
                onLeave = { sessionActive = false },
                onSwitchServer = { bookmark: ServerBookmark ->
                    connectionViewModel.address.value = bookmark.address
                    connectionViewModel.nickname.value = bookmark.nickname
                    connectionViewModel.password.value = bookmark.password ?: ""
                    connectionViewModel.channel.value = bookmark.channel ?: ""
                    sessionActive = false
                },
            )
        }
    }
}

/**
 * Each connection gets a fresh [ViewModelStore], so [ServerViewModel] can bind
 * to the service exactly once per session. Reusing an Activity-scoped instance
 * would keep `bound = true` after a disconnect and feed the next connection
 * stale collectors.
 */
@Composable
private fun PadSession(
    application: Application,
    connectionViewModel: ConnectionViewModel,
    initialChannelName: String?,
    onLeave: () -> Unit,
    onSwitchServer: (ServerBookmark) -> Unit,
) {
    val owner = remember { PadSessionViewModelStoreOwner(application) }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        val serverViewModel: ServerViewModel = viewModel()
        LaunchedEffect(Unit) {
            serverViewModel.setEnableFloatingWindow(false)
            serverViewModel.bindToService(applySavedMicMode = true)
        }
        WorkspaceScreen(
            serverViewModel = serverViewModel,
            connectionViewModel = connectionViewModel,
            initialChannelName = initialChannelName,
            onLeave = onLeave,
            onSwitchServer = onSwitchServer,
        )
    }
}

private class PadSessionViewModelStoreOwner(
    private val app: Application,
) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
    override val viewModelStore: ViewModelStore = ViewModelStore()

    override val defaultViewModelProviderFactory: ViewModelProvider.Factory =
        ViewModelProvider.AndroidViewModelFactory(app)

    override val defaultViewModelCreationExtras: CreationExtras
        get() = MutableCreationExtras().apply {
            set(ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY, app)
        }
}
