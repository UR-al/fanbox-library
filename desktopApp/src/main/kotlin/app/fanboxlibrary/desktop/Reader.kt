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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Translate
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fanboxlibrary.shared.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

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

private enum class ReaderMode { SINGLE, SPREAD, WEBTOON, COMPARE, ATTACHMENTS }
private enum class VariantFilter(val label: String) {
    ALL("전체"),
    ORIGINAL("원본"),
    TEXTLESS("Textless"),
    BOOKMARKED("♥ 북마크"),
}

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
    unpackPdf: (LibraryAsset) -> Unit = {},
    onDeleteAsset: (LibraryAsset) -> Unit = {},
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    onSaveLastPage: (Int) -> Unit = {},
    onToggleFavoritePost: () -> Unit = {},
    onToggleBookmarkAsset: (LibraryAsset) -> Unit = {},
    onSetCoverAsset: (LibraryAsset) -> Unit = {},
    onMoveAsset: (LibraryAsset, Int) -> Unit = { _, _ -> },
    onSortAssetsNaturally: () -> Unit = {},
    onDeleteWholePost: () -> Unit = {},
    onExportCbz: () -> Unit = {},
    onExportFolder: () -> Unit = {},
) {
    val colors = LocalGalleryColors.current
    val allImages = remember(post) { post.assets.filter { it.isImage } }
    val originalImages = remember(allImages) { allImages.filterNot { it.variant.equals("textless", true) } }
    val textlessImages = remember(allImages) { allImages.filter { it.variant.equals("textless", true) } }
    val bookmarkedImages = remember(allImages, post.bookmarkedAssetIds) { allImages.filter { it.id in post.bookmarkedAssetIds } }
    val hasComparePair = originalImages.isNotEmpty() && textlessImages.isNotEmpty()
    val attachments = remember(post) { post.assets.filterNot { it.isImage } }

    var mode by remember(post.id) {
        mutableStateOf(if (allImages.isNotEmpty()) ReaderMode.SINGLE else ReaderMode.ATTACHMENTS)
    }
    var rtlMode by remember { mutableStateOf(false) }
    var spreadCoverAlone by remember { mutableStateOf(true) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var pendingDeleteWholePost by remember { mutableStateOf(false) }

    var variantFilter by remember(post.id) { mutableStateOf(VariantFilter.ALL) }
    val images = remember(allImages, originalImages, textlessImages, bookmarkedImages, variantFilter) {
        when (variantFilter) {
            VariantFilter.ALL -> allImages
            VariantFilter.ORIGINAL -> originalImages.ifEmpty { allImages }
            VariantFilter.TEXTLESS -> textlessImages.ifEmpty { allImages }
            VariantFilter.BOOKMARKED -> bookmarkedImages.ifEmpty { allImages }
        }
    }
    var page by remember(post.id) {
        mutableStateOf(post.lastReadPage.coerceIn(0, (allImages.size - 1).coerceAtLeast(0)))
    }
    val active = page.coerceIn(0, (images.size - 1).coerceAtLeast(0))

    // Persist reading progress automatically (#5 Resume reading)
    LaunchedEffect(post.id, active, variantFilter) {
        if (variantFilter == VariantFilter.ALL && allImages.size > 1) {
            onSaveLastPage(active)
        }
    }

    fun stepForward() {
        if (images.isEmpty()) return
        page = when (mode) {
            ReaderMode.SPREAD -> {
                val delta = if (spreadCoverAlone && active == 0) 1 else 2
                (active + delta).coerceAtMost(images.lastIndex)
            }
            else -> (active + 1).coerceAtMost(images.lastIndex)
        }
    }

    fun stepBackward() {
        if (images.isEmpty()) return
        page = when (mode) {
            ReaderMode.SPREAD -> {
                if (spreadCoverAlone && active == 1) 0 else (active - 2).coerceAtLeast(0)
            }
            else -> (active - 1).coerceAtLeast(0)
        }
    }

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

    // Offline Uncensored Local AI Comic Translator state
    val translatorScope = rememberCoroutineScope()
    var translatorPanelOpen by remember { mutableStateOf(false) }
    var bubbleOverlayEnabled by remember { mutableStateOf(true) }
    var selectedPreset by remember { mutableStateOf(LocalComicTranslator.selectedPreset()) }
    var serverRunning by remember { mutableStateOf(LocalComicTranslator.isServerRunning()) }
    val pageTranslations = remember(post.id) { mutableStateMapOf<String, PageTranslationResult>() }
    var translatingCurrent by remember { mutableStateOf(false) }
    var batchTranslating by remember { mutableStateOf(false) }
    var batchJob by remember { mutableStateOf<Job?>(null) }
    var translatorStatus by remember { mutableStateOf("") }
    var translatorError by remember { mutableStateOf<String?>(null) }
    var highlightedBubbleIndex by remember(post.id, active) { mutableStateOf<Int?>(null) }

    // Auto-load any previously cached translations from disk for instant zero-latency display!
    LaunchedEffect(post.id, images) {
        if (images.isNotEmpty()) {
            val loaded = withContext(Dispatchers.IO) {
                images.mapNotNull { img ->
                    LocalComicTranslator.loadCachedTranslation(img.sha256)?.let { img.sha256 to it }
                }
            }
            loaded.forEach { (sha, res) -> pageTranslations[sha] = res }
        }
    }

    fun translateSinglePage(targetAsset: LibraryAsset, forceRefresh: Boolean = false) {
        if (translatingCurrent || batchTranslating) return
        translatingCurrent = true
        translatorError = null
        translatorStatus = "로컬 번역 모델 준비 중…"
        translatorScope.launch {
            try {
                val res = LocalComicTranslator.translateComicPage(
                    imageFile = File(targetAsset.path),
                    sha256 = targetAsset.sha256,
                    preset = selectedPreset,
                    forceRefresh = forceRefresh,
                    onStatus = { msg -> translatorStatus = msg },
                )
                pageTranslations[targetAsset.sha256] = res
                bubbleOverlayEnabled = true
                serverRunning = LocalComicTranslator.isServerRunning()
                translatorStatus = if (res.bubbles.isEmpty()) "감지된 말풍선/대사가 없습니다." else "대사 ${res.bubbles.size}개 한국어 번역 완료"
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                translatorError = e.message ?: "로컬 번역 실패"
                translatorStatus = ""
            } finally {
                translatingCurrent = false
                serverRunning = LocalComicTranslator.isServerRunning()
            }
        }
    }

    fun translateAllPagesInWork() {
        if (batchTranslating || translatingCurrent || images.isEmpty()) return
        batchTranslating = true
        translatorError = null
        batchJob = translatorScope.launch {
            try {
                for ((idx, img) in images.withIndex()) {
                    val existing = pageTranslations[img.sha256]
                    if (existing != null && existing.presetId == selectedPreset.id) continue
                    val res = LocalComicTranslator.translateComicPage(
                        imageFile = File(img.path),
                        sha256 = img.sha256,
                        preset = selectedPreset,
                        forceRefresh = false,
                        onStatus = { msg -> translatorStatus = "[${idx + 1}/${images.size}p] $msg" },
                    )
                    pageTranslations[img.sha256] = res
                    serverRunning = LocalComicTranslator.isServerRunning()
                }
                bubbleOverlayEnabled = true
                translatorStatus = "이 작품 전체(${images.size}장) 한국어 번역 완료!"
            } catch (_: CancellationException) {
                translatorStatus = "일괄 번역이 중지되었습니다."
            } catch (e: Exception) {
                translatorError = e.message ?: "일괄 번역 오류"
            } finally {
                batchTranslating = false
                serverRunning = LocalComicTranslator.isServerRunning()
            }
        }
    }

    val currentAsset = images.getOrNull(active)
    val currentTranslation = currentAsset?.let { pageTranslations[it.sha256] }

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
                        if (isFullscreen) { onToggleFullscreen(); true } else { back(); true }
                    }
                    Key.F11, Key.F -> {
                        onToggleFullscreen(); true
                    }
                    Key.T -> {
                        if (!ev.isCtrlPressed && !ev.isAltPressed && currentAsset != null) {
                            if (!isFullscreen && !translatorPanelOpen) translatorPanelOpen = true
                            if (currentTranslation == null) translateSinglePage(currentAsset, forceRefresh = false)
                            else bubbleOverlayEnabled = true
                            true
                        } else false
                    }
                    Key.O -> {
                        if (!ev.isCtrlPressed && !ev.isAltPressed && currentTranslation != null) {
                            bubbleOverlayEnabled = !bubbleOverlayEnabled
                            true
                        } else false
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
                            mode == ReaderMode.SINGLE || mode == ReaderMode.SPREAD -> {
                                if (rtlMode) stepForward() else stepBackward()
                                true // Never accidentally leave current work!
                            }
                            else -> false
                        }
                    }
                    Key.DirectionRight -> {
                        when {
                            jumpWorkModifier && nextPost != null -> { onSelectPost(nextPost.id); true }
                            mode == ReaderMode.SINGLE || mode == ReaderMode.SPREAD -> {
                                if (rtlMode) stepBackward() else stepForward()
                                true // Stay inside current work!
                            }
                            else -> false
                        }
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        // Reader Top Bar (hidden in immersive fullscreen mode)
        if (!isFullscreen) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.sidebar)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = back) { Text("‹ 보관함", color = colors.ink, fontWeight = FontWeight.SemiBold) }

            IconButton(onClick = onToggleFavoritePost, modifier = Modifier.size(30.dp)) {
                Icon(
                    if (post.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = "작품 즐겨찾기",
                    tint = if (post.isFavorite) Color(0xFFFBBF24) else colors.muted,
                    modifier = Modifier.size(19.dp),
                )
            }

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
                if (allImages.size >= 2) {
                    ModeTab("양면 2P", mode == ReaderMode.SPREAD) { mode = ReaderMode.SPREAD }
                }
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

            if (mode == ReaderMode.SINGLE || mode == ReaderMode.SPREAD) {
                ShelfFilter(if (rtlMode) "우→좌 (일본식)" else "좌→우", rtlMode) { rtlMode = !rtlMode }
            }
            if (mode == ReaderMode.SPREAD) {
                ShelfFilter(if (spreadCoverAlone) "표지 1장 ✓" else "표지 2장", spreadCoverAlone) { spreadCoverAlone = !spreadCoverAlone }
            }
            if (allImages.isNotEmpty()) {
                val aiLabel = when {
                    translatingCurrent || batchTranslating -> "AI 번역 중…"
                    currentTranslation != null -> "AI 번역 ✓"
                    else -> "AI 번역 (T)"
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (translatorPanelOpen || currentTranslation != null) colors.accent else colors.surface)
                        .border(1.dp, if (translatorPanelOpen || currentTranslation != null) colors.accent else colors.border, RoundedCornerShape(10.dp))
                        .clickable {
                            translatorPanelOpen = !translatorPanelOpen
                            if (translatorPanelOpen && currentAsset != null && currentTranslation == null && !translatingCurrent && !batchTranslating) {
                                translateSinglePage(currentAsset, forceRefresh = false)
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        Icons.Rounded.Translate,
                        contentDescription = null,
                        tint = if (translatorPanelOpen || currentTranslation != null) Color.White else colors.ink,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        aiLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (translatorPanelOpen || currentTranslation != null) Color.White else colors.ink,
                    )
                }
            }

            if (post.sourceUrl.isNotBlank()) {
                TextButton(onClick = { browse(post.sourceUrl) }) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("원문 열기")
                }
            }
            IconButton(onClick = onToggleFullscreen, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.Fullscreen, contentDescription = "전체화면 (F11)", tint = colors.muted)
            }
            Box {
                IconButton(onClick = { moreMenuOpen = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "더보기", tint = colors.ink)
                }
                DropdownMenu(
                    expanded = moreMenuOpen,
                    onDismissRequest = { moreMenuOpen = false },
                    containerColor = colors.surface,
                ) {
                    DropdownMenuItem(
                        text = { Text("작품 정보 수정 / 묶기", color = colors.ink) },
                        onClick = { moreMenuOpen = false; edit() },
                    )
                    DropdownMenuItem(
                        text = { Text(".cbz 만화책 파일로 내보내기", color = colors.ink) },
                        onClick = { moreMenuOpen = false; onExportCbz() },
                    )
                    DropdownMenuItem(
                        text = { Text("깔끔한 폴더로 내보내기", color = colors.ink) },
                        onClick = { moreMenuOpen = false; onExportFolder() },
                    )
                    DropdownMenuItem(
                        text = { Text("페이지 파일명 순 자동 정렬", color = colors.ink) },
                        onClick = { moreMenuOpen = false; onSortAssetsNaturally() },
                    )
                    HorizontalDivider(color = colors.border)
                    DropdownMenuItem(
                        text = { Text("이 작품 통째 삭제", color = MaterialTheme.colorScheme.error) },
                        onClick = { moreMenuOpen = false; pendingDeleteWholePost = true },
                    )
                }
            }
        }
        HorizontalDivider(color = colors.border)
        }

        // Stage Content + Optional Offline AI Translator Side Panel
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                when (mode) {
                    ReaderMode.SPREAD -> {
                        if (images.isEmpty()) {
                            EmptyReaderMessage("보관된 이미지가 없습니다.")
                        } else {
                            SpreadImageStage(
                                images = images,
                                page = active,
                                coverAlone = spreadCoverAlone,
                                rtl = rtlMode,
                                isFullscreen = isFullscreen,
                                onExitFullscreen = onToggleFullscreen,
                                onPrev = ::stepBackward,
                                onNext = ::stepForward,
                                onJumpPage = { page = it },
                            )
                        }
                    }
                    ReaderMode.ATTACHMENTS -> {
                        AttachmentsPane(
                            attachments = attachments,
                            open = open,
                            export = export,
                            openZip = openZip,
                            openPsd = openPsd,
                            unpackPdf = unpackPdf,
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
                                    hasBookmarks = bookmarkedImages.isNotEmpty(),
                                    isBookmarked = image.id in post.bookmarkedAssetIds,
                                    isCover = post.coverImage()?.id == image.id,
                                    isFullscreen = isFullscreen,
                                    variantFilter = variantFilter,
                                    onVariantFilter = { variantFilter = it },
                                    onPageChange = { page = it },
                                    onToggleBookmark = { onToggleBookmarkAsset(image) },
                                    onSetCover = { onSetCoverAsset(image) },
                                    onMoveEarlier = { onMoveAsset(image, -1); if (active > 0) page = active - 1 },
                                    onMoveLater = { onMoveAsset(image, 1); if (active < images.lastIndex) page = active + 1 },
                                    onExitFullscreen = onToggleFullscreen,
                                    onOpenExternal = { open(image) },
                                    onDeleteCurrent = { pendingDelete = image },
                                    hasSiblingPosts = prevPost != null || nextPost != null,
                                    translation = currentTranslation,
                                    translatedShaSet = pageTranslations.keys,
                                    bubbleOverlayEnabled = bubbleOverlayEnabled,
                                    onToggleBubbleOverlay = { bubbleOverlayEnabled = !bubbleOverlayEnabled },
                                    highlightedBubbleIndex = highlightedBubbleIndex,
                                    translatingStatus = if (translatingCurrent || batchTranslating) translatorStatus.ifEmpty { "AI 번역 중…" } else null,
                                )
                            }
                        }
                    }
                }
            }

            if (translatorPanelOpen && !isFullscreen && allImages.isNotEmpty()) {
                VerticalDivider(color = colors.border)
                ComicTranslatorSidePanel(
                    pageIndex = active,
                    totalPages = images.size,
                    translatedCount = images.count { pageTranslations.containsKey(it.sha256) },
                    selectedPreset = selectedPreset,
                    onSelectPreset = { p ->
                        selectedPreset = p
                        LocalComicTranslator.saveSelectedPreset(p)
                    },
                    serverRunning = serverRunning,
                    onStopServer = {
                        LocalComicTranslator.stopServer()
                        serverRunning = false
                        translatorStatus = "로컬 번역 모델 VRAM 해제 완료"
                    },
                    translatingCurrent = translatingCurrent,
                    batchTranslating = batchTranslating,
                    statusMessage = translatorStatus,
                    errorMessage = translatorError,
                    bubbleOverlayEnabled = bubbleOverlayEnabled,
                    onToggleBubbleOverlay = { bubbleOverlayEnabled = !bubbleOverlayEnabled },
                    currentResult = currentTranslation,
                    highlightedBubbleIndex = highlightedBubbleIndex,
                    onHighlightBubble = { highlightedBubbleIndex = it },
                    onTranslateCurrent = { force -> currentAsset?.let { translateSinglePage(it, forceRefresh = force) } },
                    onTranslateAll = { translateAllPagesInWork() },
                    onCancelBatch = { batchJob?.cancel() },
                    onClose = { translatorPanelOpen = false },
                )
            }
        }
    }

    if (pendingDeleteWholePost) {
        AlertDialog(
            onDismissRequest = { pendingDeleteWholePost = false },
            containerColor = colors.surface,
            titleContentColor = colors.ink,
            textContentColor = colors.ink,
            title = { Text("작품 통째 삭제") },
            text = {
                Text(
                    "‘${post.titleLabel()}’ 작품을 내 보관함에서 삭제할까요?\n(앱 내부 사본만 삭제되며 외부 연결 폴더의 원본 파일은 안전하게 보존됩니다.)",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        pendingDeleteWholePost = false
                        onDeleteWholePost()
                    },
                ) { Text("작품 삭제") }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteWholePost = false }) { Text("취소") } },
        )
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
    hasBookmarks: Boolean,
    isBookmarked: Boolean,
    isCover: Boolean,
    isFullscreen: Boolean,
    variantFilter: VariantFilter,
    onVariantFilter: (VariantFilter) -> Unit,
    onPageChange: (Int) -> Unit,
    onToggleBookmark: () -> Unit,
    onSetCover: () -> Unit,
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
    onExitFullscreen: () -> Unit,
    onOpenExternal: () -> Unit,
    onDeleteCurrent: () -> Unit,
    hasSiblingPosts: Boolean,
    translation: PageTranslationResult? = null,
    translatedShaSet: Set<String> = emptySet(),
    bubbleOverlayEnabled: Boolean = true,
    onToggleBubbleOverlay: () -> Unit = {},
    highlightedBubbleIndex: Int? = null,
    translatingStatus: String? = null,
) {
    val colors = LocalGalleryColors.current
    var scale by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var pixelScale by remember { mutableStateOf(1f) }
    var peekOriginalIndices by remember(asset.sha256) { mutableStateOf(setOf<Int>()) }
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

                // Speech Bubble 2D Korean Overlay (stays pixel-locked across zoom & pan!)
                if (bubbleOverlayEnabled && translation != null) {
                    val dispW = effW * fit * scale
                    val dispH = effH * fit * scale
                    val leftPx = (viewWidth - dispW) / 2f + translated.x
                    val topPx = (viewHeight - dispH) / 2f + translated.y
                    for (b in translation.bubbles) {
                        if (!b.hasValidBox) continue
                        val x1 = b.xmin.coerceIn(0f, 1000f)
                        val y1 = b.ymin.coerceIn(0f, 1000f)
                        val x2 = b.xmax.coerceIn(0f, 1000f)
                        val y2 = b.ymax.coerceIn(0f, 1000f)
                        val bxPx = leftPx + (x1 / 1000f) * dispW
                        val byPx = topPx + (y1 / 1000f) * dispH
                        val bwPx = ((x2 - x1) / 1000f) * dispW
                        val bhPx = ((y2 - y1) / 1000f) * dispH
                        val showOrig = b.index in peekOriginalIndices && b.original.isNotBlank()
                        val displayTxt = if (showOrig) b.original else b.korean
                        val isHi = highlightedBubbleIndex == b.index

                        val areaPerChar = ((bwPx * bhPx) / displayTxt.length.coerceAtLeast(4).toFloat()).coerceAtLeast(64f)
                        val rawFontPx = sqrt(areaPerChar.toDouble()).toFloat() * 0.54f
                        val minFontPx = 11f * density.density
                        val maxFontPx = 23f * density.density
                        val fontSp = with(density) { rawFontPx.coerceIn(minFontPx, maxFontPx).toSp() }
                        val xDp = with(density) { bxPx.toDp() }
                        val yDp = with(density) { byPx.toDp() }
                        val wDp = with(density) { bwPx.toDp() }.coerceAtLeast(44.dp)
                        val hDp = with(density) { bhPx.toDp() }.coerceAtLeast(26.dp)

                        Box(
                            modifier = Modifier
                                .offset(x = xDp, y = yDp)
                                .size(width = wDp, height = hDp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (showOrig) Color(0xEE181C26) else Color(0xF6FFFFFF))
                                .border(
                                    width = if (isHi) 2.dp else 1.dp,
                                    color = if (isHi) colors.accent else Color(0x33000000),
                                    shape = RoundedCornerShape(10.dp),
                                )
                                .clickable {
                                    peekOriginalIndices = if (b.index in peekOriginalIndices) {
                                        peekOriginalIndices - b.index
                                    } else {
                                        peekOriginalIndices + b.index
                                    }
                                }
                                .padding(horizontal = 5.dp, vertical = 3.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = displayTxt,
                                fontSize = fontSp,
                                lineHeight = fontSp * 1.22f,
                                fontWeight = FontWeight.Bold,
                                color = if (showOrig) Color(0xFFF3F4F6) else Color(0xFF12151C),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }

        if (translatingStatus != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 14.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xDD11141C))
                    .border(1.dp, colors.accent.copy(alpha = 0.65f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.accent)
                Text(translatingStatus, fontSize = 11.5.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        } else if (translation != null && translation.bubbles.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xCC11141C))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(18.dp))
                    .clickable(onClick = onToggleBubbleOverlay)
                    .padding(horizontal = 11.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(Icons.Rounded.Translate, null, tint = if (bubbleOverlayEnabled) colors.emerald else Color(0xFFB7C0D1), modifier = Modifier.size(14.dp))
                Text(
                    if (bubbleOverlayEnabled) "말풍선 한글 덮어쓰기 ON (O) · 말풍선 클릭=원문" else "원본 만화 표시 중 (O 키로 한글 켜기)",
                    fontSize = 11.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        // Top-left fullscreen exit chip
        if (isFullscreen) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(14.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xCC11141C))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(20.dp))
                    .clickable(onClick = onExitFullscreen)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Rounded.FullscreenExit, null, tint = Color.White, modifier = Modifier.size(16.dp))
                Text("전체화면 종료 (F11) · ${page + 1}/${images.size}", fontSize = 11.5.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }

        // Top-right quick variant pill if both original and textless or bookmarks exist
        if (hasComparePair || hasBookmarks) {
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

    // Thumbnail Filmstrip (hidden in fullscreen)
    if (!isFullscreen && images.size > 1) {
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
                    if (item.sha256 in translatedShaSet) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(2.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(colors.accent)
                                .padding(horizontal = 3.dp, vertical = 1.dp),
                        ) {
                            Text("번", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        }
    }

    if (isFullscreen) return
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
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            TextButton(onClick = { onPageChange(page - 1) }, enabled = page > 0) { Text("이전") }
            Text(
                "${page + 1} / ${images.size}",
                style = MaterialTheme.typography.labelLarge,
                color = colors.ink,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            TextButton(onClick = { onPageChange(page + 1) }, enabled = page + 1 < images.size) { Text("다음") }

            IconButton(onClick = onToggleBookmark, modifier = Modifier.size(30.dp)) {
                Icon(
                    if (isBookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                    contentDescription = "페이지 북마크",
                    tint = if (isBookmarked) colors.accent else colors.muted,
                    modifier = Modifier.size(18.dp),
                )
            }
            TextButton(onClick = onSetCover) {
                Text(if (isCover) "표지 ✓" else "표지 지정", color = if (isCover) colors.emerald else colors.muted, fontSize = 11.5.sp)
            }
            if (images.size > 1) {
                TextButton(onClick = onMoveEarlier, enabled = page > 0, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("◀순서", fontSize = 11.sp)
                }
                TextButton(onClick = onMoveLater, enabled = page < images.lastIndex, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("순서▶", fontSize = 11.sp)
                }
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
private fun ColumnScope.SpreadImageStage(
    images: List<LibraryAsset>,
    page: Int,
    coverAlone: Boolean,
    rtl: Boolean,
    isFullscreen: Boolean,
    onExitFullscreen: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onJumpPage: (Int) -> Unit,
) {
    val colors = LocalGalleryColors.current
    val singleCover = coverAlone && page == 0
    val first = images[page]
    val second = if (singleCover) null else images.getOrNull(page + 1)

    // In Japanese RTL manga mode, first page (N) is on the RIGHT and second page (N+1) is on the LEFT!
    val leftPage = if (rtl && second != null) second else first
    val rightPage = if (rtl && second != null) first else second

    val leftPreview = loadImage(File(leftPage.path), leftPage.sha256, 2400)
    val rightPreview = rightPage?.let { loadImage(File(it.path), it.sha256, 2400) }

    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .background(colors.cinemaBg),
        contentAlignment = Alignment.Center,
    ) {
        if (singleCover || rightPage == null) {
            if (leftPreview.bitmap != null) {
                Image(
                    bitmap = leftPreview.bitmap,
                    contentDescription = leftPage.name,
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                CircularProgressIndicator(color = colors.accent)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                    if (leftPreview.bitmap != null) {
                        Image(
                            bitmap = leftPreview.bitmap,
                            contentDescription = leftPage.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.CenterEnd,
                        )
                    } else CircularProgressIndicator(color = colors.accent)
                }
                Spacer(Modifier.width(4.dp))
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                    if (rightPreview?.bitmap != null) {
                        Image(
                            bitmap = rightPreview.bitmap,
                            contentDescription = rightPage.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.CenterStart,
                        )
                    } else CircularProgressIndicator(color = colors.accent)
                }
            }
        }

        if (isFullscreen) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(14.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xCC11141C))
                    .clickable(onClick = onExitFullscreen)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Rounded.FullscreenExit, null, tint = Color.White, modifier = Modifier.size(16.dp))
                Text("전체화면 종료 (F11)", fontSize = 11.5.sp, color = Color.White)
            }
        }
    }

    if (!isFullscreen) {
        HorizontalDivider(color = colors.border)
        Row(
            modifier = Modifier.fillMaxWidth().background(colors.sidebar).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onPrev, enabled = page > 0) { Text("이전 펼침") }
            val pageLabel = if (second != null) "${page + 1}–${page + 2} / ${images.size}" else "${page + 1} / ${images.size}"
            Text(pageLabel, style = MaterialTheme.typography.labelLarge, color = colors.ink)
            TextButton(
                onClick = onNext,
                enabled = if (singleCover) images.size > 1 else page + 2 < images.size,
            ) { Text("다음 펼침") }
            Spacer(Modifier.weight(1f))
            Text(
                if (rtl) "일본 만화 방향 (오른쪽 → 왼쪽 · ← 키로 다음 페이지)" else "일반 방향 (왼쪽 → 오른쪽 · → 키로 다음 페이지)",
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
            )
            if (page > 0) {
                TextButton(onClick = { onJumpPage(0) }) { Text("표지로") }
            }
        }
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
    unpackPdf: (LibraryAsset) -> Unit,
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
                } else if (asset.name.endsWith(".pdf", ignoreCase = true)) {
                    Button(
                        onClick = { unpackPdf(asset) },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text("PDF 만화 페이지 풀기")
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

@Composable
private fun ComicTranslatorSidePanel(
    pageIndex: Int,
    totalPages: Int,
    translatedCount: Int,
    selectedPreset: TranslatorModelPreset,
    onSelectPreset: (TranslatorModelPreset) -> Unit,
    serverRunning: Boolean,
    onStopServer: () -> Unit,
    translatingCurrent: Boolean,
    batchTranslating: Boolean,
    statusMessage: String,
    errorMessage: String?,
    bubbleOverlayEnabled: Boolean,
    onToggleBubbleOverlay: () -> Unit,
    currentResult: PageTranslationResult?,
    highlightedBubbleIndex: Int?,
    onHighlightBubble: (Int?) -> Unit,
    onTranslateCurrent: (forceRefresh: Boolean) -> Unit,
    onTranslateAll: () -> Unit,
    onCancelBatch: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalGalleryColors.current
    val busy = translatingCurrent || batchTranslating
    Column(
        modifier = Modifier
            .width(340.dp)
            .fillMaxHeight()
            .background(colors.sidebar)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Rounded.Translate, null, tint = colors.accent, modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f)) {
                Text("무검열 로컬 만화 번역", style = MaterialTheme.typography.titleSmall, color = colors.ink, fontWeight = FontWeight.Bold)
                Text(
                    "100% 오프라인 · 클라우드 검열 없음 (${translatedCount}/${totalPages}p 완료)",
                    fontSize = 10.5.sp,
                    color = colors.muted,
                )
            }
            if (serverRunning) {
                TextButton(
                    onClick = onStopServer,
                    enabled = !busy,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text("VRAM 해제", fontSize = 10.5.sp, color = colors.secondary)
                }
            }
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Rounded.Close, contentDescription = "번역 패널 닫기", tint = colors.muted, modifier = Modifier.size(17.dp))
            }
        }

        // Model Preset Switcher (4B High-Quality vs 2B Ultra-Compact)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text("내장 번역 모델 선택", fontSize = 11.sp, color = colors.muted, fontWeight = FontWeight.SemiBold)
            TranslatorModelPreset.entries.forEach { preset ->
                val isSelected = preset == selectedPreset
                val installed = remember(preset, serverRunning, translatingCurrent) {
                    LocalComicTranslator.isPresetInstalled(preset)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) colors.accentSoft else Color.Transparent)
                        .border(
                            1.dp,
                            if (isSelected) colors.accent.copy(alpha = 0.6f) else Color.Transparent,
                            RoundedCornerShape(8.dp),
                        )
                        .clickable(enabled = !busy) { onSelectPreset(preset) }
                        .padding(horizontal = 9.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            preset.title,
                            fontSize = 11.5.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = colors.ink,
                        )
                        Text(preset.subtitle, fontSize = 10.sp, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        if (installed) "내장됨 ✓" else "자동설치",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (installed) colors.emerald else colors.muted,
                    )
                }
            }
        }

        // Primary Actions: Current Page + Batch All Pages
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Button(
                onClick = { onTranslateCurrent(currentResult != null) },
                enabled = !busy,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(9.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Icon(
                    if (currentResult != null) Icons.Rounded.Refresh else Icons.Rounded.AutoAwesome,
                    null,
                    Modifier.size(15.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(if (currentResult != null) "현재 페이지 재번역" else "현재 페이지 번역 (T)", fontSize = 11.5.sp)
            }
            if (totalPages > 1) {
                if (batchTranslating) {
                    OutlinedButton(
                        onClick = onCancelBatch,
                        shape = RoundedCornerShape(9.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Text("일괄 중지", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    OutlinedButton(
                        onClick = onTranslateAll,
                        enabled = !busy,
                        shape = RoundedCornerShape(9.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Text("전체 ${totalPages}장 번역", fontSize = 11.5.sp)
                    }
                }
            }
        }

        // Bubble Overlay Switch
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(colors.surface)
                .clickable { onToggleBubbleOverlay() }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("말풍선 위 한글 직접 덮어쓰기 (O)", fontSize = 11.5.sp, color = colors.ink, fontWeight = FontWeight.SemiBold)
                Text("확대·이동 연동 · 말풍선 클릭 시 원문 확인", fontSize = 10.sp, color = colors.muted)
            }
            Switch(
                checked = bubbleOverlayEnabled,
                onCheckedChange = { onToggleBubbleOverlay() },
                modifier = Modifier.height(24.dp),
            )
        }

        if (statusMessage.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceElevated)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.accent)
                }
                Text(statusMessage, fontSize = 11.sp, color = colors.ink)
            }
        }

        if (errorMessage != null) {
            Text(
                errorMessage,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
                    .padding(8.dp),
            )
        }

        HorizontalDivider(color = colors.border)

        Text(
            "${pageIndex + 1}페이지 인식 대사 (${currentResult?.bubbles?.size ?: 0}개)",
            style = MaterialTheme.typography.labelMedium,
            color = colors.muted,
        )

        if (currentResult == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (busy) "만화 말풍선을 분석하고 있습니다…" else "‘현재 페이지 번역’ 또는 키보드 T 키를 누르면\n말풍선을 인식해 즉시 한국어로 덮어씁니다.",
                    fontSize = 12.sp,
                    color = colors.muted,
                    textAlign = TextAlign.Center,
                )
            }
        } else if (currentResult.bubbles.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("이 페이지에서 감지된 대사가 없습니다.", fontSize = 12.sp, color = colors.muted)
            }
        } else {
            SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(currentResult.bubbles, key = { it.index }) { b ->
                        val isHi = highlightedBubbleIndex == b.index
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isHi) colors.accentSoft else colors.surface)
                                .border(
                                    1.dp,
                                    if (isHi) colors.accent else colors.border,
                                    RoundedCornerShape(10.dp),
                                )
                                .clickable {
                                    onHighlightBubble(if (isHi) null else b.index)
                                }
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(colors.accent)
                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                                ) {
                                    Text("#${b.index}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                                if (!b.hasValidBox) {
                                    Text("좌표 없음 (패널 대사)", fontSize = 10.sp, color = colors.muted)
                                }
                            }
                            Text(
                                b.korean,
                                fontSize = 13.sp,
                                lineHeight = 18.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.ink,
                            )
                            if (b.original.isNotBlank()) {
                                Text(
                                    b.original,
                                    fontSize = 11.sp,
                                    lineHeight = 15.5.sp,
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
