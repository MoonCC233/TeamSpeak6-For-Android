package com.mooncc.teamspeak6.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mooncc.teamspeak6.ui.navigation.TeamSpeakNavHost
import com.mooncc.teamspeak6.ui.theme.TeamSpeakTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var showPermissionPrompt by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val denied = result.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            showPermissionPrompt = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestRuntimePermissions()
        setContent {
            TeamSpeakTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TeamSpeakNavHost()
                    if (showPermissionPrompt) {
                        AlertDialog(
                            onDismissRequest = { showPermissionPrompt = false },
                            title = { Text("录音权限受限") },
                            text = {
                                Text("TeamSpeak 需要麦克风权限才能进行语音通话；如果你拒绝了权限，可以在系统设置中重新打开。")
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showPermissionPrompt = false
                                        startActivity(
                                            Intent(
                                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                Uri.fromParts("package", packageName, null),
                                            ),
                                        )
                                    },
                                ) {
                                    Text("前往设置")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showPermissionPrompt = false }) {
                                    Text("稍后再说")
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    private fun requestRuntimePermissions() {
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }
}
