package app.fanboxlibrary.android

import android.content.Context
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fanboxlibrary.shared.FanboxLinks
import app.fanboxlibrary.shared.LibraryAsset
import app.fanboxlibrary.shared.LibraryPost
import app.fanboxlibrary.shared.PostDraft
import app.fanboxlibrary.shared.UpdateCheckResult
import app.fanboxlibrary.shared.ZipExplorer
import coil.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.launch

private enum class MobileSmartFilter(val label: String) {
    ALL("전체"),
    TEXTLESS("Textless"),
    ATTACHMENTS("PSD·첨부"),
}

private enum class PostDetailTab(val label: String) {
    ALL("전체"),
    ORIGINAL("원본"),
    TEXTLESS("Textless"),
    ATTACHMENTS("첨부"),
}

private enum class MainTab(val label: String) {
    FANBOX("FANBOX"),
    LIBRARY("내 보관함"),
    SYNC("기기 연결"),
}

private data class ActivePsdSession(val file: File, val displayName: String)

@Composable
internal fun ArchiveApp(model: ArchiveModel) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("fanbox_ui_prefs", Context.MODE_PRIVATE) }
    var darkTheme by rememberSaveable { mutableStateOf(prefs.getBoolean("dark_theme", true)) }

    var activeTabName by rememberSaveable { mutableStateOf(MainTab.FANBOX.name) }
    val activeTab = runCatching { MainTab.valueOf(activeTabName) }.getOrDefault(MainTab.FANBOX)
    var postId by rememberSaveable { mutableStateOf<String?>(null) }
    var readerId by rememberSaveable { mutableStateOf<String?>(null) }
    var activeZipAssetId by rememberSaveable { mutableStateOf<String?>(null) }
    var activePsd by remember { mutableStateOf<ActivePsdSession?>(null) }
    var fanboxFullscreen by remember { mutableStateOf(false) }
    var showUpdateDialog by rememberSaveable { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateCheckResult?>(null) }

    // Shared shelf filter state so swiping works follows the user's current list
    var artist by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var smartFilterName by rememberSaveable { mutableStateOf(MobileSmartFilter.ALL.name) }
    val smartFilter = runCatching { MobileSmartFilter.valueOf(smartFilterName) }.getOrDefault(MobileSmartFilter.ALL)

    val visiblePosts = remember(model.posts, artist, search, smartFilter) {
        model.posts
            .filter { p ->
                val matchesSmart = when (smartFilter) {
                    MobileSmartFilter.ALL -> true
                    MobileSmartFilter.TEXTLESS -> p.hasTextless()
                    MobileSmartFilter.ATTACHMENTS -> p.attachmentCount() > 0
                }
                val matchesArtist = artist.isEmpty() || p.artist == artist
                val matchesSearch = search.isBlank() ||
                    p.label.contains(search, true) ||
                    p.artist.contains(search, true) ||
                    p.assets.any { it.name.contains(search, true) }
                matchesSmart && matchesArtist && matchesSearch
            }
            .sortedWith(compareByDescending<LibraryPost> { it.year ?: 0 }.thenBy { it.label })
    }

    LaunchedEffect(model.lastImportTick) {
        if (model.lastImportTick > 0) {
            activeTabName = MainTab.LIBRARY.name
        }
    }

    val post = model.posts.find { it.id == postId }
    val snackbar = remember { SnackbarHostState() }
    val notice = model.notice
    LaunchedEffect(notice) {
        if (notice.isNotEmpty()) {
            snackbar.showSnackbar(notice, withDismissAction = true)
            if (model.notice == notice) model.notice = ""
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        model.addDocuments(uris)
    }

    fun toggleTheme() {
        darkTheme = !darkTheme
        prefs.edit().putBoolean("dark_theme", darkTheme).apply()
    }

    fun back() {
        when {
            model.importing -> model.cancelImport()
            activePsd != null -> activePsd = null
            activeZipAssetId != null -> activeZipAssetId = null
            readerId != null -> readerId = null
            activeTab == MainTab.LIBRARY && postId != null -> postId = null
            activeTab == MainTab.SYNC -> {
                model.refresh()
                activeTabName = MainTab.LIBRARY.name
            }
            activeTab != MainTab.FANBOX -> activeTabName = MainTab.FANBOX.name
        }
    }

    BackHandler(enabled = model.importing || activePsd != null || activeZipAssetId != null || readerId != null || postId != null || activeTab != MainTab.FANBOX) {
        back()
    }

    val showBottomBar = !model.importing && readerId == null && activeZipAssetId == null && activePsd == null && !fanboxFullscreen

    FanboxAndroidTheme(darkTheme = darkTheme) {
        val colors = LocalAndroidGalleryColors.current
        Scaffold(
            containerColor = colors.bg,
            snackbarHost = { SnackbarHost(snackbar) },
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar(
                        containerColor = colors.surface,
                        tonalElevation = 0.dp,
                    ) {
                        NavigationBarItem(
                            selected = activeTab == MainTab.FANBOX,
                            onClick = { activeTabName = MainTab.FANBOX.name },
                            icon = { Icon(Icons.Rounded.Explore, contentDescription = MainTab.FANBOX.label) },
                            label = { Text(MainTab.FANBOX.label, fontWeight = FontWeight.SemiBold) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = colors.accent,
                                selectedTextColor = colors.accent,
                                indicatorColor = colors.accentSoft,
                                unselectedIconColor = colors.muted,
                                unselectedTextColor = colors.muted,
                            ),
                        )
                        NavigationBarItem(
                            selected = activeTab == MainTab.LIBRARY,
                            onClick = { activeTabName = MainTab.LIBRARY.name },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (model.posts.isNotEmpty()) {
                                            Badge(containerColor = colors.accent, contentColor = Color.White) {
                                                Text("${model.posts.size}")
                                            }
                                        }
                                    },
                                ) {
                                    Icon(Icons.Rounded.PhotoLibrary, contentDescription = MainTab.LIBRARY.label)
                                }
                            },
                            label = { Text(MainTab.LIBRARY.label, fontWeight = FontWeight.SemiBold) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = colors.accent,
                                selectedTextColor = colors.accent,
                                indicatorColor = colors.accentSoft,
                                unselectedIconColor = colors.muted,
                                unselectedTextColor = colors.muted,
                            ),
                        )
                        NavigationBarItem(
                            selected = activeTab == MainTab.SYNC,
                            onClick = { activeTabName = MainTab.SYNC.name },
                            icon = { Icon(Icons.Rounded.Devices, contentDescription = MainTab.SYNC.label) },
                            label = { Text(MainTab.SYNC.label, fontWeight = FontWeight.SemiBold) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = colors.accent,
                                selectedTextColor = colors.accent,
                                indicatorColor = colors.accentSoft,
                                unselectedIconColor = colors.muted,
                                unselectedTextColor = colors.muted,
                            ),
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when {
                    model.importing -> ImportView(model, pick = { picker.launch(arrayOf("*/*")) }, back = ::back)
                    post != null && activePsd != null -> PsdLayerStudioScreen(
                        post = post,
                        psdFile = activePsd!!.file,
                        displayName = activePsd!!.displayName,
                        model = model,
                        onBack = { activePsd = null },
                    )
                    post != null && activeZipAssetId != null -> {
                        val zipAsset = post.assets.find { it.id == activeZipAssetId }
                        if (zipAsset == null) {
                            LaunchedEffect(Unit) { activeZipAssetId = null }
                        } else {
                            ZipExplorerScreen(
                                post = post,
                                zipAsset = zipAsset,
                                model = model,
                                onOpenPsdFile = { file, name, _ -> activePsd = ActivePsdSession(file, name) },
                                onBack = { activeZipAssetId = null },
                            )
                        }
                    }
                    post != null && readerId != null -> ReaderView(
                        post = post,
                        visiblePosts = visiblePosts.ifEmpty { model.posts },
                        startId = readerId!!,
                        onSwitchPost = { nextPost, startAtEnd ->
                            val imgs = nextPost.assets.filter { it.isImage }
                            postId = nextPost.id
                            readerId = if (imgs.isEmpty()) null else if (startAtEnd) imgs.last().id else imgs.first().id
                            model.notice = "작품 전환 · ${nextPost.label}"
                        },
                        back = ::back,
                        error = { model.notice = it },
                    )
                    activeTab == MainTab.FANBOX -> FanboxHubScreen(
                        model = model,
                        darkTheme = darkTheme,
                        onToggleTheme = ::toggleTheme,
                        hasUpdate = updateResult is UpdateCheckResult.UpdateAvailable,
                        onOpenUpdate = { showUpdateDialog = true },
                        onOpenArchivedPost = { localId ->
                            postId = localId
                            activeTabName = MainTab.LIBRARY.name
                        },
                        onFullscreenChanged = { fanboxFullscreen = it },
                    )
                    activeTab == MainTab.SYNC -> PairingScreen(model.store) {
                        model.refresh()
                        activeTabName = MainTab.LIBRARY.name
                    }
                    post != null -> PostView(
                        post = post,
                        visiblePosts = visiblePosts.ifEmpty { model.posts },
                        onSelectPost = { postId = it },
                        back = { postId = null },
                        read = { readerId = it },
                        openZip = { activeZipAssetId = it.id },
                        openPsd = { asset -> activePsd = ActivePsdSession(File(asset.path), asset.name) },
                        add = { model.beginImport(post.asDraft()) },
                        move = { selected -> model.beginImport(sourceId = post.id, selected = selected) },
                        deleteAssets = { selected -> model.deleteAssets(post.id, selected) },
                        error = { model.notice = it },
                    )
                    else -> LibraryView(
                        allPosts = model.posts,
                        visiblePosts = visiblePosts,
                        artist = artist,
                        onArtist = { artist = it },
                        search = search,
                        onSearch = { search = it },
                        smartFilter = smartFilter,
                        onSmartFilter = { smartFilterName = it.name },
                        darkTheme = darkTheme,
                        onToggleTheme = ::toggleTheme,
                        hasUpdate = updateResult is UpdateCheckResult.UpdateAvailable,
                        onOpenUpdate = { showUpdateDialog = true },
                        open = { postId = it },
                        browse = { openOfficial(context, FANBOX_HOME) { model.notice = it } },
                        add = { model.beginImport() },
                        pair = { activeTabName = MainTab.SYNC.name },
                    )
                }
            }
        }

        if (showUpdateDialog) {
            AndroidUpdateDialog(
                currentVersion = BuildConfig.VERSION_NAME,
                initialResult = updateResult,
                onResultUpdated = { updateResult = it },
                onError = { model.notice = it },
                onDismiss = { showUpdateDialog = false },
            )
        }
    }
}

