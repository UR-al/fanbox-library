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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CardMembership
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.fanboxlibrary.shared.FanboxLinks
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class FeedCategory(val label: String) {
    HOME("홈 피드"),
    SUPPORTING("후원중"),
    CREATORS("작가 목록"),
    SEARCH("작가 찾기"),
}

private enum class MobileServiceFilter(val label: String) {
    ALL("전체"),
    FANBOX("FANBOX"),
    PATREON("Patreon"),
}

private data class WebModalRequest(
    val url: String,
    val title: String,
    val autoCloseOnLogin: Boolean = false,
    val autoCloseOnPatreonLogin: Boolean = false,
    val gateHostToUnlock: String? = null,
    val onGateRewardsUnlocked: ((List<MobileResolvedReward>) -> Unit)? = null,
)

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

    var categoryName by rememberSaveable { mutableStateOf(FeedCategory.HOME.name) }
    val category = runCatching { FeedCategory.valueOf(categoryName) }.getOrDefault(FeedCategory.HOME)

    var showAdult by rememberSaveable { mutableStateOf(prefs.getBoolean("show_adult", false)) }
    var hideTextPosts by rememberSaveable { mutableStateOf(prefs.getBoolean("hide_text_posts", false)) }
    var gridMode by rememberSaveable { mutableStateOf(prefs.getBoolean("feed_grid_mode", false)) }
    var serviceFilter by remember { mutableStateOf(MobileServiceFilter.ALL) }
    var loggedIn by remember { mutableStateOf(FanboxSession.hasLoginCookie()) }
    var patreonLoggedIn by remember { mutableStateOf(PatreonAndroidSession.hasLoginCookie()) }

    // Explicit WebView modal (ONLY opened when user taps Login, Subscribe/Plan, or explicit Web button)
    var webModal by remember { mutableStateOf<WebModalRequest?>(null) }

    // Feed state
    var feedItems by remember { mutableStateOf<List<FanboxPostSummary>>(emptyList()) }
    var feedNextUrl by remember { mutableStateOf<String?>(null) }
    var patreonNextUrl by remember { mutableStateOf<String?>(null) }
    var feedLoading by remember { mutableStateOf(false) }
    var feedError by remember { mutableStateOf<FanboxResult<Nothing>?>(null) }

    // Creators & selected creator profile/plans state
    var creators by remember { mutableStateOf<List<FanboxCreator>>(emptyList()) }
    var selectedCreator by remember { mutableStateOf<FanboxCreator?>(null) }
    var creatorPlans by remember { mutableStateOf<List<FanboxPlan>>(emptyList()) }
    var creatorsLoading by remember { mutableStateOf(false) }
    var creatorsError by remember { mutableStateOf<FanboxResult<Nothing>?>(null) }
    var followBusyId by remember { mutableStateOf<String?>(null) }

    // Creator search state
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<FanboxCreator>>(emptyList()) }
    var searchLoading by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<FanboxResult<Nothing>?>(null) }
    var hasSearched by remember { mutableStateOf(false) }

    // Detail & online lightbox state
    var activePostId by rememberSaveable { mutableStateOf<String?>(null) }
    var lightboxImages by remember { mutableStateOf<List<FanboxImage>>(emptyList()) }
    var lightboxStartIndex by remember { mutableIntStateOf(0) }

    val archivedPostIdMap = remember(model.posts) {
        buildMap {
            model.posts.forEach { p ->
                FanboxLinks.postId(p.sourceUrl)?.let { fid -> put("FANBOX:$fid", p.id); put(fid, p.id) }
                FanboxLinks.patreonPostId(p.sourceUrl)?.let { pid -> put("PATREON:$pid", p.id); put(pid, p.id) }
            }
        }
    }

    LaunchedEffect(lightboxImages.isNotEmpty(), webModal != null) {
        onFullscreenChanged(lightboxImages.isNotEmpty() || webModal != null)
    }

    fun loadFeed(reset: Boolean = true) {
        if (feedLoading) return
        val nextFb = if (reset) null else feedNextUrl
        val nextPt = if (reset) null else patreonNextUrl
        if (!reset && nextFb == null && nextPt == null) return
        feedLoading = true
        if (reset) feedError = null
        scope.launch {
            loggedIn = FanboxSession.hasLoginCookie()
            patreonLoggedIn = PatreonAndroidSession.hasLoginCookie()
            val creator = selectedCreator
            val collected = mutableListOf<FanboxPostSummary>()
            var lastErr: FanboxResult<Nothing>? = null
            var newFbNext: String? = null
            var newPtNext: String? = null

            if (creator != null) {
                val res = if (creator.service == MobileFeedService.PATREON) {
                    PatreonAndroidClient.fetchCampaignPosts(creator.creatorId, nextPt)
                } else {
                    FanboxClient.fetchCreatorPosts(context, creator.creatorId, nextFb)
                }
                when (res) {
                    is FanboxResult.Success -> {
                        collected.addAll(res.data.items)
                        if (creator.service == MobileFeedService.PATREON) newPtNext = res.data.nextUrl else newFbNext = res.data.nextUrl
                    }
                    is FanboxResult.AuthRequired -> lastErr = res
                    is FanboxResult.CloudflareBlocked -> lastErr = res
                    is FanboxResult.Error -> lastErr = res
                }
            } else {
                val wantFb = serviceFilter != MobileServiceFilter.PATREON && loggedIn && (reset || nextFb != null)
                val wantPt = serviceFilter != MobileServiceFilter.FANBOX && patreonLoggedIn && (reset || nextPt != null)
                if (wantFb) {
                    val res = if (category == FeedCategory.SUPPORTING) FanboxClient.fetchSupportingFeed(context, nextFb)
                    else FanboxClient.fetchHomeFeed(context, nextFb)
                    when (res) {
                        is FanboxResult.Success -> { collected.addAll(res.data.items); newFbNext = res.data.nextUrl }
                        is FanboxResult.AuthRequired -> lastErr = res
                        is FanboxResult.CloudflareBlocked -> lastErr = res
                        is FanboxResult.Error -> lastErr = res
                    }
                }
                if (wantPt) {
                    when (val res = PatreonAndroidClient.fetchStream(nextPt)) {
                        is FanboxResult.Success -> { collected.addAll(res.data.items); newPtNext = res.data.nextUrl }
                        is FanboxResult.AuthRequired -> if (collected.isEmpty()) lastErr = res
                        is FanboxResult.CloudflareBlocked -> if (collected.isEmpty()) lastErr = res
                        is FanboxResult.Error -> if (collected.isEmpty()) lastErr = res
                    }
                }
            }

            if (collected.isNotEmpty() || lastErr == null) {
                val merged = if (reset) collected else (feedItems + collected)
                feedItems = merged.distinctBy { it.uniqueKey }.sortedByDescending { it.publishedDatetime }
                feedNextUrl = newFbNext
                patreonNextUrl = newPtNext
                feedError = null
            } else {
                feedError = lastErr
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
            patreonLoggedIn = PatreonAndroidSession.hasLoginCookie()
            val combined = mutableListOf<FanboxCreator>()
            var lastErr: FanboxResult<Nothing>? = null
            if (serviceFilter != MobileServiceFilter.PATREON && loggedIn) {
                when (val r = FanboxClient.fetchCreators(context)) {
                    is FanboxResult.Success -> combined.addAll(r.data)
                    is FanboxResult.AuthRequired -> lastErr = r
                    is FanboxResult.CloudflareBlocked -> lastErr = r
                    is FanboxResult.Error -> lastErr = r
                }
            }
            if (serviceFilter != MobileServiceFilter.FANBOX && patreonLoggedIn) {
                when (val r = PatreonAndroidClient.fetchSupportingCreators()) {
                    is FanboxResult.Success -> combined.addAll(r.data)
                    is FanboxResult.AuthRequired -> if (combined.isEmpty()) lastErr = r
                    is FanboxResult.CloudflareBlocked -> if (combined.isEmpty()) lastErr = r
                    is FanboxResult.Error -> if (combined.isEmpty()) lastErr = r
                }
            }
            if (combined.isNotEmpty() || lastErr == null) {
                creators = combined.distinctBy { "${it.service.name}:${it.creatorId}" }
                creatorsError = null
            } else {
                creatorsError = lastErr
            }
            creatorsLoading = false
        }
    }

    fun runCreatorSearch() {
        val q = searchQuery.trim()
        if (q.isEmpty() || searchLoading) return
        searchLoading = true
        searchError = null
        hasSearched = true
        scope.launch {
            val found = mutableListOf<FanboxCreator>()
            var err: FanboxResult<Nothing>? = null
            if (serviceFilter != MobileServiceFilter.PATREON) {
                when (val r = FanboxClient.searchCreators(context, q)) {
                    is FanboxResult.Success -> found.addAll(r.data)
                    is FanboxResult.AuthRequired -> err = r
                    is FanboxResult.CloudflareBlocked -> err = r
                    is FanboxResult.Error -> err = r
                }
            }
            if (serviceFilter != MobileServiceFilter.FANBOX) {
                when (val r = PatreonAndroidClient.searchCreators(q)) {
                    is FanboxResult.Success -> found.addAll(r.data)
                    is FanboxResult.AuthRequired -> if (found.isEmpty()) err = r
                    is FanboxResult.CloudflareBlocked -> if (found.isEmpty()) err = r
                    is FanboxResult.Error -> if (found.isEmpty()) err = r
                }
            }
            if (found.isNotEmpty() || err == null) {
                searchResults = found.distinctBy { "${it.service.name}:${it.creatorId}" }
                searchError = null
            } else {
                searchError = err
            }
            searchLoading = false
        }
    }

    fun toggleFollow(target: FanboxCreator) {
        if (followBusyId != null) return
        followBusyId = target.creatorId
        scope.launch {
            // Ensure we have the numeric userId via creator.get if missing
            val fullCreator = if (target.userId.isNotBlank()) target else {
                (FanboxClient.fetchCreatorProfile(context, target.creatorId) as? FanboxResult.Success)?.data ?: target
            }
            val desired = !fullCreator.isFollowed
            when (val res = FanboxClient.setFollowCreator(context, fullCreator.userId, desired)) {
                is FanboxResult.Success -> {
                    val updated = fullCreator.copy(isFollowed = desired)
                    if (selectedCreator?.creatorId == updated.creatorId) selectedCreator = updated
                    searchResults = searchResults.map { if (it.creatorId == updated.creatorId) updated else it }
                    creators = if (desired) {
                        (creators.filterNot { it.creatorId == updated.creatorId } + updated).sortedByDescending { it.isSupporting }
                    } else {
                        creators.filterNot { it.creatorId == updated.creatorId && !it.isSupporting }
                            .map { if (it.creatorId == updated.creatorId) updated else it }
                    }
                    model.notice = if (desired) "${updated.name} 작가를 팔로우했습니다." else "${updated.name} 팔로우를 해제했습니다."
                }
                is FanboxResult.AuthRequired -> {
                    model.notice = "팔로우하려면 FANBOX 로그인이 필요합니다."
                }
                is FanboxResult.CloudflareBlocked -> model.notice = res.message
                is FanboxResult.Error -> model.notice = res.message
            }
            followBusyId = null
        }
    }

    // Load full creator profile + plans whenever a creator is selected
    LaunchedEffect(selectedCreator?.creatorId, selectedCreator?.service) {
        val cr = selectedCreator ?: return@LaunchedEffect
        creatorPlans = emptyList()
        if (cr.service == MobileFeedService.FANBOX) {
            val profileRes = FanboxClient.fetchCreatorProfile(context, cr.creatorId)
            if (profileRes is FanboxResult.Success) {
                selectedCreator = profileRes.data.copy(
                    isSupporting = profileRes.data.isSupporting || cr.isSupporting,
                    planFee = cr.planFee,
                )
            }
            val plansRes = FanboxClient.fetchCreatorPlans(context, cr.creatorId)
            if (plansRes is FanboxResult.Success) {
                creatorPlans = plansRes.data
            }
        }
    }

    LaunchedEffect(category, serviceFilter, selectedCreator?.creatorId, loggedIn, patreonLoggedIn) {
        loggedIn = FanboxSession.hasLoginCookie()
        patreonLoggedIn = PatreonAndroidSession.hasLoginCookie()
        val anyAuth = loggedIn || patreonLoggedIn
        when {
            selectedCreator != null -> loadFeed(reset = true)
            category == FeedCategory.CREATORS -> if (anyAuth) loadCreators()
            category == FeedCategory.SEARCH -> Unit
            else -> if (anyAuth) loadFeed(reset = true)
        }
    }

    val colors = LocalAndroidGalleryColors.current

    Box(Modifier.fillMaxSize().background(colors.bg)) {
        // Hidden persistent 1x1 Chromium WebView that powers all api.fanbox.cc requests cleanly
        AndroidView(
            modifier = Modifier.size(1.dp).alpha(0f),
            factory = { ctx -> FanboxWebBridge.attachOrCreate(ctx) },
        )

        // Fullscreen Online Image Lightbox
        if (lightboxImages.isNotEmpty()) {
            BackHandler { lightboxImages = emptyList() }
            OnlineImageLightbox(
                images = lightboxImages,
                initialIndex = lightboxStartIndex,
                onClose = { lightboxImages = emptyList() },
            )
            return@Box
        }

        // Explicit WebView Modal (Login / Plan Checkout / Optional Web Browse)
        val modal = webModal
        if (modal != null) {
            BackHandler { webModal = null }
            FanboxWebModalScreen(
                request = modal,
                model = model,
                onClose = { didLogin ->
                    webModal = null
                    loggedIn = FanboxSession.hasLoginCookie()
                    patreonLoggedIn = PatreonAndroidSession.hasLoginCookie()
                    if (didLogin) {
                        FanboxWebBridge.reloadOrigin()
                        scope.launch {
                            delay(500)
                            if (selectedCreator != null) loadFeed(reset = true)
                            else if (category == FeedCategory.CREATORS) loadCreators()
                            else if (category != FeedCategory.SEARCH) loadFeed(reset = true)
                        }
                    }
                },
            )
            return@Box
        }

        // Native Post Detail Screen
        val currentPostId = activePostId
        if (currentPostId != null) {
            BackHandler { activePostId = null }
            val visibleFeed = remember(feedItems, showAdult, serviceFilter, selectedCreator) {
                feedItems.filter { p ->
                    val okAdult = showAdult || !p.hasAdultContent
                    val okSvc = selectedCreator != null || serviceFilter == MobileServiceFilter.ALL ||
                        (serviceFilter == MobileServiceFilter.FANBOX && p.service == MobileFeedService.FANBOX) ||
                        (serviceFilter == MobileServiceFilter.PATREON && p.service == MobileFeedService.PATREON)
                    okAdult && okSvc
                }
            }
            val activeSummary = remember(feedItems, currentPostId) { feedItems.firstOrNull { it.id == currentPostId } }
            FanboxPostDetailScreen(
                postId = currentPostId,
                serviceHint = activeSummary?.service ?: MobileFeedService.FANBOX,
                feedList = visibleFeed,
                model = model,
                archivedLocalId = archivedPostIdMap[activeSummary?.uniqueKey ?: currentPostId],
                onSelectPost = { activePostId = it },
                onSelectCreator = { id, name, icon, svc ->
                    activePostId = null
                    selectedCreator = FanboxCreator(id, "", name, icon, null, "", service = svc)
                },
                onOpenInWeb = { url ->
                    webModal = WebModalRequest(url = url, title = "원문 보기")
                },
                onRequestGateWebUnlock = { startUrl, gateHost, onUnlocked ->
                    webModal = WebModalRequest(
                        url = startUrl,
                        title = "Patreon 보상 잠금 해제",
                        gateHostToUnlock = gateHost,
                        onGateRewardsUnlocked = onUnlocked,
                    )
                },
                onOpenArchived = onOpenArchivedPost,
                onOpenLightbox = { imgs, start ->
                    lightboxImages = imgs
                    lightboxStartIndex = start
                },
                onBack = { activePostId = null },
            )
            return@Box
        }

        // Main Native FANBOX Hub
        Column(Modifier.fillMaxSize()) {
            // Top Header Bar (stays 100% native!)
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
                Text(
                    "FANBOX · Patreon",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.ink,
                )

                Spacer(Modifier.weight(1f))

                // R-18 Filter Toggle
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

                // Hide Text Posts Toggle
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (hideTextPosts) colors.secondarySoft else colors.surface)
                        .border(1.dp, if (hideTextPosts) colors.secondary else colors.border, RoundedCornerShape(9.dp))
                        .clickable {
                            hideTextPosts = !hideTextPosts
                            prefs.edit().putBoolean("hide_text_posts", hideTextPosts).apply()
                        }
                        .padding(horizontal = 7.dp, vertical = 6.dp),
                ) {
                    Text(
                        if (hideTextPosts) "글숨김" else "글표시",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (hideTextPosts) colors.secondary else colors.muted,
                    )
                }

                // Explicit Web Browser Icon (never auto-triggered!)
                IconButton(
                    onClick = {
                        webModal = WebModalRequest(
                            url = selectedCreator?.let { "https://${it.creatorId}.fanbox.cc/" } ?: FANBOX_HOME,
                            title = "FANBOX 웹",
                            autoCloseOnLogin = !loggedIn,
                        )
                    },
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(9.dp)),
                ) {
                    Icon(Icons.Rounded.Language, contentDescription = "FANBOX 웹 열기", tint = colors.muted, modifier = Modifier.size(17.dp))
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

            // Mobile Service Filter + Patreon Quick Login Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MobileServiceFilter.entries.forEach { sf ->
                    val sel = serviceFilter == sf
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (sel) colors.accent else colors.surface)
                            .border(1.dp, if (sel) colors.accent else colors.border, RoundedCornerShape(8.dp))
                            .clickable { serviceFilter = sf }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(sf.label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (sel) Color.White else colors.muted)
                    }
                }
                if (!loggedIn) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.accentSoft)
                            .clickable {
                                webModal = WebModalRequest("https://www.fanbox.cc/login", "FANBOX 로그인", autoCloseOnLogin = true)
                            }
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                    ) {
                        Text("FANBOX 로그인", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = colors.accent)
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (patreonLoggedIn) colors.emeraldSoft else Color(0x33FF6B3D))
                        .clickable {
                            webModal = WebModalRequest("https://www.patreon.com/login", "Patreon 로그인", autoCloseOnPatreonLogin = true)
                        }
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                ) {
                    Text(
                        if (patreonLoggedIn) "Patreon 연결됨 ✓" else "Patreon 로그인",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (patreonLoggedIn) colors.emerald else Color(0xFFFF6B3D),
                    )
                }
            }

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

            // Sub-navigation Pills (Home / Supporting / Creators / Search)
            if (selectedCreator == null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FeedCategory.entries.forEach { cat ->
                            val active = category == cat
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(if (active) colors.accent else colors.surface)
                                    .border(1.dp, if (active) colors.accent else colors.border, RoundedCornerShape(20.dp))
                                    .clickable { categoryName = cat.name }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (cat == FeedCategory.SEARCH) {
                                    Icon(
                                        Icons.Rounded.Search,
                                        null,
                                        tint = if (active) Color.White else colors.muted,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                                Text(
                                    cat.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (active) Color.White else colors.muted,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                                )
                            }
                        }
                    }

                    if (category == FeedCategory.HOME || category == FeedCategory.SUPPORTING) {
                        IconButton(
                            onClick = {
                                gridMode = !gridMode
                                prefs.edit().putBoolean("feed_grid_mode", gridMode).apply()
                            },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                if (gridMode) Icons.Rounded.ViewAgenda else Icons.Rounded.GridView,
                                contentDescription = "보기 모드",
                                tint = colors.muted,
                                modifier = Modifier.size(19.dp),
                            )
                        }
                        IconButton(onClick = { loadFeed(reset = true) }, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "새로고침", tint = colors.muted, modifier = Modifier.size(19.dp))
                        }
                    } else if (category == FeedCategory.CREATORS) {
                        IconButton(onClick = ::loadCreators, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "새로고침", tint = colors.muted, modifier = Modifier.size(19.dp))
                        }
                    }
                }
            }

            // Content Area
            when {
                selectedCreator != null -> {
                    val cr = selectedCreator!!
                    BackHandler { selectedCreator = null }
                    CreatorProfileAndFeedPane(
                        creator = cr,
                        plans = creatorPlans,
                        posts = remember(feedItems, showAdult) {
                            if (showAdult) feedItems else feedItems.filterNot { it.hasAdultContent }
                        },
                        hiddenAdultCount = remember(feedItems, showAdult) {
                            if (showAdult) 0 else feedItems.count { it.hasAdultContent }
                        },
                        gridMode = gridMode,
                        onToggleGrid = {
                            gridMode = !gridMode
                            prefs.edit().putBoolean("feed_grid_mode", gridMode).apply()
                        },
                        loading = feedLoading,
                        error = feedError,
                        hasMore = feedNextUrl != null,
                        followBusy = followBusyId == cr.creatorId,
                        archivedIds = archivedPostIdMap.keys,
                        onBack = { selectedCreator = null },
                        onToggleFollow = { toggleFollow(cr) },
                        onOpenPlansCheckout = {
                            webModal = WebModalRequest(
                                url = "https://${cr.creatorId}.fanbox.cc/plans",
                                title = "${cr.name} 후원 플랜",
                            )
                        },
                        onEnableAdult = {
                            showAdult = true
                            prefs.edit().putBoolean("show_adult", true).apply()
                        },
                        onOpenPost = { activePostId = it.id },
                        onLoadMore = { loadFeed(reset = false) },
                        onRetry = { loadFeed(reset = true) },
                        onLogin = {
                            webModal = WebModalRequest("https://www.fanbox.cc/login", "FANBOX 로그인", autoCloseOnLogin = true)
                        },
                    )
                }

                category == FeedCategory.SEARCH -> {
                    CreatorSearchPane(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onSearch = ::runCreatorSearch,
                        results = searchResults,
                        loading = searchLoading,
                        error = searchError,
                        hasSearched = hasSearched,
                        followBusyId = followBusyId,
                        onSelectCreator = { selectedCreator = it },
                        onToggleFollow = ::toggleFollow,
                        onLogin = {
                            webModal = WebModalRequest("https://www.fanbox.cc/login", "FANBOX 로그인", autoCloseOnLogin = true)
                        },
                    )
                }

                !loggedIn && feedItems.isEmpty() && creators.isEmpty() -> {
                    FanboxLoginPromptCard(
                        onLogin = {
                            webModal = WebModalRequest(
                                url = "https://www.fanbox.cc/login",
                                title = "FANBOX 로그인",
                                autoCloseOnLogin = true,
                            )
                        },
                        onGoSearch = { categoryName = FeedCategory.SEARCH.name },
                    )
                }

                category == FeedCategory.CREATORS -> {
                    CreatorsListPane(
                        creators = creators,
                        loading = creatorsLoading,
                        error = creatorsError,
                        followBusyId = followBusyId,
                        onSelectCreator = { selectedCreator = it },
                        onToggleFollow = ::toggleFollow,
                        onOpenLogin = {
                            webModal = WebModalRequest("https://www.fanbox.cc/login", "FANBOX 로그인", autoCloseOnLogin = true)
                        },
                        onRetry = ::loadCreators,
                    )
                }

                else -> {
                    val hiddenAdultCount = remember(feedItems, showAdult) {
                        if (showAdult) 0 else feedItems.count { it.hasAdultContent }
                    }
                    val visibleFeed = remember(feedItems, showAdult, hideTextPosts) {
                        feedItems.filter { p ->
                            (showAdult || !p.hasAdultContent) && (!hideTextPosts || !p.isTextOnly)
                        }
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
                            selectedCreator = FanboxCreator(p.creatorId, "", p.creatorName, p.creatorIconUrl, null, "")
                        },
                        onLoadMore = { loadFeed(reset = false) },
                        onRetry = {
                            FanboxWebBridge.reloadOrigin()
                            scope.launch { delay(500); loadFeed(reset = true) }
                        },
                        onOpenLogin = {
                            webModal = WebModalRequest("https://www.fanbox.cc/login", "FANBOX 로그인", autoCloseOnLogin = true)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CreatorSearchPane(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    results: List<FanboxCreator>,
    loading: Boolean,
    error: FanboxResult<Nothing>?,
    hasSearched: Boolean,
    followBusyId: String?,
    onSelectCreator: (FanboxCreator) -> Unit,
    onToggleFollow: (FanboxCreator) -> Unit,
    onLogin: () -> Unit,
) {
    val colors = LocalAndroidGalleryColors.current
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                placeholder = { Text("작가명, @아이디, fanbox 주소 검색…", style = MaterialTheme.typography.bodySmall) },
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = colors.muted) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Rounded.Close, "지우기", tint = colors.muted)
                        }
                    }
                } else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = colors.surface,
                    unfocusedContainerColor = colors.surface,
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.border,
                ),
            )
            Button(
                onClick = onSearch,
                enabled = query.isNotBlank() && !loading,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text("검색")
            }
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }
            error != null -> FeedErrorState(error = error, onLogin = onLogin, onRetry = onSearch)
            !hasSearched -> Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Rounded.Search, null, tint = colors.muted, modifier = Modifier.size(36.dp))
                    Text("작가 이름이나 @ID로 바로 찾아보세요", style = MaterialTheme.typography.titleSmall, color = colors.ink)
                    Text(
                        "예: ototo, @작가ID, 또는 https://작가ID.fanbox.cc",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                    )
                }
            }
            results.isEmpty() -> Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                Text("검색 결과가 없습니다. 작가의 영문 @ID로도 시도해 보세요.", color = colors.muted, style = MaterialTheme.typography.bodySmall)
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(results, key = { it.creatorId }) { creator ->
                    CreatorRowCard(
                        creator = creator,
                        followBusy = followBusyId == creator.creatorId,
                        onClick = { onSelectCreator(creator) },
                        onToggleFollow = { onToggleFollow(creator) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CreatorProfileAndFeedPane(
    creator: FanboxCreator,
    plans: List<FanboxPlan>,
    posts: List<FanboxPostSummary>,
    hiddenAdultCount: Int,
    gridMode: Boolean,
    onToggleGrid: () -> Unit,
    loading: Boolean,
    error: FanboxResult<Nothing>?,
    hasMore: Boolean,
    followBusy: Boolean,
    archivedIds: Set<String>,
    onBack: () -> Unit,
    onToggleFollow: () -> Unit,
    onOpenPlansCheckout: () -> Unit,
    onEnableAdult: () -> Unit,
    onOpenPost: (FanboxPostSummary) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onLogin: () -> Unit,
) {
    val colors = LocalAndroidGalleryColors.current
    val context = LocalContext.current
    var showPlans by remember(creator.creatorId) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        // Creator Profile Header Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.ChevronLeft, "뒤로", tint = colors.ink)
                }
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(colors.surfaceElevated),
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
                Column(Modifier.weight(1f)) {
                    Text(creator.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("@${creator.creatorId}", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                }

                // Follow / Unfollow Native Button
                Button(
                    onClick = onToggleFollow,
                    enabled = !followBusy,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (creator.isFollowed) colors.surfaceElevated else colors.accent,
                        contentColor = if (creator.isFollowed) colors.ink else Color.White,
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(
                        if (creator.isFollowed) Icons.Rounded.Check else Icons.Rounded.PersonAdd,
                        null,
                        Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (followBusy) "…" else if (creator.isFollowed) "팔로우 중" else "팔로우",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedButton(
                    onClick = { if (plans.isNotEmpty()) showPlans = !showPlans else onOpenPlansCheckout() },
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, if (creator.isSupporting) colors.emerald else colors.secondary),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(
                        Icons.Rounded.CardMembership,
                        null,
                        tint = if (creator.isSupporting) colors.emerald else colors.secondary,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        when {
                            creator.isSupporting -> "후원중 · 플랜 보기"
                            plans.isNotEmpty() -> "후원 플랜 ${plans.size}개 ${if (showPlans) "▴" else "▾"}"
                            else -> "후원 / 구독하기"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (creator.isSupporting) colors.emerald else colors.secondary,
                    )
                }

                Spacer(Modifier.weight(1f))

                IconButton(onClick = onToggleGrid, modifier = Modifier.size(32.dp)) {
                    Icon(if (gridMode) Icons.Rounded.ViewAgenda else Icons.Rounded.GridView, "보기 전환", tint = colors.muted, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onRetry, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Refresh, "새로고침", tint = colors.muted, modifier = Modifier.size(18.dp))
                }
            }

            // Expandable Subscription Plans Strip
            if (showPlans && plans.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    plans.forEach { plan ->
                        Column(
                            modifier = Modifier
                                .width(190.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.bg)
                                .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                                .clickable(onClick = onOpenPlansCheckout)
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text("¥${plan.fee}/월", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = colors.accent)
                            Text(plan.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (plan.description.isNotBlank()) {
                                Text(plan.description, style = MaterialTheme.typography.labelSmall, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Text("이 플랜 후원하기 ↗", style = MaterialTheme.typography.labelSmall, color = colors.secondary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = colors.border)

        FeedPostsPane(
            posts = posts,
            hiddenAdultCount = hiddenAdultCount,
            gridMode = gridMode,
            loading = loading,
            error = error,
            hasMore = hasMore,
            archivedIds = archivedIds,
            onEnableAdult = onEnableAdult,
            onOpenPost = onOpenPost,
            onSelectCreator = {},
            onLoadMore = onLoadMore,
            onRetry = onRetry,
            onOpenLogin = onLogin,
        )
    }
}

@Composable
private fun CreatorRowCard(
    creator: FanboxCreator,
    followBusy: Boolean,
    onClick: () -> Unit,
    onToggleFollow: () -> Unit,
) {
    val colors = LocalAndroidGalleryColors.current
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape).background(colors.surfaceElevated),
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
                Text(creator.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (creator.isSupporting) {
                    FeedTagPill(if (creator.planFee > 0) "후원 ¥${creator.planFee}" else "후원중", colors.emeraldSoft, colors.emerald)
                }
            }
            Text("@${creator.creatorId}", style = MaterialTheme.typography.labelSmall, color = colors.muted)
            if (creator.description.isNotBlank()) {
                Text(creator.description, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        OutlinedButton(
            onClick = onToggleFollow,
            enabled = !followBusy,
            shape = RoundedCornerShape(9.dp),
            border = BorderStroke(1.dp, if (creator.isFollowed) colors.border else colors.accent),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text(
                if (followBusy) "…" else if (creator.isFollowed) "팔로우 중" else "팔로우",
                style = MaterialTheme.typography.labelSmall,
                color = if (creator.isFollowed) colors.muted else colors.accent,
            )
        }
    }
}

@Composable
private fun FanboxLoginPromptCard(
    onLogin: () -> Unit,
    onGoSearch: () -> Unit,
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
            Text("FANBOX 로그인", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.ink)
            Text(
                "pixiv 계정에 한 번 로그인하면 자동으로 피드로 돌아와 후원·팔로우 작가의 새 글을 바로 감상하고 보관함에 저장할 수 있습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
            Button(onClick = onLogin, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.AutoMirrored.Rounded.Login, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("FANBOX 로그인하기")
            }
            OutlinedButton(
                onClick = onGoSearch,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, colors.border),
            ) {
                Icon(Icons.Rounded.Search, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("로그인 없이 작가 검색해보기")
            }
            Text(
                "비공식 클라이언트입니다. 로그인 정보는 기기 내부에만 유지됩니다.",
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
    followBusyId: String?,
    onSelectCreator: (FanboxCreator) -> Unit,
    onToggleFollow: (FanboxCreator) -> Unit,
    onOpenLogin: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = LocalAndroidGalleryColors.current
    if (loading && creators.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = colors.accent)
        }
        return
    }
    if (error != null && creators.isEmpty()) {
        FeedErrorState(error, onOpenLogin, onRetry)
        return
    }
    if (creators.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("후원 또는 팔로우 중인 작가가 없습니다. ‘작가 찾기’에서 추가해 보세요!", color = colors.muted, style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(creators, key = { it.creatorId }) { creator ->
            CreatorRowCard(
                creator = creator,
                followBusy = followBusyId == creator.creatorId,
                onClick = { onSelectCreator(creator) },
                onToggleFollow = { onToggleFollow(creator) },
            )
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
    onOpenLogin: () -> Unit,
) {
    val colors = LocalAndroidGalleryColors.current

    if (loading && posts.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = colors.accent)
        }
        return
    }
    if (error != null && posts.isEmpty()) {
        FeedErrorState(error, onOpenLogin, onRetry)
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
                        "R-18 게시글 ${hiddenAdultCount}개 숨김 · 탭하여 모두 표시",
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
                        OutlinedButton(onClick = onLoadMore, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, colors.border)) {
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
                    Text(post.creatorName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(post.displayDate, style = MaterialTheme.typography.labelSmall, color = colors.muted)
                }
            }

            if (isArchived) FeedTagPill("보관됨", colors.emeraldSoft, colors.emerald)
            if (post.feeRequired > 0) FeedTagPill("¥${post.feeRequired}", colors.secondarySoft, colors.secondary)
            else FeedTagPill("무료", colors.surfaceElevated, colors.muted)
            if (post.hasAdultContent) FeedTagPill("R-18", colors.accentSoft, colors.accent)
        }

        if (post.coverImageUrl != null) {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1.45f).background(colors.surfaceElevated),
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

        Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(post.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (post.excerpt.isNotBlank()) {
                Text(post.excerpt, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(top = 2.dp)) {
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
            modifier = Modifier.fillMaxWidth().aspectRatio(0.8f).background(colors.surfaceElevated),
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
                if (isArchived) FeedTagPill("보관됨", Color(0xCC059669), Color.White)
                else Spacer(Modifier.width(1.dp))
                if (post.isRestricted) FeedTagPill("잠금", Color(0xCC11141C), Color.White)
                else if (post.feeRequired > 0) FeedTagPill("¥${post.feeRequired}", Color(0xCC4F46E5), Color.White)
            }
        }
        Column(modifier = Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(post.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(post.creatorName, style = MaterialTheme.typography.labelSmall, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun FeedTagPill(text: String, bg: Color, fg: Color) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(bg).padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = fg)
    }
}

@Composable
private fun FeedErrorState(
    error: FanboxResult<Nothing>,
    onLogin: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = LocalAndroidGalleryColors.current
    val (title, body, needsLogin) = when (error) {
        is FanboxResult.AuthRequired -> Triple("로그인이 필요합니다", error.message, true)
        is FanboxResult.CloudflareBlocked -> Triple("연결 확인 중", error.message, false)
        is FanboxResult.Error -> Triple("불러올 수 없습니다", error.message, false)
        is FanboxResult.Success -> Triple("오류", "알 수 없는 상태입니다.", false)
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
                Button(onClick = onRetry, shape = RoundedCornerShape(10.dp)) { Text("다시 시도") }
                if (needsLogin) {
                    OutlinedButton(onClick = onLogin, shape = RoundedCornerShape(10.dp)) { Text("로그인") }
                }
            }
        }
    }
}

@Composable
private fun FanboxPostDetailScreen(
    postId: String,
    serviceHint: MobileFeedService,
    feedList: List<FanboxPostSummary>,
    model: ArchiveModel,
    archivedLocalId: String?,
    onSelectPost: (String) -> Unit,
    onSelectCreator: (creatorId: String, name: String, iconUrl: String?, svc: MobileFeedService) -> Unit,
    onOpenInWeb: (String) -> Unit,
    onRequestGateWebUnlock: (startUrl: String, gateHost: String, onUnlocked: (List<MobileResolvedReward>) -> Unit) -> Unit,
    onOpenArchived: (String) -> Unit,
    onOpenLightbox: (List<FanboxImage>, Int) -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalAndroidGalleryColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 80.dp.toPx() }

    val idx = remember(feedList, postId) { feedList.indexOfFirst { it.id == postId } }
    val activeService = feedList.getOrNull(idx)?.service ?: serviceHint
    val prevPost = if (idx > 0) feedList[idx - 1] else null
    val nextPost = if (idx in 0 until feedList.lastIndex) feedList[idx + 1] else null

    var loading by remember(postId) { mutableStateOf(true) }
    var detail by remember(postId) { mutableStateOf<FanboxPostDetail?>(null) }
    var error by remember(postId) { mutableStateOf<FanboxResult<Nothing>?>(null) }
    var reloadKey by remember(postId) { mutableIntStateOf(0) }
    var unlockedMultiRewards by remember(postId) { mutableStateOf<List<MobileResolvedReward>>(emptyList()) }

    LaunchedEffect(postId, reloadKey, activeService) {
        loading = true
        error = null
        val res = if (activeService == MobileFeedService.PATREON) {
            PatreonAndroidClient.fetchPostDetail(postId)
        } else {
            FanboxClient.fetchPostDetail(context, postId)
        }
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
            Row(
                modifier = Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 8.dp, vertical = 6.dp),
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
                IconButton(onClick = { onOpenInWeb(webTarget) }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Rounded.Language, "원문 웹", tint = colors.muted, modifier = Modifier.size(18.dp))
                }
            }

            if (model.savingProgress.isNotEmpty()) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accent)
                Text(model.savingProgress, style = MaterialTheme.typography.labelSmall, color = colors.ink, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
            }

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent)
                }
                error != null -> FeedErrorState(
                    error = error!!,
                    onLogin = { onOpenInWeb("https://www.fanbox.cc/login") },
                    onRetry = { reloadKey++ },
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
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { onSelectCreator(s.creatorId, s.creatorName, s.creatorIconUrl, s.service) }
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
                                        Text("${s.displayDate} · ${if (s.feeRequired > 0) "¥${s.feeRequired} 플랜" else "무료 공개"}", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                                    }
                                }

                                Text(s.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = colors.ink)

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
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(colors.surface)
                                            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                                            .padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text("후원자 한정 게시글입니다.", style = MaterialTheme.typography.titleSmall, color = colors.ink)
                                        Text("작가 페이지에서 후원 플랜을 확인하고 구독할 수 있습니다.", style = MaterialTheme.typography.bodySmall, color = colors.muted)
                                        OutlinedButton(
                                            onClick = { onSelectCreator(s.creatorId, s.creatorName, s.creatorIconUrl, s.service) },
                                            shape = RoundedCornerShape(10.dp),
                                        ) { Text("작가 플랜 보기") }
                                    }
                                }

                                if (d.externalLinks.isNotEmpty()) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(colors.surface)
                                            .border(1.dp, colors.emerald.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text("외부 보상 원클릭 인증 · 저장", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = colors.emerald)
                                        d.externalLinks.forEach { extLink ->
                                            Button(
                                                onClick = {
                                                    scope.launch {
                                                        when (val chk = AndroidRewardTools.checkExternalLink(extLink)) {
                                                            is MobileGateCheckResult.Ready -> {
                                                                if (chk.rewards.size == 1) model.saveMobileExternalRewards(d, chk.rewards)
                                                                else unlockedMultiRewards = chk.rewards
                                                            }
                                                            is MobileGateCheckResult.NeedsWebUnlock -> {
                                                                onRequestGateWebUnlock(chk.startUrl, chk.gateHost) { unlocked ->
                                                                    if (unlocked.size == 1) model.saveMobileExternalRewards(d, unlocked)
                                                                    else unlockedMultiRewards = unlocked
                                                                }
                                                            }
                                                            is MobileGateCheckResult.Failed -> model.notice = chk.message
                                                        }
                                                    }
                                                },
                                                enabled = model.savingPostId == null && !model.busy,
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(10.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = colors.emerald),
                                            ) {
                                                Icon(Icons.Rounded.Download, null, Modifier.size(16.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text("${extLink.kind.badge} · ${extLink.label}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                        unlockedMultiRewards.forEach { rw ->
                                            OutlinedButton(
                                                onClick = { model.saveMobileExternalRewards(d, listOf(rw)) },
                                                enabled = model.savingPostId == null,
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(9.dp),
                                            ) {
                                                Text("저장: ${rw.label}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                }

                                if (d.text.isNotBlank()) {
                                    Text(d.text, style = MaterialTheme.typography.bodyMedium, color = colors.ink)
                                }

                                if (d.tags.isNotEmpty()) {
                                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        d.tags.forEach { tag -> FeedTagPill("#$tag", colors.surface, colors.muted) }
                                    }
                                }
                            }
                        }

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

                        if (d.files.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text("첨부 파일 · ${d.files.size}개", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = colors.muted, modifier = Modifier.padding(top = 6.dp))
                            }
                            items(items = d.files, key = { it.id }, span = { GridItemSpan(maxLineSpan) }) { file ->
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
                                        modifier = Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)).background(colors.secondarySoft),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(Icons.Rounded.FolderZip, null, tint = colors.secondary, modifier = Modifier.size(19.dp))
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(file.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis, color = colors.ink, style = MaterialTheme.typography.bodySmall)
                                        Text(Formatter.formatFileSize(context, file.size), style = MaterialTheme.typography.labelSmall, color = colors.muted)
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
                        scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                    },
                    loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accent) } },
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun FanboxWebModalScreen(
    request: WebModalRequest,
    model: ArchiveModel,
    onClose: (didLogin: Boolean) -> Unit,
) {
    val colors = LocalAndroidGalleryColors.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var pageTitle by remember { mutableStateOf(request.title) }
    var currentUrl by remember { mutableStateOf(request.url) }
    var progress by remember { mutableIntStateOf(0) }
    val initiallyLoggedIn = remember { FanboxSession.hasLoginCookie() }
    var nowLoggedIn by remember { mutableStateOf(initiallyLoggedIn) }

    BackHandler {
        if (canGoBack) webViewRef?.goBack() else onClose(nowLoggedIn && !initiallyLoggedIn)
    }

    Column(Modifier.fillMaxSize().background(colors.bg)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            TextButton(onClick = { onClose(nowLoggedIn) }) {
                Text("‹ 피드로", color = colors.ink, fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = { webViewRef?.goBack() }, enabled = canGoBack, modifier = Modifier.size(32.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "뒤로", modifier = Modifier.size(17.dp))
            }
            IconButton(onClick = { webViewRef?.goForward() }, enabled = canGoForward, modifier = Modifier.size(32.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, "앞으로", modifier = Modifier.size(17.dp))
            }
            IconButton(onClick = { webViewRef?.reload() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.Refresh, "새로고침", modifier = Modifier.size(17.dp))
            }
            Text(
                pageTitle,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (progress in 1..99) {
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth(), color = colors.accent)
        }

        if (nowLoggedIn && !initiallyLoggedIn) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.emeraldSoft)
                    .clickable { onClose(true) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = colors.emerald, modifier = Modifier.size(16.dp))
                Text("로그인 완료! 피드로 돌아갑니다…", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colors.emerald)
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
                        override fun onProgressChanged(view: WebView?, newProgress: Int) { progress = newProgress }
                        override fun onReceivedTitle(view: WebView?, title: String?) { if (!title.isNullOrBlank()) pageTitle = title }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, req: WebResourceRequest?): Boolean {
                            val u = req?.url?.toString().orEmpty()
                            return !(u.startsWith("https://") || u.startsWith("http://"))
                        }
                        override fun onPageFinished(view: WebView?, url: String?) {
                            canGoBack = view?.canGoBack() == true
                            canGoForward = view?.canGoForward() == true
                            if (!url.isNullOrBlank()) currentUrl = url
                            FanboxSession.flushCookies()
                            val logged = FanboxSession.hasLoginCookie()
                            val ptLogged = PatreonAndroidSession.hasLoginCookie()
                            nowLoggedIn = logged || ptLogged
                            if (request.autoCloseOnLogin && !initiallyLoggedIn && logged &&
                                url != null && url.contains("fanbox.cc") && !url.contains("/login")
                            ) {
                                onClose(true)
                                return
                            }
                            if (request.autoCloseOnPatreonLogin && ptLogged &&
                                url != null && url.contains("patreon.com") && !url.contains("/login")
                            ) {
                                onClose(true)
                                return
                            }
                            val gateHost = request.gateHostToUnlock
                            if (!gateHost.isNullOrBlank() && url != null && url.contains(gateHost, ignoreCase = true) &&
                                !url.contains("/patreon-flow") && !url.contains("/patreon-authorization")
                            ) {
                                view?.evaluateJavascript("(function(){return document.documentElement ? document.documentElement.outerHTML : '';})()") { jsVal ->
                                    val decodedHtml = runCatching { org.json.JSONTokener(jsVal).nextValue() as? String }.getOrNull().orEmpty()
                                    val (rewards, _) = AndroidRewardTools.parseGateHtml(url, decodedHtml)
                                    if (rewards.isNotEmpty()) {
                                        request.onGateRewardsUnlocked?.invoke(rewards)
                                        onClose(false)
                                    }
                                }
                            }
                        }
                    }
                    setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                        val guessed = URLUtil.guessFileName(url, contentDisposition, mimeType)
                        model.saveWebDownload(url = url, fileName = guessed, pageUrl = currentUrl, pageTitle = pageTitle)
                    }
                    loadUrl(request.url)
                    webViewRef = this
                }
            },
            update = { webViewRef = it },
        )
    }
}
