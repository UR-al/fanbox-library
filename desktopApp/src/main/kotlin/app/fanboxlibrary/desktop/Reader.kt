package app.fanboxlibrary.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ViewColumn
import androidx.compose.material.icons.rounded.ViewDay
import androidx.compose.material.icons.rounded.ViewStream
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fanboxlibrary.shared.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class Preview(
    val bitmap: ImageBitmap? = null,
    val width: Int = 0,
    val height: Int = 0,
    val loading: Boolean = false,
)

private val decodeThreads = Dispatchers.IO.limitedParallelism(4)

private val localPreviewCache = java.util.Collections.synchronizedMap(
    object : LinkedHashMap<String, Preview>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Preview>?): Boolean = size > 48
    },
)

private fun cacheKey(file: File, hash: String, edge: Int) = "${file.path}|$hash|$edge"

private fun decode(file: File, hash: String, edge: Int): Preview {
    val k = cacheKey(file, hash, edge)
    localPreviewCache[k]?.let { return it }
    if (!file.isFile) return Preview()
    ImageIO.createImageInputStream(file)?.use { stream ->
        val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: return Preview()
        try {
            reader.setInput(stream, true, true)
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            val sample = ceil(max(width, height).toDouble() / edge).toInt().coerceAtLeast(1)
            val options = reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) }
            val result = Preview(reader.read(0, options).toComposeImageBitmap(), width, height)
            localPreviewCache[k] = result
            return result
        } finally {
            reader.dispose()
        }
    }
    return Preview()
}