private fun LibraryPost.asDraft() = PostDraft(creator, title, year, sourceUrl, id)
internal val LibraryPost.label get() = title.ifBlank { "미분류" }
internal val LibraryPost.artist get() = creator.ifBlank { "미분류" }
internal fun LibraryPost.hasTextless() = assets.any { it.isImage && it.variant.equals("textless", ignoreCase = true) }
internal fun LibraryPost.imageCount() = assets.count { it.isImage }
internal fun LibraryPost.attachmentCount() = assets.count { !it.isImage }

@Composable
private fun LibraryView(
    allPosts: List<LibraryPost>,
    visiblePosts: List<LibraryPost>,
    artist: String,
    onArtist: (String) -> Unit,
    search: String,
    onSearch: (String) -> Unit,
    smartFilter: MobileSmartFilter,
    onSmartFilter: (MobileSmartFilter) -> Unit,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    hasUpdate: Boolean,
    onOpenUpdate: () -> Unit,
    open: (String) -> Unit,
    browse: () -> Unit,
    add: () -> Unit,
    pair: () -> Unit,
) {
    val colors = LocalAndroidGalleryColors.current
    val artists = remember(allPosts) { allPosts.map { it.artist }.distinct().sorted() }
    val groups = remember(visiblePosts) { visiblePosts.groupBy { it.year } }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(154.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Brush.linearGradient(listOf(colors.accent, colors.secondary))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("F", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "작품 보관함",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = colors.ink,
                        )
                        Text(
                            "v${BuildConfig.VERSION_NAME} · ${allPosts.size}작품",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.muted,
                        )
                    }

                    // GitHub Update Check Icon Button
                    IconButton(
                        onClick = onOpenUpdate,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (hasUpdate) colors.accentSoft else colors.surface)
                            .border(1.dp, if (hasUpdate) colors.accent else colors.border, RoundedCornerShape(10.dp)),
                    ) {
                        Box {
                            Icon(
                                Icons.Rounded.SystemUpdateAlt,
                                contentDescription = "업데이트 확인",
                                tint = if (hasUpdate) colors.accent else colors.muted,
                                modifier = Modifier.size(19.dp),
                            )
                            if (hasUpdate) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .align(Alignment.TopEnd)
                                        .clip(CircleShape)
                                        .background(colors.accent),
                                )
                            }
                        }
                    }

                    // Theme Toggle Button
                    IconButton(
                        onClick = onToggleTheme,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.surface)
                            .border(1.dp, colors.border, RoundedCornerShape(10.dp)),
                    ) {
                        Icon(
                            if (darkTheme) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                            contentDescription = if (darkTheme) "라이트 모드" else "다크 모드",
                            tint = colors.muted,
                            modifier = Modifier.size(19.dp),
                        )
                    }

                    // Device Pairing Button
                    OutlinedButton(
                        onClick = pair,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, colors.border),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.Rounded.Devices, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("기기 연결", style = MaterialTheme.typography.labelMedium)
                    }
                }

                // Primary Actions Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = add,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Rounded.AddPhotoAlternate, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("파일 가져오기")
                    }
                    OutlinedButton(
                        onClick = browse,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, colors.border),
                    ) {
                        Icon(Icons.Rounded.Language, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("FANBOX 열기")
                    }
                }

                if (allPosts.isNotEmpty()) {
                    // Search Field
                    OutlinedTextField(
                        value = search,
                        onValueChange = onSearch,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        placeholder = { Text("작품 · 작가 · 파일명 검색…") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null, tint = colors.muted) },
                        trailingIcon = if (search.isNotEmpty()) {
                            {
                                IconButton(onClick = { onSearch("") }) {
                                    Icon(Icons.Rounded.Close, "검색어 지우기", tint = colors.muted)
                                }
                            }
                        } else null,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = colors.surface,
                            unfocusedContainerColor = colors.surface,
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.border,
                        ),
                    )

                    // Smart Filter + Horizontal Artist Chips (uses horizontalScroll to keep single Lazy node for tests)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        MobileSmartFilter.entries.forEach { f ->
                            val selected = smartFilter == f
                            FilterPill(
                                label = f.label,
                                selected = selected,
                                activeColor = if (f == MobileSmartFilter.TEXTLESS) colors.emerald else colors.accent,
                                onClick = { onSmartFilter(f) },
                            )
                        }
                        if (artists.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 4.dp)
                                    .width(1.dp)
                                    .height(26.dp)
                                    .align(Alignment.CenterVertically)
                                    .background(colors.border),
                            )
                            FilterPill(
                                label = "모든 작가",
                                selected = artist.isEmpty(),
                                activeColor = colors.secondary,
                                onClick = { onArtist("") },
                            )
                            artists.forEach { name ->
                                FilterPill(
                                    label = name,
                                    selected = artist == name,
                                    activeColor = colors.secondary,
                                    onClick = { onArtist(if (artist == name) "" else name) },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (groups.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        if (allPosts.isEmpty()) "보관 중인 작품이 없습니다" else "검색 결과가 없습니다",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.ink,
                    )
                    Text(
                        if (allPosts.isEmpty()) "내려받은 원본 이미지나 ZIP을 가져오거나 PC와 기기 연결로 동기화해 보세요."
                        else "다른 검색어나 필터를 선택해 보세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                    )
                }
            }
        }

        groups.forEach { (year, group) ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    Text(
                        year?.let { "${it}년" } ?: "연도 미상",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.ink,
                    )
                    Text("${group.size}", style = MaterialTheme.typography.labelMedium, color = colors.muted)
                }
            }
            items(group, key = { it.id }) { post ->
                AndroidPosterCard(post = post, onClick = { open(post.id) })
            }
        }
    }
}

