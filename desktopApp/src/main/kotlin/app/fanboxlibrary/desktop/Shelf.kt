package app.fanboxlibrary.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFolderUpload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MergeType
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fanboxlibrary.shared.LibraryPost
import java.io.File
import kotlin.math.abs

internal fun LibraryPost.artistLabel() = creator.ifBlank { "작가 미분류" }
internal fun LibraryPost.yearLabel() = year?.toString() ?: "연도 미상"
internal fun LibraryPost.titleLabel() = title.ifBlank { "제목 없는 작품" }
internal fun LibraryPost.hasTextless() = assets.any { it.isImage && it.variant.equals("textless", ignoreCase = true) }
internal fun LibraryPost.attachmentCount() = assets.count { !it.isImage }
internal fun LibraryPost.imageCount() = assets.count { it.isImage }

internal enum class SmartFilter(val label: String) {
    ALL("전체 보관함"),
    FAVORITES("★ 즐겨찾기"),
    TEXTLESS("Textless 보유작"),
    ATTACHMENTS("PSD · ZIP 포함"),
}

internal enum class SortMode(val label: String) {
    YEAR_DESC("최신 연도순"),
    TITLE_ASC("제목순"),
    ASSETS_DESC("파일 많은 순"),
}

internal fun computeVisiblePosts(
    posts: List<LibraryPost>,
    smartFilter: SmartFilter,
    creator: String?,
    year: String?,
    query: String,
    sortMode: SortMode,
): List<LibraryPost> {
    val bySmart = when (smartFilter) {
        SmartFilter.ALL -> posts
        SmartFilter.FAVORITES -> posts.filter { it.isFavorite }
        SmartFilter.TEXTLESS -> posts.filter { it.hasTextless() }
        SmartFilter.ATTACHMENTS -> posts.filter { it.attachmentCount() > 0 }
    }
    val groups = bySmart.groupingBy { it.artistLabel() }.eachCount()
    val activeArtist = creator?.takeIf { it in groups }
    val byArtist = if (activeArtist == null) bySmart else bySmart.filter { it.artistLabel() == activeArtist }
    val validYears = byArtist.map { it.yearLabel() }.toSet()
    val activeYear = year?.takeIf { it in validYears }
    val term = query.trim()

    val filtered = byArtist.filter { p ->
        (activeYear == null || p.yearLabel() == activeYear) &&
            (term.isEmpty() ||
                p.title.contains(term, true) ||
                p.creator.contains(term, true) ||
                p.sourceUrl.contains(term, true) ||
                p.assets.any { it.name.contains(term, true) })
    }

    return when (sortMode) {
        SortMode.YEAR_DESC -> filtered.sortedWith(
            compareByDescending<LibraryPost> { it.year ?: 0 }
                .thenByDescending { it.title }
                .thenBy { it.id },
        )
        SortMode.TITLE_ASC -> filtered.sortedWith(
            compareBy<LibraryPost> { it.titleLabel().lowercase() }
                .thenByDescending { it.year ?: 0 },
        )
        SortMode.ASSETS_DESC -> filtered.sortedWith(
            compareByDescending<LibraryPost> { it.assets.size }
                .thenByDescending { it.year ?: 0 }
                .thenBy { it.titleLabel().lowercase() },
        )
    }
}

private val AvatarPalette = listOf(
    Color(0xFFFF5C7C),
    Color(0xFF6366F1),
    Color(0xFF10B981),
    Color(0xFFF59E0B),
    Color(0xFF06B6D4),
    Color(0xFFEC4899),
    Color(0xFF8B5CF6),
)

private fun artistColor(name: String): Color = AvatarPalette[abs(name.hashCode()) % AvatarPalette.size]

