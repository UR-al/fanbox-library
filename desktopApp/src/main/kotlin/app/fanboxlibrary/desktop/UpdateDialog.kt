package app.fanboxlibrary.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.fanboxlibrary.shared.GitHubUpdater
import app.fanboxlibrary.shared.ReleaseAsset
import app.fanboxlibrary.shared.UpdateCheckResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.net.URI

@Composable
internal fun UpdateDialog(
    currentVersion: String,
    initialResult: UpdateCheckResult?,
    onResultUpdated: (UpdateCheckResult) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalGalleryColors.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(initialResult == null) }
    var result by remember { mutableStateOf(initialResult) }
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var downloadStatus by remember { mutableStateOf("") }
    var downloadedFile by remember { mutableStateOf<File?>(null) }

    fun runCheck() {
        if (checking || downloading) return
        checking = true
        downloadStatus = ""
        scope.launch {
            val fetched = withContext(Dispatchers.IO) { GitHubUpdater.checkLatest(currentVersion) }
            result = fetched
            onResultUpdated(fetched)
            checking = false
        }
    }

    fun openExternalUrl(url: String) {
        scope.launch(Dispatchers.IO) {
            runCatching { Desktop.getDesktop().browse(URI(url)) }
        }
    }

    fun startDownload(asset: ReleaseAsset) {
        if (downloading) return
        downloading = true
        downloadProgress = 0f
        downloadStatus = "${asset.name} 다운로드 중…"
        downloadedFile = null
        scope.launch {
            runCatching {
                val downloadsDir = File(System.getProperty("user.home"), "Downloads").let {
                    if (it.isDirectory) File(it, "FanboxLibrary") else File(System.getProperty("java.io.tmpdir"), "FanboxLibrary")
                }
                val dest = File(downloadsDir, asset.name)
                withContext(Dispatchers.IO) {
                    GitHubUpdater.downloadAsset(asset, dest) { done, total ->
                        if (total > 0) {
                            downloadProgress = (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                        }
                    }
                }
            }.onSuccess { file ->
                downloadedFile = file
                downloadProgress = 1f
                downloadStatus = "다운로드 완료: ${file.name}"
            }.onFailure { err ->
                downloadStatus = "다운로드 실패: ${err.localizedMessage ?: err.javaClass.simpleName}"
            }
            downloading = false
        }
    }

    LaunchedEffect(Unit) {
        if (initialResult == null) runCheck()
    }

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        containerColor = colors.surface,
        titleContentColor = colors.ink,
        textContentColor = colors.ink,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.NewReleases, contentDescription = null, tint = colors.accent)
                Column {
                    Text("GitHub 업데이트", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${GitHubUpdater.REPO_OWNER}/${GitHubUpdater.REPO_NAME} · 현재 v$currentVersion",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.widthIn(min = 420.dp, max = 500.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    checking -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp, color = colors.accent)
                            Text("GitHub Releases에서 최신 버전을 확인하는 중…", color = colors.muted)
                        }
                    }

                    result is UpdateCheckResult.UpdateAvailable -> {
                        val avail = result as UpdateCheckResult.UpdateAvailable
                        val release = avail.release
                        val installer = release.findWindowsInstaller()

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.accentSoft)
                                .border(1.dp, colors.accent.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.Rounded.NewReleases, contentDescription = null, tint = colors.accent)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "새 버전 ${release.tagName} 출시!",
                                    fontWeight = FontWeight.Bold,
                                    color = colors.ink,
                                )
                                Text(
                                    release.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.muted,
                                )
                            }
                        }

                        if (release.notes.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 180.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(colors.bg)
                                    .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                                    .padding(12.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                Text(release.notes, style = MaterialTheme.typography.bodySmall, color = colors.ink)
                            }
                        }

                        if (downloading) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                LinearProgressIndicator(
                                    progress = { downloadProgress },
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                                    color = colors.accent,
                                )
                                Text(
                                    "$downloadStatus (${(downloadProgress * 100).toInt()}%)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.muted,
                                )
                            }
                        } else if (downloadStatus.isNotBlank()) {
                            Text(downloadStatus, style = MaterialTheme.typography.bodySmall, color = colors.emerald)
                        }

                        if (downloadedFile != null) {
                            val file = downloadedFile!!
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        scope.launch(Dispatchers.IO) { runCatching { Desktop.getDesktop().open(file) } }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.emerald),
                                ) {
                                    Icon(Icons.Rounded.Download, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("설치 파일 실행")
                                }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            file.parentFile?.let { runCatching { Desktop.getDesktop().open(it) } }
                                        }
                                    },
                                ) {
                                    Icon(Icons.Rounded.FolderOpen, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("폴더 열기")
                                }
                            }
                        } else if (installer != null) {
                            Button(
                                onClick = { startDownload(installer) },
                                enabled = !downloading,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Windows 설치 파일 다운로드 (${installer.name})")
                            }
                        }
                    }

                    result is UpdateCheckResult.UpToDate -> {
                        val ok = result as UpdateCheckResult.UpToDate
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.emeraldSoft)
                                .border(1.dp, colors.emerald.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = colors.emerald)
                            Column {
                                Text("최신 버전을 사용 중입니다.", fontWeight = FontWeight.SemiBold, color = colors.ink)
                                ok.latestRelease?.let {
                                    Text(
                                        "최신 릴리즈: ${it.tagName}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.muted,
                                    )
                                }
                            }
                        }
                    }

                    result is UpdateCheckResult.NoReleaseYet -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.surfaceElevated)
                                .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text("아직 등록된 GitHub 릴리즈가 없습니다.", fontWeight = FontWeight.SemiBold, color = colors.ink)
                            Text(
                                "GitHub 저장소에 새 태그(예: v1.1.0)로 릴리즈를 올리면 이곳에서 바로 업데이트를 확인할 수 있습니다.",
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
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0x26EF4444))
                                .border(1.dp, Color(0x66EF4444), RoundedCornerShape(10.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = Color(0xFFEF4444))
                            Text(err.message, style = MaterialTheme.typography.bodySmall, color = colors.ink)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val releaseUrl = (result as? UpdateCheckResult.UpdateAvailable)?.release?.htmlUrl
                    ?: (result as? UpdateCheckResult.UpToDate)?.latestRelease?.htmlUrl
                    ?: GitHubUpdater.RELEASES_PAGE_URL
                OutlinedButton(
                    onClick = { openExternalUrl(releaseUrl) },
                    enabled = !downloading,
                ) {
                    Icon(Icons.Rounded.OpenInBrowser, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("GitHub 열기")
                }
                TextButton(
                    onClick = ::runCheck,
                    enabled = !checking && !downloading,
                ) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("다시 확인")
                }
                Button(onClick = onDismiss, enabled = !downloading) {
                    Text("닫기")
                }
            }
        },
    )
}
