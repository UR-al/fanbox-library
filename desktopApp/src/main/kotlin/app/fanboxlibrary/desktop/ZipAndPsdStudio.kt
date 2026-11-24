package app.fanboxlibrary.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import app.fanboxlibrary.shared.ImportSource
import app.fanboxlibrary.shared.LibraryAsset
import app.fanboxlibrary.shared.LibraryPost
import app.fanboxlibrary.shared.LibraryStore
import app.fanboxlibrary.shared.PostDraft
import app.fanboxlibrary.shared.PsdDocument
import app.fanboxlibrary.shared.PsdEngine
import app.fanboxlibrary.shared.PsdImageBuffer
import app.fanboxlibrary.shared.PsdPresets
import app.fanboxlibrary.shared.ZipExplorer
import app.fanboxlibrary.shared.ZipItem
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DesktopZipExplorerDialog(
    darkTheme: Boolean,
    post: LibraryPost,
    zipAsset: LibraryAsset,
    store: LibraryStore,
    onLibraryChanged: (String) -> Unit,
    onOpenPsd: (File, String) -> Unit,
    onDismiss: () -> Unit,
) {
    DialogWindow(
        onCloseRequest = onDismiss,
        title = "ZIP 탐색기 · ${zipAsset.name}",
        state = rememberDialogState(width = 860.dp, height = 640.dp),
    ) {
        LaunchedEffect(window) { window.minimumSize = Dimension(680, 480) }
        FanboxLibraryTheme(darkTheme = darkTheme) {
            val colors = LocalGalleryColors.current
            val scope = rememberCoroutineScope()
            val zipFile = remember(zipAsset.path) { File(zipAsset.path) }

            var loading by remember { mutableStateOf(true) }
            var entries by remember { mutableStateOf<List<ZipItem>>(emptyList()) }
            var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
            var status by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            var previewItem by remember { mutableStateOf<ZipItem?>(null) }
            var previewBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

            LaunchedEffect(zipAsset.id) {
                loading = true
                runCatching { withContext(Dispatchers.IO) { ZipExplorer.listEntries(zipFile) } }
                    .onSuccess { entries = it }
                    .onFailure { status = it.localizedMessage ?: "ZIP을 읽을 수 없습니다." }
                loading = false
            }

            Surface(Modifier.fillMaxSize(), color = colors.bg) {
                Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(zipAsset.name, style = MaterialTheme.typography.titleLarge, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("총 ${entries.size}개 항목 · 이미지 클릭 시 미리보기, PSD 클릭 시 레이어 스튜디오 열기", style = MaterialTheme.typography.bodySmall, color = colors.muted)
                        }
                        Button(
                            enabled = !busy && entries.isNotEmpty(),
                            onClick = {
                                busy = true
                                status = "보관함 작품으로 압축 해제하는 중…"
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            ZipExplorer.extractIntoLibrary(
                                                zipFile = zipFile,
                                                store = store,
                                                targetPost = post,
                                                entryNames = selected.takeIf { it.isNotEmpty() },
                                            )
                                        }
                                    }.onSuccess { report ->
                                        val msg = "압축 해제 완료 · 추가 ${report.added}개 · 기존 ${report.reused}개"
                                        status = msg
                                        onLibraryChanged(msg)
                                    }.onFailure { err ->
                                        status = "압축 해제 실패: ${err.localizedMessage ?: err.javaClass.simpleName}"
                                    }
                                    busy = false
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(Icons.Rounded.Unarchive, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (selected.isEmpty()) "전체 압축풀기 → 이 작품에 추가" else "선택 ${selected.size}개 압축풀기")
                        }
                    }

                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accent)
                    if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, color = colors.emerald)

                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        // Left: Entry List
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.surface)
                                .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                                .padding(8.dp),
                        ) {
                            when {
                                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = colors.accent)
                                }
                                entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("항목이 없습니다.", color = colors.muted)
                                }
                                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    items(entries, key = { it.entryName }) { item ->
                                        val checked = item.entryName in selected
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (previewItem?.entryName == item.entryName) colors.accentSoft else Color.Transparent)
                                                .clickable(enabled = !busy) {
                                                    when {
                                                        item.isPsd -> {
                                                            busy = true
                                                            status = "PSD 여는 중: ${item.displayName}…"
                                                            scope.launch {
                                                                runCatching {
                                                                    withContext(Dispatchers.IO) {
                                                                        val tmpDir = Files.createTempDirectory("fanbox-psd-").toFile().apply { deleteOnExit() }
                                                                        val out = File(tmpDir, item.displayName.substringAfterLast('/')).apply { deleteOnExit() }
                                                                        ZipExplorer.extractEntryToFile(zipFile, item.entryName, out)
                                                                    }
                                                                }.onSuccess { extracted ->
                                                                    onOpenPsd(extracted, item.displayName.substringAfterLast('/'))
                                                                }.onFailure { err ->
                                                                    status = err.localizedMessage ?: "PSD 열기 실패"
                                                                }
                                                                busy = false
                                                            }
                                                        }
                                                        item.isImage -> {
                                                            previewItem = item
                                                            previewBitmap = null
                                                            scope.launch {
                                                                runCatching {
                                                                    withContext(Dispatchers.IO) {
                                                                        val bytes = ZipExplorer.readEntryBytes(zipFile, item.entryName)
                                                                        ImageIO.read(ByteArrayInputStream(bytes))?.toComposeImageBitmap()
                                                                    }
                                                                }.onSuccess { previewBitmap = it }
                                                            }
                                                        }
                                                        else -> selected = if (checked) selected - item.entryName else selected + item.entryName
                                                    }
                                                }
                                                .padding(horizontal = 6.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Checkbox(checked, { selected = if (it) selected + item.entryName else selected - item.entryName })
                                            Icon(
                                                when {
                                                    item.isPsd -> Icons.Rounded.Layers
                                                    item.isImage -> Icons.Rounded.Image
                                                    else -> Icons.Rounded.FolderZip
                                                },
                                                null,
                                                tint = when {
                                                    item.isPsd -> colors.emerald
                                                    item.isImage -> colors.accent
                                                    else -> colors.muted
                                                },
                                                modifier = Modifier.size(18.dp),
                                            )
                                            Text(item.displayName, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = colors.ink)
                                            if (item.isPsd) {
                                                Text("레이어 편집", style = MaterialTheme.typography.labelSmall, color = colors.emerald, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Right: Quick Image Preview
                        Box(
                            Modifier
                                .width(320.dp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.cinemaBg)
                                .border(1.dp, colors.border, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            when {
                                previewBitmap != null -> Image(previewBitmap!!, previewItem?.displayName, Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit)
                                previewItem != null -> CircularProgressIndicator(color = colors.accent)
                                else -> Text("이미지 선택 시\n여기에 미리보기 표시", color = Color(0xFF8B95A8), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DesktopPsdStudioDialog(
    darkTheme: Boolean,
    post: LibraryPost,
    psdFile: File,
    displayName: String,
    store: LibraryStore,
    onLibraryChanged: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    DialogWindow(
        onCloseRequest = onDismiss,
        title = "PSD 레이어 스튜디오 · $displayName",
        state = rememberDialogState(width = 1120.dp, height = 760.dp),
    ) {
        LaunchedEffect(window) { window.minimumSize = Dimension(860, 580) }
        FanboxLibraryTheme(darkTheme = darkTheme) {
            val colors = LocalGalleryColors.current
            val scope = rememberCoroutineScope()

            var loadingDoc by remember { mutableStateOf(true) }
            var doc by remember { mutableStateOf<PsdDocument?>(null) }
            var error by remember { mutableStateOf("") }
            var visibility by remember { mutableStateOf<Map<Int, Boolean>>(emptyMap()) }
            var opacities by remember { mutableStateOf<Map<Int, Float>>(emptyMap()) }
            var selectedLayer by remember { mutableStateOf<Int?>(null) }
            var renderVersion by remember { mutableIntStateOf(0) }
            var rendering by remember { mutableStateOf(false) }
            var previewBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
            var exportStatus by remember { mutableStateOf("") }
            var exporting by remember { mutableStateOf(false) }

            LaunchedEffect(psdFile.path) {
                loadingDoc = true
                runCatching { withContext(Dispatchers.IO) { PsdEngine.open(psdFile) } }
                    .onSuccess { opened ->
                        doc = opened
                        visibility = opened.defaultVisibilityMap()
                        opacities = opened.defaultOpacityMap()
                        renderVersion++
                    }
                    .onFailure { error = it.localizedMessage ?: "PSD를 열 수 없습니다." }
                loadingDoc = false
            }

            LaunchedEffect(doc, renderVersion) {
                val active = doc ?: return@LaunchedEffect
                rendering = true
                val vis = visibility
                val ops = opacities
                runCatching {
                    withContext(Dispatchers.Default) {
                        PsdEngine.render(active, vis, ops, maxDimension = 1600).toBufferedImage().toComposeImageBitmap()
                    }
                }.onSuccess { previewBitmap = it }
                rendering = false
            }

            fun saveToLibrary() {
                val active = doc ?: return
                if (exporting) return
                exporting = true
                exportStatus = "원본 해상도 PNG 합성 및 보관함 저장 중…"
                val vis = visibility
                val ops = opacities
                scope.launch {
                    runCatching {
                        val base = displayName.substringBeforeLast('.').ifBlank { "psd_edit" }
                        val pngName = "${base}_textless_${System.currentTimeMillis() % 10000}.png"
                        withContext(Dispatchers.Default) {
                            val img = PsdEngine.render(active, vis, ops, maxDimension = Int.MAX_VALUE).toBufferedImage()
                            val bytes = ByteArrayOutputStream().use { bos -> ImageIO.write(img, "png", bos); bos.toByteArray() }
                            withContext(Dispatchers.IO) {
                                store.importManaged(
                                    PostDraft(existingId = post.id),
                                    listOf(ImportSource(pngName, "TEXTLESS/$pngName") { ByteArrayInputStream(bytes) }),
                                )
                            }
                        }
                        pngName
                    }.onSuccess { pngName ->
                        val msg = "보관함에 Textless PNG 저장 완료: $pngName"
                        exportStatus = msg
                        onLibraryChanged(msg)
                    }.onFailure { err ->
                        exportStatus = "저장 실패: ${err.localizedMessage ?: err.javaClass.simpleName}"
                    }
                    exporting = false
                }
            }

            fun exportAsPngFile() {
                val active = doc ?: return
                if (exporting) return
                val base = displayName.substringBeforeLast('.').ifBlank { "psd_edit" }
                val picker = JFileChooser().apply { selectedFile = File("${base}_edited.png") }
                if (picker.showSaveDialog(window) != JFileChooser.APPROVE_OPTION) return
                val dest = picker.selectedFile
                exporting = true
                exportStatus = "PNG 파일로 내보내는 중…"
                val vis = visibility
                val ops = opacities
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.Default) {
                            val img = PsdEngine.render(active, vis, ops, maxDimension = Int.MAX_VALUE).toBufferedImage()
                            withContext(Dispatchers.IO) { ImageIO.write(img, "png", dest) }
                        }
                    }.onSuccess {
                        exportStatus = "내보내기 완료: ${dest.name}"
                    }.onFailure { err ->
                        exportStatus = "내보내기 실패: ${err.localizedMessage ?: err.javaClass.simpleName}"
                    }
                    exporting = false
                }
            }

            Surface(Modifier.fillMaxSize(), color = colors.bg) {
                Column(Modifier.fillMaxSize()) {
                    // Header bar
                    Row(
                        Modifier.fillMaxWidth().background(colors.sidebar).padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(displayName, style = MaterialTheme.typography.titleMedium, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            doc?.let {
                                Text("${it.width} × ${it.height} · 레이어 ${it.uiLayers.size}개", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                            }
                        }
                        doc?.let { active ->
                            OutlinedButton(onClick = { visibility = PsdPresets.applyHideText(active, visibility); renderVersion++ }, shape = RoundedCornerShape(9.dp)) {
                                Text("텍스트 끄기")
                            }
                            OutlinedButton(onClick = { visibility = PsdPresets.applyHideCensor(active, visibility); renderVersion++ }, shape = RoundedCornerShape(9.dp)) {
                                Text("검열 끄기")
                            }
                            TextButton(onClick = { visibility = active.defaultVisibilityMap(); opacities = active.defaultOpacityMap(); renderVersion++ }) {
                                Text("초기화")
                            }
                        }
                        OutlinedButton(onClick = ::exportAsPngFile, enabled = doc != null && !exporting, shape = RoundedCornerShape(9.dp)) {
                            Icon(Icons.Rounded.SaveAlt, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("PNG로 저장")
                        }
                        Button(onClick = ::saveToLibrary, enabled = doc != null && !exporting, shape = RoundedCornerShape(9.dp)) {
                            Icon(Icons.Rounded.Save, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("보관함에 Textless 저장")
                        }
                    }
                    HorizontalDivider(color = colors.border)

                    if (exporting) LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.emerald)
                    if (exportStatus.isNotBlank()) {
                        Text(exportStatus, style = MaterialTheme.typography.bodySmall, color = colors.emerald, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
                    }

                    when {
                        loadingDoc -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = colors.accent)
                        }
                        error.isNotEmpty() && doc == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(error, color = MaterialTheme.colorScheme.error)
                        }
                        doc != null -> {
                            val active = doc!!
                            Row(Modifier.weight(1f).fillMaxWidth()) {
                                // Left: Live Preview Stage
                                Box(Modifier.weight(1f).fillMaxHeight().background(colors.cinemaBg), contentAlignment = Alignment.Center) {
                                    previewBitmap?.let {
                                        Image(it, displayName, Modifier.fillMaxSize().padding(16.dp), contentScale = ContentScale.Fit)
                                    }
                                    if (rendering) {
                                        Box(
                                            Modifier.align(Alignment.TopEnd).padding(14.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xCC11141C)).padding(horizontal = 12.dp, vertical = 6.dp),
                                        ) {
                                            Text("합성 중…", color = Color.White, fontSize = 11.5.sp)
                                        }
                                    }
                                }

                                Box(Modifier.fillMaxHeight().width(1.dp).background(colors.border))

                                // Right: Layer Tree Panel
                                Column(Modifier.width(320.dp).fillMaxHeight().background(colors.sidebar).padding(12.dp)) {
                                    Text("레이어 (${active.uiLayers.size})", style = MaterialTheme.typography.titleSmall, color = colors.ink, modifier = Modifier.padding(bottom = 8.dp))
                                    val sel = selectedLayer?.let { active.layers.getOrNull(it) }
                                    if (sel != null) {
                                        val op = opacities[sel.index] ?: (sel.opacity / 255f)
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("투명도", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                                            Slider(
                                                value = op,
                                                onValueChange = { opacities = opacities + (sel.index to it) },
                                                onValueChangeFinished = { renderVersion++ },
                                                modifier = Modifier.weight(1f),
                                            )
                                            Text("${(op * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                                        }
                                    }
                                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        items(active.uiLayers, key = { it.index }) { layer ->
                                            val ownVis = visibility[layer.index] ?: layer.defaultVisible
                                            val effVis = active.isEffectivelyVisible(layer, visibility)
                                            val isSel = selectedLayer == layer.index
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(start = (layer.depth.coerceAtMost(5) * 14).dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(if (isSel) colors.accentSoft else colors.surface)
                                                    .border(1.dp, if (isSel) colors.accent else colors.border, RoundedCornerShape(8.dp))
                                                    .clickable { selectedLayer = if (isSel) null else layer.index }
                                                    .padding(horizontal = 6.dp, vertical = 5.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                IconButton(
                                                    onClick = { visibility = visibility + (layer.index to !ownVis); renderVersion++ },
                                                    modifier = Modifier.size(28.dp),
                                                ) {
                                                    Icon(
                                                        if (ownVis) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                                        null,
                                                        tint = if (ownVis) (if (layer.isGroupStart) colors.secondary else colors.accent) else colors.muted.copy(alpha = 0.4f),
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                }
                                                Icon(
                                                    when {
                                                        layer.isGroupStart -> Icons.Rounded.Folder
                                                        layer.isTextLayer -> Icons.Rounded.TextFields
                                                        else -> Icons.Rounded.Layers
                                                    },
                                                    null,
                                                    tint = colors.muted,
                                                    modifier = Modifier.size(15.dp),
                                                )
                                                Text(
                                                    (if (layer.clipping) "↳ " else "") + layer.name,
                                                    Modifier.weight(1f),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (effVis) colors.ink else colors.muted.copy(alpha = 0.5f),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun PsdImageBuffer.toBufferedImage(): BufferedImage {
    val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    img.setRGB(0, 0, width, height, argb, 0, width)
    return img
}
