package app.fanboxlibrary.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CardMembership
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.HideImage
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import app.fanboxlibrary.shared.FanboxLinks
import app.fanboxlibrary.shared.ImportSource
import app.fanboxlibrary.shared.LibraryPost
import app.fanboxlibrary.shared.LibraryStore
import app.fanboxlibrary.shared.PostDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI
import java.util.prefs.Preferences

private enum class DesktopFeedTab(val label: String, val icon: ImageVector) {
    HOME("홈 피드", Icons.Rounded.Explore),
    SUPPORTING("후원중", Icons.Rounded.Star),
    CREATORS("작가 목록", Icons.Rounded.People),
    SEARCH("작가 · 태그 찾기", Icons.Rounded.Search),
}

private enum class ServiceFilter(val label: String) {
    ALL("전체"),
    FANBOX("FANBOX"),
    PATREON("Patreon"),
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
    var serviceFilter by remember { mutableStateOf(ServiceFilter.ALL) }
    var showAdult by remember { mutableStateOf(fanboxUiPrefs.getBoolean("showAdult", false)) }
    var hideTextPosts by remember { mutableStateOf(fanboxUiPrefs.getBoolean("hideTextPosts", false)) }
    var supportingGroupedByCreator by remember { mutableStateOf(true) }

    var fanboxLoggedIn by remember { mutableStateOf(DesktopFanboxSession.hasLogin()) }
    var patreonLoggedIn by remember { mutableStateOf(DesktopPatreonSession.hasLogin()) }
    var loginModalService by remember { mutableStateOf<FeedService?>(null) }

    // Feed state
    var feedItems by remember { mutableStateOf<List<DesktopFanboxPostSummary>>(emptyList()) }
    var fanboxNextUrl by remember { mutableStateOf<String?>(null) }
    var patreonNextUrl by remember { mutableStateOf<String?>(null) }
    var feedLoading by remember { mutableStateOf(false) }
    var feedError by remember { mutableStateOf<DesktopFanboxResult<Nothing>?>(null) }

    // Creators & selected creator state
    var creators by remember { mutableStateOf<List<DesktopFanboxCreator>>(emptyList()) }
    var recommendedCreators by remember { mutableStateOf<List<DesktopFanboxCreator>>(emptyList()) }
    var selectedCreator by remember { mutableStateOf<DesktopFanboxCreator?>(null) }
    var creatorPlans by remember { mutableStateOf<List<DesktopFanboxPlan>>(emptyList()) }
    var creatorTags by remember { mutableStateOf<List<String>>(emptyList()) }
    var activeCreatorTag by remember { mutableStateOf<String?>(null) }
    var creatorsLoading by remember { mutableStateOf(false) }
    var creatorsError by remember { mutableStateOf<DesktopFanboxResult<Nothing>?>(null) }
    var followBusyId by remember { mutableStateOf<String?>(null) }

