package app.fanboxlibrary.android

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fanboxlibrary.shared.FanboxLinks
import app.fanboxlibrary.shared.LibraryPost
import app.fanboxlibrary.shared.PostDraft
import coil.compose.AsyncImage
import java.io.File

private val ArchiveColors = lightColorScheme(
    primary = Color(0xff285442), onPrimary = Color.White, primaryContainer = Color(0xffdcebdc),
    background = Color(0xfffaf8f4), surface = Color(0xfffaf8f4),
    surfaceVariant = Color(0xffe9e7df), onSurface = Color(0xff202a24),
    onSurfaceVariant = Color(0xff505c52)
)

@Composable
internal fun ArchiveApp(model: ArchiveModel) {
    var postId by rememberSaveable { mutableStateOf<String?>(null) }
    var readerId by rememberSaveable { mutableStateOf<String?>(null) }
    var pairing by rememberSaveable { mutableStateOf(false) }
    val post = model.posts.find { it.id == postId }
    val context = LocalContext.current
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
    fun back() {
        when {
            model.importing -> model.cancelImport()
            pairing -> { pairing = false; model.refresh() }
            readerId != null -> readerId = null
            else -> postId = null
        }
    }
    BackHandler(enabled = model.importing || pairing || postId != null) { back() }
    MaterialTheme(colorScheme = ArchiveColors) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            contentWindowInsets = WindowInsets.safeDrawing
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when {
                    model.importing -> ImportView(model, pick = { picker.launch(arrayOf("*/*")) }, back = ::back)
                    pairing -> PairingScreen(model.store) { pairing = false; model.refresh() }
                    post != null && readerId != null -> ReaderView(post, readerId!!, ::back) { model.notice = it }
                    post != null -> PostView(
                        post, back = ::back, read = { readerId = it },
                        add = { model.beginImport(post.asDraft()) },
                        move = { selected -> model.beginImport(sourceId = post.id, selected = selected) },
                        error = { model.notice = it }
                    )
                    else -> LibraryView(
                        model.posts, open = { postId = it },
                        browse = { openOfficial(context, FANBOX_HOME) { model.notice = it } },
                        add = { model.beginImport() }, pair = { pairing = true }
                    )
                }
            }
        }
    }
}

private fun LibraryPost.asDraft() = PostDraft(creator, title, year, sourceUrl, id)
private val LibraryPost.label get() = title.ifBlank { "미분류" }
private val LibraryPost.artist get() = creator.ifBlank { "미분류" }