@Composable
internal fun Shelf(
    posts: List<LibraryPost>,
    visiblePosts: List<LibraryPost>,
    query: String,
    onQuery: (String) -> Unit,
    smartFilter: SmartFilter,
    onSmartFilter: (SmartFilter) -> Unit,
    creator: String?,
    onCreator: (String?) -> Unit,
    year: String?,
    onYear: (String?) -> Unit,
    sortMode: SortMode,
    onSortMode: (SortMode) -> Unit,
    largeGrid: Boolean,
    onToggleGrid: () -> Unit,
    onPost: (String) -> Unit,
    onBrowse: () -> Unit,
    onLinkFolder: () -> Unit,
    onMergeSeries: (targetId: String, sourceIds: Set<String>, seriesTitle: String) -> Unit = { _, _, _ -> },
    onToggleFavorite: (String) -> Unit = {},
    onDeletePost: (LibraryPost) -> Unit = {},
    onExportBackup: (List<LibraryPost>) -> Unit = {},
) {
    val colors = LocalGalleryColors.current
    val favCount = remember(posts) { posts.count { it.isFavorite } }
    val textlessCount = remember(posts) { posts.count { it.hasTextless() } }
    val attachPostCount = remember(posts) { posts.count { it.attachmentCount() > 0 } }
    val totalImages = remember(posts) { posts.sumOf { it.imageCount() } }
    val totalAttachments = remember(posts) { posts.sumOf { it.attachmentCount() } }

    var groupByArtist by remember { mutableStateOf(true) }
    var mergeMode by remember { mutableStateOf(false) }
    var mergeSelected by remember { mutableStateOf<List<String>>(emptyList()) }
    var showMergeDialog by remember { mutableStateOf(false) }
    var postPendingDelete by remember { mutableStateOf<LibraryPost?>(null) }

    val bySmart = remember(posts, smartFilter) {
        when (smartFilter) {
            SmartFilter.ALL -> posts
            SmartFilter.FAVORITES -> posts.filter { it.isFavorite }
            SmartFilter.TEXTLESS -> posts.filter { it.hasTextless() }
            SmartFilter.ATTACHMENTS -> posts.filter { it.attachmentCount() > 0 }
        }
    }
    val groups = remember(bySmart) { bySmart.groupingBy { it.artistLabel() }.eachCount().toSortedMap() }
    val activeArtist = creator?.takeIf { it in groups }
    val byArtist = remember(bySmart, activeArtist) {
        if (activeArtist == null) bySmart else bySmart.filter { it.artistLabel() == activeArtist }
    }
    val years = remember(byArtist) {
        byArtist.map { it.yearLabel() }.distinct().sortedByDescending { it.toIntOrNull() ?: 0 }
    }
    val selectedYear = year?.takeIf { it in years }
    var sortMenuOpen by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxSize()) {
        // Left Sidebar
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(232.dp)
                .background(colors.sidebar)
                .padding(14.dp),
        ) {
            Text(
                "라이브러리",
                style = MaterialTheme.typography.labelMedium,
                color = colors.muted,
                modifier = Modifier.padding(start = 10.dp, top = 4.dp, bottom = 8.dp),
            )
            SmartNavItem(
                icon = Icons.Rounded.Collections,
                label = SmartFilter.ALL.label,
                count = posts.size,
                selected = smartFilter == SmartFilter.ALL && activeArtist == null,
                tint = colors.accent,
                onClick = { onSmartFilter(SmartFilter.ALL); onCreator(null) },
            )
            Spacer(Modifier.height(3.dp))
            SmartNavItem(
                icon = Icons.Rounded.Star,
                label = SmartFilter.FAVORITES.label,
                count = favCount,
                selected = smartFilter == SmartFilter.FAVORITES && activeArtist == null,
                tint = Color(0xFFF59E0B),
                onClick = { onSmartFilter(SmartFilter.FAVORITES); onCreator(null) },
            )
            Spacer(Modifier.height(3.dp))
            SmartNavItem(
                icon = Icons.Rounded.AutoAwesome,
                label = SmartFilter.TEXTLESS.label,
                count = textlessCount,
                selected = smartFilter == SmartFilter.TEXTLESS && activeArtist == null,
                tint = colors.emerald,
                onClick = { onSmartFilter(SmartFilter.TEXTLESS); onCreator(null) },
            )
            Spacer(Modifier.height(3.dp))
            SmartNavItem(
                icon = Icons.Rounded.FolderZip,
                label = SmartFilter.ATTACHMENTS.label,
                count = attachPostCount,
                selected = smartFilter == SmartFilter.ATTACHMENTS && activeArtist == null,
                tint = colors.secondary,
                onClick = { onSmartFilter(SmartFilter.ATTACHMENTS); onCreator(null) },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = colors.border)
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("작가", style = MaterialTheme.typography.labelMedium, color = colors.muted, modifier = Modifier.weight(1f))
                Text("${groups.size}", style = MaterialTheme.typography.labelSmall, color = colors.muted)
            }
            Spacer(Modifier.height(4.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                items(groups.keys.toList(), key = { it }) { name ->
                    ArtistSideItem(
                        name = name,
                        count = groups.getValue(name),
                        selected = activeArtist == name,
                        onClick = { onCreator(if (activeArtist == name) null else name) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            // Bottom library stats summary
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("보관함 요약", style = MaterialTheme.typography.labelMedium, color = colors.muted)
                Text(
                    "작품 ${posts.size}개 · 이미지 ${totalImages}장",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.ink,
                )
                Text(
                    "Textless ${textlessCount}작 · 첨부 ${totalAttachments}개",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
                )
            }
        }

        // Sidebar vertical hairline divider
        Box(Modifier.fillMaxHeight().width(1.dp).background(colors.border))

        // Main Gallery Area
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(colors.bg)
                .padding(horizontal = 24.dp, vertical = 18.dp),
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (activeArtist != null) {
                            OutlinedButton(
                                onClick = { onCreator(null) },
                                shape = RoundedCornerShape(9.dp),
                                border = BorderStroke(1.dp, colors.border),
                                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp),
                            ) {
                                Icon(Icons.Rounded.ChevronLeft, null, Modifier.size(16.dp))
                                Text("모든 작가", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Text(
                            activeArtist ?: smartFilter.label,
                            style = MaterialTheme.typography.headlineSmall,
                            color = colors.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(colors.accentSoft)
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                        ) {
                            Text(
                                "${visiblePosts.size}개",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.accent,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    if (activeArtist != null && smartFilter != SmartFilter.ALL) {
                        Text(
                            "필터: ${smartFilter.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.muted,
                        )
                    }
                }

                // 1차 작가별 / 전체 작품 토글 (작가가 2명 이상일 때)
                if (activeArtist == null && groups.size > 1) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.surface)
                            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        ShelfFilter("작가별", groupByArtist) { groupByArtist = true; mergeMode = false; mergeSelected = emptyList() }
                        ShelfFilter("전체", !groupByArtist) { groupByArtist = false }
                    }
                }

                // 시리즈 합치기 토글 (게시글 목록 뷰일 때)
                val showingArtistFolders = activeArtist == null && groupByArtist && query.isBlank() && groups.size > 1
                if (!showingArtistFolders && visiblePosts.size >= 2) {
                    OutlinedButton(
                        onClick = {
                            if (mergeMode && mergeSelected.size >= 2) {
                                showMergeDialog = true
                            } else {
                                mergeMode = !mergeMode
                                mergeSelected = emptyList()
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, if (mergeMode) colors.accent else colors.border),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (mergeMode) colors.accentSoft else Color.Transparent,
                        ),
                        contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.Rounded.MergeType, null, Modifier.size(16.dp), tint = if (mergeMode) colors.accent else colors.muted)
                        Spacer(Modifier.width(5.dp))
                        Text(
                            when {
                                !mergeMode -> "시리즈 합치기"
                                mergeSelected.size >= 2 -> "${mergeSelected.size}개 합치기"
                                else -> "취소"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (mergeMode) colors.accent else colors.ink,
                        )
                    }
                    if (mergeMode && mergeSelected.isNotEmpty()) {
                        TextButton(onClick = { mergeMode = false; mergeSelected = emptyList() }) { Text("취소") }
                    }
                }

                // Compact Pill Search Bar
                Row(
                    modifier = Modifier
                        .width(246.dp)
                        .height(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                        .padding(horizontal = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.Search, null, tint = colors.muted, modifier = Modifier.size(16.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                "작품 · 작가 · 파일 검색…",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = onQuery,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.ink),
                            cursorBrush = SolidColor(colors.accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (query.isNotEmpty()) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "검색어 지우기",
                            tint = colors.muted,
                            modifier = Modifier.size(15.dp).clickable { onQuery("") },
                        )
                    }
                }

                // Sort Dropdown
                Box {
                    OutlinedButton(
                        onClick = { sortMenuOpen = true },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, colors.border),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.muted)
                        Spacer(Modifier.width(6.dp))
                        Text(sortMode.label, style = MaterialTheme.typography.labelLarge, color = colors.ink)
                    }
                    DropdownMenu(
                        expanded = sortMenuOpen,
                        onDismissRequest = { sortMenuOpen = false },
                        containerColor = colors.surface,
                    ) {
                        SortMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        mode.label,
                                        color = if (mode == sortMode) colors.accent else colors.ink,
                                        fontWeight = if (mode == sortMode) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                },
                                onClick = { onSortMode(mode); sortMenuOpen = false },
                            )
                        }
                    }
                }

                if (visiblePosts.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { onExportBackup(visiblePosts) },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, colors.border),
                        contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.Rounded.DriveFolderUpload, null, Modifier.size(16.dp), tint = colors.emerald)
                        Spacer(Modifier.width(5.dp))
                        Text("폴더 백업", style = MaterialTheme.typography.labelMedium, color = colors.ink)
                    }
                }

                // Grid Size Toggle
                OutlinedIconButton(
                    onClick = onToggleGrid,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, colors.border),
                ) {
                    Icon(
                        if (largeGrid) Icons.Rounded.GridView else Icons.Rounded.ViewAgenda,
                        contentDescription = if (largeGrid) "보통 카드 크기" else "큰 카드 크기",
                        tint = colors.muted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // Year Filter Chips
            if (years.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 12.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ShelfFilter("전체 연도", selectedYear == null) { onYear(null) }
                    years.forEach { y -> ShelfFilter(y, selectedYear == y) { onYear(y) } }
                }
            }

            Spacer(Modifier.height(14.dp))

            val showingArtistFolders = activeArtist == null && groupByArtist && query.isBlank() && groups.size > 1
            if (visiblePosts.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(colors.surface)
                            .border(1.dp, colors.border, RoundedCornerShape(18.dp))
                            .padding(horizontal = 36.dp, vertical = 32.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(colors.accentSoft),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.PhotoLibrary, null, tint = colors.accent, modifier = Modifier.size(28.dp))
                        }
                        Text(
                            if (posts.isEmpty()) "보관한 작품이 없습니다" else "조건에 맞는 작품이 없습니다",
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.ink,
                        )
                        Text(
                            if (posts.isEmpty()) "기존 작가 폴더를 연결하거나 파일을 창으로 드래그해서 추가하세요."
                            else "다른 검색어, 작가, 또는 연도 필터를 선택해 보세요.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.muted,
                        )
                        if (posts.isEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                                Button(onClick = onLinkFolder) { Text("작가 폴더 연결") }
                                OutlinedButton(onClick = onBrowse) { Text("FANBOX에서 작품 열기") }
                            }
                        }
                    }
                }
            } else if (showingArtistFolders) {
                val artistBuckets = remember(visiblePosts) {
                    visiblePosts.groupBy { it.artistLabel() }.entries.sortedBy { it.key.lowercase() }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(if (largeGrid) 260.dp else 210.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 28.dp),
                ) {
                    items(artistBuckets, key = { it.key }) { (artistName, artistPosts) ->
                        ArtistFolderCard(
                            artistName = artistName,
                            artistPosts = artistPosts,
                            onClick = { onCreator(artistName) },
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(if (largeGrid) 248.dp else 192.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 28.dp),
                ) {
                    items(visiblePosts, key = { it.id }) { post ->
                        val isSelectedForMerge = post.id in mergeSelected
                        CoverCard(
                            post = post,
                            mergeMode = mergeMode,
                            selectedForMerge = isSelectedForMerge,
                            onToggleFavorite = { onToggleFavorite(post.id) },
                            onRequestDelete = { postPendingDelete = post },
                            onClick = {
                                if (mergeMode) {
                                    mergeSelected = if (isSelectedForMerge) mergeSelected - post.id else mergeSelected + post.id
                                } else {
                                    onPost(post.id)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (postPendingDelete != null) {
        val target = postPendingDelete!!
        AlertDialog(
            onDismissRequest = { postPendingDelete = null },
            containerColor = colors.surface,
            titleContentColor = colors.ink,
            textContentColor = colors.ink,
            title = { Text("작품 통째 삭제") },
            text = {
                Text(
                    "‘${target.titleLabel()}’ (${target.artistLabel()}) 작품을 내 보관함에서 삭제할까요?\n(앱 내부 사본만 정리되며, 외부 작가 폴더의 원본 파일은 절대 삭제되지 않습니다.)",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        postPendingDelete = null
                        onDeletePost(target)
                    },
                ) { Text("작품 삭제") }
            },
            dismissButton = { TextButton(onClick = { postPendingDelete = null }) { Text("취소") } },
        )
    }

    if (showMergeDialog && mergeSelected.size >= 2) {
        val targetId = mergeSelected.first()
        val firstPost = visiblePosts.firstOrNull { it.id == targetId }
        var seriesTitle by remember(mergeSelected) { mutableStateOf(firstPost?.titleLabel().orEmpty()) }
        AlertDialog(
            onDismissRequest = { showMergeDialog = false },
            containerColor = colors.surface,
            titleContentColor = colors.ink,
            textContentColor = colors.ink,
            title = { Text("시리즈로 합치기 (${mergeSelected.size}개 작품)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "선택한 ${mergeSelected.size}개 작품의 모든 이미지와 첨부파일을 하나의 시리즈 폴더로 통합합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                    )
                    OutlinedTextField(
                        value = seriesTitle,
                        onValueChange = { seriesTitle = it },
                        label = { Text("통합 시리즈 제목") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = seriesTitle.isNotBlank(),
                    onClick = {
                        val sources = mergeSelected.drop(1).toSet()
                        showMergeDialog = false
                        mergeMode = false
                        mergeSelected = emptyList()
                        onMergeSeries(targetId, sources, seriesTitle.trim())
                    },
                ) { Text("하나로 합치기") }
            },
            dismissButton = { TextButton(onClick = { showMergeDialog = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun SmartNavItem(
    icon: ImageVector,
    label: String,
    count: Int,
    selected: Boolean,
    tint: Color,
    onClick: () -> Unit,
) {
    val colors = LocalGalleryColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.surfaceElevated else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) colors.borderStrong else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) tint else colors.muted, modifier = Modifier.size(18.dp))
        Text(
            label,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) colors.ink else colors.muted,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (selected) tint.copy(alpha = 0.2f) else colors.surface)
                .padding(horizontal = 7.dp, vertical = 2.dp),
        ) {
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) tint else colors.muted,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ArtistSideItem(name: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalGalleryColors.current
    val avatarColor = remember(name) { artistColor(name) }
    val initial = remember(name) { name.trim().firstOrNull()?.uppercase() ?: "?" }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.accentSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(avatarColor.copy(alpha = if (selected) 0.95f else 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                initial,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) Color.White else avatarColor,
            )
        }
        Text(
            name,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) colors.accent else colors.ink,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) colors.accent else colors.muted,
        )
    }
}

@Composable
internal fun ShelfFilter(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalGalleryColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) colors.accent else colors.surface)
            .border(
                width = 1.dp,
                color = if (selected) colors.accent else colors.border,
                shape = RoundedCornerShape(20.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 6.dp),
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
private fun ArtistFolderCard(
    artistName: String,
    artistPosts: List<LibraryPost>,
    onClick: () -> Unit,
) {
    val colors = LocalGalleryColors.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val coverAsset = remember(artistPosts) {
        artistPosts.firstNotNullOfOrNull { p -> p.coverImage() }
    }
    val totalImgs = remember(artistPosts) { artistPosts.sumOf { it.imageCount() } }
    val avatarColor = remember(artistName) { artistColor(artistName) }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(if (hovered) 1.5.dp else 1.dp, if (hovered) colors.accent else colors.border, RoundedCornerShape(14.dp))
            .hoverable(interaction)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1.1f).background(colors.surfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            if (coverAsset != null) {
                val preview = loadImage(File(coverAsset.path), coverAsset.sha256, 480)
                if (preview.bitmap != null) {
                    Image(preview.bitmap, artistName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                }
            } else {
                Icon(Icons.Rounded.Folder, null, tint = avatarColor, modifier = Modifier.size(44.dp))
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xCC0E1016))
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            ) {
                Text("작품 ${artistPosts.size}개 · 이미지 ${totalImgs}장", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(
                modifier = Modifier.size(28.dp).clip(CircleShape).background(avatarColor.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(artistName.take(1).uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = avatarColor)
            }
            Text(artistName, style = MaterialTheme.typography.titleSmall, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CoverCard(
    post: LibraryPost,
    mergeMode: Boolean = false,
    selectedForMerge: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    onRequestDelete: () -> Unit = {},
    onClick: () -> Unit,
) {
    val colors = LocalGalleryColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val imgCount = remember(post) { post.imageCount() }
    val attachCount = remember(post) { post.attachmentCount() }
    val textless = remember(post) { post.hasTextless() }
    val coverAsset = remember(post) { post.coverImage() }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(
                width = if (selectedForMerge || hovered) 2.dp else 1.dp,
                color = if (selectedForMerge || hovered) colors.accent else colors.border,
                shape = RoundedCornerShape(14.dp),
            )
            .hoverable(interactionSource)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.76f)
                .background(colors.surfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            if (coverAsset == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Rounded.FolderZip, null, tint = colors.muted, modifier = Modifier.size(30.dp))
                    Text("첨부파일 전용", color = colors.muted, style = MaterialTheme.typography.labelMedium)
                }
            } else {
                val preview = loadImage(File(coverAsset.path), coverAsset.sha256, 520)
                if (preview.bitmap != null) {
                    Image(
                        bitmap = preview.bitmap,
                        contentDescription = post.titleLabel(),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                    )
                } else if (preview.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = colors.muted.copy(alpha = 0.5f),
                    )
                } else {
                    Text("미리보기 없음", color = colors.muted, style = MaterialTheme.typography.labelSmall)
                }
            }

            // Top subtle scrim for badges
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.56f), Color.Transparent),
                        ),
                    ),
            )

            // Top overlay badges
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (post.isFavorite || hovered) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color(0xB80E1016))
                                .clickable(onClick = onToggleFavorite)
                                .padding(4.dp),
                        ) {
                            Icon(
                                if (post.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                contentDescription = "즐겨찾기",
                                tint = if (post.isFavorite) Color(0xFFFBBF24) else Color.White,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                    if (textless) {
                        OverlayPill(
                            text = "Textless",
                            bg = Color(0xCC059669),
                            fg = Color.White,
                            icon = Icons.Rounded.AutoAwesome,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (post.lastReadPage > 0 && imgCount > 1) {
                        OverlayPill(
                            text = "이어보기 p.${post.lastReadPage + 1}",
                            bg = Color(0xCC2563EB),
                            fg = Color.White,
                        )
                    }
                    if (imgCount > 0) {
                        OverlayPill(
                            text = "$imgCount",
                            bg = Color(0xB80E1016),
                            fg = Color.White,
                            icon = Icons.Rounded.Image,
                        )
                    }
                    if (hovered && !mergeMode) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color(0xCC7F1D1D))
                                .clickable(onClick = onRequestDelete)
                                .padding(4.dp),
                        ) {
                            Icon(
                                Icons.Rounded.DeleteOutline,
                                contentDescription = "작품 삭제",
                                tint = Color.White,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }

            // Bottom attachment badge & merge checkbox
            if (attachCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp),
                ) {
                    OverlayPill(
                        text = "PSD·첨부 $attachCount",
                        bg = Color(0xCC4F46E5),
                        fg = Color.White,
                    )
                }
            }
            if (mergeMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(if (selectedForMerge) colors.accent else Color(0xAA0E1016))
                        .padding(4.dp),
                ) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = if (selectedForMerge) Color.White else Color.White.copy(alpha = 0.45f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                post.titleLabel(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
                color = colors.ink,
                lineHeight = 18.sp,
            )
            Text(
                "${post.artistLabel()} · ${post.yearLabel()}",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = colors.muted,
            )
        }
    }
}

@Composable
private fun OverlayPill(
    text: String,
    bg: Color,
    fg: Color,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(11.dp))
        }
        Text(text, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = fg)
    }
}
