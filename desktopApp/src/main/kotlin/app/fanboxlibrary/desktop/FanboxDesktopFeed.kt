package app.fanboxlibrary.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CardMembership
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fanboxlibrary.shared.FanboxLinks
import app.fanboxlibrary.shared.ImportSource
import app.fanboxlibrary.shared.LibraryPost
import app.fanboxlibrary.shared.LibraryStore
import app.fanboxlibrary.shared.PostDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI
import java.util.prefs.Preferences

private enum class DesktopFeedTab(val label: String, val icon: ImageVector) {
    HOME("홈 피드", Icons.Rounded.Explore),
    SUPPORTING("후원중", Icons.Rounded.Star),
    CREATORS("작가 목록", Icons.Rounded.People),
    SEARCH("작가 찾기", Icons.Rounded.Search),
}

private val fanboxUiPrefs: Preferences = Preferences.userRoot().node("app/fanboxlibrary/desktop/fanbox_ui")

@Composable
internal fun DesktopFanboxHub(
    store: LibraryStore?,
    libraryPosts: List<LibraryPost>,
    onLibraryUpdated: (String) -> Unit,
    onOpenArchivedPost: (String) -> Unit,
) {
    val colors = LocalGalleryColors.current
    val scope = rememberCoroutineScope()

    var activeTab by remember { mutableStateOf(DesktopFeedTab.HOME) }
    var showAdult by remember { mutableStateOf(fanboxUiPrefs.getBoolean("showAdult", false)) }
    var loggedIn by remember { mutableStateOf(DesktopFanboxSession.hasLogin()) }
    var showLoginDialog by remember { mutableStateOf(false) }

    // Feed state
    var feedItems by remember { mutableStateOf<List<DesktopFanboxPostSummary>>(emptyList()) }
    var feedNextUrl by remember { mutableStateOf<String?>(null) }
    var feedLoading by remember { mutableStateOf(false) }
    var feedError by remember { mutableStateOf<DesktopFanboxResult<Nothing>?>(null) }

    // Creators & selected creator state
    var creators by remember { mutableStateOf<List<DesktopFanboxCreator>>(emptyList()) }
    var selectedCreator by remember { mutableStateOf<DesktopFanboxCreator?>(null) }
    var creatorPlans by remember { mutableStateOf<List<DesktopFanboxPlan>>(emptyList()) }
    var creatorsLoading by remember { mutableStateOf(false) }
    var creatorsError by remember { mutableStateOf<DesktopFanboxResult<Nothing>?>(null) }
    var followBusyId by remember { mutableStateOf<String?>(null) }

    // Search state
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<DesktopFanboxCreator>>(emptyList()) }
    var searchLoading by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<DesktopFanboxResult<Nothing>?>(null) }
    var hasSearched by remember { mutableStateOf(false) }

    // Detail & saving state
    var activePostId by remember { mutableStateOf<String?>(null) }
    var savingPostId by remember { mutableStateOf<String?>(null) }
    var savingProgress by remember { mutableStateOf("") }

    val archivedMap = remember(libraryPosts) {
        buildMap {
            libraryPosts.forEach { p -> FanboxLinks.postId(p.sourceUrl)?.let { fid -> put(fid, p.id) } }
        }
    }

    fun openExternal(url: String) {
        scope.launch(Dispatchers.IO) { runCatching { Desktop.getDesktop().browse(URI(url)) } }
    }

    fun loadFeed(reset: Boolean = true) {
        if (feedLoading) return
        val next = if (reset) null else feedNextUrl
        if (!reset && next == null) return
        feedLoading = true
        if (reset) feedError = null
        scope.launch {
            val cr = selectedCreator
            val res = withContext(Dispatchers.IO) {
                when {
                    cr != null -> DesktopFanboxClient.fetchCreatorPosts(cr.creatorId, next)
                    activeTab == DesktopFeedTab.SUPPORTING -> DesktopFanboxClient.fetchSupportingFeed(next)
                    else -> DesktopFanboxClient.fetchHomeFeed(next)
                }
            }
            when (res) {
                is DesktopFanboxResult.Success -> {
                    feedItems = if (reset) res.data.items else (feedItems + res.data.items).distinctBy { it.id }
                    feedNextUrl = res.data.nextUrl
                    feedError = null
                }
                is DesktopFanboxResult.AuthRequired -> feedError = res
                is DesktopFanboxResult.Error -> feedError = res
            }
            feedLoading = false
        }
    }

    fun loadCreators() {
        if (creatorsLoading) return
        creatorsLoading = true
        creatorsError = null
        scope.launch {
            val res = withContext(Dispatchers.IO) { DesktopFanboxClient.fetchCreators() }
            when (res) {
                is DesktopFanboxResult.Success -> { creators = res.data; creatorsError = null }
                is DesktopFanboxResult.AuthRequired -> creatorsError = res
                is DesktopFanboxResult.Error -> creatorsError = res
            }
            creatorsLoading = false
        }
    }

    fun runSearch() {
        val q = searchQuery.trim()
        if (q.isEmpty() || searchLoading) return
        searchLoading = true
        searchError = null
        hasSearched = true
        scope.launch {
            val res = withContext(Dispatchers.IO) { DesktopFanboxClient.searchCreators(q) }
            when (res) {
                is DesktopFanboxResult.Success -> { searchResults = res.data; searchError = null }
                is DesktopFanboxResult.AuthRequired -> searchError = res
                is DesktopFanboxResult.Error -> searchError = res
            }
            searchLoading = false
        }
    }

    fun toggleFollow(target: DesktopFanboxCreator) {
        if (followBusyId != null) return
        if (!loggedIn) {
            showLoginDialog = true
            return
        }
        followBusyId = target.creatorId
        scope.launch {
            val full = if (target.userId.isNotBlank()) target else {
                withContext(Dispatchers.IO) {
                    (DesktopFanboxClient.fetchCreatorProfile(target.creatorId) as? DesktopFanboxResult.Success)?.data
                } ?: target
            }
            val desired = !full.isFollowed
            val res = withContext(Dispatchers.IO) { DesktopFanboxClient.setFollowCreator(full.userId, desired) }
            when (res) {
                is DesktopFanboxResult.Success -> {
                    val updated = full.copy(isFollowed = desired)
                    if (selectedCreator?.creatorId == updated.creatorId) selectedCreator = updated
                    searchResults = searchResults.map { if (it.creatorId == updated.creatorId) updated else it }
                    creators = creators.map { if (it.creatorId == updated.creatorId) updated else it }
                    onLibraryUpdated(if (desired) "${updated.name} 작가를 팔로우했습니다." else "${updated.name} 팔로우를 해제했습니다.")
                }
                is DesktopFanboxResult.AuthRequired -> showLoginDialog = true
                is DesktopFanboxResult.Error -> onLibraryUpdated(res.message)
            }
            followBusyId = null
        }
    }

    fun savePostToLibrary(detail: DesktopFanboxPostDetail) {
        val activeStore = store ?: return
        if (savingPostId != null) return
        val targets = buildList<Pair<String, String>> {
            detail.images.forEachIndexed { idx, img -> add("%02d_${img.fileName}".format(idx + 1) to img.originalUrl) }
            detail.files.forEach { f -> add(f.fileName to f.url) }
        }
        if (targets.isEmpty()) return
        savingPostId = detail.summary.id
        savingProgress = "보관함 저장 준비 중 (0/${targets.size})…"
        scope.launch {
            var added = 0
            var reused = 0
            var skipped = 0
            try {
                val draft = PostDraft(
                    creator = detail.summary.creatorName,
                    title = detail.summary.title,
                    year = detail.summary.year,
                    sourceUrl = detail.summary.postUrl,
                )
                for ((i, pair) in targets.withIndex()) {
                    val (fileName, url) = pair
                    savingProgress = "보관함에 저장 중 (${i + 1}/${targets.size}) · $fileName"
                    val report = withContext(Dispatchers.IO) {
                        val stream = DesktopFanboxClient.openDownloadStream(url)
                        stream.use { input ->
                            activeStore.importManaged(draft, listOf(ImportSource(fileName, "원본/$fileName") { input }))
                        }
                    }
                    added += report.added
                    reused += report.reused
                    skipped += report.skipped
                }
                onLibraryUpdated("내 보관함에 저장 완료 · 추가 ${added}개 · 기존 ${reused}개 · 건너뜀 ${skipped}개")
            } catch (e: Exception) {
                onLibraryUpdated("보관함 저장 실패: ${e.localizedMessage ?: e.javaClass.simpleName}")
            } finally {
                savingPostId = null
                savingProgress = ""
            }
        }
    }

    LaunchedEffect(selectedCreator?.creatorId) {
        val cr = selectedCreator ?: return@LaunchedEffect
        creatorPlans = emptyList()
        withContext(Dispatchers.IO) {
            val prof = DesktopFanboxClient.fetchCreatorProfile(cr.creatorId)
            val plans = DesktopFanboxClient.fetchCreatorPlans(cr.creatorId)
            prof to plans
        }.let { (prof, plans) ->
            if (prof is DesktopFanboxResult.Success) {
                selectedCreator = prof.data.copy(isSupporting = prof.data.isSupporting || cr.isSupporting, planFee = cr.planFee)
            }
            if (plans is DesktopFanboxResult.Success) creatorPlans = plans.data
        }
    }

    LaunchedEffect(activeTab, selectedCreator?.creatorId, loggedIn) {
        when {
            selectedCreator != null -> loadFeed(reset = true)
            activeTab == DesktopFeedTab.CREATORS -> if (loggedIn && creators.isEmpty()) loadCreators()
            activeTab == DesktopFeedTab.SEARCH -> Unit
            else -> if (loggedIn) loadFeed(reset = true)
        }
    }

    Row(Modifier.fillMaxSize().background(colors.bg)) {
        // Left Sidebar
        Column(
            modifier = Modifier.fillMaxHeight().width(232.dp).background(colors.sidebar).padding(14.dp),
        ) {
            Text(
                "FANBOX 피드",
                style = MaterialTheme.typography.labelMedium,
                color = colors.muted,
                modifier = Modifier.padding(start = 10.dp, top = 4.dp, bottom = 8.dp),
            )
            DesktopFeedTab.entries.forEach { tab ->
                val selected = activeTab == tab && selectedCreator == null && activePostId == null
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) colors.surfaceElevated else Color.Transparent)
                        .border(1.dp, if (selected) colors.borderStrong else Color.Transparent, RoundedCornerShape(10.dp))
                        .clickable {
                            activeTab = tab
                            selectedCreator = null
                            activePostId = null
                        }
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Icon(tab.icon, null, tint = if (selected) colors.accent else colors.muted, modifier = Modifier.size(18.dp))
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected) colors.ink else colors.muted,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = colors.border)
            Spacer(Modifier.height(14.dp))

            // R-18 Filter Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (showAdult) colors.accentSoft else colors.surface)
                    .border(1.dp, if (showAdult) colors.accent else colors.border, RoundedCornerShape(10.dp))
                    .clickable {
                        showAdult = !showAdult
                        fanboxUiPrefs.putBoolean("showAdult", showAdult)
                    }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.Shield, null, tint = if (showAdult) colors.accent else colors.muted, modifier = Modifier.size(16.dp))
                Text("R-18 표시", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.ink)
                Text(
                    if (showAdult) "ON" else "OFF",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (showAdult) colors.accent else colors.muted,
                )
            }

            Spacer(Modifier.weight(1f))

            // Bottom Session Status Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (loggedIn) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = colors.emerald, modifier = Modifier.size(16.dp))
                        Text("FANBOX 로그인됨", style = MaterialTheme.typography.labelLarge, color = colors.ink)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { showLoginDialog = true }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                            Text("쿠키 변경", style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(
                            onClick = {
                                DesktopFanboxSession.clearSession()
                                loggedIn = false
                                feedItems = emptyList()
                                creators = emptyList()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.Logout, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("로그아웃", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                } else {
                    Text("FANBOX 미연결", style = MaterialTheme.typography.labelMedium, color = colors.muted)
                    Button(
                        onClick = { showLoginDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(9.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Login, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("FANBOX 로그인")
                    }
                }
            }
        }

        Box(Modifier.fillMaxHeight().width(1.dp).background(colors.border))

        // Main Right Panel
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (savingProgress.isNotEmpty()) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accent)
                Text(savingProgress, style = MaterialTheme.typography.bodySmall, color = colors.ink, modifier = Modifier.padding(horizontal = 20.dp, vertical = 5.dp))
            }

            val currentPostId = activePostId
            val visibleFeed = remember(feedItems, showAdult) {
                if (showAdult) feedItems else feedItems.filterNot { it.hasAdultContent }
            }

            when {
                currentPostId != null -> DesktopFanboxPostDetailPane(
                    postId = currentPostId,
                    feedList = visibleFeed,
                    archivedLocalId = archivedMap[currentPostId],
                    saving = savingPostId != null,
                    onSelectPost = { activePostId = it },
                    onSelectCreator = { id, name, icon ->
                        activePostId = null
                        selectedCreator = DesktopFanboxCreator(id, "", name, icon, null, "")
                    },
                    onSaveToLibrary = ::savePostToLibrary,
                    onOpenArchived = onOpenArchivedPost,
                    onOpenExternal = ::openExternal,
                    onBack = { activePostId = null },
                )

                selectedCreator != null -> {
                    val cr = selectedCreator!!
                    DesktopCreatorProfilePane(
                        creator = cr,
                        plans = creatorPlans,
                        posts = visibleFeed,
                        hiddenAdultCount = if (showAdult) 0 else feedItems.count { it.hasAdultContent },
                        loading = feedLoading,
                        error = feedError,
                        hasMore = feedNextUrl != null,
                        followBusy = followBusyId == cr.creatorId,
                        archivedIds = archivedMap.keys,
                        onBack = { selectedCreator = null },
                        onToggleFollow = { toggleFollow(cr) },
                        onOpenPlans = { openExternal("https://${cr.creatorId}.fanbox.cc/plans") },
                        onEnableAdult = { showAdult = true; fanboxUiPrefs.putBoolean("showAdult", true) },
                        onOpenPost = { activePostId = it.id },
                        onLoadMore = { loadFeed(reset = false) },
                        onRetry = { loadFeed(reset = true) },
                        onLogin = { showLoginDialog = true },
                    )
                }

                activeTab == DesktopFeedTab.SEARCH -> DesktopCreatorSearchPane(
                    query = searchQuery,
                    onQuery = { searchQuery = it },
                    onSearch = ::runSearch,
                    results = searchResults,
                    loading = searchLoading,
                    error = searchError,
                    hasSearched = hasSearched,
                    followBusyId = followBusyId,
                    onSelectCreator = { selectedCreator = it },
                    onToggleFollow = ::toggleFollow,
                )

                !loggedIn && feedItems.isEmpty() && creators.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            modifier = Modifier
                                .widthIn(max = 460.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(colors.surface)
                                .border(1.dp, colors.border, RoundedCornerShape(18.dp))
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier.size(54.dp).clip(RoundedCornerShape(15.dp)).background(Brush.linearGradient(listOf(colors.accent, colors.secondary))),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.Login, null, tint = Color.White, modifier = Modifier.size(28.dp))
                            }
                            Text("PC에서도 FANBOX 피드를 바로 보세요", style = MaterialTheme.typography.titleLarge, color = colors.ink)
                            Text(
                                "브라우저에서 로그인한 FANBOX 세션(FANBOXSESSID)을 한 번만 연결하면 홈 피드·후원중·작가 검색·팔로우·원클릭 보관함 저장을 PC에서도 똑같이 쓸 수 있습니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.muted,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                                Button(onClick = { showLoginDialog = true }) { Text("FANBOX 로그인 연결") }
                                OutlinedButton(onClick = { activeTab = DesktopFeedTab.SEARCH }) { Text("로그인 없이 작가 찾기") }
                            }
                        }
                    }
                }

                activeTab == DesktopFeedTab.CREATORS -> DesktopCreatorsListPane(
                    creators = creators,
                    loading = creatorsLoading,
                    error = creatorsError,
                    followBusyId = followBusyId,
                    onSelectCreator = { selectedCreator = it },
                    onToggleFollow = ::toggleFollow,
                    onRefresh = ::loadCreators,
                    onLogin = { showLoginDialog = true },
                )

                else -> Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                        Text(activeTab.label, style = MaterialTheme.typography.headlineSmall, color = colors.ink)
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = { loadFeed(reset = true) }, shape = RoundedCornerShape(10.dp)) {
                            Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("새로고침")
                        }
                    }
                    DesktopFeedGrid(
                        posts = visibleFeed,
                        hiddenAdultCount = if (showAdult) 0 else feedItems.count { it.hasAdultContent },
                        loading = feedLoading,
                        error = feedError,
                        hasMore = feedNextUrl != null,
                        archivedIds = archivedMap.keys,
                        onEnableAdult = { showAdult = true; fanboxUiPrefs.putBoolean("showAdult", true) },
                        onOpenPost = { activePostId = it.id },
                        onSelectCreator = { p -> selectedCreator = DesktopFanboxCreator(p.creatorId, "", p.creatorName, p.creatorIconUrl, null, "") },
                        onLoadMore = { loadFeed(reset = false) },
                        onRetry = { loadFeed(reset = true) },
                        onLogin = { showLoginDialog = true },
                    )
                }
            }
        }
    }

    if (showLoginDialog) {
        DesktopFanboxLoginDialog(
            onOpenBrowser = { openExternal("https://www.fanbox.cc/") },
            onSaved = {
                loggedIn = DesktopFanboxSession.hasLogin()
                showLoginDialog = false
                if (loggedIn) {
                    if (activeTab == DesktopFeedTab.CREATORS) loadCreators()
                    else if (activeTab != DesktopFeedTab.SEARCH) loadFeed(reset = true)
                }
            },
            onDismiss = { showLoginDialog = false },
        )
    }
}