    // Search state (Creators + #Tag search)
    var searchQuery by remember { mutableStateOf("") }
    var tagSearchMode by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<DesktopFanboxCreator>>(emptyList()) }
    var searchLoading by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<DesktopFanboxResult<Nothing>?>(null) }
    var hasSearched by remember { mutableStateOf(false) }

    // Detail & batch saving state
    var activePost by remember { mutableStateOf<DesktopFanboxPostSummary?>(null) }
    var savingJob by remember { mutableStateOf<Job?>(null) }
    var savingProgress by remember { mutableStateOf("") }

    val archivedMap = remember(libraryPosts) {
        buildMap {
            libraryPosts.forEach { p ->
                FanboxLinks.postId(p.sourceUrl)?.let { fid -> put("FANBOX:$fid", p.id) }
                FanboxLinks.patreonPostId(p.sourceUrl)?.let { pid -> put("PATREON:$pid", p.id) }
            }
        }
    }

    fun openExternal(url: String) {
        scope.launch(Dispatchers.IO) { runCatching { Desktop.getDesktop().browse(URI(url)) } }
    }

    fun loadFeed(reset: Boolean = true) {
        if (feedLoading) return
        val nextFb = if (reset) null else fanboxNextUrl
        val nextPt = if (reset) null else patreonNextUrl
        if (!reset && nextFb == null && nextPt == null) return
        feedLoading = true
        if (reset) feedError = null
        scope.launch {
            val cr = selectedCreator
            val collected = mutableListOf<DesktopFanboxPostSummary>()
            var lastErr: DesktopFanboxResult<Nothing>? = null
            var newFbNext: String? = null
            var newPtNext: String? = null

            withContext(Dispatchers.IO) {
                if (cr != null) {
                    val res = if (cr.service == FeedService.PATREON) {
                        DesktopPatreonClient.fetchCampaignPosts(cr.creatorId, nextPt)
                    } else {
                        DesktopFanboxClient.fetchCreatorPosts(cr.creatorId, nextFb, activeCreatorTag)
                    }
                    when (res) {
                        is DesktopFanboxResult.Success -> {
                            collected.addAll(res.data.items)
                            if (cr.service == FeedService.PATREON) newPtNext = res.data.nextUrl else newFbNext = res.data.nextUrl
                        }
                        is DesktopFanboxResult.AuthRequired -> lastErr = res
                        is DesktopFanboxResult.Error -> lastErr = res
                    }
                } else {
                    val wantFanbox = serviceFilter != ServiceFilter.PATREON && fanboxLoggedIn && (reset || nextFb != null)
                    val wantPatreon = serviceFilter != ServiceFilter.FANBOX && patreonLoggedIn && (reset || nextPt != null)

                    if (wantFanbox) {
                        val res = if (activeTab == DesktopFeedTab.SUPPORTING) {
                            DesktopFanboxClient.fetchSupportingFeed(nextFb)
                        } else {
                            DesktopFanboxClient.fetchHomeFeed(nextFb)
                        }
                        when (res) {
                            is DesktopFanboxResult.Success -> {
                                collected.addAll(res.data.items)
                                newFbNext = res.data.nextUrl
                            }
                            is DesktopFanboxResult.AuthRequired -> lastErr = res
                            is DesktopFanboxResult.Error -> lastErr = res
                        }
                    }

                    if (wantPatreon) {
                        val res = DesktopPatreonClient.fetchStream(nextPt)
                        when (res) {
                            is DesktopFanboxResult.Success -> {
                                collected.addAll(res.data.items)
                                newPtNext = res.data.nextUrl
                            }
                            is DesktopFanboxResult.AuthRequired -> if (collected.isEmpty()) lastErr = res
                            is DesktopFanboxResult.Error -> if (collected.isEmpty()) lastErr = res
                        }
                    }
                }
            }

            if (collected.isNotEmpty() || lastErr == null) {
                val merged = if (reset) collected else (feedItems + collected)
                feedItems = merged.distinctBy { it.uniqueKey }.sortedByDescending { it.publishedDatetime }
                fanboxNextUrl = newFbNext
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
            val combined = mutableListOf<DesktopFanboxCreator>()
            var lastErr: DesktopFanboxResult<Nothing>? = null
            withContext(Dispatchers.IO) {
                if (serviceFilter != ServiceFilter.PATREON && fanboxLoggedIn) {
                    when (val r = DesktopFanboxClient.fetchCreators()) {
                        is DesktopFanboxResult.Success -> combined.addAll(r.data)
                        is DesktopFanboxResult.AuthRequired -> lastErr = r
                        is DesktopFanboxResult.Error -> lastErr = r
                    }
                }
                if (serviceFilter != ServiceFilter.FANBOX && patreonLoggedIn) {
                    when (val r = DesktopPatreonClient.fetchSupportingCreators()) {
                        is DesktopFanboxResult.Success -> combined.addAll(r.data)
                        is DesktopFanboxResult.AuthRequired -> if (combined.isEmpty()) lastErr = r
                        is DesktopFanboxResult.Error -> if (combined.isEmpty()) lastErr = r
                    }
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

    fun runSearch() {
        val q = searchQuery.trim()
        if (q.isEmpty() || searchLoading) return
        if (tagSearchMode) {
            hasSearched = true
            return
        }
        searchLoading = true
        searchError = null
        hasSearched = true
        scope.launch {
            val found = mutableListOf<DesktopFanboxCreator>()
            var err: DesktopFanboxResult<Nothing>? = null
            withContext(Dispatchers.IO) {
                if (serviceFilter != ServiceFilter.PATREON) {
                    when (val r = DesktopFanboxClient.searchCreators(q)) {
                        is DesktopFanboxResult.Success -> found.addAll(r.data)
                        is DesktopFanboxResult.AuthRequired -> err = r
                        is DesktopFanboxResult.Error -> err = r
                    }
                }
                if (serviceFilter != ServiceFilter.FANBOX) {
                    when (val r = DesktopPatreonClient.searchCreators(q)) {
                        is DesktopFanboxResult.Success -> found.addAll(r.data)
                        is DesktopFanboxResult.AuthRequired -> if (found.isEmpty()) err = r
                        is DesktopFanboxResult.Error -> if (found.isEmpty()) err = r
                    }
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

    fun toggleFollow(target: DesktopFanboxCreator) {
        if (target.service == FeedService.PATREON) {
            openExternal(target.campaignUrl.ifBlank { "https://www.patreon.com/" })
            return
        }
        if (followBusyId != null) return
        if (!fanboxLoggedIn) {
            loginModalService = FeedService.FANBOX
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
                    recommendedCreators = recommendedCreators.map { if (it.creatorId == updated.creatorId) updated else it }
                    // Immediately sync creators list so newly followed creator appears in 작가 목록!
                    creators = if (desired) {
                        (creators.filterNot { it.creatorId == updated.creatorId && it.service == FeedService.FANBOX } + updated)
                            .sortedByDescending { it.isSupporting }
                    } else {
                        creators.filterNot { it.creatorId == updated.creatorId && it.service == FeedService.FANBOX && !it.isSupporting }
                            .map { if (it.creatorId == updated.creatorId) updated else it }
                    }
                    onLibraryUpdated(if (desired) "${updated.name} 작가를 팔로우했습니다." else "${updated.name} 팔로우를 해제했습니다.")
                }
                is DesktopFanboxResult.AuthRequired -> loginModalService = FeedService.FANBOX
                is DesktopFanboxResult.Error -> onLibraryUpdated(res.message)
            }
            followBusyId = null
        }
    }

    fun saveSinglePost(detail: DesktopFanboxPostDetail) {
        val activeStore = store ?: return
        if (savingJob?.isActive == true) return
        val targets = buildList<Pair<String, String>> {
            detail.images.forEachIndexed { idx, img -> add("%02d_${img.fileName}".format(idx + 1) to img.originalUrl) }
            detail.files.forEach { f -> add(f.fileName to f.url) }
        }
        if (targets.isEmpty()) return
        savingJob = scope.launch {
            var added = 0
            var reused = 0
            try {
                val draft = PostDraft(
                    creator = detail.summary.creatorName,
                    title = detail.summary.title,
                    year = detail.summary.year,
                    sourceUrl = detail.summary.postUrl,
                )
                for ((i, pair) in targets.withIndex()) {
                    if (!isActive) throw CancellationException()
                    val (fileName, url) = pair
                    savingProgress = "저장 중 (${i + 1}/${targets.size}) · $fileName"
                    val report = withContext(Dispatchers.IO) {
                        val stream = if (detail.summary.service == FeedService.PATREON) {
                            DesktopPatreonClient.openDownloadStream(url)
                        } else {
                            DesktopFanboxClient.openDownloadStream(url)
                        }
                        stream.use { input ->
                            activeStore.importManaged(draft, listOf(ImportSource(fileName, "원본/$fileName") { input }))
                        }
                    }
                    added += report.added
                    reused += report.reused
                }
                onLibraryUpdated("내 보관함에 저장 완료 · 추가 ${added}개 · 기존 ${reused}개")
            } catch (_: CancellationException) {
                onLibraryUpdated("저장을 중단했습니다. (지금까지 추가 ${added}개)")
            } catch (e: Exception) {
                onLibraryUpdated("저장 실패: ${e.localizedMessage ?: e.javaClass.simpleName}")
            } finally {
                savingProgress = ""
                savingJob = null
            }
        }
    }

    /**
     * One-click batch archival of ALL accessible posts for a supported creator!
     */
    fun saveAllCreatorPosts(creator: DesktopFanboxCreator) {
        val activeStore = store ?: return
        if (savingJob?.isActive == true) return
        savingJob = scope.launch {
            var totalAdded = 0
            var totalReused = 0
            var postsDone = 0
            try {
                savingProgress = "[${creator.name}] 전체 게시물 목록을 수집하는 중…"
                val allPosts = mutableListOf<DesktopFanboxPostSummary>()
                var nextUrl: String? = null
                var pageGuard = 0
                while (pageGuard++ < 40) {
                    if (!isActive) throw CancellationException()
                    val res = withContext(Dispatchers.IO) {
                        if (creator.service == FeedService.PATREON) {
                            DesktopPatreonClient.fetchCampaignPosts(creator.creatorId, nextUrl)
                        } else {
                            DesktopFanboxClient.fetchCreatorPosts(creator.creatorId, nextUrl)
                        }
                    }
                    if (res is DesktopFanboxResult.Success) {
                        allPosts.addAll(res.data.items.filterNot { it.isRestricted })
                        nextUrl = res.data.nextUrl
                        savingProgress = "[${creator.name}] 게시물 목록 수집 중… (${allPosts.size}개 발견)"
                        if (nextUrl == null) break
                    } else break
                }

                if (allPosts.isEmpty()) {
                    onLibraryUpdated("[${creator.name}] 저장 가능한 게시물이 없습니다.")
                    return@launch
                }

                for ((pIdx, summary) in allPosts.withIndex()) {
                    if (!isActive) throw CancellationException()
                    savingProgress = "[${creator.name}] ${pIdx + 1}/${allPosts.size} · ${summary.title} 확인 중…"
                    val detailRes = withContext(Dispatchers.IO) {
                        if (summary.service == FeedService.PATREON) DesktopPatreonClient.fetchPostDetail(summary.id)
                        else DesktopFanboxClient.fetchPostDetail(summary.id)
                    }
                    val detail = (detailRes as? DesktopFanboxResult.Success)?.data ?: continue
                    val targets = buildList<Pair<String, String>> {
                        detail.images.forEachIndexed { i, img -> add("%02d_${img.fileName}".format(i + 1) to img.originalUrl) }
                        detail.files.forEach { f -> add(f.fileName to f.url) }
                    }
                    if (targets.isEmpty()) continue
                    val draft = PostDraft(
                        creator = detail.summary.creatorName,
                        title = detail.summary.title,
                        year = detail.summary.year,
                        sourceUrl = detail.summary.postUrl,
                    )
                    for ((fIdx, pair) in targets.withIndex()) {
                        if (!isActive) throw CancellationException()
                        val (fileName, url) = pair
                        savingProgress = "[${creator.name}] ${pIdx + 1}/${allPosts.size} (${fIdx + 1}/${targets.size}) · $fileName"
                        runCatching {
                            withContext(Dispatchers.IO) {
                                val stream = if (summary.service == FeedService.PATREON) {
                                    DesktopPatreonClient.openDownloadStream(url)
                                } else {
                                    DesktopFanboxClient.openDownloadStream(url)
                                }
                                stream.use { input ->
                                    activeStore.importManaged(draft, listOf(ImportSource(fileName, "원본/$fileName") { input }))
                                }
                            }
                        }.onSuccess { rep ->
                            totalAdded += rep.added
                            totalReused += rep.reused
                        }
                    }
                    postsDone++
                }
                onLibraryUpdated("[${creator.name}] 일괄 저장 완료 · ${postsDone}작품 · 추가 ${totalAdded}개 · 기존 ${totalReused}개")
            } catch (_: CancellationException) {
                onLibraryUpdated("[${creator.name}] 일괄 저장 중단됨 · ${postsDone}작품 · 추가 ${totalAdded}개")
            } catch (e: Exception) {
                onLibraryUpdated("일괄 저장 오류: ${e.localizedMessage ?: e.javaClass.simpleName}")
            } finally {
                savingProgress = ""
                savingJob = null
            }
        }
    }

    // Load recommended creators once for the Search tab
    LaunchedEffect(activeTab) {
        if (activeTab == DesktopFeedTab.SEARCH && recommendedCreators.isEmpty()) {
            val rec = withContext(Dispatchers.IO) { DesktopFanboxClient.fetchRecommendedCreators() }
            if (rec is DesktopFanboxResult.Success) recommendedCreators = rec.data
        }
    }

    LaunchedEffect(selectedCreator?.creatorId, selectedCreator?.service) {
        val cr = selectedCreator ?: return@LaunchedEffect
        creatorPlans = emptyList()
        creatorTags = emptyList()
        activeCreatorTag = null
        if (cr.service == FeedService.FANBOX) {
            withContext(Dispatchers.IO) {
                Triple(
                    DesktopFanboxClient.fetchCreatorProfile(cr.creatorId),
                    DesktopFanboxClient.fetchCreatorPlans(cr.creatorId),
                    DesktopFanboxClient.fetchCreatorTags(cr.creatorId),
                )
            }.let { (prof, plans, tags) ->
                if (prof is DesktopFanboxResult.Success) {
                    selectedCreator = prof.data.copy(isSupporting = prof.data.isSupporting || cr.isSupporting, planFee = cr.planFee)
                }
                if (plans is DesktopFanboxResult.Success) creatorPlans = plans.data
                if (tags is DesktopFanboxResult.Success) creatorTags = tags.data
            }
        }
    }

    LaunchedEffect(activeTab, serviceFilter, selectedCreator?.creatorId, activeCreatorTag, fanboxLoggedIn, patreonLoggedIn) {
        when {
            selectedCreator != null -> loadFeed(reset = true)
            activeTab == DesktopFeedTab.CREATORS -> if (fanboxLoggedIn || patreonLoggedIn) loadCreators()
            activeTab == DesktopFeedTab.SEARCH -> Unit
            else -> if (fanboxLoggedIn || patreonLoggedIn) loadFeed(reset = true)
        }
    }

    val filteredFeed = remember(feedItems, showAdult, hideTextPosts, serviceFilter, selectedCreator) {
        feedItems.filter { p ->
            val okService = selectedCreator != null || serviceFilter == ServiceFilter.ALL ||
                (serviceFilter == ServiceFilter.FANBOX && p.service == FeedService.FANBOX) ||
                (serviceFilter == ServiceFilter.PATREON && p.service == FeedService.PATREON)
            val okAdult = showAdult || !p.hasAdultContent
            val okText = !hideTextPosts || !p.isTextOnly
            okService && okAdult && okText
        }
    }

    Row(Modifier.fillMaxSize().background(colors.bg)) {
        // Left Sidebar
        Column(
            modifier = Modifier.fillMaxHeight().width(238.dp).background(colors.sidebar).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("서비스 필터", style = MaterialTheme.typography.labelMedium, color = colors.muted, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ServiceFilter.entries.forEach { sf ->
                    val sel = serviceFilter == sf
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (sel) colors.accent else Color.Transparent)
                            .clickable { serviceFilter = sf }
                            .padding(vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(sf.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (sel) Color.White else colors.muted)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text("피드 메뉴", style = MaterialTheme.typography.labelMedium, color = colors.muted, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))

            DesktopFeedTab.entries.forEach { tab ->
                val selected = activeTab == tab && selectedCreator == null && activePost == null
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) colors.surfaceElevated else Color.Transparent)
                        .border(1.dp, if (selected) colors.borderStrong else Color.Transparent, RoundedCornerShape(10.dp))
                        .clickable {
                            activeTab = tab
                            selectedCreator = null
                            activePost = null
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

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = colors.border)
            Spacer(Modifier.height(10.dp))

            // Toggles: R-18 & Hide Text Posts
            SidebarToggleRow(
                icon = Icons.Rounded.Shield,
                label = "R-18 표시",
                active = showAdult,
                activeColor = colors.accent,
                onClick = { showAdult = !showAdult; fanboxUiPrefs.putBoolean("showAdult", showAdult) },
            )
            SidebarToggleRow(
                icon = Icons.Rounded.HideImage,
                label = "텍스트 글 숨김",
                active = hideTextPosts,
                activeColor = colors.secondary,
                onClick = { hideTextPosts = !hideTextPosts; fanboxUiPrefs.putBoolean("hideTextPosts", hideTextPosts) },
            )

            Spacer(Modifier.weight(1f))

            // Bottom Login Cards (FANBOX + Patreon)
            ServiceSessionCard(
                name = "FANBOX",
                connected = fanboxLoggedIn,
                accent = colors.accent,
                onConnect = { loginModalService = FeedService.FANBOX },
                onLogout = {
                    DesktopFanboxSession.clearSession()
                    fanboxLoggedIn = false
                    feedItems = feedItems.filter { it.service != FeedService.FANBOX }
                    creators = creators.filter { it.service != FeedService.FANBOX }
                },
            )
            Spacer(Modifier.height(6.dp))
            ServiceSessionCard(
                name = "Patreon",
                connected = patreonLoggedIn,
                accent = Color(0xFFFF6B3D),
                onConnect = { loginModalService = FeedService.PATREON },
                onLogout = {
                    DesktopPatreonSession.clearSession()
                    patreonLoggedIn = false
                    feedItems = feedItems.filter { it.service != FeedService.PATREON }
                    creators = creators.filter { it.service != FeedService.PATREON }
                },
            )
        }

        Box(Modifier.fillMaxHeight().width(1.dp).background(colors.border))

        // Main Panel
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (savingProgress.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.accent)
                    Text(savingProgress, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { savingJob?.cancel() }) { Text("중단", color = MaterialTheme.colorScheme.error) }
                }
                HorizontalDivider(color = colors.border)
            }

            val currentPost = activePost
            val anyLoggedIn = fanboxLoggedIn || patreonLoggedIn

            when {
                currentPost != null -> DesktopFanboxPostDetailPane(
                    summary = currentPost,
                    feedList = filteredFeed,
                    archivedLocalId = archivedMap[currentPost.uniqueKey],
                    saving = savingJob?.isActive == true,
                    onSelectPost = { activePost = it },
                    onSelectCreator = { id, name, icon, svc ->
                        activePost = null
                        selectedCreator = DesktopFanboxCreator(id, "", name, icon, null, "", service = svc)
                    },
                    onSaveToLibrary = ::saveSinglePost,
                    onOpenArchived = onOpenArchivedPost,
                    onOpenExternal = ::openExternal,
                    onBack = { activePost = null },
                )

                selectedCreator != null -> {
                    val cr = selectedCreator!!
                    DesktopCreatorProfilePane(
                        creator = cr,
                        plans = creatorPlans,
                        tags = creatorTags,
                        activeTag = activeCreatorTag,
                        onSelectTag = { tag -> activeCreatorTag = if (activeCreatorTag == tag) null else tag },
                        posts = filteredFeed,
                        hiddenAdultCount = if (showAdult) 0 else feedItems.count { it.hasAdultContent },
                        hideTextPosts = hideTextPosts,
                        onToggleHideText = { hideTextPosts = !hideTextPosts; fanboxUiPrefs.putBoolean("hideTextPosts", hideTextPosts) },
                        loading = feedLoading,
                        error = feedError,
                        hasMore = fanboxNextUrl != null || patreonNextUrl != null,
                        followBusy = followBusyId == cr.creatorId,
                        batchSaving = savingJob?.isActive == true,
                        archivedKeys = archivedMap.keys,
                        onBack = { selectedCreator = null },
                        onToggleFollow = { toggleFollow(cr) },
                        onSaveAllPosts = { saveAllCreatorPosts(cr) },
                        onOpenPlans = {
                            val url = if (cr.service == FeedService.PATREON) cr.campaignUrl.ifBlank { "https://www.patreon.com/" }
                            else "https://${cr.creatorId}.fanbox.cc/plans"
                            openExternal(url)
                        },
                        onEnableAdult = { showAdult = true; fanboxUiPrefs.putBoolean("showAdult", true) },
                        onOpenPost = { activePost = it },
                        onLoadMore = { loadFeed(reset = false) },
                        onRetry = { loadFeed(reset = true) },
                        onLogin = { loginModalService = cr.service },
                    )
                }

                activeTab == DesktopFeedTab.SEARCH -> DesktopCreatorAndTagSearchPane(
                    query = searchQuery,
                    onQuery = { searchQuery = it },
                    tagMode = tagSearchMode,
                    onToggleTagMode = { tagSearchMode = it },
                    onSearch = ::runSearch,
                    results = searchResults,
                    recommended = recommendedCreators,
                    feedPostsForTagSearch = feedItems,
                    loading = searchLoading,
                    error = searchError,
                    hasSearched = hasSearched,
                    followBusyId = followBusyId,
                    archivedKeys = archivedMap.keys,
                    onSelectCreator = { selectedCreator = it },
                    onToggleFollow = ::toggleFollow,
                    onOpenPost = { activePost = it },
                )

                !anyLoggedIn && feedItems.isEmpty() && creators.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            modifier = Modifier
                                .widthIn(max = 480.dp)
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
                            Text("FANBOX · Patreon 통합 피드", style = MaterialTheme.typography.titleLarge, color = colors.ink)
                            Text(
                                "원클릭 웹 로그인으로 FANBOX와 Patreon 계정을 연결하면 후원 작가 피드, 일괄 저장, 태그 검색을 한 화면에서 이용할 수 있습니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.muted,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                                Button(onClick = { loginModalService = FeedService.FANBOX }) { Text("FANBOX 로그인") }
                                OutlinedButton(onClick = { loginModalService = FeedService.PATREON }) { Text("Patreon 로그인") }
                            }
                            TextButton(onClick = { activeTab = DesktopFeedTab.SEARCH }) { Text("로그인 없이 추천 작가 · 검색 둘러보기") }
                        }
                    }
                }

                activeTab == DesktopFeedTab.CREATORS -> DesktopCreatorsListPane(
                    creators = creators,
                    loading = creatorsLoading,
                    error = creatorsError,
                    followBusyId = followBusyId,
                    batchSaving = savingJob?.isActive == true,
                    onSelectCreator = { selectedCreator = it },
                    onToggleFollow = ::toggleFollow,
                    onSaveAllPosts = ::saveAllCreatorPosts,
                    onRefresh = ::loadCreators,
                    onLogin = { loginModalService = FeedService.FANBOX },
                )

                else -> Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
                    // Feed Header Toolbar
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 12.dp),
                    ) {
                        Text(activeTab.label, style = MaterialTheme.typography.headlineSmall, color = colors.ink)
                        Box(
                            Modifier.clip(RoundedCornerShape(14.dp)).background(colors.accentSoft).padding(horizontal = 9.dp, vertical = 2.dp),
                        ) {
                            Text("${filteredFeed.size}개", style = MaterialTheme.typography.labelSmall, color = colors.accent, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.weight(1f))

                        if (activeTab == DesktopFeedTab.SUPPORTING) {
                            ShelfFilter("작가별 1줄 보기", supportingGroupedByCreator) { supportingGroupedByCreator = true }
                            ShelfFilter("최신순 그리드", !supportingGroupedByCreator) { supportingGroupedByCreator = false }
                        }

                        ShelfFilter(if (hideTextPosts) "텍스트 숨김 ✓" else "텍스트 글 숨기기", hideTextPosts) {
                            hideTextPosts = !hideTextPosts
                            fanboxUiPrefs.putBoolean("hideTextPosts", hideTextPosts)
                        }

                        OutlinedButton(onClick = { loadFeed(reset = true) }, shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp)) {
                            Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("새로고침", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    if (activeTab == DesktopFeedTab.SUPPORTING && supportingGroupedByCreator) {
                        DesktopSupportingByCreatorPane(
                            posts = filteredFeed,
                            loading = feedLoading,
                            error = feedError,
                            hasMore = fanboxNextUrl != null || patreonNextUrl != null,
                            batchSaving = savingJob?.isActive == true,
                            archivedKeys = archivedMap.keys,
                            onOpenPost = { activePost = it },
                            onOpenCreator = { p ->
                                selectedCreator = DesktopFanboxCreator(p.creatorId, "", p.creatorName, p.creatorIconUrl, null, "", isSupporting = true, service = p.service)
                            },
                            onSaveAllForCreator = { p ->
                                saveAllCreatorPosts(DesktopFanboxCreator(p.creatorId, "", p.creatorName, p.creatorIconUrl, null, "", isSupporting = true, service = p.service))
                            },
                            onLoadMore = { loadFeed(reset = false) },
                            onRetry = { loadFeed(reset = true) },
                            onLogin = { loginModalService = FeedService.FANBOX },
                        )
                    } else {
                        DesktopFeedGrid(
                            posts = filteredFeed,
                            hiddenAdultCount = if (showAdult) 0 else feedItems.count { it.hasAdultContent },
                            loading = feedLoading,
                            error = feedError,
                            hasMore = fanboxNextUrl != null || patreonNextUrl != null,
                            archivedKeys = archivedMap.keys,
                            onEnableAdult = { showAdult = true; fanboxUiPrefs.putBoolean("showAdult", true) },
                            onOpenPost = { activePost = it },
                            onSelectCreator = { p ->
                                selectedCreator = DesktopFanboxCreator(p.creatorId, "", p.creatorName, p.creatorIconUrl, null, "", service = p.service)
                            },
                            onLoadMore = { loadFeed(reset = false) },
                            onRetry = { loadFeed(reset = true) },
                            onLogin = { loginModalService = FeedService.FANBOX },
                        )
                    }
                }
            }
        }
    }

    loginModalService?.let { svc ->
        DesktopServiceLoginDialog(
            service = svc,
            onOpenBrowser = {
                openExternal(if (svc == FeedService.PATREON) "https://www.patreon.com/login" else "https://www.fanbox.cc/login")
            },
            onSaved = {
                fanboxLoggedIn = DesktopFanboxSession.hasLogin()
                patreonLoggedIn = DesktopPatreonSession.hasLogin()
                loginModalService = null
                if (activeTab == DesktopFeedTab.CREATORS) loadCreators()
                else if (activeTab != DesktopFeedTab.SEARCH) loadFeed(reset = true)
            },
            onDismiss = { loginModalService = null },
        )
    }
}

