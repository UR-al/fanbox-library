package app.fanboxlibrary.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import app.fanboxlibrary.shared.*
import java.awt.Dimension
import java.io.File

@Composable
internal fun BrowserDialog(previous: String, close: () -> Unit, go: (String) -> Unit) {
    var url by remember { mutableStateOf(previous.ifBlank { "https://www.fanbox.cc/" }) }
    val valid = FanboxLinks.isOfficialUrl(url.trim())
    AlertDialog(onDismissRequest = close, title = { Text("공식 FANBOX 열기") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("브라우저에서 pixiv에 로그인해 원본 이미지나 첨부를 저장한 뒤 보관함으로 가져오세요.")
            OutlinedTextField(url, { url = it }, singleLine = true, label = { Text("FANBOX 주소") },
                isError = url.isNotBlank() && !valid, modifier = Modifier.fillMaxWidth())
            if (!valid) Text("https로 시작하는 FANBOX 공식 주소를 입력하세요.", color = muted,
                style = MaterialTheme.typography.caption)
        }
    }, confirmButton = { Button(onClick = { go(url.trim()) }, enabled = valid) { Text("브라우저에서 열기") } },
        dismissButton = { TextButton(onClick = close) { Text("취소") } })
}

@Composable
internal fun NoticeDialog(warnings: List<String>, close: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text("가져오기 상세") }, text = {
        LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(warnings) { Text(it, style = MaterialTheme.typography.body2) }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("확인") } })
}

@Composable
internal fun PostEditDialog(
    posts: List<LibraryPost>, initial: LibraryPost?, source: LibraryPost?, inputs: List<File>,
    previousUrl: String, close: () -> Unit, save: (PostDraft, Set<String>, List<File>) -> Unit
) {
    val modeTitle = if (source == null) "파일 가져오기" else "작품으로 묶기"
    val targets = remember(posts, source) { posts.filter { it.id != source?.id } }
    var existing by remember { mutableStateOf(initial != null) }
    var targetId by remember { mutableStateOf(initial?.id) }
    var lookup by remember { mutableStateOf("") }
    var url by remember { mutableStateOf(previousUrl.takeIf { FanboxLinks.postId(it) != null }.orEmpty()) }
    var artist by remember { mutableStateOf(source?.creator.orEmpty()) }
    var title by remember { mutableStateOf("") }
    var year by remember { mutableStateOf(source?.year?.toString().orEmpty()) }
    var selection by remember { mutableStateOf(source?.assets?.map { it.id }?.toSet().orEmpty()) }
    var ordered by remember { mutableStateOf(inputs) }
    val validUrl = url.isBlank() || FanboxLinks.postId(url.trim()) != null
    val validYear = year.isEmpty() || year.toIntOrNull()?.let { it in 1000..9999 } == true
    val hasFiles = if (source == null) ordered.isNotEmpty() else selection.isNotEmpty()
    val canSave = hasFiles && (if (existing) targets.any { it.id == targetId }
        else validUrl && validYear && (title.isNotBlank() || url.isNotBlank() || source == null))

    DialogWindow(onCloseRequest = close, title = modeTitle, state = rememberDialogState(width = 630.dp, height = 700.dp)) {
        LaunchedEffect(window) { window.minimumSize = Dimension(500, 550) }
        Surface(modifier = Modifier.fillMaxSize(), color = paper) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(modeTitle, style = MaterialTheme.typography.h6)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ShelfFilter("새 작품", !existing) { existing = false }
                    if (targets.isNotEmpty()) ShelfFilter("기존 작품에 추가", existing) { existing = true }
                }
                if (existing) {
                    OutlinedTextField(lookup, { lookup = it }, label = { Text("대상 작품 찾기") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    LazyColumn(Modifier.fillMaxWidth().heightIn(min = 50.dp, max = 166.dp)) {
                        items(targets.filter { it.title.contains(lookup, true) || it.creator.contains(lookup, true) }, key = { it.id }) { post ->
                            Row(Modifier.fillMaxWidth().clickable { targetId = post.id }.padding(5.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = targetId == post.id, onClick = null)
                                Spacer(Modifier.width(8.dp))
                                Text("${post.artistLabel()} · ${post.titleLabel()}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                } else {
                    OutlinedTextField(url, { url = it }, singleLine = true, isError = !validUrl,
                        label = { Text("FANBOX 게시물 주소 (선택)") }, modifier = Modifier.fillMaxWidth())
                    if (!validUrl) Text("공식 게시물 주소의 /posts/번호를 확인하세요.", style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.error)
                    OutlinedTextField(title, { title = it }, label = { Text("작품 제목") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(artist, { artist = it }, singleLine = true,
                            label = { Text("작가 (선택)") }, modifier = Modifier.weight(1f))
                        OutlinedTextField(year, { value -> if (value.length <= 4 && value.all(Char::isDigit)) year = value },
                            singleLine = true, label = { Text("연도 (선택)") }, isError = !validYear, modifier = Modifier.width(118.dp))
                    }
                }
                Divider(color = line)
                if (source == null) {
                    Text("가져올 파일 ${ordered.size}개", style = MaterialTheme.typography.subtitle2)
                    LazyColumn(Modifier.weight(1f)) {
                        itemsIndexed(ordered, key = { _, file -> file.absolutePath }) { index, file ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("${index + 1}. ${file.name}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (ordered.size > 1) {
                                    TextButton(enabled = index > 0, onClick = { ordered = reordered(ordered, index, index - 1) }) { Text("위로") }
                                    TextButton(enabled = index < ordered.lastIndex, onClick = { ordered = reordered(ordered, index, index + 1) }) { Text("아래로") }
                                }
                            }
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("옮길 파일 ${selection.size}개", Modifier.weight(1f), style = MaterialTheme.typography.subtitle2)
                        TextButton(onClick = {
                            selection = if (selection.size == source.assets.size) emptySet() else source.assets.map { it.id }.toSet()
                        }) { Text(if (selection.size == source.assets.size) "선택 해제" else "전체 선택") }
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        items(source.assets, key = { it.id }) { asset ->
                            Row(Modifier.fillMaxWidth().clickable {
                                selection = if (asset.id in selection) selection - asset.id else selection + asset.id
                            }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(asset.id in selection, onCheckedChange = null)
                                Spacer(Modifier.width(8.dp))
                                Text(asset.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(asset.variant, style = MaterialTheme.typography.caption, color = muted)
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = close) { Text("취소") }
                    Spacer(Modifier.width(6.dp))
                    Button(enabled = canSave, onClick = {
                        val draft = if (existing) PostDraft(existingId = targetId)
                        else PostDraft(creator = artist.trim(), title = title.trim(), year = year.toIntOrNull(), sourceUrl = url.trim())
                        save(draft, selection, ordered)
                    }) { Text(if (source == null) "가져오기" else "묶기") }
                }
            }
        }
    }
}

private fun <T> reordered(items: List<T>, from: Int, to: Int): List<T> = items.toMutableList().apply {
    add(to, removeAt(from))
}