@Composable
private fun DesktopFanboxLoginDialog(
    onOpenBrowser: () -> Unit,
    onSaved: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalGalleryColors.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf(DesktopFanboxSession.rawCookie()) }
    var showManual by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var loginJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    DisposableEffect(Unit) {
        onDispose { loginJob?.cancel() }
    }

    AlertDialog(
        onDismissRequest = {
            loginJob?.cancel()
            onDismiss()
        },
        containerColor = colors.surface,
        titleContentColor = colors.ink,
        textContentColor = colors.ink,
        title = { Text("FANBOX 로그인 (PC)", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.widthIn(min = 440.dp, max = 520.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "모바일과 똑같이 전용 로그인 창이 열립니다. pixiv/FANBOX에 로그인하면 자동으로 연결되고 창이 닫힙니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )

                Button(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    onClick = {
                        busy = true
                        status = "로그인 창을 준비하는 중…"
                        loginJob = scope.launch {
                            val result = DesktopWebLogin.runPopupLogin { msg -> status = msg }
                            result.onSuccess {
                                status = "세션을 검증하는 중…"
                                val check = withContext(Dispatchers.IO) { DesktopFanboxClient.fetchHomeFeed() }
                                when (check) {
                                    is DesktopFanboxResult.Success -> onSaved()
                                    is DesktopFanboxResult.AuthRequired -> status = "로그인 세션 검증에 실패했습니다. 다시 시도해 주세요."
                                    is DesktopFanboxResult.Error -> {
                                        // Even if feed check temporarily failed, cookie is saved
                                        onSaved()
                                    }
                                }
                            }.onFailure { err ->
                                if (err !is kotlinx.coroutines.CancellationException) {
                                    status = err.localizedMessage ?: "웹 로그인 실패"
                                }
                            }
                            busy = false
                        }
                    },
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Login, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (busy) "로그인 대기 중…" else "웹 창으로 간편 로그인")
                }

                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accent)
                }

                if (status.isNotBlank()) {
                    Text(status, style = MaterialTheme.typography.bodySmall, color = colors.accent)
                }

                HorizontalDivider(color = colors.border)

                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showManual = !showManual }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (showManual) "▴ 쿠키 직접 붙여넣기 접기" else "▾ 또는 FANBOXSESSID 쿠키 직접 붙여넣기",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.muted,
                    )
                }

                if (showManual) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("FANBOXSESSID 값 (또는 전체 Cookie)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onOpenBrowser, shape = RoundedCornerShape(9.dp)) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("브라우저 열기")
                        }
                        Button(
                            enabled = input.isNotBlank() && !busy,
                            shape = RoundedCornerShape(9.dp),
                            onClick = {
                                busy = true
                                status = "쿠키를 확인하는 중…"
                                scope.launch {
                                    DesktopFanboxSession.saveSessionInput(input)
                                    val check = withContext(Dispatchers.IO) { DesktopFanboxClient.fetchHomeFeed() }
                                    when (check) {
                                        is DesktopFanboxResult.Success -> onSaved()
                                        is DesktopFanboxResult.AuthRequired -> status = "유효하지 않은 FANBOXSESSID입니다."
                                        is DesktopFanboxResult.Error -> status = check.message
                                    }
                                    busy = false
                                }
                            },
                        ) { Text("쿠키 저장") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    loginJob?.cancel()
                    onDismiss()
                },
            ) { Text("닫기") }
        },
    )
}

