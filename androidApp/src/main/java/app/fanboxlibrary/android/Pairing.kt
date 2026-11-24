package app.fanboxlibrary.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.fanboxlibrary.shared.LibraryPost
import app.fanboxlibrary.shared.LibraryStore
import app.fanboxlibrary.transfer.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PairingScreen(store: LibraryStore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var client by remember { mutableStateOf<PairClient?>(null) }
    var remote by remember { mutableStateOf(emptyList<PostSummary>()) }
    var local by remember { mutableStateOf(emptyList<LibraryPost>()) }
    var sending by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var detail by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf(emptyList<FileSummary>()) }
    var subsets by remember { mutableStateOf(emptyMap<String, Set<String>>()) }
    var compared by remember { mutableStateOf(emptyMap<String, PostComparison>()) }
    var search by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    DisposableEffect(client) { val current = client; onDispose { current?.close() } }
    BackHandler { onClose() }
    fun runTask(action: suspend () -> Unit) {
        if (busy) return
        busy = true; message = ""
        scope.launch {
            try { action() }
            catch (e: Exception) { message = e.localizedMessage ?: "연결할 수 없습니다." }
            finally { busy = false }
        }
    }
    fun resetSelection() {
        selected = emptySet(); subsets = emptyMap()
        detail = null; compared = emptyMap(); message = ""
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("기기 연결", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onClose) { Text("닫기") }
        }
        val peer = client
        if (peer == null) {
            Text("PC에서 ‘기기 연결’을 열고 코드를 복사하세요. PC와 같은 Wi-Fi 네트워크에 연결한 뒤 코드를 입력하세요.")
            OutlinedTextField(code, { code = it }, label = { Text("연결 코드") }, modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy && code.isNotBlank(), onClick = { runTask {
                val next = PairClient(PairingAddress.parse(code))
                try {
                    remote = withContext(Dispatchers.IO) { next.available() }
                    local = withContext(Dispatchers.IO) { store.posts() }
                    client = next; code = ""
                } catch (ex: Exception) { next.close(); throw ex }
            } }) { Text("PC 연결") }
        } else {
            val rows = if (sending) local.map { PostSummary(it.id, it.creator, it.title, it.assets.size) } else remote
            Row {
                for ((mode, label) in listOf(false to "폰으로 받기", true to "PC로 보내기")) {
                    FilterChip(selected = mode == sending, onClick = {
                        if (mode != sending) { sending = mode; artist = null; resetSelection() }
                    }, enabled = !busy, label = { Text(label) })
                    Spacer(Modifier.width(6.dp))
                }
            }
            OutlinedTextField(search, { search = it; resetSelection() }, enabled = !busy,
                label = { Text("작가 또는 작품 검색") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            val visible = filterSharedWorks(rows, artist, search)
            var chooseArtist by remember { mutableStateOf(false) }
            Row {
                Box(Modifier.weight(1f)) {
                    OutlinedButton(enabled = !busy, onClick = { chooseArtist = true }) {
                        Text(artist?.ifBlank { "작가 미분류" } ?: "전체 작가", maxLines = 1)
                    }
                    DropdownMenu(expanded = chooseArtist, onDismissRequest = { chooseArtist = false },
                        modifier = Modifier.heightIn(max = 280.dp)) {
                        DropdownMenuItem(text = { Text("전체 작가") }, onClick = {
                            artist = null; chooseArtist = false; resetSelection()
                        })
                        for (name in rows.map { it.creator }.distinct().sorted()) {
                            DropdownMenuItem(text = { Text(name.ifBlank { "작가 미분류" }) }, onClick = {
                                artist = name; chooseArtist = false; resetSelection()
                            })
                        }
                    }
                }
                if (!sending) Button(enabled = !busy && visible.isNotEmpty(), onClick = { runTask {
                    resetSelection()
                    val localIndex = withContext(Dispatchers.IO) { LocalInventory(store.posts()) }
                    val results = linkedMapOf<String, PostComparison>()
                    for ((i, post) in visible.withIndex()) {
                        message = "공유 PC 대조 ${i+1}/${visible.size} · ${post.title}"
                        results[post.id] = withContext(Dispatchers.IO) { localIndex.compare(post.id, peer.inventory(post.id)) }
                    }
                    compared = results
                    subsets = results.mapValues { it.value.missingIds }.filterValues { it.isNotEmpty() }
                    selected = subsets.keys.toSet()
                    detail = null
                    val totals = results.values.groupingBy { it.state }.eachCount()
                    message = "공유 PC 기준 · 새 작품 ${totals[PeerPostState.NEW] ?: 0} · 추가·변경 ${totals[PeerPostState.CHANGED] ?: 0} · 보관 완료 ${totals[PeerPostState.COMPLETE] ?: 0}"
                } }) { Text("누락분 확인") }
            }
            Row {
                TextButton(onClick = {
                    val all = visible.map { it.id }.toSet()
                    selected = selected + all
                    subsets = subsets - all
                }, enabled = !busy) { Text("검색 결과 전체 선택") }
                TextButton(onClick = { selected = emptySet(); subsets = emptyMap() }, enabled = !busy) { Text("선택 해제") }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(visible, key = { it.id }) { row ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(row.id in selected, enabled = !busy, onCheckedChange = { on ->
                            selected = if (on) selected + row.id else selected - row.id
                            val suggested = compared[row.id]?.missingIds.orEmpty()
                            subsets = if (on && suggested.isNotEmpty()) subsets + (row.id to suggested) else subsets - row.id
                        })
                        Column(Modifier.weight(1f)) {
                            Text(row.title)
                            Text(row.creator, style = MaterialTheme.typography.bodySmall)
                            compared[row.id]?.let { result ->
                                val label = when (result.state) {
                                    PeerPostState.NEW -> "새 작품"
                                    PeerPostState.CHANGED -> "추가·변경"
                                    PeerPostState.COMPLETE -> "보관 완료"
                                }
                                Text("$label · 누락 ${result.missingIds.size}개", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        TextButton(enabled = !busy, onClick = {
                            if (detail == row.id) detail = null
                            else runTask {
                                files = if (sending) local.first { it.id == row.id }.assets.map { FileSummary(it.id, it.name, it.variant) }
                                else compared[row.id]?.files?.map { it.remote } ?: withContext(Dispatchers.IO) { peer.files(row.id) }
                                detail = row.id
                            }
                        }) { Text("파일 ${row.count}") }
                    }
                    if (row.id == detail) {
                        val current = subsets[row.id] ?: if (row.id in selected) files.map { it.id }.toSet() else emptySet()
                        val status = remember(compared[row.id]) { compared[row.id]?.files?.associate { it.remote.id to it.state }.orEmpty() }
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 212.dp)) {
                            items(files, key = { it.id }) { file ->
                                Row(Modifier.padding(start = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(file.id in current, enabled = !busy, onCheckedChange = { checked ->
                                        val next = if (checked) current + file.id else current - file.id
                                        subsets = subsets + (row.id to next)
                                        selected = if (next.isEmpty()) selected - row.id else selected + row.id
                                    })
                                    val suffix = when (status[file.id]) {
                                        LocalFileState.PRESENT -> " · 보관됨"
                                        LocalFileState.PRESENT_OTHER_NAME -> " · 다른 이름으로 보관됨"
                                        LocalFileState.LINK_LOCAL_BYTES -> " · 로컬 파일 연결"
                                        LocalFileState.RECEIVE_BYTES -> " · 받을 파일"
                                        null -> ""
                                    }
                                    Text("${file.name} · ${file.variant}$suffix", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
            Button(enabled = !busy && selected.isNotEmpty(), onClick = { runTask {
                val queued = rows.filter { it.id in selected }
                try {
                    for ((i, row) in queued.withIndex()) {
                        message = "${i + 1}/${queued.size} · ${row.title}"
                        val ids = subsets[row.id]
                        withContext(Dispatchers.IO) {
                            if (sending) {
                                val post = local.first { it.id == row.id }
                                peer.push(if (ids == null) post else post.copy(assets = post.assets.filter { it.id in ids }))
                            } else peer.pull(row.id, store, ids)
                        }
                        selected = selected - row.id
                        subsets = subsets - row.id
                        compared[row.id]?.let { prior ->
                            compared = compared + (row.id to prior.received(ids))
                        }
                    }
                } finally {
                    if (!sending) local = withContext(Dispatchers.IO) { store.posts() }
                    detail = null
                }
                message = "${queued.size}개 작품 동기화 완료"
            } }) { Text("선택한 작품 동기화 · ${selected.size}") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (message.isNotEmpty()) Text(message)
    }
}