@Composable
private fun FilterPill(
    label: String,
    selected: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
) {
    val colors = LocalAndroidGalleryColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) activeColor else colors.surface)
            .border(1.dp, if (selected) activeColor else colors.border, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
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
private fun AndroidPosterCard(post: LibraryPost, onClick: () -> Unit) {
    val colors = LocalAndroidGalleryColors.current
    val cover = remember(post) {
        post.assets.firstOrNull { it.isImage && !it.variant.equals("textless", true) }
            ?: post.assets.firstOrNull { it.isImage }
    }
    val imgCount = remember(post) { post.imageCount() }
    val attachCount = remember(post) { post.attachmentCount() }
    val textless = remember(post) { post.hasTextless() }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .background(colors.surfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            if (cover == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Rounded.FolderZip, null, tint = colors.muted, modifier = Modifier.size(28.dp))
                    Text("첨부파일", color = colors.muted, style = MaterialTheme.typography.labelSmall)
                }
            } else {
                AsyncImage(
                    model = File(cover.path),
                    contentDescription = post.label,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                )
            }

            // Top gradient scrim
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.54f), Color.Transparent))),
            )

            Row(
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (textless) {
                    MobileBadge("TL", Color(0xCC059669), Icons.Rounded.AutoAwesome)
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                if (imgCount > 0) {
                    MobileBadge("$imgCount", Color(0xB80E1016), Icons.Rounded.Image)
                }
            }

            if (attachCount > 0) {
                Box(modifier = Modifier.align(Alignment.BottomStart).padding(7.dp)) {
                    MobileBadge("PSD $attachCount", Color(0xCC4F46E5))
                }
            }
        }
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                post.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 17.sp,
            )
            Text(
                post.artist,
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MobileBadge(text: String, bg: Color, icon: ImageVector? = null) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = Color.White, modifier = Modifier.size(10.dp))
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
private fun ImportView(model: ArchiveModel, pick: () -> Unit, back: () -> Unit) {
    val colors = LocalAndroidGalleryColors.current
    val draft = model.draft
    val busy = model.busy || model.readingSelection
    val moving = model.moveIds.isNotEmpty()
    val context = LocalContext.current
    var destinationMenu by remember { mutableStateOf(false) }
    val targets = model.posts.filter { it.id != model.moveFrom }
    val editing = draft.existingId == null && !busy
    val yearOk = draft.year?.let { it in 1000..9999 } ?: true

    LazyColumn(
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            TextButton(onClick = back, enabled = !busy) { Text("‹ 보관함") }
            Text(
                if (moving) "다른 작품에 묶기" else "작품 가져오기",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = colors.ink,
            )
        }
        if (targets.isNotEmpty()) item {
            Box {
                OutlinedButton(onClick = { destinationMenu = true }, enabled = !busy, shape = RoundedCornerShape(10.dp)) {
                    Text(if (draft.existingId == null) "대상 작품 선택 ▾" else "${draft.title.ifBlank { "미분류" }} ▾")
                }
                DropdownMenu(destinationMenu, { destinationMenu = false }, containerColor = colors.surface) {
                    DropdownMenuItem(text = { Text("새 작품") }, onClick = {
                        model.draft = PostDraft(); destinationMenu = false
                    })
                    targets.forEach { p ->
                        DropdownMenuItem(text = { Text("${p.artist} / ${p.label}") }, onClick = {
                            model.draft = p.asDraft(); destinationMenu = false
                        })
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                draft.sourceUrl,
                { model.draft = draft.copy(sourceUrl = it) },
                Modifier.fillMaxWidth(),
                enabled = editing,
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                label = { Text("공식 게시물 주소 (선택)") },
            )
            TextButton(
                onClick = { openOfficial(context, draft.sourceUrl.trim().ifBlank { FANBOX_HOME }) { model.notice = it } },
                enabled = !busy,
            ) { Text(if (draft.sourceUrl.isBlank()) "FANBOX에서 원본 받기 ↗" else "공식 게시물 열기 ↗") }
        }
        item {
            OutlinedTextField(
                draft.title,
                { model.draft = draft.copy(title = it) },
                Modifier.fillMaxWidth(),
                enabled = editing,
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                label = { Text("작품 제목") },
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    draft.creator,
                    { model.draft = draft.copy(creator = it) },
                    Modifier.weight(1f),
                    enabled = editing,
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    label = { Text("작가") },
                )
                OutlinedTextField(
                    draft.year?.toString().orEmpty(),
                    { if (it.length <= 4 && it.all(Char::isDigit)) model.draft = draft.copy(year = it.toIntOrNull()) },
                    Modifier.width(104.dp),
                    enabled = editing,
                    singleLine = true,
                    isError = !yearOk,
                    shape = RoundedCornerShape(12.dp),
                    label = { Text("연도") },
                )
            }
        }
        item {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accent)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!moving) OutlinedButton(onClick = pick, enabled = !busy, shape = RoundedCornerShape(12.dp)) { Text("파일 고르기") }
                Button(
                    onClick = model::commit,
                    enabled = !busy && yearOk && (moving || model.documents.isNotEmpty()),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(if (moving) "${model.moveIds.size}개 묶기" else "${model.documents.size}개 가져오기")
                }
            }
        }
        itemsIndexed(model.documents, key = { _, d -> d.uri.toString() }) { index, doc ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${index + 1}. ${doc.name}",
                    Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = colors.ink,
                )
                TextButton(onClick = { model.shift(index, -1) }, enabled = !busy && index > 0) { Text("위") }
                TextButton(onClick = { model.shift(index, 1) }, enabled = !busy && index + 1 < model.documents.size) { Text("아래") }
                TextButton(onClick = { model.removeAt(index) }, enabled = !busy) { Text("빼기") }
            }
        }
    }
}

