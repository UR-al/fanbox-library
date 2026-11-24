package app.fanboxlibrary.android

import android.annotation.SuppressLint
import android.content.Context
import android.text.format.Formatter
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.fanboxlibrary.shared.FanboxLinks
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class FanboxViewMode { FEED, WEB }
private enum class FeedCategory(val label: String) {
    HOME("홈 피드"),
    SUPPORTING("후원중"),
    CREATORS("작가 목록"),
}

@Composable
internal fun FanboxHubScreen(
    model: ArchiveModel,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    hasUpdate: Boolean,
    onOpenUpdate: () -> Unit,
    onOpenArchivedPost: (String) -> Unit,
    onFullscreenChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("fanbox_ui_prefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var viewModeName by rememberSaveable { mutableStateOf(FanboxViewMode.FEED.name) }
    val viewMode = runCatching { FanboxViewMode.valueOf(viewModeName) }.getOrDefault(FanboxViewMode.FEED)
    var categoryName by rememberSaveable { mutableStateOf(FeedCategory.HOME.name) }
    val category = runCatching { FeedCategory.valueOf(categoryName) }.getOrDefault(FeedCategory.HOME)

    var showAdult by rememberSaveable { mutableStateOf(prefs.getBoolean("show_adult", false)) }
    var gridMode by rememberSaveable { mutableStateOf(prefs.getBoolean("feed_grid_mode", false)) }
    var webUrl by rememberSaveable { mutableStateOf(FANBOX_HOME) }
    var loggedIn by remember { mutableStateOf(FanboxSession.hasLoginCookie()) }

    // Feed state
    var feedItems by remember { mutableStateOf<List<FanboxPostSummary>>(emptyList()) }
    var feedNextUrl by remember { mutableStateOf<String?>(null) }
    var feedLoading by remember { mutableStateOf(false) }
    var feedError by remember { mutableStateOf<FanboxResult<Nothing>?>(null) }

    // Creators state
    var creators by remember { mutableStateOf<List<FanboxCreator>>(emptyList()) }
    var selectedCreator by remember { mutableStateOf<FanboxCreator?>(null) }
    var creatorsLoading by remember { mutableStateOf(false) }
    var creatorsError by remember { mutableStateOf<FanboxResult<Nothing>?>(null) }

    // Detail & online lightbox state
    var activePostId by rememberSaveable { mutableStateOf<String?>(null) }
    var lightboxImages by remember { mutableStateOf<List<FanboxImage>>(emptyList()) }
    var lightboxStartIndex by remember { mutableIntStateOf(0) }

    val archivedPostIdMap = remember(model.posts) {
        buildMap {
            model.posts.forEach { p ->
                FanboxLinks.postId(p.sourceUrl)?.let { fid -> put(fid, p.id) }
            }
        }
    }

    LaunchedEffect(activePostId, lightboxImages.isNotEmpty()) {
        onFullscreenChanged(lightboxImages.isNotEmpty())
    }

    fun loadFeed(reset: Boolean = true) {
        if (feedLoading) return
        val next = if (reset) null else feedNextUrl
        if (!reset && next == null) return
        feedLoading = true
        if (reset) feedError = null
        scope.launch {
            loggedIn = FanboxSession.hasLoginCookie()
            val result = withContext(Dispatchers.IO) {
                val creator = selectedCreator
                when {
                    creator != null -> FanboxClient.fetchCreatorPosts(context, creator.creatorId, next)
                    category == FeedCategory.SUPPORTING -> FanboxClient.fetchSupportingFeed(context, next)
                    else -> FanboxClient.fetchHomeFeed(context, next)
                }
            }
            when (result) {
                is FanboxResult.Success -> {
                    feedItems = if (reset) result.data.items else (feedItems + result.data.items).distinctBy { it.id }
                    feedNextUrl = result.data.nextUrl
                    feedError = null
                }
                is FanboxResult.AuthRequired -> {
                    loggedIn = false
                    feedError = result
                }
                is FanboxResult.CloudflareBlocked -> feedError = result
                is FanboxResult.Error -> feedError = result
            }
            feedLoading = false
        }
    }

    fun loadCreators() {
        if (creatorsLoading) return
        creatorsLoading = true
        creatorsError = null
        scope.launch {
            loggedIn = FanboxSession.hasLoginCookie()
            val result = withContext(Dispatchers.IO) { FanboxClient.fetchCreators(context) }
            when (result) {
                is FanboxResult.Success -> {
                    creators = result.data
                    creatorsError = null
                }
                is FanboxResult.AuthRequired -> {
                    loggedIn = false
                    creatorsError = result
                }
                is FanboxResult.CloudflareBlocked -> creatorsError = result
                is FanboxResult.Error -> creatorsError = result
            }
            creatorsLoading = false
        }
    }

    LaunchedEffect(viewMode, category, selectedCreator?.creatorId) {
        if (viewMode == FanboxViewMode.FEED) {
            loggedIn = FanboxSession.hasLoginCookie()
            if (loggedIn) {
                if (category == FeedCategory.CREATORS && selectedCreator == null) {
                    if (creators.isEmpty()) loadCreators()
                } else {
                    loadFeed(reset = true)
                }
            }
        }
    }

    // Lightbox fullscreen online image viewer
    if (lightboxImages.isNotEmpty()) {
        BackHandler { lightboxImages = emptyList() }
        OnlineImageLightbox(
            images = lightboxImages,
            initialIndex = lightboxStartIndex,
            onClose = { lightboxImages = emptyList() },
        )
        return
    }

    // Native Post Detail View
    val currentPostId = activePostId
    if (viewMode == FanboxViewMode.FEED && currentPostId != null) {
        BackHandler { activePostId = null }
        val visibleFeed = remember(feedItems, showAdult) {
            if (showAdult) feedItems else feedItems.filterNot { it.hasAdultContent }
        }
        FanboxPostDetailScreen(
            postId = currentPostId,
            feedList = visibleFeed,
            model = model,
            archivedLocalId = archivedPostIdMap[currentPostId],
            onSelectPost = { activePostId = it },
            onSelectCreator = { id, name, icon ->
                activePostId = null
                selectedCreator = FanboxCreator(id, name, icon, null, "", isSupporting = false)
            },
            onOpenInWeb = { url ->
                webUrl = url
                activePostId = null
                viewModeName = FanboxViewMode.WEB.name
            },
            onOpenArchived = onOpenArchivedPost,
            onOpenLightbox = { imgs, start ->
                lightboxImages = imgs
                lightboxStartIndex = start
            },
            onBack = { activePostId = null },
        )
        return
    }

    val colors = LocalAndroidGalleryColors.current
    Column(Modifier.fillMaxSize().background(colors.bg)) {
        // Unified Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Brush.linearGradient(listOf(colors.accent, colors.secondary))),
                contentAlignment = Alignment.Center,
            ) {
                Text("F", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
            }

            // Hybrid Mode Switcher [피드 | 웹뷰]
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ModePill("피드", viewMode == FanboxViewMode.FEED) {
                    viewModeName = FanboxViewMode.FEED.name
                    loggedIn = FanboxSession.hasLoginCookie()
                    if (loggedIn) {
                        if (category == FeedCategory.CREATORS && selectedCreator == null) loadCreators()
                        else loadFeed(reset = true)
                    }
                }
                ModePill("웹뷰", viewMode == FanboxViewMode.WEB) {
                    viewModeName = FanboxViewMode.WEB.name
                }
            }

            Spacer(Modifier.weight(1f))

            // R-18 Toggle (Play Store friendly: defaults OFF)
            if (viewMode == FanboxViewMode.FEED) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (showAdult) colors.accentSoft else colors.surface)
                        .border(1.dp, if (showAdult) colors.accent else colors.border, RoundedCornerShape(9.dp))
                        .clickable {
                            showAdult = !showAdult
                            prefs.edit().putBoolean("show_adult", showAdult).apply()
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(
                        if (showAdult) "R-18 ON" else "R-18 OFF",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (showAdult) colors.accent else colors.muted,
                    )
                }
            }

            // Update Button
            IconButton(
                onClick = onOpenUpdate,
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (hasUpdate) colors.accentSoft else colors.surface)
                    .border(1.dp, if (hasUpdate) colors.accent else colors.border, RoundedCornerShape(9.dp)),
            ) {
                Box {
                    Icon(
                        Icons.Rounded.SystemUpdateAlt,
                        contentDescription = "업데이트 확인",
                        tint = if (hasUpdate) colors.accent else colors.muted,
                        modifier = Modifier.size(17.dp),
                    )
                    if (hasUpdate) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .align(Alignment.TopEnd)
                                .clip(CircleShape)
                                .background(colors.accent),
                        )
                    }
                }
            }

            // Theme Toggle
            IconButton(
                onClick = onToggleTheme,
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(9.dp)),
            ) {
                Icon(
                    if (darkTheme) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                    contentDescription = "테마 전환",
                    tint = colors.muted,
                    modifier = Modifier.size(17.dp),
                )
            }
        }

        // Saving Progress Banner (if downloading from WebView or Detail)
        if (model.savingProgress.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accent)
                Text(
                    model.savingProgress,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (viewMode == FanboxViewMode.WEB) {
            FanboxWebViewPane(
                initialUrl = webUrl,
                model = model,
                onSwitchToFeed = {
                    loggedIn = FanboxSession.hasLoginCookie()
                    viewModeName = FanboxViewMode.FEED.name
                    if (loggedIn) loadFeed(reset = true)
                },
            )
        } else {
            // Native Feed Mode
            if (!loggedIn && feedItems.isEmpty()) {
                FanboxLoginPromptCard(
                    onLoginInWebView = {
                        webUrl = "https://www.fanbox.cc/login"
                        viewModeName = FanboxViewMode.WEB.name
                    },
                    onBrowseWeb = {
                        webUrl = FANBOX_HOME
                        viewModeName = FanboxViewMode.WEB.name
                    },
                )
            } else {
                // Sub-tabs row (Home / Supporting / Creators + Grid toggle + Refresh)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (selectedCreator != null) {
                        val cr = selectedCreator!!
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.surface)
                                .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Rounded.ChevronLeft,
                                contentDescription = "뒤로",
                                tint = colors.ink,
                                modifier = Modifier.size(20.dp).clickable { selectedCreator = null },
                            )
                            if (cr.iconUrl != null) {
                                AsyncImage(
                                    model = FanboxSession.imageRequest(context, cr.iconUrl),
                                    contentDescription = cr.name,
                                    modifier = Modifier.size(24.dp).clip(CircleShape),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                            Text(
                                cr.name,
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.Bold,
                                color = colors.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "웹 ↗",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.accent,
                                modifier = Modifier.clickable {
                                    webUrl = "https://${cr.creatorId}.fanbox.cc/"
                                    viewModeName = FanboxViewMode.WEB.name
                                },
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            FeedCategory.entries.forEach { cat ->
                                val active = category == cat
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(if (active) colors.accent else colors.surface)
                                        .border(1.dp, if (active) colors.accent else colors.border, RoundedCornerShape(20.dp))
                                        .clickable {
                                            categoryName = cat.name
                                            selectedCreator = null
                                        }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                ) {
                                    Text(
                                        cat.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (active) Color.White else colors.muted,
                                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                                    )
                                }
                            }
                        }
                    }

                    if (selectedCreator != null || category != FeedCategory.CREATORS) {
                        IconButton(
                            onClick = {
                                gridMode = !gridMode
                                prefs.edit().putBoolean("feed_grid_mode", gridMode).apply()
                            },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                if (gridMode) Icons.Rounded.ViewAgenda else Icons.Rounded.GridView,
                                contentDescription = "보기 모드 전환",
                                tint = colors.muted,
                                modifier = Modifier.size(19.dp),
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            if (category == FeedCategory.CREATORS && selectedCreator == null) loadCreators()
                            else loadFeed(reset = true)
                        },
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "새로고침", tint = colors.muted, modifier = Modifier.size(19.dp))
                    }
                }

                // Body Area
                if (category == FeedCategory.CREATORS && selectedCreator == null) {
                    CreatorsListPane(
                        creators = creators,
                        loading = creatorsLoading,
                        error = creatorsError,
                        onSelectCreator = { selectedCreator = it },
                        onOpenWeb = {
                            webUrl = FANBOX_HOME
                            viewModeName = FanboxViewMode.WEB.name
                        },
                        onRetry = ::loadCreators,
                    )
                } else {
                    val hiddenAdultCount = remember(feedItems, showAdult) {
                        if (showAdult) 0 else feedItems.count { it.hasAdultContent }
                    }
                    val visibleFeed = remember(feedItems, showAdult) {
                        if (showAdult) feedItems else feedItems.filterNot { it.hasAdultContent }
                    }
                    FeedPostsPane(
                        posts = visibleFeed,
                        hiddenAdultCount = hiddenAdultCount,
                        gridMode = gridMode,
                        loading = feedLoading,
                        error = feedError,
                        hasMore = feedNextUrl != null,
                        archivedIds = archivedPostIdMap.keys,
                        onEnableAdult = {
                            showAdult = true
                            prefs.edit().putBoolean("show_adult", true).apply()
                        },
                        onOpenPost = { activePostId = it.id },
                        onSelectCreator = { p ->
                            selectedCreator = FanboxCreator(p.creatorId, p.creatorName, p.creatorIconUrl, null, "", false)
                        },
                        onLoadMore = { loadFeed(reset = false) },
                        onRetry = { loadFeed(reset = true) },
                        onOpenWeb = {
                            webUrl = if (feedError is FanboxResult.AuthRequired) "https://www.fanbox.cc/login" else FANBOX_HOME
                            viewModeName = FanboxViewMode.WEB.name
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModePill(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalAndroidGalleryColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) colors.accent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Color.White else colors.muted,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun FanboxLoginPromptCard(
    onLoginInWebView: () -> Unit,
    onBrowseWeb: () -> Unit,
) {
    val colors = LocalAndroidGalleryColors.current
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(20.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(listOf(colors.accent, colors.secondary))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Rounded.Login, null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Text(
                "FANBOX 바로 보기",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = colors.ink,
            )
            Text(
                "앱 내 웹뷰에서 pixiv 계정에 한 번만 로그인하면 후원·팔로우 중인 작가의 새 글을 네이티브 피드로 바로 보고 원탭으로 내 보관함에 저장할 수 있습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
            Button(
                onClick = onLoginInWebView,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.AutoMirrored.Rounded.Login, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("앱 안에서 FANBOX 로그인")
            }
            OutlinedButton(
                onClick = onBrowseWeb,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, colors.border),
            ) {
                Icon(Icons.Rounded.Language, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("웹뷰로 바로 둘러보기")
            }
            Text(
                "비공식 클라이언트입니다. 로그인 쿠키는 기기 내부에만 저장됩니다.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
            )
        }
    }
}

@Composable
private fun CreatorsListPane(
    creators: List<FanboxCreator>,
    loading: Boolean,
    error: FanboxResult<Nothing>?,
    onSelectCreator: (FanboxCreator) -> Unit,
    onOpenWeb: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = LocalAndroidGalleryColors.current
    val context = LocalContext.current

    if (loading && creators.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = colors.accent)
        }
        return
    }
    if (error != null && creators.isEmpty()) {
        FeedErrorState(error, onOpenWeb, onRetry)
        return
    }
    if (creators.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("후원 또는 팔로우 중인 작가가 없습니다.", color = colors.muted)
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(creators, key = { it.creatorId }) { creator ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                    .clickable { onSelectCreator(creator) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(colors.surfaceElevated),
                    contentAlignment = Alignment.Center,
                ) {
                    if (creator.iconUrl != null) {
                        AsyncImage(
                            model = FanboxSession.imageRequest(context, creator.iconUrl),
                            contentDescription = creator.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Text(creator.name.take(1).uppercase(), fontWeight = FontWeight.Bold, color = colors.accent)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            creator.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (creator.isSupporting) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(colors.emeraldSoft)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    if (creator.planFee > 0) "후원 ¥${creator.planFee}" else "후원중",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.emerald,
                                )
                            }
                        }
                    }
                    Text("@${creator.creatorId}", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                    if (creator.description.isNotBlank()) {
                        Text(
                            creator.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = colors.muted)
            }
        }
    }
}

@Composable
private fun FeedPostsPane(
    posts: List<FanboxPostSummary>,
    hiddenAdultCount: Int,
    gridMode: Boolean,
    loading: Boolean,
    error: FanboxResult<Nothing>?,
    hasMore: Boolean,
    archivedIds: Set<String>,
    onEnableAdult: () -> Unit,
    onOpenPost: (FanboxPostSummary) -> Unit,
    onSelectCreator: (FanboxPostSummary) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onOpenWeb: () -> Unit,
) {
    val colors = LocalAndroidGalleryColors.current

    if (loading && posts.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = colors.accent)
        }
        return
    }
    if (error != null && posts.isEmpty()) {
        FeedErrorState(error, onOpenWeb, onRetry)
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(if (gridMode) 2 else 1),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (hiddenAdultCount > 0) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                        .clickable(onClick = onEnableAdult)
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.Shield, null, tint = colors.muted, modifier = Modifier.size(16.dp))
                    Text(
                        "R-18 게시글 ${hiddenAdultCount}개가 숨겨져 있습니다 · 탭하여 표시",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.muted,
                    )
                }
            }
        }

        if (posts.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) {
                    Text("표시할 게시글이 없습니다.", color = colors.muted)
                }
            }
        }

        items(posts, key = { it.id }) { post ->
            if (gridMode) {
                FeedGridCard(post = post, isArchived = post.id in archivedIds, onClick = { onOpenPost(post) })
            } else {
                FeedListCard(
                    post = post,
                    isArchived = post.id in archivedIds,
                    onClick = { onOpenPost(post) },
                    onCreatorClick = { onSelectCreator(post) },
                )
            }
        }

        if (hasMore) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = colors.accent)
                    } else {
                        OutlinedButton(
                            onClick = onLoadMore,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, colors.border),
                        ) {
                            Text("더 불러오기")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedListCard(
    post: FanboxPostSummary,
    isArchived: Boolean,
    onClick: () -> Unit,
    onCreatorClick: () -> Unit,
) {
    val colors = LocalAndroidGalleryColors.current
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    ) {
        // Creator header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(
                modifier = Modifier.weight(1f).clickable(onClick = onCreatorClick),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Box(
                    modifier = Modifier.size(32.dp).clip(CircleShape).background(colors.surfaceElevated),
                    contentAlignment = Alignment.Center,
                ) {
                    if (post.creatorIconUrl != null) {
                        AsyncImage(
                            model = FanboxSession.imageRequest(context, post.creatorIconUrl),
                            contentDescription = post.creatorName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Text(post.creatorName.take(1), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.accent)
                    }
                }
                Column {
                    Text(
                        post.creatorName,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(post.displayDate, style = MaterialTheme.typography.labelSmall, color = colors.muted)
                }
            }

            if (isArchived) {
                FeedTagPill("보관됨", colors.emeraldSoft, colors.emerald)
            }
            if (post.feeRequired > 0) {
                FeedTagPill("¥${post.feeRequired}", colors.secondarySoft, colors.secondary)
            } else {
                FeedTagPill("무료", colors.surfaceElevated, colors.muted)
            }
            if (post.hasAdultContent) {
                FeedTagPill("R-18", colors.accentSoft, colors.accent)
            }
        }

        // Cover Image
        if (post.coverImageUrl != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.45f)
                    .background(colors.surfaceElevated),
            ) {
                AsyncImage(
                    model = FanboxSession.imageRequest(context, post.coverImageUrl),
                    contentDescription = post.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                )
                if (post.isRestricted) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xCC0D0F16))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(Icons.Rounded.Lock, null, tint = Color.White, modifier = Modifier.size(12.dp))
                        Text("후원자 한정", color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Title & Excerpt
        Column(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                post.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (post.excerpt.isNotBlank()) {
                Text(
                    post.excerpt,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Icon(Icons.Rounded.Favorite, null, tint = colors.accent.copy(alpha = 0.8f), modifier = Modifier.size(14.dp))
                Text("${post.likeCount}", style = MaterialTheme.typography.labelSmall, color = colors.muted)
            }
        }
    }
}

