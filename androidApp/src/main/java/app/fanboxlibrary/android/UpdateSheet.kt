package app.fanboxlibrary.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.fanboxlibrary.shared.GitHubUpdater
import app.fanboxlibrary.shared.UpdateCheckResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun isInstalledFromPlayStore(context: Context): Boolean = runCatching {
    val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getInstallerPackageName(context.packageName)
    }
    installer == "com.android.vending"
}.getOrDefault(false)

private fun openInBrowser(context: Context, url: String, onError: (String) -> Unit) {
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url))
    } catch (_: ActivityNotFoundException) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            onError("링크를 열 브라우저를 찾을 수 없습니다.")
        }
    }
}

private fun openPlayStoreListing(context: Context, onError: (String) -> Unit) {
    val pkg = context.packageName.removeSuffix(".preview")
    val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
    try {
        context.startActivity(marketIntent)
    } catch (_: ActivityNotFoundException) {
        openInBrowser(context, "https://play.google.com/store/apps/details?id=$pkg", onError)
    }
}

/**
 * Google Play Policy Compliant Update Dialog:
 * - Queries GitHub Releases API to check version & display release notes.
 * - Never downloads or self-installs APKs (no REQUEST_INSTALL_PACKAGES permission).
 * - Routes Play Store installs to Google Play Store, and GitHub/preview builds to the GitHub Releases webpage.
 */
@Composable
internal fun AndroidUpdateDialog(
    currentVersion: String,
    initialResult: UpdateCheckResult?,
    onResultUpdated: (UpdateCheckResult) -> Unit,
    onError: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalAndroidGalleryColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fromPlayStore = remember(context) { isInstalledFromPlayStore(context) }

    var checking by remember { mutableStateOf(initialResult == null) }
    var result by remember { mutableStateOf(initialResult) }

    fun runCheck() {
        if (checking) return
        checking = true
        scope.launch {
            val fetched = withContext(Dispatchers.IO) { GitHubUpdater.checkLatest(currentVersion) }
            result = fetched
            onResultUpdated(fetched)
            checking = false
        }
    }

    LaunchedEffect(Unit) {
        if (initialResult == null) runCheck()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.ink,
        textContentColor = colors.ink,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.NewReleases, contentDescription = null, tint = colors.accent)
                Column {
                    Text("앱 업데이트 확인", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "현재 v$currentVersion · ${if (fromPlayStore) "Google Play" else "GitHub"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    checking -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp, color = colors.accent)
                            Text("GitHub에서 최신 버전을 확인하는 중…", color = colors.muted)
                        }
                    }

                    result is UpdateCheckResult.UpdateAvailable -> {
                        val release = (result as UpdateCheckResult.UpdateAvailable).release
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.accentSoft)
                                .border(1.dp, colors.accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text("새 버전 ${release.tagName} 출시!", fontWeight = FontWeight.Bold, color = colors.ink)
                            Text(release.title, style = MaterialTheme.typography.bodySmall, color = colors.muted)
                        }

                        if (release.notes.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 170.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(colors.bg)
                                    .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                                    .padding(10.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                Text(release.notes, style = MaterialTheme.typography.bodySmall, color = colors.ink)
                            }
                        }

                        if (fromPlayStore) {
                            Button(
                                onClick = { openPlayStoreListing(context, onError) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Icon(Icons.Rounded.Shop, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Google Play에서 업데이트")
                            }
                            OutlinedButton(
                                onClick = { openInBrowser(context, release.htmlUrl, onError) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Icon(Icons.Rounded.OpenInBrowser, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("GitHub 릴리즈 노트 보기")
                            }
                        } else {
                            Button(
                                onClick = { openInBrowser(context, release.htmlUrl, onError) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Icon(Icons.Rounded.OpenInBrowser, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("GitHub 릴리즈 페이지에서 받기")
                            }
                            Text(
                                "Google Play 출시 버전에서는 Play 스토어로 안전하게 연결됩니다.",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.muted,
                            )
                        }
                    }

                    result is UpdateCheckResult.UpToDate -> {
                        val ok = result as UpdateCheckResult.UpToDate
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.emeraldSoft)
                                .border(1.dp, colors.emerald.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = colors.emerald)
                            Column {
                                Text("최신 버전을 사용 중입니다.", fontWeight = FontWeight.SemiBold, color = colors.ink)
                                ok.latestRelease?.let {
                                    Text("최신 릴리즈: ${it.tagName}", style = MaterialTheme.typography.bodySmall, color = colors.muted)
                                }
                            }
                        }
                    }

                    result is UpdateCheckResult.NoReleaseYet -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.surfaceElevated)
                                .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text("아직 등록된 GitHub 릴리즈가 없습니다.", fontWeight = FontWeight.SemiBold, color = colors.ink)
                            Text(
                                "${GitHubUpdater.REPO_OWNER}/${GitHubUpdater.REPO_NAME}에 새 릴리즈를 발행하면 이곳에서 바로 확인할 수 있습니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.muted,
                            )
                        }
                    }

                    result is UpdateCheckResult.Error -> {
                        val err = result as UpdateCheckResult.Error
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0x26EF4444))
                                .border(1.dp, Color(0x66EF4444), RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.Rounded.ErrorOutline, null, tint = Color(0xFFEF4444))
                            Text(err.message, style = MaterialTheme.typography.bodySmall, color = colors.ink)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = {
                        val url = (result as? UpdateCheckResult.UpdateAvailable)?.release?.htmlUrl
                            ?: (result as? UpdateCheckResult.UpToDate)?.latestRelease?.htmlUrl
                            ?: GitHubUpdater.RELEASES_PAGE_URL
                        openInBrowser(context, url, onError)
                    },
                ) {
                    Text("GitHub")
                }
                TextButton(onClick = ::runCheck, enabled = !checking) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("다시 확인")
                }
                TextButton(onClick = onDismiss) { Text("닫기") }
            }
        },
    )
}