@Composable
private fun PostView(
    post: LibraryPost,
    visiblePosts: List<LibraryPost>,
    onSelectPost: (String) -> Unit,
    back: () -> Unit,
    read: (String) -> Unit,
    openZip: (LibraryAsset) -> Unit,
    openPsd: (LibraryAsset) -> Unit,
    add: () -> Unit,
    move: (Set<String>) -> Unit,
    deleteAssets: (Set<String>) -> Unit,
    error: (String) -> Unit,
) {
    val colors = LocalAndroidGalleryColors.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 80.dp.toPx() }

    val postIndex = remember(visiblePosts, post.id) { visiblePosts.indexOfFirst { it.id == post.id } }
    val prevPost = if (postIndex > 0) visiblePosts[postIndex - 1] else null
    val nextPost = if (postIndex in 0 until visiblePosts.lastIndex) visiblePosts[postIndex + 1] else null

    var slideDirection by remember { mutableIntStateOf(1) }
    val dragOffset = remember { Animatable(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(post.id, prevPost?.id, nextPost?.id) {
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onHorizontalDrag = { change, dragAmount ->
                        val canDragRight = prevPost != null
                        val canDragLeft = nextPost != null
                        totalDrag += dragAmount
                        val damped = when {
                            totalDrag > 0f && !canDragRight -> totalDrag * 0.15f
                            totalDrag < 0f && !canDragLeft -> totalDrag * 0.15f
                            else -> totalDrag * 0.42f
                        }
                        if (kotlin.math.abs(totalDrag) > 12f) {
                            change.consume()
                            scope.launch { dragOffset.snapTo(damped.coerceIn(-160f, 160f)) }
                        }
                    },
                    onDragCancel = {
                        scope.launch { dragOffset.animateTo(0f, tween(160)) }
                    },
                    onDragEnd = {
                        when {
                            totalDrag < -swipeThresholdPx && nextPost != null -> {
                                slideDirection = 1
                                scope.launch { dragOffset.snapTo(0f) }
                                onSelectPost(nextPost.id)
                            }
                            totalDrag > swipeThresholdPx && prevPost != null -> {
                                slideDirection = -1
                                scope.launch { dragOffset.snapTo(0f) }
                                onSelectPost(prevPost.id)
                            }
                            else -> {
                                scope.launch { dragOffset.animateTo(0f, tween(160)) }
                            }
                        }
                    },
                )
            },
    ) {
        AnimatedContent(
            targetState = post,
            transitionSpec = {
                val enter = slideInHorizontally(tween(220)) { width -> if (slideDirection >= 0) width / 3 else -width / 3 } +
                    fadeIn(tween(200))
                val exit = slideOutHorizontally(tween(200)) { width -> if (slideDirection >= 0) -width / 3 else width / 3 } +
                    fadeOut(tween(160))
                enter togetherWith exit
            },
            label = "post-swipe-transition",
        ) { activePost ->
            PostDetailBody(
                post = activePost,
                postIndex = postIndex,
                totalPosts = visiblePosts.size,
                hasPrev = prevPost != null,
                hasNext = nextPost != null,
                onPrev = {
                    prevPost?.let {
                        slideDirection = -1
                        onSelectPost(it.id)
                    }
                },
                onNext = {
                    nextPost?.let {
                        slideDirection = 1
                        onSelectPost(it.id)
                    }
                },
                back = back,
                read = read,
                openZip = openZip,
                openPsd = openPsd,
                add = add,
                move = move,
                deleteAssets = deleteAssets,
                error = error,
                modifier = Modifier.graphicsLayer { translationX = dragOffset.value },
            )
        }

        // Swipe direction visual indicator while dragging
        if (dragOffset.value > 28f && prevPost != null) {
            SwipeHintPill(
                text = "‹ 이전 작품: ${prevPost.label}",
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
            )
        } else if (dragOffset.value < -28f && nextPost != null) {
            SwipeHintPill(
                text = "다음 작품: ${nextPost.label} ›",
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
            )
        }
    }
}