@Composable
private fun DesktopCreatorSearchPane(
    query: String,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    results: List<DesktopFanboxCreator>,
    loading: Boolean,
    error: DesktopFanboxResult<Nothing>?,
    hasSearched: Boolean,
    followBusyId: String?,
    onSelectCreator: (DesktopFanboxCreator) -> Unit,
    onToggleFollow: (DesktopFanboxCreator) -> Unit,
) {
    val colors = LocalGalleryColors.current
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("작가 찾기", style = MaterialTheme.typography.headlineSmall, color = colors.ink)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.Search, null, tint = colors.muted, modifier = Modifier.size(18.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text("작가명, @ID, 또는 https://작가ID.fanbox.cc 입력 후 Enter…", style = MaterialTheme.typography.bodySmall, color = colors.muted)
                    BasicTextField(
                        value = query,
                        onValueChange = onQuery,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.ink),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier.fillMaxWidth().onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) { onSearch(); true } else false
                        },
                    )
                }
                if (query.isNotEmpty()) {
                    Icon(Icons.Rounded.Close, "지우기", tint = colors.muted, modifier = Modifier.size(16.dp).clickable { onQuery("") })
                }
            }
            Button(onClick = onSearch, enabled = query.isNotBlank() && !loading, shape = RoundedCornerShape(10.dp)) { Text("검색") }
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accent) }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text((error as? DesktopFanboxResult.Error)?.message ?: "검색 오류", color = colors.muted) }
            !hasSearched -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("예: ototo, @official, 또는 https://ototo.fanbox.cc", color = colors.muted)
            }
            results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("검색 결과가 없습니다.", color = colors.muted)
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(results, key = { it.creatorId }) { cr ->
                    DesktopCreatorRow(cr, followBusyId == cr.creatorId, { onSelectCreator(cr) }, { onToggleFollow(cr) })
                }
            }
        }
    }
}