private suspend fun prefetchImage(asset: LibraryAsset, edge: Int) {
    val file = File(asset.path)
    val k = cacheKey(file, asset.sha256, edge)
    if (localPreviewCache.containsKey(k)) return
    try {
        withContext(decodeThreads) { decode(file, asset.sha256, edge) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
    }
}

@Composable
internal fun loadImage(file: File, hash: String, edge: Int): Preview = key(file.path, hash, edge) {
    val cached = remember(file.path, hash, edge) { localPreviewCache[cacheKey(file, hash, edge)] }
    val preview by produceState(cached ?: Preview(loading = true)) {
        if (value.bitmap == null) {
            value = try {
                withContext(decodeThreads) { decode(file, hash, edge) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Preview()
            }
        }
    }
    preview
}

private enum class ReaderMode { SINGLE, WEBTOON, COMPARE, ATTACHMENTS }
private enum class VariantFilter(val label: String) { ALL("전체"), ORIGINAL("원본"), TEXTLESS("Textless") }

private class FractionClipShape(private val fraction: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val width = (size.width * fraction.coerceIn(0f, 1f))
        return Outline.Rectangle(Rect(0f, 0f, width, size.height))
    }
}

@Composable
internal fun PostReader(
    post: LibraryPost,
    visiblePosts: List<LibraryPost>,
    onSelectPost: (String) -> Unit,
    enabled: Boolean,
    back: () -> Unit,
    edit: () -> Unit,
    browse: (String) -> Unit,
    open: (LibraryAsset) -> Unit,
    export: (LibraryAsset) -> Unit,
    openZip: (LibraryAsset) -> Unit = {},
    openPsd: (LibraryAsset) -> Unit = {},
    onDeleteAsset: (LibraryAsset) -> Unit = {},
) {
    val colors = LocalGalleryColors.current
    val allImages = remember(post) { post.assets.filter { it.isImage } }
    val originalImages = remember(allImages) { allImages.filterNot { it.variant.equals("textless", true) } }
    val textlessImages = remember(allImages) { allImages.filter { it.variant.equals("textless", true) } }
    val hasComparePair = originalImages.isNotEmpty() && textlessImages.isNotEmpty()
    val attachments = remember(post) { post.assets.filterNot { it.isImage } }

    var mode by remember(post.id) {
        mutableStateOf(if (allImages.isNotEmpty()) ReaderMode.SINGLE else ReaderMode.ATTACHMENTS)
    }
    var variantFilter by remember(post.id) { mutableStateOf(VariantFilter.ALL) }
    val images = remember(allImages, originalImages, textlessImages, variantFilter) {
        when (variantFilter) {
            VariantFilter.ALL -> allImages
            VariantFilter.ORIGINAL -> originalImages.ifEmpty { allImages }
            VariantFilter.TEXTLESS -> textlessImages.ifEmpty { allImages }
        }
    }
    var page by remember(post.id, variantFilter) { mutableStateOf(0) }
    val active = page.coerceIn(0, (images.size - 1).coerceAtLeast(0))

    val postIndex = remember(visiblePosts, post.id) { visiblePosts.indexOfFirst { it.id == post.id } }
    val prevPost = if (postIndex > 0) visiblePosts[postIndex - 1] else null
    val nextPost = if (postIndex in 0 until visiblePosts.lastIndex) visiblePosts[postIndex + 1] else null

    val focus = remember { FocusRequester() }
    LaunchedEffect(post.id) { focus.requestFocus() }

    // Eagerly prefetch adjacent pages so flipping pages is instantaneous without stutter!
    LaunchedEffect(post.id, active, images) {
        if (images.isNotEmpty()) {
            images.getOrNull(active + 1)?.let { prefetchImage(it, 2600) }
            images.getOrNull(active - 1)?.let { prefetchImage(it, 2600) }
            images.getOrNull(active + 2)?.let { prefetchImage(it, 2600) }
        }
    }

    var pendingDelete by remember { mutableStateOf<LibraryAsset?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .focusRequester(focus)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val jumpWorkModifier = ev.isCtrlPressed || ev.isShiftPressed
                when (ev.key) {
                    Key.Escape -> {
                        back(); true
                    }
                    Key.PageUp, Key.LeftBracket -> {
                        if (prevPost != null) { onSelectPost(prevPost.id); true } else false
                    }
                    Key.PageDown, Key.RightBracket -> {
                        if (nextPost != null) { onSelectPost(nextPost.id); true } else false
                    }
                    Key.DirectionLeft -> {
                        when {
                            jumpWorkModifier && prevPost != null -> { onSelectPost(prevPost.id); true }
                            mode == ReaderMode.SINGLE -> {
                                if (active > 0) { page = active - 1; true } else true // Never accidentally leave current work!
                            }
                            else -> false
                        }
                    }
                    Key.DirectionRight -> {
                        when {
                            jumpWorkModifier && nextPost != null -> { onSelectPost(nextPost.id); true }
                            mode == ReaderMode.SINGLE -> {
                                if (active < images.lastIndex) { page = active + 1; true } else true // Stay on last image!
                            }
                            else -> false
                        }
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        // Reader Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.sidebar)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = back) { Text("‹ 보관함", color = colors.ink, fontWeight = FontWeight.SemiBold) }

            if (visiblePosts.size > 1 && postIndex >= 0) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { prevPost?.let { onSelectPost(it.id) } },
                        enabled = prevPost != null,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(Icons.Rounded.ChevronLeft, contentDescription = "이전 작품", modifier = Modifier.size(18.dp))
                    }
                    Text(
                        "작품 ${postIndex + 1}/${visiblePosts.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.muted,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    IconButton(
                        onClick = { nextPost?.let { onSelectPost(it.id) } },
                        enabled = nextPost != null,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(Icons.Rounded.ChevronRight, contentDescription = "다음 작품", modifier = Modifier.size(18.dp))
                    }
                }
            }

            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    post.titleLabel(),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${post.artistLabel()} · ${post.yearLabel()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Mode Tabs
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ModeTab("단일", mode == ReaderMode.SINGLE) { mode = ReaderMode.SINGLE }
                ModeTab("웹툰", mode == ReaderMode.WEBTOON) { mode = ReaderMode.WEBTOON }
                if (hasComparePair) {
                    ModeTab("1:1 비교", mode == ReaderMode.COMPARE, accentColor = colors.emerald) {
                        mode = ReaderMode.COMPARE
                    }
                }
                ModeTab("첨부 ${attachments.size}", mode == ReaderMode.ATTACHMENTS) {
                    mode = ReaderMode.ATTACHMENTS
                }
            }

            if (post.sourceUrl.isNotBlank()) {
                TextButton(onClick = { browse(post.sourceUrl) }) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("원문 열기")
                }
            }
            OutlinedButton(
                onClick = edit,
                enabled = enabled && post.assets.isNotEmpty(),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, colors.border),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text("작품으로 묶기", style = MaterialTheme.typography.labelLarge)
            }
        }
        HorizontalDivider(color = colors.border)

        // Stage Content
        when (mode) {
            ReaderMode.ATTACHMENTS -> {
                AttachmentsPane(
                    attachments = attachments,
                    open = open,
                    export = export,
                    openZip = openZip,
                    openPsd = openPsd,
                    onDelete = { pendingDelete = it },
                )
            }
            ReaderMode.WEBTOON -> {
                if (images.isEmpty()) {
                    EmptyReaderMessage("보관된 이미지가 없습니다.")
                } else {
                    WebtoonPane(
                        images = images,
                        nextPost = nextPost,
                        onNextPost = { nextPost?.let { onSelectPost(it.id) } },
                        open = open,
                    )
                }
            }
            ReaderMode.COMPARE -> {
                if (!hasComparePair) {
                    EmptyReaderMessage("비교할 원본과 Textless 이미지가 모두 필요합니다.")
                } else {
                    ComparePane(
                        originals = originalImages,
                        textless = textlessImages,
                        open = open,
                    )
                }
            }
            ReaderMode.SINGLE -> {
                if (images.isEmpty()) {
                    EmptyReaderMessage("보관된 이미지가 없습니다.")
                } else {
                    val image = images[active]
                    key(image.id, image.sha256) {
                        SingleImageStage(
                            asset = image,
                            images = images,
                            page = active,
                            hasComparePair = hasComparePair,
                            variantFilter = variantFilter,
                            onVariantFilter = { variantFilter = it },
                            onPageChange = { page = it },
                            onOpenExternal = { open(image) },
                            onDeleteCurrent = { pendingDelete = image },
                            hasSiblingPosts = prevPost != null || nextPost != null,
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { assetToDelete ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = colors.surface,
            titleContentColor = colors.ink,
            textContentColor = colors.ink,
            title = { Text("파일 삭제") },
            text = {
                Text(
                    "‘${assetToDelete.name}’ 항목을 이 작품에서 삭제할까요?\n(외부 연결 폴더의 원본 파일은 안전하게 보존되며 보관함 목록에서만 제거됩니다.)",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        pendingDelete = null
                        onDeleteAsset(assetToDelete)
                    },
                ) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("취소") } },
        )
    }
}

@Composable
private fun ModeTab(
    label: String,
    selected: Boolean,
    accentColor: Color = LocalGalleryColors.current.accent,
    onClick: () -> Unit,
) {
    val colors = LocalGalleryColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) accentColor else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Color.White else colors.muted,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
private fun EmptyReaderMessage(text: String) {
    val colors = LocalGalleryColors.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = colors.muted, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ColumnScope.SingleImageStage(
    asset: LibraryAsset,
    images: List<LibraryAsset>,
    page: Int,
    hasComparePair: Boolean,
    variantFilter: VariantFilter,
    onVariantFilter: (VariantFilter) -> Unit,
    onPageChange: (Int) -> Unit,
    onOpenExternal: () -> Unit,
    onDeleteCurrent: () -> Unit,
    hasSiblingPosts: Boolean,
) {
    val colors = LocalGalleryColors.current
    var scale by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var pixelScale by remember { mutableStateOf(1f) }
    // Fast 2600px cached decode by default; seamlessly upgrades to 4608px when zoomed in!
    val targetEdge = if (scale > 1.55f) 4608 else 2600
    val preview = loadImage(File(asset.path), asset.sha256, targetEdge)
    val fallbackThumb = if (preview.bitmap == null) loadImage(File(asset.path), asset.sha256, 520) else preview

    fun magnify(new: Float) {
        scale = new.coerceIn(1f, max(12f, pixelScale))
        if (scale == 1f) pan = Offset.Zero
    }

    BoxWithConstraints(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .clipToBounds()
            .background(colors.cinemaBg)
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                if (delta != 0f) {
                    val factor = if (delta < 0f) 1.18f else 1f / 1.18f
                    magnify(scale * factor)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = preview.bitmap ?: fallbackThumb.bitmap
        val effW = if (preview.bitmap != null) preview.width else fallbackThumb.width
        val effH = if (preview.bitmap != null) preview.height else fallbackThumb.height
        if (bitmap == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (preview.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), color = colors.accent)
                    Text("이미지를 여는 중…", color = Color(0xFFD0D6E2))
                } else {
                    Text("미리보기를 열 수 없습니다. 원본 열기를 이용하세요.", color = Color(0xFFD0D6E2))
                }
            }
        } else {
            val density = LocalDensity.current
            val viewWidth = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
            val viewHeight = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
            val fit = min(viewWidth / effW.coerceAtLeast(1), viewHeight / effH.coerceAtLeast(1))
            LaunchedEffect(fit) { pixelScale = 1f / fit }
            val boundX = max(0f, (effW * fit * scale - viewWidth) / 2f)
            val boundY = max(0f, (effH * fit * scale - viewHeight) / 2f)
            val translated = Offset(pan.x.coerceIn(-boundX, boundX), pan.y.coerceIn(-boundY, boundY))

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(scale, boundX, boundY) {
                        detectDragGestures { change, amount ->
                            change.consume()
                            pan = Offset(
                                (pan.x.coerceIn(-boundX, boundX) + amount.x).coerceIn(-boundX, boundX),
                                (pan.y.coerceIn(-boundY, boundY) + amount.y).coerceIn(-boundY, boundY),
                            )
                        }
                    }
                    .pointerInput(scale) {
                        detectTapGestures(
                            onDoubleTap = { magnify(if (scale > 1.1f) 1f else 2.2f) },
                        )
                    },
            ) {
                Image(
                    bitmap = bitmap,
                    contentDescription = asset.name,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = translated.x
                            translationY = translated.y
                        },
                    contentScale = ContentScale.Fit,
                )
            }
        }

        // Top-right quick variant pill if both original and textless exist
        if (hasComparePair) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(14.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xCC11141C))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(20.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                VariantFilter.entries.forEach { f ->
                    val activeFilter = f == variantFilter
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (activeFilter) colors.emerald else Color.Transparent)
                            .clickable { onVariantFilter(f) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            f.label,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (activeFilter) Color.White else Color(0xFFB7C0D1),
                        )
                    }
                }
            }
        }
    }

    // Thumbnail Filmstrip
    if (images.size > 1) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.sidebar)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(images, key = { _, it -> it.id }) { idx, item ->
                val thumb = loadImage(File(item.path), item.sha256, 160)
                val isCurrent = idx == page
                Box(
                    modifier = Modifier
                        .size(width = 48.dp, height = 56.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(colors.surfaceElevated)
                        .border(
                            width = if (isCurrent) 2.dp else 1.dp,
                            color = if (isCurrent) colors.accent else colors.border,
                            shape = RoundedCornerShape(7.dp),
                        )
                        .clickable { onPageChange(idx) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (thumb.bitmap != null) {
                        Image(
                            bitmap = thumb.bitmap,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Text("${idx + 1}", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                    }
                    if (item.variant.equals("textless", true)) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(2.dp)
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(colors.emerald),
                        )
                    }
                }
            }
        }
    }

    HorizontalDivider(color = colors.border)

    // Bottom Control Bar
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.sidebar)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = { onPageChange(page - 1) }, enabled = page > 0) { Text("이전") }
            Text(
                "${page + 1} / ${images.size}",
                style = MaterialTheme.typography.labelLarge,
                color = colors.ink,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            TextButton(onClick = { onPageChange(page + 1) }, enabled = page + 1 < images.size) { Text("다음") }

            if (hasSiblingPosts) {
                Text(
                    "← → 페이지 이동 · Ctrl+←→ / PgUp·PgDn 작품 전환",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }

            Spacer(Modifier.weight(1f))
            TextButton(onClick = { magnify(scale / 1.4f) }, enabled = scale > 1f) { Text("축소") }
            Text(
                "${(scale * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = colors.muted,
                modifier = Modifier.widthIn(min = 42.dp),
            )
            TextButton(onClick = { magnify(scale * 1.4f) }, enabled = !preview.loading && preview.bitmap != null) {
                Text("확대")
            }
            TextButton(onClick = { magnify(1f) }) { Text("맞춤") }
            TextButton(onClick = { magnify(pixelScale) }, enabled = pixelScale > 1f) { Text("1:1") }
            TextButton(onClick = onOpenExternal) { Text("원본 열기") }
            TextButton(
                onClick = onDeleteCurrent,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(16.dp))
                Spacer(Modifier.width(3.dp))
                Text("삭제")
            }
        }
        Text(
            "${asset.name} · ${asset.variant} · ${formatSize(asset.size)}",
            style = MaterialTheme.typography.labelSmall,
            color = colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 10.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun ColumnScope.WebtoonPane(
    images: List<LibraryAsset>,
    nextPost: LibraryPost?,
    onNextPost: () -> Unit,
    open: (LibraryAsset) -> Unit,
) {
    val colors = LocalGalleryColors.current
    LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth().background(colors.cinemaBg),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(vertical = 20.dp, horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(images, key = { _, it -> it.id }) { idx, asset ->
            val preview = loadImage(File(asset.path), asset.sha256, 2048)
            Column(
                modifier = Modifier.widthIn(max = 980.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "${idx + 1} / ${images.size} · ${asset.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF9AA3B5),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { open(asset) }) { Text("원본 열기", fontSize = 11.sp) }
                }
                if (preview.bitmap != null) {
                    val ratio = if (preview.height > 0) preview.width.toFloat() / preview.height.toFloat() else 0.75f
                    Image(
                        bitmap = preview.bitmap,
                        contentDescription = asset.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(ratio.coerceIn(0.25f, 3f))
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (preview.loading) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = colors.accent)
                        else Text("이미지를 불러올 수 없습니다.", color = colors.muted)
                    }
                }
            }
        }
        if (nextPost != null) {
            item {
                Button(
                    onClick = onNextPost,
                    modifier = Modifier.padding(top = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("다음 작품: ${nextPost.titleLabel()}  →")
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.ComparePane(
    originals: List<LibraryAsset>,
    textless: List<LibraryAsset>,
    open: (LibraryAsset) -> Unit,
) {
    val colors = LocalGalleryColors.current
    var leftIndex by remember(originals) { mutableStateOf(0) }
    var rightIndex by remember(textless) { mutableStateOf(0) }
    var sideBySide by remember { mutableStateOf(false) }
    var splitFraction by remember { mutableStateOf(0.5f) }

    val leftAsset = originals[leftIndex.coerceIn(0, originals.lastIndex)]
    val rightAsset = textless[rightIndex.coerceIn(0, textless.lastIndex)]
    val leftPreview = loadImage(File(leftAsset.path), leftAsset.sha256, 3072)
    val rightPreview = loadImage(File(rightAsset.path), rightAsset.sha256, 3072)

    // Compare Toolbar
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Compare, null, tint = colors.emerald, modifier = Modifier.size(18.dp))
        Text("원본 vs Textless 1:1 비교", style = MaterialTheme.typography.titleSmall, color = colors.ink)
        Spacer(Modifier.width(8.dp))

        if (originals.size > 1) {
            Text("원본:", style = MaterialTheme.typography.labelSmall, color = colors.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                originals.indices.take(8).forEach { i ->
                    ShelfFilter("${i + 1}", i == leftIndex) { leftIndex = i }
                }
            }
        }
        if (textless.size > 1) {
            Text("Textless:", style = MaterialTheme.typography.labelSmall, color = colors.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                textless.indices.take(8).forEach { i ->
                    ShelfFilter("T${i + 1}", i == rightIndex) { rightIndex = i }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(
            onClick = { sideBySide = !sideBySide },
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(
                if (sideBySide) Icons.Rounded.ViewDay else Icons.Rounded.ViewColumn,
                null,
                Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(if (sideBySide) "슬라이더 겹쳐보기" else "1:1 나란히 보기")
        }
    }
    HorizontalDivider(color = colors.border)

    if (sideBySide) {
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth().background(colors.cinemaBg).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CompareSideCard("원본 · ${leftAsset.name}", leftPreview, colors.accent, { open(leftAsset) }, Modifier.weight(1f))
            CompareSideCard("Textless · ${rightAsset.name}", rightPreview, colors.emerald, { open(rightAsset) }, Modifier.weight(1f))
        }
    } else {
        // Interactive Split Slider Overlay
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(colors.cinemaBg)
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        splitFraction = (change.position.x / w).coerceIn(0.02f, 0.98f)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        splitFraction = (offset.x / w).coerceIn(0.02f, 0.98f)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (rightPreview.bitmap != null) {
                Image(
                    bitmap = rightPreview.bitmap,
                    contentDescription = rightAsset.name,
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            if (leftPreview.bitmap != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(FractionClipShape(splitFraction)),
                ) {
                    Image(
                        bitmap = leftPreview.bitmap,
                        contentDescription = leftAsset.name,
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            }

            // Vertical divider line + handle
            val xOffset = maxWidth * splitFraction
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = xOffset - 1.5.dp)
                    .fillMaxHeight()
                    .width(3.dp)
                    .background(Color.White.copy(alpha = 0.9f)),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = xOffset - 18.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(colors.accent)
                    .border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("↔", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }

            // Corner badges
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xCC11141C))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text("원본 · ${leftAsset.name}", color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xCC059669))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text("TEXTLESS · ${rightAsset.name}", color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun CompareSideCard(
    label: String,
    preview: Preview,
    accent: Color,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGalleryColors.current
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.ink, style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = onOpen) { Text("원본 열기", fontSize = 11.sp) }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(colors.cinemaBg), contentAlignment = Alignment.Center) {
            if (preview.bitmap != null) {
                Image(preview.bitmap, label, Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit)
            } else if (preview.loading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = accent)
            } else {
                Text("미리보기 없음", color = colors.muted)
            }
        }
    }
}

@Composable
private fun ColumnScope.AttachmentsPane(
    attachments: List<LibraryAsset>,
    open: (LibraryAsset) -> Unit,
    export: (LibraryAsset) -> Unit,
    openZip: (LibraryAsset) -> Unit,
    openPsd: (LibraryAsset) -> Unit,
    onDelete: (LibraryAsset) -> Unit,
) {
    val colors = LocalGalleryColors.current
    if (attachments.isEmpty()) {
        EmptyReaderMessage("첨부파일이 없습니다.")
        return
    }
    LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(attachments, key = { it.id }) { asset ->
            val ext = asset.name.substringAfterLast('.', "").uppercase().ifBlank { "FILE" }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.secondarySoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(ext.take(4), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = colors.secondary)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        asset.name,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.ink,
                    )
                    Text(
                        "${asset.variant} · ${formatSize(asset.size)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                    )
                }
                if (ZipExplorer.isZipFile(asset.name)) {
                    Button(
                        onClick = { openZip(asset) },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text("ZIP 탐색")
                    }
                } else if (ZipExplorer.isPsdFile(asset.name)) {
                    Button(
                        onClick = { openPsd(asset) },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.emerald),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text("PSD 레이어 편집")
                    }
                }
                TextButton(onClick = { export(asset) }) {
                    Icon(Icons.Rounded.Download, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("다른 이름으로 저장")
                }
                if (canOpen(asset)) {
                    OutlinedButton(
                        onClick = { open(asset) },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, colors.border),
                    ) {
                        Text("열기")
                    }
                }
                IconButton(onClick = { onDelete(asset) }) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "삭제", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