@Composable
private fun SidebarToggleRow(
    icon: ImageVector,
    label: String,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
) {
    val colors = LocalGalleryColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(if (active) activeColor.copy(alpha = 0.16f) else colors.surface)
            .border(1.dp, if (active) activeColor else colors.border, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = if (active) activeColor else colors.muted, modifier = Modifier.size(16.dp))
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.ink)
        Text(if (active) "ON" else "OFF", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = if (active) activeColor else colors.muted)
    }
}

@Composable
private fun ServiceSessionCard(
    name: String,
    connected: Boolean,
    accent: Color,
    onConnect: () -> Unit,
    onLogout: () -> Unit,
) {
    val colors = LocalGalleryColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (connected) colors.emerald else colors.muted.copy(alpha = 0.4f)))
        Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = colors.ink)
        if (connected) {
            TextButton(onClick = onLogout, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                Text("해제", style = MaterialTheme.typography.labelSmall, color = colors.muted)
            }
        } else {
            TextButton(onClick = onConnect, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                Text("로그인", style = MaterialTheme.typography.labelSmall, color = accent, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Supporting tab grouped by Creator:
 * - One horizontal scrollable row per creator (with Expand/Collapse button + One-click "전체 저장"!)
 */
@Composable
private fun DesktopSupportingByCreatorPane(
    posts: List<DesktopFanboxPostSummary>,
    loading: Boolean,
    error: DesktopFanboxResult<Nothing>?,
    hasMore: Boolean,
    batchSaving: Boolean,
    archivedKeys: Set<String>,
    onOpenPost: (DesktopFanboxPostSummary) -> Unit,
    onOpenCreator: (DesktopFanboxPostSummary) -> Unit,
    onSaveAllForCreator: (DesktopFanboxPostSummary) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onLogin: () -> Unit,
) {
    val colors = LocalGalleryColors.current
    var expandedCreators by remember { mutableStateOf<Set<String>>(emptySet()) }

    if (loading && posts.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accent) }
        return
    }
    if (error != null && posts.isEmpty()) {
        DesktopFeedError(error, onLogin, onRetry)
        return
    }

    val grouped = remember(posts) {
        posts.groupBy { "${it.service.name}:${it.creatorId}" }.entries.toList()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        items(grouped, key = { it.key }) { (groupKey, creatorPosts) ->
            val first = creatorPosts.first()
            val expanded = groupKey in expandedCreators
            val avatar = DesktopFanboxClient.rememberRemoteImage(first.creatorIconUrl, 96)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(colors.surfaceElevated), contentAlignment = Alignment.Center) {
                        if (avatar != null) Image(avatar, first.creatorName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Text(first.creatorName.take(1).uppercase(), fontWeight = FontWeight.Bold, color = colors.accent)
                    }
                    Column(Modifier.weight(1f).clickable { onOpenCreator(first) }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(first.creatorName, style = MaterialTheme.typography.titleMedium, color = colors.ink)
                            FeedBadge(first.service.label, if (first.service == FeedService.PATREON) Color(0xCCEA580C) else Color(0xCC059669))
                            Text("${creatorPosts.size}개", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                        }
                        Text("클릭해서 작가 전체 보기", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                    }
                    OutlinedButton(
                        onClick = { onSaveAllForCreator(first) },
                        enabled = !batchSaving,
                        shape = RoundedCornerShape(9.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Icon(Icons.Rounded.CloudDownload, null, Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("전체 게시물 저장", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(
                        onClick = {
                            expandedCreators = if (expanded) expandedCreators - groupKey else expandedCreators + groupKey
                        },
                    ) {
                        Text(if (expanded) "접기" else "펼치기 (${creatorPosts.size})")
                        Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(18.dp))
                    }
                }

                if (!expanded) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(creatorPosts, key = { it.uniqueKey }) { post ->
                            FeedPostCardWithHoverPreview(
                                post = post,
                                isArchived = post.uniqueKey in archivedKeys,
                                onClick = { onOpenPost(post) },
                                onSelectCreator = { onOpenCreator(post) },
                                modifier = Modifier.width(232.dp),
                            )
                        }
                    }
                } else {
                    val rows = creatorPosts.chunked(4)
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        rows.forEach { rowPosts ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                rowPosts.forEach { post ->
                                    FeedPostCardWithHoverPreview(
                                        post = post,
                                        isArchived = post.uniqueKey in archivedKeys,
                                        onClick = { onOpenPost(post) },
                                        onSelectCreator = { onOpenCreator(post) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                repeat(4 - rowPosts.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }

        if (hasMore) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    if (loading) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = colors.accent)
                    else OutlinedButton(onClick = onLoadMore, shape = RoundedCornerShape(10.dp)) { Text("더 불러오기") }
                }
            }
        }
    }
}

/**
 * Feed card with 1.2s delayed high-res hover preview popup (never flickers on fast mouse moves!).
 */
@Composable
private fun FeedPostCardWithHoverPreview(
    post: DesktopFanboxPostSummary,
    isArchived: Boolean,
    onClick: () -> Unit,
    onSelectCreator: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGalleryColors.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var showPreviewPopup by remember(post.uniqueKey) { mutableStateOf(false) }

    LaunchedEffect(hovered, post.coverImageUrl) {
        if (hovered && !post.coverImageUrl.isNullOrBlank()) {
            delay(1250L) // Calm 1.25s dwell before showing enlarged original preview
            showPreviewPopup = true
        } else {
            showPreviewPopup = false
        }
    }

    val cover = DesktopFanboxClient.rememberRemoteImage(post.coverImageUrl, 520)

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.surface)
                .border(if (hovered) 1.5.dp else 1.dp, if (hovered) colors.accent else colors.border, RoundedCornerShape(14.dp))
                .hoverable(interaction)
                .clickable(onClick = onClick),
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
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (post.service == FeedService.PATREON) FeedBadge("Patreon", Color(0xCCEA580C))
                        if (isArchived) FeedBadge("보관됨", Color(0xCC059669))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (post.hasAdultContent) FeedBadge("R-18", Color(0xCCE11D48))
                        if (post.feeRequired > 0) {
                            val feeLabel = if (post.service == FeedService.PATREON) "$${post.feeRequired}" else "¥${post.feeRequired}"
                            FeedBadge(feeLabel, Color(0xCC4F46E5))
                        } else {
                            FeedBadge("무료", Color(0xB80E1016))
                        }
                    }
                }
                if (post.isRestricted) {
                    Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) { FeedBadge("후원자 한정", Color(0xCC11141C)) }
                }
            }
            Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(post.title, style = MaterialTheme.typography.titleSmall, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        post.creatorName,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).clickable(onClick = onSelectCreator),
                    )
                    Text("· ${post.displayDate}", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Rounded.Favorite, null, tint = colors.accent.copy(alpha = 0.75f), modifier = Modifier.size(12.dp))
                    Text("${post.likeCount}", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                }
            }
        }

        // Delayed 1.25s High-Res First Image Hover Preview Popup
        if (showPreviewPopup && !post.coverImageUrl.isNullOrBlank()) {
            val highRes = DesktopFanboxClient.rememberRemoteImage(post.coverImageUrl, 1200) ?: cover
            Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, -14)) {
                Column(
                    modifier = Modifier
                        .width(340.dp)
                        .shadow(20.dp, RoundedCornerShape(14.dp))
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.cinemaBg)
                        .border(1.5.dp, colors.accent.copy(alpha = 0.75f), RoundedCornerShape(14.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 380.dp), contentAlignment = Alignment.Center) {
                        if (highRes != null) {
                            Image(highRes, post.title, Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)), contentScale = ContentScale.Fit)
                        } else {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), color = colors.accent)
                        }
                    }
                    Text(post.title, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun DesktopServiceLoginDialog(
    service: FeedService,
    onOpenBrowser: () -> Unit,
    onSaved: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalGalleryColors.current
    val scope = rememberCoroutineScope()
    val isPatreon = service == FeedService.PATREON
    var input by remember { mutableStateOf(if (isPatreon) DesktopPatreonSession.rawCookie() else DesktopFanboxSession.rawCookie()) }
    var showManual by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var loginJob by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) { onDispose { loginJob?.cancel() } }

    AlertDialog(
        onDismissRequest = { loginJob?.cancel(); onDismiss() },
        containerColor = colors.surface,
        titleContentColor = colors.ink,
        textContentColor = colors.ink,
        title = { Text("${service.label} 로그인 (PC)", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.widthIn(min = 440.dp, max = 520.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "전용 로그인 창이 열립니다. ${service.label}에 로그인하면 세션을 자동 감지해 창이 닫히고 피드가 바로 연결됩니다.",
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
                            val res = if (isPatreon) DesktopWebLogin.runPatreonPopupLogin { status = it }
                            else DesktopWebLogin.runPopupLogin { status = it }
                            res.onSuccess { onSaved() }
                                .onFailure { err -> if (err !is CancellationException) status = err.localizedMessage ?: "로그인 실패" }
                            busy = false
                        }
                    },
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Login, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (busy) "로그인 대기 중…" else "${service.label} 웹 창으로 간편 로그인")
                }

                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accent)
                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, color = colors.accent)

                HorizontalDivider(color = colors.border)
                Row(Modifier.fillMaxWidth().clickable { showManual = !showManual }) {
                    val cookieName = if (isPatreon) "session_id" else "FANBOXSESSID"
                    Text(if (showManual) "▴ 쿠키 직접 붙여넣기 접기" else "▾ 또는 $cookieName 쿠키 직접 입력", style = MaterialTheme.typography.labelMedium, color = colors.muted)
                }
                if (showManual) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text(if (isPatreon) "Patreon session_id 또는 Cookie" else "FANBOXSESSID 또는 Cookie") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onOpenBrowser) { Text("브라우저 열기") }
                        Button(
                            enabled = input.isNotBlank() && !busy,
                            onClick = {
                                if (isPatreon) DesktopPatreonSession.saveCookie(input)
                                else DesktopFanboxSession.saveSessionInput(input)
                                onSaved()
                            },
                        ) { Text("저장") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { loginJob?.cancel(); onDismiss() }) { Text("닫기") } },
    )
}