@Composable
private fun FeedGridCard(
    post: FanboxPostSummary,
    isArchived: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalAndroidGalleryColors.current
    val context = LocalContext.current

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
                .aspectRatio(0.8f)
                .background(colors.surfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            if (post.coverImageUrl != null) {
                AsyncImage(
                    model = FanboxSession.imageRequest(context, post.coverImageUrl),
                    contentDescription = post.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                )
            } else {
                Text("텍스트", style = MaterialTheme.typography.labelSmall, color = colors.muted)
            }
            Row(
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (isArchived) {
                    FeedTagPill("보관됨", Color(0xCC059669), Color.White)
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                if (post.isRestricted) {
                    FeedTagPill("잠금", Color(0xCC11141C), Color.White)
                } else if (post.feeRequired > 0) {
                    FeedTagPill("¥${post.feeRequired}", Color(0xCC4F46E5), Color.White)
                }
            }
        }
        Column(modifier = Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                post.title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                post.creatorName,
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FeedTagPill(text: String, bg: Color, fg: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = fg)
    }
}

@Composable
private fun FeedErrorState(
    error: FanboxResult<Nothing>,
    onOpenWeb: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = LocalAndroidGalleryColors.current
    val (title, body, webBtn) = when (error) {
        is FanboxResult.AuthRequired -> Triple("로그인이 필요합니다", error.message, "웹뷰에서 로그인")
        is FanboxResult.CloudflareBlocked -> Triple("보안 확인 필요", error.message, "웹뷰에서 인증")
        is FanboxResult.Error -> Triple("불러올 수 없습니다", error.message, "웹뷰로 열기")
        is FanboxResult.Success -> Triple("오류", "알 수 없는 상태입니다.", "웹뷰로 열기")
    }
    Box(Modifier.fillMaxSize().padding(22.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.ink)
            Text(body, style = MaterialTheme.typography.bodySmall, color = colors.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenWeb, shape = RoundedCornerShape(10.dp)) { Text(webBtn) }
                OutlinedButton(onClick = onRetry, shape = RoundedCornerShape(10.dp)) { Text("다시 시도") }
            }
        }
    }
}

