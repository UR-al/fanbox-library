package app.fanboxlibrary.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fanboxlibrary.shared.LibraryPost
import java.io.File

internal fun LibraryPost.artistLabel() = creator.ifBlank { "작가 미분류" }
internal fun LibraryPost.yearLabel() = year?.toString() ?: "연도 미상"
internal fun LibraryPost.titleLabel() = title.ifBlank { "제목 없는 작품" }

@Composable
internal fun Shelf(
    posts: List<LibraryPost>, query: String, onQuery: (String) -> Unit,
    creator: String?, onCreator: (String?) -> Unit, year: String?, onYear: (String?) -> Unit,
    onPost: (String) -> Unit, onBrowse: () -> Unit
) {
    val groups = remember(posts) { posts.groupingBy { it.artistLabel() }.eachCount().toSortedMap() }
    val artist = creator?.takeIf { it in groups }
    val byArtist = remember(posts, artist) { if (artist == null) posts else posts.filter { it.artistLabel() == artist } }
    val years = remember(byArtist) { byArtist.map { it.yearLabel() }.distinct().sortedByDescending { it.toIntOrNull() ?: 0 } }
    val selectedYear = year?.takeIf { it in years }
    val filtered = remember(byArtist, query, selectedYear) {
        val term = query.trim()
        byArtist.filter { p -> (selectedYear == null || p.yearLabel() == selectedYear) &&
            (term.isEmpty() || p.title.contains(term, true) || p.creator.contains(term, true) ||
                p.sourceUrl.contains(term, true) || p.assets.any { it.name.contains(term, true) }) }
            .sortedWith(compareByDescending<LibraryPost> { it.year ?: 0 }.thenByDescending { it.title }.thenBy { it.id })
    }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxHeight().width(166.dp).background(Color(0xFFF0EEEB)).padding(12.dp)) {
            Text("작가", Modifier.padding(start = 7.dp, top = 8.dp, bottom = 10.dp), style = MaterialTheme.typography.subtitle2, color = muted)
            SideItem("전체 작품", posts.size, artist == null) { onCreator(null) }
            Spacer(Modifier.height(4.dp))
            LazyColumn {
                items(groups.keys.toList(), key = { it }) { name ->
                    SideItem(name, groups.getValue(name), artist == name) { onCreator(name) }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 22.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(artist ?: "전체 작품", style = MaterialTheme.typography.h5, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("작품 ${filtered.size}개", style = MaterialTheme.typography.caption, color = muted)
                }
                OutlinedTextField(query, onQuery, label = { Text("작품·작가·파일 검색") }, singleLine = true, modifier = Modifier.width(210.dp))
            }
            if (years.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ShelfFilter("전체 연도", selectedYear == null) { onYear(null) }
                    years.forEach { y -> ShelfFilter(y, selectedYear == y) { onYear(y) } }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (posts.isEmpty()) "보관한 작품이 없습니다" else "조건에 맞는 작품이 없습니다",
                            style = MaterialTheme.typography.h6, color = ink)
                        Spacer(Modifier.height(8.dp))
                        Text(if (posts.isEmpty()) "기존 작가 폴더를 연결하거나 저장한 파일을 가져오세요." else "다른 검색어나 연도를 선택해 보세요.",
                            style = MaterialTheme.typography.body2, color = muted)
                        if (posts.isEmpty()) TextButton(onClick = onBrowse) { Text("FANBOX에서 작품 열기") }
                    }
                }
            } else {
                LazyVerticalGrid(columns = GridCells.Adaptive(170.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                    items(filtered, key = { it.id }) { post -> Cover(post) { onPost(post.id) } }
                }
            }
        }
    }
}

@Composable
private fun SideItem(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp))
        .background(if (selected) Color(0xFFE1D7EA) else Color.Transparent)
        .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (selected) violet else ink, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
        Text(count.toString(), style = MaterialTheme.typography.caption, color = muted)
    }
}

@Composable
internal fun ShelfFilter(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(label, modifier = Modifier.clip(RoundedCornerShape(20.dp))
        .background(if (selected) Color(0xFFE7DDEE) else Color.Transparent)
        .clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 6.dp),
        style = MaterialTheme.typography.caption, color = if (selected) violet else muted)
}

@Composable
private fun Cover(post: LibraryPost, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.32f).clip(RoundedCornerShape(8.dp)).background(Color(0xFFECE9E6)),
            contentAlignment = Alignment.Center) {
            val item = post.assets.firstOrNull { it.isImage }
            if (item == null) {
                Text("첨부파일", color = muted, style = MaterialTheme.typography.subtitle2)
            } else {
                val preview = loadImage(File(item.path), item.sha256, 420)
                if (preview.bitmap != null) Image(preview.bitmap, post.titleLabel(), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else if (!preview.loading) Text("미리보기 없음", color = muted, style = MaterialTheme.typography.caption)
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(post.titleLabel(), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, color = ink)
        Text("${post.artistLabel()} · ${post.yearLabel()} · 파일 ${post.assets.size}개",
            style = MaterialTheme.typography.caption, maxLines = 1, overflow = TextOverflow.Ellipsis, color = muted)
    }
}