@Composable
private fun DesktopCreatorAndTagSearchPane(
    query: String,
    onQuery: (String) -> Unit,
    tagMode: Boolean,
    onToggleTagMode: (Boolean) -> Unit,
    onSearch: () -> Unit,
    results: List<DesktopFanboxCreator>,
    recommended: List<DesktopFanboxCreator>,
    feedPostsForTagSearch: List<DesktopFanboxPostSummary>,
    loading: Boolean,
    error: DesktopFanboxResult<Nothing>?,
    hasSearched: Boolean,
    followBusyId: String?,
    archivedKeys: Set<String>,
    onSelectCreator: (DesktopFanboxCreator) -> Unit,
    onToggleFollow: (DesktopFanboxCreator) -> Unit,
    onOpenPost: (DesktopFanboxPostSummary) -> Unit,
) {
    val colors = LocalGalleryColors.current
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("작가 · 태그 찾기", style = MaterialTheme.typography.headlineSmall, color = colors.ink)
            ShelfFilter("작가 검색", !tagMode) { onToggleTagMode(false) }
            ShelfFilter("#태그 검색", tagMode) { onToggleTagMode(true) }
        }

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
                Icon(if (tagMode) Icons.Rounded.Tag else Icons.Rounded.Search, null, tint = colors.muted, modifier = Modifier.size(18.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            if (tagMode) "불러온 피드 내 #태그·제목 키워드 검색 (예: 差分, PSD, R18)…"
                            else "작가명, @ID, 또는 fanbox.cc 주소 입력 후 Enter…",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.muted,
                        )
                    }
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
            if (!tagMode) {
                Button(onClick = onSearch, enabled = query.isNotBlank() && !loading, shape = RoundedCornerShape(10.dp)) { Text("검색") }
            }
        }

        if (tagMode) {
            val allTags = remember(feedPostsForTagSearch) {
                feedPostsForTagSearch.flatMap { it.tags }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(24)
            }
            if (allTags.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    allTags.forEach { (tag, count) ->
                        ShelfFilter("#$tag ($count)", query.equals(tag, true)) { onQuery(if (query.equals(tag, true)) "" else tag) }
                    }
                }
            }
            val term = query.trim().removePrefix("#")
            val matched = remember(feedPostsForTagSearch, term) {
                if (term.isEmpty()) feedPostsForTagSearch.filter { it.tags.isNotEmpty() }
                else feedPostsForTagSearch.filter { p -> p.tags.any { it.contains(term, true) } || p.title.contains(term, true) }
            }
            if (matched.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("일치하는 태그 게시글이 없습니다. 먼저 홈/후원중 피드를 불러오거나 작가 페이지에서 인기 태그를 선택해 보세요.", color = colors.muted, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(245.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(matched, key = { it.uniqueKey }) { post ->
                        FeedPostCardWithHoverPreview(
                            post = post,
                            isArchived = post.uniqueKey in archivedKeys,
                            onClick = { onOpenPost(post) },
                            onSelectCreator = { onSelectCreator(DesktopFanboxCreator(post.creatorId, "", post.creatorName, post.creatorIconUrl, null, "", service = post.service)) },
                        )
                    }
                }
            }
        } else {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accent) }
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text((error as? DesktopFanboxResult.Error)?.message ?: "검색 오류", color = colors.muted) }
                hasSearched && results.isNotEmpty() -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(results, key = { "${it.service.name}:${it.creatorId}" }) { cr ->
                        DesktopCreatorRow(cr, followBusyId == cr.creatorId, false, { onSelectCreator(cr) }, { onToggleFollow(cr) }, null)
                    }
                }
                hasSearched && results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("검색 결과가 없습니다.", color = colors.muted)
                }
                else -> {
                    // Show FANBOX Recommended Creators!
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = colors.emerald, modifier = Modifier.size(18.dp))
                        Text("FANBOX 추천 작가", style = MaterialTheme.typography.titleMedium, color = colors.ink)
                    }
                    if (recommended.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("추천 작가를 불러오는 중이거나 위 검색창에서 작가를 찾아보세요.", color = colors.muted)
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(recommended, key = { it.creatorId }) { cr ->
                                DesktopCreatorRow(cr, followBusyId == cr.creatorId, false, { onSelectCreator(cr) }, { onToggleFollow(cr) }, null)
                            }
                        }
                    }
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
    batchSaving: Boolean,
    onSelectCreator: (DesktopFanboxCreator) -> Unit,
    onToggleFollow: (DesktopFanboxCreator) -> Unit,
    onSaveAllPosts: (DesktopFanboxCreator) -> Unit,
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
                items(creators, key = { "${it.service.name}:${it.creatorId}" }) { cr ->
                    DesktopCreatorRow(
                        creator = cr,
                        followBusy = followBusyId == cr.creatorId,
                        batchSaving = batchSaving,
                        onClick = { onSelectCreator(cr) },
                        onToggleFollow = { onToggleFollow(cr) },
                        onSaveAllPosts = if (cr.isSupporting) ({ onSaveAllPosts(cr) }) else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopCreatorRow(
    creator: DesktopFanboxCreator,
    followBusy: Boolean,
    batchSaving: Boolean,
    onClick: () -> Unit,
    onToggleFollow: () -> Unit,
    onSaveAllPosts: (() -> Unit)?,
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(creator.name, style = MaterialTheme.typography.titleMedium, color = colors.ink)
                if (creator.service == FeedService.PATREON) {
                    FeedBadge("Patreon", Color(0xCCEA580C))
                }
                if (creator.isSupporting) {
                    val feeTxt = when {
                        creator.planFee > 0 && creator.service == FeedService.PATREON -> "후원중 $${creator.planFee}"
                        creator.planFee > 0 -> "후원중 ¥${creator.planFee}"
                        else -> "후원중"
                    }
                    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(colors.emeraldSoft).padding(horizontal = 7.dp, vertical = 2.dp)) {
                        Text(feeTxt, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = colors.emerald)
                    }
                }
            }
            Text(if (creator.service == FeedService.PATREON) "Patreon" else "@${creator.creatorId}", style = MaterialTheme.typography.labelSmall, color = colors.muted)
            if (creator.description.isNotBlank()) {
                Text(creator.description, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (onSaveAllPosts != null) {
            Button(
                onClick = onSaveAllPosts,
                enabled = !batchSaving,
                shape = RoundedCornerShape(9.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.emerald),
                contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp),
            ) {
                Icon(Icons.Rounded.CloudDownload, null, Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text("모든 게시물 저장", style = MaterialTheme.typography.labelSmall)
            }
        }
        OutlinedButton(
            onClick = onToggleFollow,
            enabled = !followBusy,
            shape = RoundedCornerShape(9.dp),
            border = BorderStroke(1.dp, if (creator.isFollowed) colors.border else colors.accent),
        ) {
            Text(
                when {
                    creator.service == FeedService.PATREON -> "페이지 ↗"
                    followBusy -> "…"
                    creator.isFollowed -> "팔로우 중"
                    else -> "팔로우"
                },
                color = if (creator.isFollowed) colors.muted else colors.accent,
            )
        }
    }
}

@Composable
private fun DesktopCreatorProfilePane(
    creator: DesktopFanboxCreator,
    plans: List<DesktopFanboxPlan>,
    tags: List<String>,
    activeTag: String?,
    onSelectTag: (String) -> Unit,
    posts: List<DesktopFanboxPostSummary>,
    hiddenAdultCount: Int,
    hideTextPosts: Boolean,
    onToggleHideText: () -> Unit,
    loading: Boolean,
    error: DesktopFanboxResult<Nothing>?,
    hasMore: Boolean,
    followBusy: Boolean,
    batchSaving: Boolean,
    archivedKeys: Set<String>,
    onBack: () -> Unit,
    onToggleFollow: () -> Unit,
    onSaveAllPosts: () -> Unit,
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
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ChevronLeft, "뒤로", tint = colors.ink) }
            Box(Modifier.size(46.dp).clip(CircleShape).background(colors.surfaceElevated), contentAlignment = Alignment.Center) {
                if (avatar != null) Image(avatar, creator.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text(creator.name.take(1).uppercase(), fontWeight = FontWeight.Bold, color = colors.accent)
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(creator.name, style = MaterialTheme.typography.titleLarge, color = colors.ink)
                    if (creator.isSupporting) FeedBadge("후원중", colors.emerald)
                }
                Text(if (creator.service == FeedService.PATREON) "Patreon" else "@${creator.creatorId}", style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
            Button(
                onClick = onSaveAllPosts,
                enabled = !batchSaving,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.emerald),
            ) {
                Icon(Icons.Rounded.CloudDownload, null, Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("모든 게시물 저장")
            }
            if (creator.service == FeedService.FANBOX) {
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
                    Text(if (followBusy) "…" else if (creator.isFollowed) "팔로우 중" else "팔로우")
                }
            }
            OutlinedButton(onClick = onOpenPlans, shape = RoundedCornerShape(10.dp)) {
                Icon(Icons.Rounded.CardMembership, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (creator.isSupporting) "플랜 관리 ↗" else "후원 플랜 ↗")
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

        // Creator Featured Tags + Hide Text Toggle
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShelfFilter(if (hideTextPosts) "텍스트 숨김 ✓" else "텍스트 글 숨기기", hideTextPosts, onToggleHideText)
            if (tags.isNotEmpty()) {
                tags.forEach { t ->
                    ShelfFilter("#$t", activeTag == t) { onSelectTag(t) }
                }
            }
        }

        DesktopFeedGrid(
            posts = posts,
            hiddenAdultCount = hiddenAdultCount,
            loading = loading,
            error = error,
            hasMore = hasMore,
            archivedKeys = archivedKeys,
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
    archivedKeys: Set<String>,
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
        items(posts, key = { it.uniqueKey }) { post ->
            FeedPostCardWithHoverPreview(
                post = post,
                isArchived = post.uniqueKey in archivedKeys,
                onClick = { onOpenPost(post) },
                onSelectCreator = { onSelectCreator(post) },
            )
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
                if (error is DesktopFanboxResult.AuthRequired) OutlinedButton(onClick = onLogin) { Text("로그인") }
            }
        }
    }
}