@Composable
private fun DesktopCreatorsListPane(
    creators: List<DesktopFanboxCreator>,
    loading: Boolean,
    error: DesktopFanboxResult<Nothing>?,
    followBusyId: String?,
    onSelectCreator: (DesktopFanboxCreator) -> Unit,
    onToggleFollow: (DesktopFanboxCreator) -> Unit,
    onRefresh: () -> Unit,
    onLogin: () -> Unit,
) {
    val colors = LocalGalleryColors.current
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("후원 · 팔로우 작가 (${creators.size})", style = MaterialTheme.typography.headlineSmall, color = colors.ink)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onRefresh, shape = RoundedCornerShape(10.dp)) { Text("새로고침") }
        }
        when {
            loading && creators.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accent) }
            error != null && creators.isEmpty() -> DesktopFeedError(error, onLogin, onRefresh)
            creators.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("표시할 작가가 없습니다.", color = colors.muted) }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(creators, key = { it.creatorId }) { cr ->
                    DesktopCreatorRow(cr, followBusyId == cr.creatorId, { onSelectCreator(cr) }, { onToggleFollow(cr) })
                }
            }
        }
    }
}

@Composable
private fun DesktopCreatorRow(
    creator: DesktopFanboxCreator,
    followBusy: Boolean,
    onClick: () -> Unit,
    onToggleFollow: () -> Unit,
) {
    val colors = LocalGalleryColors.current
    val avatar = DesktopFanboxClient.rememberRemoteImage(creator.iconUrl, 128)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(colors.surfaceElevated), contentAlignment = Alignment.Center) {
            if (avatar != null) Image(avatar, creator.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(creator.name.take(1).uppercase(), fontWeight = FontWeight.Bold, color = colors.accent)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(creator.name, style = MaterialTheme.typography.titleMedium, color = colors.ink)
                if (creator.isSupporting) {
                    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(colors.emeraldSoft).padding(horizontal = 7.dp, vertical = 2.dp)) {
                        Text(if (creator.planFee > 0) "후원 ¥${creator.planFee}" else "후원중", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = colors.emerald)
                    }
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
        ) {
            Text(if (followBusy) "…" else if (creator.isFollowed) "팔로우 중" else "팔로우", color = if (creator.isFollowed) colors.muted else colors.accent)
        }
    }
}

