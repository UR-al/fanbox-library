package app.fanboxlibrary.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import app.fanboxlibrary.shared.LibraryPost
import app.fanboxlibrary.shared.LibraryStore
import app.fanboxlibrary.transfer.PairHost
import app.fanboxlibrary.transfer.localAddresses
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.concurrent.TimeUnit

@Composable
internal fun DeviceShareDialog(store: LibraryStore, onDismiss: () -> Unit) {
    var posts by remember { mutableStateOf(emptyList<LibraryPost>()) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val addresses = remember { runCatching { localAddresses() }.getOrDefault(emptyList()) }
    var address by remember { mutableStateOf(addresses.firstOrNull()) }
    var chooseAddress by remember { mutableStateOf(false) }
    var receive by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("") }
    var server by remember { mutableStateOf<PairHost?>(null) }
    var starting by remember { mutableStateOf(false) }
    DisposableEffect(server) {
        val current = server
        onDispose { current?.close() }
    }
    LaunchedEffect(store) {
        runCatching { withContext(Dispatchers.IO) { store.posts() } }
            .onSuccess { posts = it }.onFailure { state = it.localizedMessage ?: "보관함 열기 실패" }
    }
    DialogWindow(onCloseRequest = onDismiss, title = "기기 연결",
        state = rememberDialogState(width = 610.dp, height = 640.dp)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("휴대폰과 보관함 연결", style = MaterialTheme.typography.h5)
                Text("같은 Wi-Fi 또는 Tailscale에서 사용할 주소와 공유할 작품을 선택하세요.")
                val host = server
                if (host == null) {
                    Box {
                        OutlinedButton(onClick = { chooseAddress = true }, enabled = addresses.isNotEmpty()) {
                            Text(address?.hostAddress ?: "연결 가능한 네트워크 없음")
                        }
                        DropdownMenu(chooseAddress, { chooseAddress = false }) {
                            addresses.forEach { ip -> DropdownMenuItem(onClick = { address = ip; chooseAddress = false }) { Text(ip.hostAddress) } }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(receive, { receive = it })
                        Text("휴대폰에서 선택한 작품을 이 PC로 받기")
                    }
                    OutlinedTextField(filter, { filter = it }, label = { Text("공유할 작가 또는 작품 검색") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    val visible = posts.filter { it.title.contains(filter, true) || it.creator.contains(filter, true) }
                    Row {
                        TextButton(onClick = { selected = selected + visible.map { it.id } }) { Text("검색 결과 전체 선택") }
                        TextButton(onClick = { selected = emptySet() }) { Text("선택 해제") }
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        items(visible, key = { it.id }) { post ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(post.id in selected, { checked ->
                                    selected = if (checked) selected + post.id else selected - post.id
                                })
                                Text("${post.creator} · ${post.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    Button(enabled = address != null && (receive || selected.isNotEmpty()) && !starting, onClick = { starting = true }) {
                        Text(if (starting) "연결 준비 중…" else "연결 코드 만들기 · 공유 ${selected.size}개")
                    }
                    if (starting) LaunchedEffect(Unit) {
                        try {
                            val identity = withContext(Dispatchers.Default) {
                                HeldCertificate.Builder().commonName("Folio device transfer")
                                    .duration(2, TimeUnit.HOURS).build()
                            }
                            val context = HandshakeCertificates.Builder().heldCertificate(identity).build().sslContext()
                            server = PairHost(requireNotNull(address), context, identity.certificate, store,
                                posts.filter { it.id in selected }, receive) { event ->
                                java.awt.EventQueue.invokeLater { state = event }
                            }
                            state = "휴대폰 연결 대기 중"
                        } catch (ex: Exception) { state = ex.localizedMessage ?: "연결 실패" }
                        finally { starting = false }
                    }
                } else {
                    Text("휴대폰의 ‘기기 연결’에 아래 코드를 붙여 넣으세요. 이 창을 닫으면 연결이 종료됩니다.")
                    OutlinedTextField(host.pairing.encode(), {}, readOnly = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text("30분 동안 유효한 연결 코드") })
                    Row {
                        Button(onClick = {
                            runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(host.pairing.encode()), null) }
                                .onSuccess { state = "연결 코드를 복사했습니다." }.onFailure { state = "위 코드를 직접 선택해 복사하세요." }
                        }) { Text("코드 복사") }
                        TextButton(onClick = { server = null; state = "" }) { Text("연결 종료") }
                    }
                }
                if (state.isNotBlank()) Text(state)
            }
        }
    }
}