@Composable
private fun FanboxPostDetailScreen(
    postId: String,
    feedList: List<FanboxPostSummary>,
    model: ArchiveModel,
    archivedLocalId: String?,
    onSelectPost: (String) -> Unit,
    onSelectCreator: (creatorId: String, name: String, iconUrl: String?) -> Unit,
    onOpenInWeb: (String) -> Unit,
    onOpenArchived: (String) -> Unit,
    onOpenLightbox: (List<FanboxImage>, Int) -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalAndroidGalleryColors.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 80.dp.toPx() }

    val idx = remember(feedList, postId) { feedList.indexOfFirst { it.id == postId } }
    val prevPost = if (idx > 0) feedList[idx - 1] else null
    val nextPost = if (idx in 0 until feedList.lastIndex) feedList[idx + 1] else null

    var loading by remember(postId) { mutableStateOf(true) }
    var detail by remember(postId) { mutableStateOf<FanboxPostDetail?>(null) }
    var error by remember(postId) { mutableStateOf<FanboxResult<Nothing>?>(null) }

    LaunchedEffect(postId) {
        loading = true
        error = null
        val res = withContext(Dispatchers.IO) { FanboxClient.fetchPostDetail(context, postId) }
        when (res) {
            is FanboxResult.Success -> detail = res.data
            is FanboxResult.AuthRequired -> error = res
            is FanboxResult.CloudflareBlocked -> error = res
            is FanboxResult.Error -> error = res
        }
        loading = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .pointerInput(postId, prevPost?.id, nextPost?.id) {
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onHorizontalDrag = { change, amount ->
                        totalDrag += amount
                        if (kotlin.math.abs(totalDrag) > 16f) change.consume()
                    },
                    onDragEnd = {
                        when {
                            totalDrag < -swipeThresholdPx && nextPost != null -> onSelectPost(nextPost.id)
                            totalDrag > swipeThresholdPx && prevPost != null -> onSelectPost(prevPost.id)
                        }
                    },
                )
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            // Detail Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("‹ 피드", color = colors.ink, fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.weight(1f))
                if (feedList.size > 1 && idx >= 0) {
                    IconButton(onClick = { prevPost?.let { onSelectPost(it.id) } }, enabled = prevPost != null, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.ChevronLeft, "이전 게시글", modifier = Modifier.size(19.dp))
                    }
                    Text("${idx + 1}/${feedList.size}", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                    IconButton(onClick = { nextPost?.let { onSelectPost(it.id) } }, enabled = nextPost != null, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.ChevronRight, "다음 게시글", modifier = Modifier.size(19.dp))
                    }
                }
                val webTarget = detail?.summary?.postUrl ?: feedList.getOrNull(idx)?.postUrl ?: FANBOX_HOME
                TextButton(onClick = { onOpenInWeb(webTarget) }) { Text("웹뷰 ↗", color = colors.accent) }
            }

            if (model.savingProgress.isNotEmpty()) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accent)
                Text(
                    model.savingProgress,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.ink,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent)
                }
                error != null -> FeedErrorState(
                    error = error!!,
                    onOpenWeb = { onOpenInWeb(feedList.getOrNull(idx)?.postUrl ?: FANBOX_HOME) },
                    onRetry = { onSelectPost(postId) },
                )
                detail != null -> {
                    val d = detail!!
                    val s = d.summary
                    val isSavingThis = model.savingPostId == s.id
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                // Creator bar
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { onSelectCreator(s.creatorId, s.creatorName, s.creatorIconUrl) }
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (s.creatorIconUrl != null) {
                                        AsyncImage(
                                            model = FanboxSession.imageRequest(context, s.creatorIconUrl),
                                            contentDescription = s.creatorName,
                                            modifier = Modifier.size(30.dp).clip(CircleShape),
                                            contentScale = ContentScale.Crop,
                                        )
                                    }
                                    Column {
                                        Text(s.creatorName, fontWeight = FontWeight.Bold, color = colors.ink)
                                        Text(
                                            "${s.displayDate} · ${if (s.feeRequired > 0) "¥${s.feeRequired} 플랜" else "무료 공개"}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = colors.muted,
                                        )
                                    }
                                }

                                Text(
                                    s.title,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.ink,
                                )

                                // One-tap Save into Offline Library CTA
                                val assetTotal = d.images.size + d.files.size
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { model.saveFanboxPost(d, includeFiles = true) },
                                        enabled = assetTotal > 0 && model.savingPostId == null && !model.busy,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp),
                                    ) {
                                        Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            when {
                                                isSavingThis -> "저장 중…"
                                                assetTotal == 0 -> "저장할 파일 없음"
                                                archivedLocalId != null -> "보관함 업데이트 (${d.images.size}장·첨부${d.files.size})"
                                                else -> "내 보관함에 저장 (${d.images.size}장·첨부${d.files.size})"
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (archivedLocalId != null) {
                                        OutlinedButton(
                                            onClick = { onOpenArchived(archivedLocalId) },
                                            shape = RoundedCornerShape(12.dp),
                                            border = BorderStroke(1.dp, colors.emerald),
                                        ) {
                                            Icon(Icons.Rounded.CheckCircle, null, tint = colors.emerald, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("열기", color = colors.emerald)
                                        }
                                    }
                                }

                                if (s.isRestricted && assetTotal == 0) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(colors.surface)
                                            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                                            .padding(14.dp),
                                    ) {
                                        Text(
                                            "후원자 한정 게시글입니다. 웹뷰에서 플랜을 확인하거나 후원 후 다시 열어보세요.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.muted,
                                        )
                                    }
                                }

                                if (d.text.isNotBlank()) {
                                    Text(d.text, style = MaterialTheme.typography.bodyMedium, color = colors.ink)
                                }

                                if (d.tags.isNotEmpty()) {
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        d.tags.forEach { tag ->
                                            FeedTagPill("#$tag", colors.surface, colors.muted)
                                        }
                                    }
                                }
                            }
                        }

                        // 3-Column Online Images Grid
                        itemsIndexed(d.images, key = { _, img -> img.id }) { imgIdx, img ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.82f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(colors.surfaceElevated)
                                    .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                                    .clickable { onOpenLightbox(d.images, imgIdx) },
                            ) {
                                AsyncImage(
                                    model = FanboxSession.imageRequest(context, img.thumbnailUrl),
                                    contentDescription = img.fileName,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                    alignment = Alignment.TopCenter,
                                )
                            }
                        }

                        // Attachments
                        if (d.files.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    "첨부 파일 · ${d.files.size}개",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.muted,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                            items(
                                items = d.files,
                                key = { it.id },
                                span = { GridItemSpan(maxLineSpan) },
                            ) { file ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(colors.surface)
                                        .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(RoundedCornerShape(9.dp))
                                            .background(colors.secondarySoft),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(Icons.Rounded.FolderZip, null, tint = colors.secondary, modifier = Modifier.size(19.dp))
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(file.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis, color = colors.ink, style = MaterialTheme.typography.bodySmall)
                                        Text(
                                            Formatter.formatFileSize(context, file.size),
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
}

@Composable
private fun OnlineImageLightbox(
    images: List<FanboxImage>,
    initialIndex: Int,
    onClose: () -> Unit,
) {
    val colors = LocalAndroidGalleryColors.current
    val context = LocalContext.current
    val pager = rememberPagerState(initialPage = initialIndex.coerceIn(0, (images.size - 1).coerceAtLeast(0))) { images.size }

    Column(Modifier.fillMaxSize().background(colors.cinemaBg)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xE60B0D13)).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("‹ 닫기", color = Color.White, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.weight(1f))
            Text("${pager.currentPage + 1} / ${images.size}", color = Color.White, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(56.dp))
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), key = { images[it].id }) { idx ->
            val img = images[idx]
            var scale by remember(img.id) { mutableFloatStateOf(1f) }
            var offset by remember(img.id) { mutableStateOf(Offset.Zero) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .pointerInput(img.id) {
                        detectTapGestures(onDoubleTap = {
                            scale = if (scale > 1f) 1f else 2.2f
                            offset = Offset.Zero
                        })
                    }
                    .pointerInput(img.id) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.count { it.pressed }
                                if (pressed >= 2 || (pressed > 0 && scale > 1f)) {
                                    val next = (scale * event.calculateZoom()).coerceIn(1f, 6f)
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
                SubcomposeAsyncImage(
                    model = FanboxSession.imageRequest(context, img.originalUrl),
                    contentDescription = img.fileName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                    loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accent) } },
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun FanboxWebViewPane(
    initialUrl: String,
    model: ArchiveModel,
    onSwitchToFeed: () -> Unit,
) {
    val colors = LocalAndroidGalleryColors.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var pageTitle by remember { mutableStateOf("FANBOX") }
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var progress by remember { mutableIntStateOf(0) }
    var loginDetected by remember { mutableStateOf(FanboxSession.hasLoginCookie()) }

    BackHandler(enabled = canGoBack) {
        webViewRef?.goBack()
    }

    Column(Modifier.fillMaxSize()) {
        // Compact WebView Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(onClick = { webViewRef?.goBack() }, enabled = canGoBack, modifier = Modifier.size(34.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "뒤로", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { webViewRef?.goForward() }, enabled = canGoForward, modifier = Modifier.size(34.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, "앞으로", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { webViewRef?.reload() }, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Rounded.Refresh, "새로고침", modifier = Modifier.size(18.dp))
            }
            Text(
                pageTitle,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onSwitchToFeed) {
                Text("피드로 보기", color = colors.accent, fontWeight = FontWeight.SemiBold)
            }
        }

        if (progress in 1..99) {
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth(), color = colors.accent)
        }

        if (loginDetected) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.emeraldSoft)
                    .clickable(onClick = onSwitchToFeed)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = colors.emerald, modifier = Modifier.size(16.dp))
                Text(
                    "로그인 세션 확인됨 · 탭해서 네이티브 피드로 바로 보기",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.emerald,
                )
            }
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                        }
                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            if (!title.isNullOrBlank()) pageTitle = title
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val u = request?.url?.toString().orEmpty()
                            return !(u.startsWith("https://") || u.startsWith("http://"))
                        }
                        override fun onPageFinished(view: WebView?, url: String?) {
                            canGoBack = view?.canGoBack() == true
                            canGoForward = view?.canGoForward() == true
                            if (!url.isNullOrBlank()) currentUrl = url
                            FanboxSession.flushCookies()
                            loginDetected = FanboxSession.hasLoginCookie()
                        }
                    }
                    setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                        val guessed = URLUtil.guessFileName(url, contentDisposition, mimeType)
                        model.saveWebDownload(
                            url = url,
                            fileName = guessed,
                            pageUrl = currentUrl,
                            pageTitle = pageTitle,
                        )
                    }
                    loadUrl(initialUrl)
                    webViewRef = this
                }
            },
            update = { view ->
                webViewRef = view
            },
        )
    }
}