@Composable
private fun SwipeHintPill(text: String, modifier: Modifier = Modifier) {
    val colors = LocalAndroidGalleryColors.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(colors.accent.copy(alpha = 0.92f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PostDetailBody(
    post: LibraryPost,
    postIndex: Int,
    totalPosts: Int,
    hasPrev: Boolean,
    hasNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    back: () -> Unit,
    read: (String) -> Unit,
    openZip: (LibraryAsset) -> Unit,
    openPsd: (LibraryAsset) -> Unit,
    add: () -> Unit,
    move: (Set<String>) -> Unit,
    deleteAssets: (Set<String>) -> Unit,
    error: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAndroidGalleryColors.current
    val context = LocalContext.current
    var selecting by remember(post.id) { mutableStateOf(false) }
    var selected by remember(post.id) { mutableStateOf(emptySet<String>()) }
    var activeTab by remember(post.id) { mutableStateOf(PostDetailTab.ALL) }

    val allImages = remember(post) { post.assets.filter { it.isImage } }
    val origImages = remember(allImages) { allImages.filterNot { it.variant.equals("textless", true) } }
    val textlessImages = remember(allImages) { allImages.filter { it.variant.equals("textless", true) } }
    val attachments = remember(post) { post.assets.filterNot { it.isImage } }

    val visibleImages = when (activeTab) {
        PostDetailTab.ALL -> allImages
        PostDetailTab.ORIGINAL -> origImages
        PostDetailTab.TEXTLESS -> textlessImages
        PostDetailTab.ATTACHMENTS -> emptyList()
    }
    val visibleAttachments = when (activeTab) {
        PostDetailTab.ALL, PostDetailTab.ATTACHMENTS -> attachments
        else -> emptyList()
    }

    fun toggle(id: String) {
        selected = if (id in selected) selected - id else selected + id
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Top Bar with Prev/Next Work Switcher
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = back) { Text("‹ 보관함", color = colors.ink, fontWeight = FontWeight.SemiBold) }
                    Spacer(Modifier.weight(1f))
                    if (totalPosts > 1 && postIndex >= 0) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.surface)
                                .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = onPrev, enabled = hasPrev, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Rounded.ChevronLeft, "이전 작품", modifier = Modifier.size(18.dp))
                            }
                            Text(
                                "${postIndex + 1} / $totalPosts",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.muted,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                            IconButton(onClick = onNext, enabled = hasNext, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Rounded.ChevronRight, "다음 작품", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                // Title & Metadata
                Text(
                    post.label,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.ink,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        listOfNotNull(post.artist, post.year?.toString(), "파일 ${post.assets.size}개").joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                    )
                    if (totalPosts > 1) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Icon(Icons.Rounded.SwapHoriz, null, tint = colors.accent, modifier = Modifier.size(14.dp))
                            Text("스와이프 전환", style = MaterialTheme.typography.labelSmall, color = colors.accent)
                        }
                    }
                }

                // Action Row
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(
                        onClick = add,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, colors.border),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) { Text("파일 추가", style = MaterialTheme.typography.labelMedium) }

                    if (FanboxLinks.postId(post.sourceUrl) != null) {
                        OutlinedButton(
                            onClick = { openOfficial(context, post.sourceUrl, error) },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, colors.border),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) { Text("원문 ↗", style = MaterialTheme.typography.labelMedium) }
                    }

                    OutlinedButton(
                        onClick = { selecting = !selecting; selected = emptySet() },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, if (selecting) colors.accent else colors.border),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) { Text(if (selecting) "취소" else "작품에 묶기", style = MaterialTheme.typography.labelMedium) }
                }

                if (selecting) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { move(selected) },
                            enabled = selected.isNotEmpty(),
                            shape = RoundedCornerShape(10.dp),
                        ) { Text("선택 ${selected.size}개 묶기") }
                        OutlinedButton(
                            onClick = {
                                val ids = selected
                                selecting = false
                                selected = emptySet()
                                deleteAssets(ids)
                            },
                            enabled = selected.isNotEmpty(),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        ) { Text("삭제 (${selected.size})", color = MaterialTheme.colorScheme.error) }
                    }
                }

                // Variant / Attachment Filter Tabs
                if (textlessImages.isNotEmpty() || attachments.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterPill("전체 ${post.assets.size}", activeTab == PostDetailTab.ALL, colors.accent) {
                            activeTab = PostDetailTab.ALL
                        }
                        if (origImages.isNotEmpty()) {
                            FilterPill("원본 ${origImages.size}", activeTab == PostDetailTab.ORIGINAL, colors.accent) {
                                activeTab = PostDetailTab.ORIGINAL
                            }
                        }
                        if (textlessImages.isNotEmpty()) {
                            FilterPill("Textless ${textlessImages.size}", activeTab == PostDetailTab.TEXTLESS, colors.emerald) {
                                activeTab = PostDetailTab.TEXTLESS
                            }
                        }
                        if (attachments.isNotEmpty()) {
                            FilterPill("첨부 ${attachments.size}", activeTab == PostDetailTab.ATTACHMENTS, colors.secondary) {
                                activeTab = PostDetailTab.ATTACHMENTS
                            }
                        }
                    }
                }
            }
        }

        // 3-Column Image Gallery Grid
        items(visibleImages, key = { it.id }) { asset ->
            GalleryImageTile(
                asset = asset,
                selecting = selecting,
                checked = asset.id in selected,
                onClick = {
                    if (selecting) toggle(asset.id) else read(asset.id)
                },
            )
        }

        // Attachment Section Header & Cards
        if (visibleAttachments.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "첨부 파일 · ${visibleAttachments.size}개",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(
                items = visibleAttachments,
                key = { it.id },
                span = { GridItemSpan(maxLineSpan) },
            ) { asset ->
                AttachmentRowCard(
                    asset = asset,
                    selecting = selecting,
                    checked = asset.id in selected,
                    onClick = {
                        when {
                            selecting -> toggle(asset.id)
                            ZipExplorer.isZipFile(asset.name) -> openZip(asset)
                            ZipExplorer.isPsdFile(asset.name) -> openPsd(asset)
                            else -> openAttachment(context, asset, error)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun GalleryImageTile(
    asset: LibraryAsset,
    selecting: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalAndroidGalleryColors.current
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface)
            .border(
                width = if (checked) 2.dp else 1.dp,
                color = if (checked) colors.accent else colors.border,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.82f)
                .background(colors.surfaceElevated),
        ) {
            AsyncImage(
                model = File(asset.path),
                contentDescription = asset.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
            )
            if (asset.variant.equals("textless", true)) {
                Box(modifier = Modifier.align(Alignment.TopStart).padding(5.dp)) {
                    MobileBadge("TL", Color(0xCC059669))
                }
            }
            if (selecting) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = null,
                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
                )
            }
        }
        Text(
            asset.name,
            style = MaterialTheme.typography.labelSmall,
            color = colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun AttachmentRowCard(
    asset: LibraryAsset,
    selecting: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalAndroidGalleryColors.current
    val context = LocalContext.current
    val ext = asset.name.substringAfterLast('.', "FILE").uppercase().take(4)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, if (checked) colors.accent else colors.border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selecting) Checkbox(checked = checked, onCheckedChange = null)
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.secondarySoft),
            contentAlignment = Alignment.Center,
        ) {
            Text(ext, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = colors.secondary)
        }
        Column(Modifier.weight(1f)) {
            Text(asset.name, maxLines = 2, overflow = TextOverflow.Ellipsis, color = colors.ink, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${asset.variant} · ${Formatter.formatFileSize(context, asset.size)}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
            )
        }
        when {
            ZipExplorer.isZipFile(asset.name) -> MobileBadge("ZIP 탐색", colors.secondary)
            ZipExplorer.isPsdFile(asset.name) -> MobileBadge("PSD 편집", colors.emerald)
        }
    }
}
