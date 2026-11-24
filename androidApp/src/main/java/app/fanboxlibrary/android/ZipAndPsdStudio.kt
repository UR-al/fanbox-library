package app.fanboxlibrary.android

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.RestartAlt
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fanboxlibrary.shared.ImportSource
import app.fanboxlibrary.shared.LibraryAsset
import app.fanboxlibrary.shared.LibraryPost
import app.fanboxlibrary.shared.PostDraft
import app.fanboxlibrary.shared.PsdDocument
import app.fanboxlibrary.shared.PsdEngine
import app.fanboxlibrary.shared.PsdImageBuffer
import app.fanboxlibrary.shared.PsdLayer
import app.fanboxlibrary.shared.PsdPresets
import app.fanboxlibrary.shared.ZipExplorer
import app.fanboxlibrary.shared.ZipItem
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * In-app ZIP content explorer:
 * - Previews images inside ZIP without extracting everything
 * - Opens PSD/PSB entries directly in PsdLayerStudioScreen
 * - Extracts selected or all entries straight into the current LibraryPost
 */
@Composable
internal fun ZipExplorerScreen(
    post: LibraryPost,
    zipAsset: LibraryAsset,
    model: ArchiveModel,
    onOpenPsdFile: (file: File, displayName: String, isTemp: Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalAndroidGalleryColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zipFile = remember(zipAsset.path) { File(zipAsset.path) }

    var loading by remember(zipAsset.id) { mutableStateOf(true) }
    var entries by remember(zipAsset.id) { mutableStateOf<List<ZipItem>>(emptyList()) }
    var errorText by remember(zipAsset.id) { mutableStateOf("") }
    var selected by remember(zipAsset.id) { mutableStateOf<Set<String>>(emptySet()) }
    var busyMessage by remember { mutableStateOf("") }
    var previewItem by remember { mutableStateOf<ZipItem?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }

    BackHandler {
        if (previewItem != null) {
            previewItem = null
            previewBitmap = null
        } else {
            onBack()
        }
    }

    LaunchedEffect(zipAsset.id) {
        loading = true
        errorText = ""
        runCatching { withContext(Dispatchers.IO) { ZipExplorer.listEntries(zipFile) } }
            .onSuccess { entries = it }
            .onFailure { errorText = it.localizedMessage ?: "ZIP 목록을 읽을 수 없습니다." }
        loading = false
    }

    // Inline Image Preview Modal for ZIP image entry
    if (previewItem != null) {
        Column(Modifier.fillMaxSize().background(colors.cinemaBg)) {
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0xE60B0D13)).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { previewItem = null; previewBitmap = null }) {
                    Text("‹ 목록", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    previewItem!!.displayName.substringAfterLast('/'),
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val bmp = previewBitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = previewItem!!.displayName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    CircularProgressIndicator(color = colors.accent)
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().background(colors.bg)) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = onBack, enabled = busyMessage.isEmpty()) {
                Text("‹ 작품", color = colors.ink, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    zipAsset.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "ZIP 내부 ${entries.size}개 항목 · PSD 바로 편집 가능",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
                )
            }
            TextButton(onClick = { openAttachment(context, zipAsset) { model.notice = it } }) {
                Text("외부 앱", style = MaterialTheme.typography.labelMedium)
            }
        }

        if (busyMessage.isNotEmpty()) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accent)
            Text(
                busyMessage,
                style = MaterialTheme.typography.labelSmall,
                color = colors.ink,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }

        // Action bar
        if (entries.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    enabled = busyMessage.isEmpty(),
                    onClick = {
                        busyMessage = "보관함 작품에 압축 해제하는 중…"
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    ZipExplorer.extractIntoLibrary(
                                        zipFile = zipFile,
                                        store = model.store,
                                        targetPost = post,
                                        entryNames = selected.takeIf { it.isNotEmpty() },
                                    )
                                }
                            }.onSuccess { report ->
                                model.refresh()
                                model.notice = "압축 해제 완료 · 추가 ${report.added}개 · 기존 ${report.reused}개"
                                selected = emptySet()
                            }.onFailure { err ->
                                model.notice = err.localizedMessage ?: "압축 해제 실패"
                            }
                            busyMessage = ""
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Rounded.Unarchive, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (selected.isEmpty()) "전체 압축풀기 → 이 작품에 추가" else "선택 ${selected.size}개 압축풀기")
                }
                if (selected.isNotEmpty()) {
                    TextButton(onClick = { selected = emptySet() }) { Text("해제") }
                }
            }
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }
            errorText.isNotEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(errorText, color = MaterialTheme.colorScheme.error)
            }
            entries.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("ZIP 안에 표시할 파일이 없습니다.", color = colors.muted)
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { it.entryName }) { item ->
                    val isChecked = item.entryName in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surface)
                            .border(1.dp, if (isChecked) colors.accent else colors.border, RoundedCornerShape(12.dp))
                            .clickable(enabled = busyMessage.isEmpty()) {
                                when {
                                    item.isPsd -> {
                                        busyMessage = "PSD 여는 중: ${item.displayName.substringAfterLast('/')}…"
                                        scope.launch {
                                            runCatching {
                                                withContext(Dispatchers.IO) {
                                                    val cacheDir = File(context.cacheDir, "psd_zip_cache").apply { mkdirs() }
                                                    val outFile = File(cacheDir, "${zipAsset.sha256.take(10)}_${item.displayName.substringAfterLast('/')}")
                                                    ZipExplorer.extractEntryToFile(zipFile, item.entryName, outFile)
                                                }
                                            }.onSuccess { extracted ->
                                                onOpenPsdFile(extracted, item.displayName.substringAfterLast('/'), true)
                                            }.onFailure { err ->
                                                model.notice = err.localizedMessage ?: "PSD를 열 수 없습니다."
                                            }
                                            busyMessage = ""
                                        }
                                    }
                                    item.isImage -> {
                                        previewItem = item
                                        previewBitmap = null
                                        scope.launch {
                                            runCatching {
                                                withContext(Dispatchers.IO) {
                                                    val bytes = ZipExplorer.readEntryBytes(zipFile, item.entryName)
                                                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                                                    val longest = maxOf(opts.outWidth, opts.outHeight).coerceAtLeast(1)
                                                    val sample = (longest / 2048).coerceAtLeast(1)
                                                    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                                                }
                                            }.onSuccess { bmp ->
                                                if (bmp != null) previewBitmap = bmp
                                                else {
                                                    previewItem = null
                                                    model.notice = "이미지를 디코딩할 수 없습니다."
                                                }
                                            }.onFailure { err ->
                                                previewItem = null
                                                model.notice = err.localizedMessage ?: "이미지 미리보기 실패"
                                            }
                                        }
                                    }
                                    else -> {
                                        selected = if (isChecked) selected - item.entryName else selected + item.entryName
                                    }
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = {
                                selected = if (it) selected + item.entryName else selected - item.entryName
                            },
                        )
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(
                                    when {
                                        item.isPsd -> colors.emeraldSoft
                                        item.isImage -> colors.accentSoft
                                        else -> colors.secondarySoft
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                when {
                                    item.isPsd -> Icons.Rounded.Layers
                                    item.isImage -> Icons.Rounded.Image
                                    else -> Icons.Rounded.FolderZip
                                },
                                contentDescription = null,
                                tint = when {
                                    item.isPsd -> colors.emerald
                                    item.isImage -> colors.accent
                                    else -> colors.secondary
                                },
                                modifier = Modifier.size(19.dp),
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = colors.ink,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                Formatter.formatFileSize(context, item.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.muted,
                            )
                        }
                        if (item.isPsd) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(colors.emerald)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Text("레이어 편집", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        } else if (item.isImage) {
                            Text("미리보기", style = MaterialTheme.typography.labelSmall, color = colors.accent)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Built-in Android PSD / PSB Layer Studio:
 * - Live pinch-zoomable composited preview
 * - Toggle individual layers & folders on/off, adjust opacity
 * - One-tap presets: Hide Text / Hide Censor / Reset
 * - Export to current LibraryPost (as 'textless' for instant A/B viewing!) OR Android Gallery (Pictures/FanboxLibrary)
 */
@Composable
internal fun PsdLayerStudioScreen(
    post: LibraryPost,
    psdFile: File,
    displayName: String,
    model: ArchiveModel,
    onBack: () -> Unit,
) {
    val colors = LocalAndroidGalleryColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loadingDoc by remember(psdFile.path) { mutableStateOf(true) }
    var doc by remember(psdFile.path) { mutableStateOf<PsdDocument?>(null) }
    var errorText by remember(psdFile.path) { mutableStateOf("") }

    var visibility by remember(psdFile.path) { mutableStateOf<Map<Int, Boolean>>(emptyMap()) }
    var opacities by remember(psdFile.path) { mutableStateOf<Map<Int, Float>>(emptyMap()) }
    var selectedLayerIndex by remember(psdFile.path) { mutableStateOf<Int?>(null) }

    var rendering by remember { mutableStateOf(false) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var exportingText by remember { mutableStateOf("") }
    var renderVersion by remember { mutableIntStateOf(0) }

    BackHandler(enabled = exportingText.isEmpty()) { onBack() }

    LaunchedEffect(psdFile.path) {
        loadingDoc = true
        errorText = ""
        runCatching { withContext(Dispatchers.IO) { PsdEngine.open(psdFile) } }
            .onSuccess { opened ->
                doc = opened
                visibility = opened.defaultVisibilityMap()
                opacities = opened.defaultOpacityMap()
                renderVersion++
            }
            .onFailure { err ->
                errorText = err.localizedMessage ?: "PSD 파일을 해석할 수 없습니다."
            }
        loadingDoc = false
    }

    LaunchedEffect(doc, renderVersion) {
        val activeDoc = doc ?: return@LaunchedEffect
        rendering = true
        val visSnapshot = visibility
        val opSnapshot = opacities
        runCatching {
            withContext(Dispatchers.Default) {
                val buf = PsdEngine.render(activeDoc, visSnapshot, opSnapshot, maxDimension = 1100)
                buf.toAndroidBitmap()
            }
        }.onSuccess { bmp ->
            previewBitmap = bmp
        }.onFailure { err ->
            errorText = err.localizedMessage ?: "미리보기 렌더링 오류"
        }
        rendering = false
    }

    fun exportToLibrary(variantLabel: String) {
        val activeDoc = doc ?: return
        if (exportingText.isNotEmpty()) return
        exportingText = "고해상도 PNG 합성 및 보관함 저장 중…"
        val visSnapshot = visibility
        val opSnapshot = opacities
        scope.launch {
            runCatching {
                val baseName = displayName.substringBeforeLast('.').ifBlank { "psd_edit" }
                val pngName = "${baseName}_${variantLabel}_${System.currentTimeMillis() % 10000}.png"
                val report = withContext(Dispatchers.Default) {
                    val maxEdge = 4096 // safe ceiling for mobile RAM while staying ultra-crisp
                    val buf = PsdEngine.render(activeDoc, visSnapshot, opSnapshot, maxDimension = maxEdge)
                    val bmp = buf.toAndroidBitmap()
                    val pngBytes = ByteArrayOutputStream().use { bos ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
                        bmp.recycle()
                        bos.toByteArray()
                    }
                    withContext(Dispatchers.IO) {
                        val draft = PostDraft(existingId = post.id)
                        val hint = if (variantLabel == "textless") "TEXTLESS/$pngName" else "수정본/$pngName"
                        model.store.importManaged(
                            draft,
                            listOf(ImportSource(pngName, hint) { ByteArrayInputStream(pngBytes) }),
                        )
                    }
                }
                model.refresh()
                report to pngName
            }.onSuccess { (_, pngName) ->
                model.notice = "보관함에 저장했습니다 ($pngName) · 작품 화면에서 바로 감상하세요!"
            }.onFailure { err ->
                model.notice = "내보내기 실패: ${err.localizedMessage ?: err.javaClass.simpleName}"
            }
            exportingText = ""
        }
    }

    fun exportToGallery() {
        val activeDoc = doc ?: return
        if (exportingText.isNotEmpty()) return
        exportingText = "갤러리(Pictures/FanboxLibrary)로 내보내는 중…"
        val visSnapshot = visibility
        val opSnapshot = opacities
        scope.launch {
            runCatching {
                val baseName = displayName.substringBeforeLast('.').ifBlank { "psd_edit" }
                val pngName = "${baseName}_edited_${System.currentTimeMillis() % 10000}.png"
                withContext(Dispatchers.Default) {
                    val buf = PsdEngine.render(activeDoc, visSnapshot, opSnapshot, maxDimension = 4096)
                    val bmp = buf.toAndroidBitmap()
                    try {
                        withContext(Dispatchers.IO) {
                            saveBitmapToAndroidGallery(context, bmp, pngName)
                        }
                    } finally {
                        bmp.recycle()
                    }
                }
                pngName
            }.onSuccess { pngName ->
                model.notice = "갤러리에 저장했습니다: Pictures/FanboxLibrary/$pngName"
            }.onFailure { err ->
                model.notice = "갤러리 저장 실패: ${err.localizedMessage ?: err.javaClass.simpleName}"
            }
            exportingText = ""
        }
    }

    Column(Modifier.fillMaxSize().background(colors.bg)) {
        // Top Studio Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = onBack, enabled = exportingText.isEmpty()) {
                Text("‹ 닫기", color = colors.ink, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                doc?.let {
                    Text(
                        "${it.width}×${it.height} · 레이어 ${it.uiLayers.size}개",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.muted,
                    )
                }
            }
            OutlinedButton(
                onClick = ::exportToGallery,
                enabled = doc != null && exportingText.isEmpty(),
                shape = RoundedCornerShape(9.dp),
                border = BorderStroke(1.dp, colors.border),
                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 5.dp),
            ) {
                Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("갤러리", style = MaterialTheme.typography.labelSmall)
            }
            Button(
                onClick = { exportToLibrary("textless") },
                enabled = doc != null && exportingText.isEmpty(),
                shape = RoundedCornerShape(9.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Icon(Icons.Rounded.Save, null, Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("보관함 저장", style = MaterialTheme.typography.labelSmall)
            }
        }

        if (exportingText.isNotEmpty()) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.emerald)
            Text(
                exportingText,
                style = MaterialTheme.typography.labelSmall,
                color = colors.ink,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }

        when {
            loadingDoc -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(color = colors.accent)
                    Text("PSD 레이어 구조를 분석하는 중…", color = colors.muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            errorText.isNotEmpty() && doc == null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(errorText, color = MaterialTheme.colorScheme.error)
            }
            doc != null -> {
                val activeDoc = doc!!

                // Top Half: Live Interactive Preview Canvas (46% height)
                var scale by remember { mutableFloatStateOf(1f) }
                var offset by remember { mutableStateOf(Offset.Zero) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.47f)
                        .clipToBounds()
                        .background(colors.cinemaBg)
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = {
                                scale = if (scale > 1f) 1f else 2.2f
                                offset = Offset.Zero
                            })
                        }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.count { it.pressed }
                                    if (pressed >= 2 || (pressed > 0 && scale > 1f)) {
                                        val next = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                                        val pan = offset * (next / scale) + event.calculatePan()
                                        val dx = size.width * (next - 1f) / 2
                                        val dy = size.height * (next - 1f) / 2
                                        offset = Offset(pan.x.coerceIn(-dx, dx), pan.y.coerceIn(-dy, dy))
                                        scale = next
                                        event.changes.forEach { it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = previewBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = displayName,
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            },
                            contentScale = ContentScale.Fit,
                        )
                    }
                    if (rendering) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(10.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xCC11141C))
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.accent)
                            Text("합성 중…", color = Color.White, fontSize = 11.sp)
                        }
                    }
                }

                // Quick Preset Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surface)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PresetChip("텍스트 끄기", colors.emerald) {
                        visibility = PsdPresets.applyHideText(activeDoc, visibility)
                        renderVersion++
                    }
                    PresetChip("검열/모자이크 끄기", colors.accent) {
                        visibility = PsdPresets.applyHideCensor(activeDoc, visibility)
                        renderVersion++
                    }
                    PresetChip("원본 복원", colors.secondary) {
                        visibility = activeDoc.defaultVisibilityMap()
                        opacities = activeDoc.defaultOpacityMap()
                        renderVersion++
                    }
                }

                // Opacity Slider for Selected Layer
                val selLayer = selectedLayerIndex?.let { idx -> activeDoc.layers.getOrNull(idx) }
                if (selLayer != null) {
                    val curOp = opacities[selLayer.index] ?: (selLayer.opacity / 255f)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.surfaceElevated)
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "${selLayer.name} 투명도",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 120.dp),
                        )
                        Slider(
                            value = curOp,
                            onValueChange = { v -> opacities = opacities + (selLayer.index to v) },
                            onValueChangeFinished = { renderVersion++ },
                            modifier = Modifier.weight(1f),
                        )
                        Text("${(curOp * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                    }
                }

                HorizontalDivider(color = colors.border)

                // Bottom Half: Hierarchical Layer Tree (53% height)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(0.53f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(activeDoc.uiLayers, key = { it.index }) { layer ->
                        val ownVisible = visibility[layer.index] ?: layer.defaultVisible
                        val effVisible = activeDoc.isEffectivelyVisible(layer, visibility)
                        val isSelected = selectedLayerIndex == layer.index
                        val curOp = ((opacities[layer.index] ?: (layer.opacity / 255f)) * 100).toInt()
                        val indent = (layer.depth.coerceAtMost(5) * 16).dp

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = indent)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) colors.accentSoft else colors.surface)
                                .border(
                                    1.dp,
                                    if (isSelected) colors.accent else colors.border,
                                    RoundedCornerShape(10.dp),
                                )
                                .clickable { selectedLayerIndex = if (isSelected) null else layer.index }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            IconButton(
                                onClick = {
                                    visibility = visibility + (layer.index to !ownVisible)
                                    renderVersion++
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    if (ownVisible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                    contentDescription = if (ownVisible) "숨기기" else "보이기",
                                    tint = when {
                                        !ownVisible -> colors.muted.copy(alpha = 0.45f)
                                        !effVisible -> colors.muted
                                        layer.isGroupStart -> colors.secondary
                                        else -> colors.accent
                                    },
                                    modifier = Modifier.size(19.dp),
                                )
                            }

                            Icon(
                                when {
                                    layer.isGroupStart -> Icons.Rounded.Folder
                                    layer.isTextLayer -> Icons.Rounded.TextFields
                                    else -> Icons.Rounded.Layers
                                },
                                contentDescription = null,
                                tint = if (effVisible) colors.muted else colors.muted.copy(alpha = 0.35f),
                                modifier = Modifier.size(16.dp),
                            )

                            Column(Modifier.weight(1f)) {
                                Text(
                                    (if (layer.clipping) "↳ " else "") + layer.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (layer.isGroupStart) FontWeight.Bold else FontWeight.Medium,
                                    color = if (effVisible) colors.ink else colors.muted.copy(alpha = 0.55f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (!layer.isGroupStart && (layer.blendMode != "norm" || curOp < 100 || layer.isTextLayer)) {
                                    Text(
                                        buildString {
                                            if (layer.isTextLayer) append("TEXT · ")
                                            if (layer.blendMode != "norm") append("${layer.blendMode.trim().uppercase()} · ")
                                            append("$curOp%")
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.muted,
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

@Composable
private fun PresetChip(label: String, tint: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(tint.copy(alpha = 0.16f))
            .border(1.dp, tint.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = tint)
    }
}

private fun PsdImageBuffer.toAndroidBitmap(): Bitmap =
    Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)

private fun saveBitmapToAndroidGallery(context: Context, bitmap: Bitmap, fileName: String) {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/FanboxLibrary")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: throw IOException("갤러리 파일을 생성할 수 없습니다.")
    try {
        resolver.openOutputStream(uri)?.use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw IOException("PNG 인코딩에 실패했습니다.")
            }
        } ?: throw IOException("출력 스트림을 열 수 없습니다.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        throw e
    }
}
