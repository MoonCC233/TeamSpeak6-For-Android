package dev.tsdroid.ui.screen

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import dev.tsdroid.han.R
import dev.tsdroid.ui.theme.generateColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URL

private data class GitHubContributor(
    val login: String,
    val avatarUrl: String,
    val contributions: Int,
)

/**
 * About page. It is deliberately plain white paper — the wallpaper is never drawn here — so the
 * long license text stays on a neutral, high-contrast background.
 *
 * Two things follow from that white surface and are handled here rather than left to the theme:
 * the app's *light* palette is pinned (a dark system theme would otherwise paint pale text on
 * white), and the system-bar icons are switched to their dark variants (or they would vanish into
 * the white until the user leaves the page).
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = context as? Activity
    val systemInDarkTheme = isSystemInDarkTheme()
    val lightScheme = remember { generateColorScheme(Color(0xFF6750A4), isDark = false) }

    DisposableEffect(view, activity) {
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = true
        controller?.isAppearanceLightNavigationBars = true
        onDispose {
            // Hand the bars back to the rest of the app, which lets them follow the system theme.
            controller?.isAppearanceLightStatusBars = !systemInDarkTheme
            controller?.isAppearanceLightNavigationBars = !systemInDarkTheme
        }
    }

    MaterialTheme(colorScheme = lightScheme) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .navigationBarsPadding(),
        ) {
            AboutContent(onBack = onBack)
        }
    }
}

@Composable
private fun AboutContent(onBack: () -> Unit) {
    val context = LocalContext.current
    val repoUrl = "https://github.com/YUAXI/TS6_Droid_CN"
    val scrollState = rememberScrollState()

    var contributors by remember { mutableStateOf<List<GitHubContributor>>(emptyList()) }
    var isLoadingContributors by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val url = URL("https://api.github.com/repos/YUAXI/TS6_Droid_CN/contributors")
                val conn = url.openConnection()
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                val json = conn.getInputStream().bufferedReader().use { it.readText() }
                val arr = JSONArray(json)
                val list = mutableListOf<GitHubContributor>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        GitHubContributor(
                            login = obj.getString("login"),
                            avatarUrl = obj.getString("avatar_url"),
                            contributions = obj.getInt("contributions"),
                        )
                    )
                }
                contributors = list
            } catch (_: Exception) {
            } finally {
                isLoadingContributors = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(scrollState)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
        ) {
            Text(
                text = stringResource(R.string.about_back),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable { onBack() }.padding(8.dp)
            )
            Spacer(modifier = Modifier.width(24.dp))
            Text(
                text = stringResource(R.string.about_software),
                style = MaterialTheme.typography.headlineSmall,
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        Text(text = stringResource(R.string.about_credits_title), style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.about_credits_desc),
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(text = stringResource(R.string.about_enhancement_title), style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.about_enhancement_desc),
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(text = stringResource(R.string.about_license_title), style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.about_license_desc),
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(text = stringResource(R.string.about_repo_title), style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.about_repo_desc),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.about_repo_link),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(repoUrl))
                    context.startActivity(intent)
                } catch (_: Exception) {
                }
            }
        )

        Spacer(modifier = Modifier.height(28.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
        Spacer(modifier = Modifier.height(28.dp))

        Text(text = stringResource(R.string.about_contributors_title), style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(12.dp))

        if (isLoadingContributors) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp)
            )
        } else if (contributors.isEmpty()) {
            Text(
                text = stringResource(R.string.contributors_load_error),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            contributors.forEach { contributor ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = contributor.avatarUrl,
                        contentDescription = contributor.login,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = contributor.login,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.about_contributions_count, contributor.contributions),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
        Spacer(modifier = Modifier.height(28.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier.padding(bottom = 24.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = stringResource(R.string.about_warning_title),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.about_warning_desc),
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