@Composable
private fun DesktopFanboxPostDetailPane(
    summary: DesktopFanboxPostSummary,
    feedList: List<DesktopFanboxPostSummary>,
    archivedLocalId: String?,
    saving: Boolean,
    onSelectPost: (DesktopFanboxPostSummary) -> Unit,
    onSelectCreator: (String, String, String?, FeedService) -> Unit,
    onSaveToLibrary: (DesktopFanboxPostDetail) -> Unit,
    onOpenArchived: (String) -> Unit,
    onOpenExternal: (String) -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalGalleryColors.current
    val idx = remember(feedList, summary.uniqueKey) { feedList.indexOfFirst { it.uniqueKey == summary.uniqueKey } }
    val prevPost = if (idx > 0) feedList[idx - 1] else null
    val nextPost = if (idx in 0 until feedList.lastIndex) feedList[idx + 1] else null

    var loading by remember(summary.uniqueKey) { mutableStateOf(true) }
    var detail by remember(summary.uniqueKey) { mutableStateOf<DesktopFanboxPostDetail?>(null) }
    var error by remember(summary.uniqueKey) { mutableStateOf<DesktopFanboxResult<Nothing>?>(null) }
    var lightboxIdx by remember(summary.uniqueKey) { mutableStateOf<Int?>(null) }
    var reloadKey by remember(summary.uniqueKey) { mutableIntStateOf(0) }

    val focus = remember { FocusRequester() }
    LaunchedEffect(summary.uniqueKey, reloadKey) {
        focus.requestFocus()
        loading = true
        error = null
        val res = withContext(Dispatchers.IO) {
            if (summary.service == FeedService.PATREON) DesktopPatreonClient.fetchPostDetail(summary.id)
            else DesktopFanboxClient.fetchPostDetail(summary.id)
        }
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
                    lb != null && ev.key == Key.DirectionLeft -> { if (lb > 0) lightboxIdx = lb - 1; true }
                    lb != null && ev.key == Key.DirectionRight -> { if (lb < imgs.lastIndex) lightboxIdx = lb + 1; true }
                    lb == null && (ev.key == Key.PageUp || (ev.isCtrlPressed && ev.key == Key.DirectionLeft)) && prevPost != null -> {
                        onSelectPost(prevPost); true
                    }
                    lb == null && (ev.key == Key.PageDown || (ev.isCtrlPressed && ev.key == Key.DirectionRight)) && nextPost != null -> {
                        onSelectPost(nextPost); true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        val lb = lightboxIdx
        val activeDetail = detail
        if (lb != null && activeDetail != null && lb in activeDetail.images.indices) {
            val img = activeDetail.images[lb]
            val bmp = DesktopFanboxClient.rememberRemoteImage(img.originalUrl, 2400)
            // Prefetch adjacent online image!
            LaunchedEffect(lb) {
                activeDetail.images.getOrNull(lb + 1)?.let { nextImg ->
                    withContext(Dispatchers.IO) { runCatching { DesktopFanboxClient.decodeRemoteImage(nextImg.originalUrl, 2400) } }
                }
            }
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

        Row(
            modifier = Modifier.fillMaxWidth().background(colors.sidebar).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onBack) { Text("‹ 피드", color = colors.ink, fontWeight = FontWeight.SemiBold) }
            if (feedList.size > 1 && idx >= 0) {
                IconButton(onClick = { prevPost?.let(onSelectPost) }, enabled = prevPost != null, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Rounded.ChevronLeft, "이전 게시글")
                }
                Text("게시글 ${idx + 1}/${feedList.size}", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                IconButton(onClick = { nextPost?.let(onSelectPost) }, enabled = nextPost != null, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Rounded.ChevronRight, "다음 게시글")
                }
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { onOpenExternal(detail?.summary?.postUrl ?: summary.postUrl) }, shape = RoundedCornerShape(9.dp)) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text("브라우저에서 열기")
            }
        }
        HorizontalDivider(color = colors.border)

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accent) }
            error != null -> DesktopFeedError(error!!, {}, { reloadKey++ })
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
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (s.service == FeedService.PATREON) FeedBadge("Patreon", Color(0xCCEA580C))
                                Text(s.title, style = MaterialTheme.typography.headlineSmall, color = colors.ink)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    s.creatorName,
                                    color = colors.accent,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable { onSelectCreator(s.creatorId, s.creatorName, s.creatorIconUrl, s.service) },
                                )
                                Text("· ${s.displayDate}", style = MaterialTheme.typography.bodySmall, color = colors.muted)
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
                            if (activeDetail.tags.isNotEmpty()) {
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    activeDetail.tags.forEach { t -> FeedBadge("#$t", colors.surfaceElevated) }
                                }
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