@Composable
private fun LibraryView(posts: List<LibraryPost>, open: (String) -> Unit, browse: () -> Unit, add: () -> Unit,
                        pair: () -> Unit) {
    var artist by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var showArtists by remember { mutableStateOf(false) }
    val artists = remember(posts) { posts.map { it.artist }.distinct().sorted() }
    val groups = remember(posts, artist, search) {
        posts.filter { (artist.isEmpty() || it.artist == artist) &&
                (search.isBlank() || it.label.contains(search, true) || it.artist.contains(search, true))
        }.sortedWith(compareByDescending<LibraryPost> { it.year ?: 0 }.thenBy { it.label }).groupBy { it.year }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(145.dp), contentPadding = PaddingValues(18.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("작품 보관함", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold)
                    TextButton(onClick = pair) { Text("기기 연결") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = add) { Text("파일 가져오기") }
                    OutlinedButton(onClick = browse) { Text("FANBOX 열기") }
                }
                if (posts.isNotEmpty()) {
                    OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("작품 또는 작가 검색") })
                    Box {
                        OutlinedButton(onClick = { showArtists = true }) { Text(artist.ifEmpty { "모든 작가" } + " ▾") }
                        DropdownMenu(showArtists, { showArtists = false }) {
                            (listOf("") + artists).forEach { name ->
                                DropdownMenuItem(text = { Text(name.ifEmpty { "모든 작가" }) }, onClick = {
                                    artist = name; showArtists = false
                                })
                            }
                        }
                    }
                }
            }
        }
        if (groups.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text(if (posts.isEmpty()) "보관 중인 작품이 없습니다. 내려받은 원본 이미지나 ZIP을 가져와 보세요."
                 else "검색 결과가 없습니다.", Modifier.padding(vertical = 24.dp))
        }
        groups.forEach { (year, group) ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(year?.let { "${it}년" } ?: "연도 미상", style = MaterialTheme.typography.titleLarge)
            }
            items(group, key = { it.id }) { post ->
                Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable { open(post.id) }) {
                    val cover = post.assets.firstOrNull { it.isImage }
                    Box(Modifier.fillMaxWidth().aspectRatio(0.92f)
                        .clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center) {
                        if (cover == null) Text("첨부파일", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else AsyncImage(File(cover.path), post.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Text(post.label, Modifier.padding(top = 6.dp), fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${post.artist} · ${post.assets.size}개", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ImportView(model: ArchiveModel, pick: () -> Unit, back: () -> Unit) {
    val draft = model.draft
    val busy = model.busy || model.readingSelection
    val moving = model.moveIds.isNotEmpty()
    val context = LocalContext.current
    var destinationMenu by remember { mutableStateOf(false) }
    val targets = model.posts.filter { it.id != model.moveFrom }
    val editing = draft.existingId == null && !busy
    val yearOk = draft.year?.let { it in 1000..9999 } ?: true
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            TextButton(onClick = back, enabled = !busy) { Text("‹ 보관함") }
            Text(if (moving) "다른 작품에 묶기" else "작품 가져오기",
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        if (targets.isNotEmpty()) item {
            Box {
                OutlinedButton(onClick = { destinationMenu = true }, enabled = !busy) {
                    Text(if (draft.existingId == null) "대상 작품 선택 ▾" else "${draft.title.ifBlank { "미분류" }} ▾")
                }
                DropdownMenu(destinationMenu, { destinationMenu = false }) {
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
            OutlinedTextField(draft.sourceUrl, { model.draft = draft.copy(sourceUrl = it) }, Modifier.fillMaxWidth(),
                enabled = editing, singleLine = true, label = { Text("공식 게시물 주소 (선택)") })
            TextButton(onClick = {
                openOfficial(context, draft.sourceUrl.trim().ifBlank { FANBOX_HOME }) { model.notice = it }
            }, enabled = !busy) { Text(if (draft.sourceUrl.isBlank()) "FANBOX에서 원본 받기 ↗" else "공식 게시물 열기 ↗") }
        }
        item {
            OutlinedTextField(draft.title, { model.draft = draft.copy(title = it) }, Modifier.fillMaxWidth(),
                enabled = editing, singleLine = true, label = { Text("작품 제목") })
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(draft.creator, { model.draft = draft.copy(creator = it) }, Modifier.weight(1f),
                    enabled = editing, singleLine = true, label = { Text("작가") })
                OutlinedTextField(draft.year?.toString().orEmpty(), {
                    if (it.length <= 4 && it.all(Char::isDigit)) model.draft = draft.copy(year = it.toIntOrNull())
                }, Modifier.width(100.dp), enabled = editing, singleLine = true,
                    isError = !yearOk, label = { Text("연도") })
            }
        }
        item {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!moving) OutlinedButton(onClick = pick, enabled = !busy) { Text("파일 고르기") }
                Button(onClick = model::commit, enabled = !busy && yearOk && (moving || model.documents.isNotEmpty())) {
                    Text(if (moving) "${model.moveIds.size}개 묶기" else "${model.documents.size}개 가져오기")
                }
            }
        }
        itemsIndexed(model.documents, key = { _, d -> d.uri.toString() }) { index, doc ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}. ${doc.name}", Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { model.shift(index, -1) }, enabled = !busy && index > 0) { Text("위") }
                TextButton(onClick = { model.shift(index, 1) }, enabled = !busy && index + 1 < model.documents.size) { Text("아래") }
                TextButton(onClick = { model.removeAt(index) }, enabled = !busy) { Text("빼기") }
            }
        }
    }
}

@Composable
private fun PostView(post: LibraryPost, back: () -> Unit, read: (String) -> Unit, add: () -> Unit,
                     move: (Set<String>) -> Unit, error: (String) -> Unit) {
    val context = LocalContext.current
    var selecting by remember(post.id) { mutableStateOf(false) }
    var selected by remember(post.id) { mutableStateOf(emptySet<String>()) }
    fun toggle(id: String) { selected = if (id in selected) selected - id else selected + id }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            TextButton(onClick = back) { Text("‹ 보관함") }
            Text(post.label, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(listOfNotNull(post.artist, post.year?.toString(), "${post.assets.size}개").joinToString(" · "))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = add) { Text("파일 추가") }
                if (FanboxLinks.postId(post.sourceUrl) != null)
                    TextButton(onClick = { openOfficial(context, post.sourceUrl, error) }) { Text("원문 ↗") }
                TextButton(onClick = { selecting = !selecting; selected = emptySet() }) {
                    Text(if (selecting) "취소" else "작품에 묶기")
                }
            }
            if (selecting) Button(onClick = { move(selected) }, enabled = selected.isNotEmpty()) {
                Text("선택한 ${selected.size}개 묶기")
            }
        }
        items(post.assets, key = { it.id }) { asset ->
            Row(Modifier.fillMaxWidth().clickable {
                if (selecting) toggle(asset.id)
                else if (asset.isImage) read(asset.id)
                else openAttachment(context, asset, error)
            }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (selecting) Checkbox(asset.id in selected, onCheckedChange = null)
                Box(Modifier.size(76.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center) {
                    if (asset.isImage) AsyncImage(File(asset.path), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Text(asset.name.substringAfterLast('.', "파일").uppercase().take(5))
                }
                Column(Modifier.weight(1f)) {
                    Text(asset.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${asset.variant} · ${android.text.format.Formatter.formatFileSize(context, asset.size)}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