@Composable
private fun DesktopCreatorProfilePane(
    creator: DesktopFanboxCreator,
    plans: List<DesktopFanboxPlan>,
    posts: List<DesktopFanboxPostSummary>,
    hiddenAdultCount: Int,
    loading: Boolean,
    error: DesktopFanboxResult<Nothing>?,
    hasMore: Boolean,
    followBusy: Boolean,
    archivedIds: Set<String>,
    onBack: () -> Unit,
    onToggleFollow: () -> Unit,
    onOpenPlans: () -> Unit,
    onEnableAdult: () -> Unit,
    onOpenPost: (DesktopFanboxPostSummary) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onLogin: () -> Unit,
) {
    val colors = LocalGalleryColors.current
    val avatar = DesktopFanboxClient.rememberRemoteImage(creator.iconUrl, 128)
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ChevronLeft, "뒤로", tint = colors.ink) }
            Box(Modifier.size(46.dp).clip(CircleShape).background(colors.surfaceElevated), contentAlignment = Alignment.Center) {
                if (avatar != null) Image(avatar, creator.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text(creator.name.take(1).uppercase(), fontWeight = FontWeight.Bold, color = colors.accent)
            }
            Column(Modifier.weight(1f)) {
                Text(creator.name, style = MaterialTheme.typography.titleLarge, color = colors.ink)
                Text("@${creator.creatorId}", style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
            Button(
                onClick = onToggleFollow,
                enabled = !followBusy,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (creator.isFollowed) colors.surfaceElevated else colors.accent,
                    contentColor = if (creator.isFollowed) colors.ink else Color.White,
                ),
            ) {
                Icon(if (creator.isFollowed) Icons.Rounded.Check else Icons.Rounded.PersonAdd, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (creator.isFollowed) "팔로우 중" else "팔로우")
            }
            OutlinedButton(onClick = onOpenPlans, shape = RoundedCornerShape(10.dp)) {
                Icon(Icons.Rounded.CardMembership, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (creator.isSupporting) "후원중 · 플랜 관리 ↗" else "후원 플랜 구독 ↗")
            }
        }

        if (plans.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                plans.forEach { plan ->
                    Column(
                        modifier = Modifier
                            .width(210.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surface)
                            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                            .clickable(onClick = onOpenPlans)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text("¥${plan.fee}/월", fontWeight = FontWeight.Bold, color = colors.accent)
                        Text(plan.title, style = MaterialTheme.typography.titleSmall, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (plan.description.isNotBlank()) Text(plan.description, style = MaterialTheme.typography.labelSmall, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        DesktopFeedGrid(
            posts = posts,
            hiddenAdultCount = hiddenAdultCount,
            loading = loading,
            error = error,
            hasMore = hasMore,
            archivedIds = archivedIds,
            onEnableAdult = onEnableAdult,
            onOpenPost = onOpenPost,
            onSelectCreator = {},
            onLoadMore = onLoadMore,
            onRetry = onRetry,
            onLogin = onLogin,
        )
    }
}

@Composable
private fun DesktopFeedGrid(
    posts: List<DesktopFanboxPostSummary>,
    hiddenAdultCount: Int,
    loading: Boolean,
    error: DesktopFanboxResult<Nothing>?,
    hasMore: Boolean,
    archivedIds: Set<String>,
    onEnableAdult: () -> Unit,
    onOpenPost: (DesktopFanboxPostSummary) -> Unit,
    onSelectCreator: (DesktopFanboxPostSummary) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onLogin: () -> Unit,
) {
    val colors = LocalGalleryColors.current
    if (loading && posts.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accent) }
        return
    }
    if (error != null && posts.isEmpty()) {
        DesktopFeedError(error, onLogin, onRetry)
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(245.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        if (hiddenAdultCount > 0) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                        .clickable(onClick = onEnableAdult)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.Shield, null, tint = colors.muted, modifier = Modifier.size(16.dp))
                    Text("R-18 게시글 ${hiddenAdultCount}개가 숨겨져 있습니다 · 클릭하여 모두 표시", style = MaterialTheme.typography.bodySmall, color = colors.muted)
                }
            }
        }
        items(posts, key = { it.id }) { post ->
            val cover = DesktopFanboxClient.rememberRemoteImage(post.coverImageUrl, 520)
            val isArchived = post.id in archivedIds
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                    .clickable { onOpenPost(post) },
            ) {
                Box(Modifier.fillMaxWidth().aspectRatio(1.35f).background(colors.surfaceElevated), contentAlignment = Alignment.Center) {
                    if (cover != null) {
                        Image(cover, post.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                    } else if (post.coverImageUrl == null) {
                        Text("텍스트 게시글", color = colors.muted, style = MaterialTheme.typography.labelSmall)
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.muted.copy(alpha = 0.5f))
                    }
                    Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (isArchived) FeedBadge("보관됨", Color(0xCC059669))
                        else Spacer(Modifier.width(1.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (post.hasAdultContent) FeedBadge("R-18", Color(0xCCE11D48))
                            if (post.feeRequired > 0) FeedBadge("¥${post.feeRequired}", Color(0xCC4F46E5))
                            else FeedBadge("무료", Color(0xB80E1016))
                        }
                    }
                    if (post.isRestricted) {
                        Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) { FeedBadge("후원자 한정", Color(0xCC11141C)) }
                    }
                }
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(post.title, style = MaterialTheme.typography.titleSmall, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            post.creatorName,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.accent,
                            modifier = Modifier.clickable { onSelectCreator(post) },
                        )
                        Text("· ${post.displayDate}", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Rounded.Favorite, null, tint = colors.accent.copy(alpha = 0.75f), modifier = Modifier.size(12.dp))
                        Text("${post.likeCount}", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                    }
                }
            }
        }
        if (hasMore) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    if (loading) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = colors.accent)
                    else OutlinedButton(onClick = onLoadMore, shape = RoundedCornerShape(10.dp)) { Text("더 불러오기") }
                }
            }
        }
    }
}

@Composable
private fun FeedBadge(text: String, bg: Color) {
    Box(Modifier.clip(RoundedCornerShape(7.dp)).background(bg).padding(horizontal = 7.dp, vertical = 2.dp)) {
        Text(text, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
private fun DesktopFeedError(error: DesktopFanboxResult<Nothing>, onLogin: () -> Unit, onRetry: () -> Unit) {
    val colors = LocalGalleryColors.current
    val msg = when (error) {
        is DesktopFanboxResult.AuthRequired -> error.message
        is DesktopFanboxResult.Error -> error.message
        is DesktopFanboxResult.Success -> ""
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(msg, color = colors.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetry) { Text("다시 시도") }
                if (error is DesktopFanboxResult.AuthRequired) OutlinedButton(onClick = onLogin) { Text("FANBOX 로그인") }
            }
        }
    }
}

@Composable
private fun DesktopFanboxPostDetailPane(
    postId: String,
    feedList: List<DesktopFanboxPostSummary>,
    archivedLocalId: String?,
    saving: Boolean,
    onSelectPost: (String) -> Unit,
    onSelectCreator: (String, String, String?) -> Unit,
    onSaveToLibrary: (DesktopFanboxPostDetail) -> Unit,
    onOpenArchived: (String) -> Unit,
    onOpenExternal: (String) -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalGalleryColors.current
    val idx = remember(feedList, postId) { feedList.indexOfFirst { it.id == postId } }
    val prevPost = if (idx > 0) feedList[idx - 1] else null
    val nextPost = if (idx in 0 until feedList.lastIndex) feedList[idx + 1] else null

    var loading by remember(postId) { mutableStateOf(true) }
    var detail by remember(postId) { mutableStateOf<DesktopFanboxPostDetail?>(null) }
    var error by remember(postId) { mutableStateOf<DesktopFanboxResult<Nothing>?>(null) }
    var lightboxIdx by remember(postId) { mutableStateOf<Int?>(null) }

    val focus = remember { FocusRequester() }
    LaunchedEffect(postId) {
        focus.requestFocus()
        loading = true
        error = null
        val res = withContext(Dispatchers.IO) { DesktopFanboxClient.fetchPostDetail(postId) }
        when (res) {
            is DesktopFanboxResult.Success -> detail = res.data
            is DesktopFanboxResult.AuthRequired -> error = res
            is DesktopFanboxResult.Error -> error = res
        }
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val imgs = detail?.images.orEmpty()
                val lb = lightboxIdx
                when {
                    ev.key == Key.Escape && lb != null -> { lightboxIdx = null; true }
                    ev.key == Key.Escape -> { onBack(); true }
                    lb != null && ev.key == Key.DirectionLeft && lb > 0 -> { lightboxIdx = lb - 1; true }
                    lb != null && ev.key == Key.DirectionRight && lb < imgs.lastIndex -> { lightboxIdx = lb + 1; true }
                    lb == null && ev.key == Key.DirectionLeft && prevPost != null -> { onSelectPost(prevPost.id); true }
                    lb == null && ev.key == Key.DirectionRight && nextPost != null -> { onSelectPost(nextPost.id); true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        // Lightbox overlay
        val lb = lightboxIdx
        val activeDetail = detail
        if (lb != null && activeDetail != null && lb in activeDetail.images.indices) {
            val img = activeDetail.images[lb]
            val bmp = DesktopFanboxClient.rememberRemoteImage(img.originalUrl, 2400)
            Column(Modifier.fillMaxSize().background(colors.cinemaBg)) {
                Row(Modifier.fillMaxWidth().background(colors.sidebar).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { lightboxIdx = null }) { Text("‹ 게시글로 돌아가기", color = colors.ink) }
                    Spacer(Modifier.weight(1f))
                    Text("${lb + 1} / ${activeDetail.images.size} · ${img.fileName}", color = colors.ink, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.weight(1f))
                    TextButton(enabled = lb > 0, onClick = { lightboxIdx = lb - 1 }) { Text("이전") }
                    TextButton(enabled = lb < activeDetail.images.lastIndex, onClick = { lightboxIdx = lb + 1 }) { Text("다음") }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (bmp != null) Image(bmp, img.fileName, Modifier.fillMaxSize().padding(16.dp), contentScale = ContentScale.Fit)
                    else CircularProgressIndicator(color = colors.accent)
                }
            }
            return
        }

        // Top Bar
        Row(
            modifier = Modifier.fillMaxWidth().background(colors.sidebar).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onBack) { Text("‹ 피드", color = colors.ink, fontWeight = FontWeight.SemiBold) }
            if (feedList.size > 1 && idx >= 0) {
                IconButton(onClick = { prevPost?.let { onSelectPost(it.id) } }, enabled = prevPost != null, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Rounded.ChevronLeft, "이전 게시글")
                }
                Text("게시글 ${idx + 1}/${feedList.size}", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                IconButton(onClick = { nextPost?.let { onSelectPost(it.id) } }, enabled = nextPost != null, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Rounded.ChevronRight, "다음 게시글")
                }
            }
            Spacer(Modifier.weight(1f))
            val url = detail?.summary?.postUrl ?: feedList.getOrNull(idx)?.postUrl ?: DesktopFanboxSession.WEB_REFERER
            OutlinedButton(onClick = { onOpenExternal(url) }, shape = RoundedCornerShape(9.dp)) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text("브라우저에서 열기")
            }
        }
        HorizontalDivider(color = colors.border)

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accent) }
            error != null -> DesktopFeedError(error!!, {}, { onSelectPost(postId) })
            activeDetail != null -> {
                val s = activeDetail.summary
                val totalAssets = activeDetail.images.size + activeDetail.files.size
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(210.dp),
                    contentPadding = PaddingValues(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(s.title, style = MaterialTheme.typography.headlineSmall, color = colors.ink)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    s.creatorName,
                                    color = colors.accent,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable { onSelectCreator(s.creatorId, s.creatorName, s.creatorIconUrl) },
                                )
                                Text("· ${s.displayDate} · ${if (s.feeRequired > 0) "¥${s.feeRequired} 플랜" else "무료"}", style = MaterialTheme.typography.bodySmall, color = colors.muted)
                                Spacer(Modifier.weight(1f))
                                Button(
                                    onClick = { onSaveToLibrary(activeDetail) },
                                    enabled = totalAssets > 0 && !saving,
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Icon(Icons.Rounded.Download, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (archivedLocalId != null) "보관함 업데이트 (${activeDetail.images.size}장·첨부${activeDetail.files.size})" else "내 보관함에 전체 저장 (${activeDetail.images.size}장·첨부${activeDetail.files.size})")
                                }
                                if (archivedLocalId != null) {
                                    OutlinedButton(onClick = { onOpenArchived(archivedLocalId) }, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, colors.emerald)) {
                                        Text("내 보관함에서 열기", color = colors.emerald)
                                    }
                                }
                            }
                            if (activeDetail.text.isNotBlank()) {
                                Text(activeDetail.text, style = MaterialTheme.typography.bodyMedium, color = colors.ink)
                            }
                        }
                    }

                    itemsIndexed(activeDetail.images, key = { _, img -> img.id }) { imgIdx, img ->
                        val bmp = DesktopFanboxClient.rememberRemoteImage(img.thumbnailUrl, 520)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.82f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.surfaceElevated)
                                .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                                .clickable { lightboxIdx = imgIdx },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (bmp != null) Image(bmp, img.fileName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                            else CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.muted)
                        }
                    }

                    if (activeDetail.files.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text("첨부 파일 (${activeDetail.files.size})", style = MaterialTheme.typography.titleMedium, color = colors.ink, modifier = Modifier.padding(top = 8.dp))
                        }
                        items(activeDetail.files, key = { it.id }, span = { GridItemSpan(maxLineSpan) }) { f ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(colors.surface)
                                    .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(Icons.Rounded.FolderZip, null, tint = colors.secondary)
                                Text(f.fileName, Modifier.weight(1f), color = colors.ink)
                                Text(
                                    if (f.size >= 1048576L) "%.1f MB".format(f.size / 1048576.0) else "%.1f KB".format(f.size / 1024.0),
                                    style = MaterialTheme.typography.bodySmall,
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
