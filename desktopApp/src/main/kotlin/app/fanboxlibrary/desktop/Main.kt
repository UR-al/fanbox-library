package app.fanboxlibrary.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.fanboxlibrary.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Window as AwtWindow
import java.io.File
import java.net.URI
import java.nio.file.Files
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

internal val paper = Color(0xFFF8F7F5)
internal val ink = Color(0xFF27242D)
internal val muted = Color(0xFF625E6A)
internal val violet = Color(0xFF633F80)
internal val line = Color(0xFFDCD8DD)

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "FANBOX 보관함",
        state = rememberWindowState(width = 1150.dp, height = 810.dp)
    ) {
        LaunchedEffect(window) { window.minimumSize = Dimension(720, 560) }
        MaterialTheme(colors = lightColors(primary = violet, secondary = violet, background = paper, onBackground = ink)) {
            LibraryApp(window)
        }
    }
}

private fun libraryDirectory(): File {
    val base = System.getenv("LOCALAPPDATA")?.let(::File) ?: File(System.getProperty("user.home"), ".local/share")
    return File(base, "FanboxLibrary")
}

private fun chooseFiles(owner: AwtWindow?, directory: Boolean): List<File> {
    val chooser = JFileChooser().apply {
        dialogTitle = if (directory) "연결할 작가 폴더 선택" else "작품에 넣을 파일 선택"
        fileSelectionMode = if (directory) JFileChooser.DIRECTORIES_ONLY else JFileChooser.FILES_ONLY
        isMultiSelectionEnabled = !directory
        if (!directory) {
            addChoosableFileFilter(FileNameExtensionFilter("이미지·작업 파일", "jpg", "jpeg", "png", "webp",
                "gif", "bmp", "tif", "tiff", "zip", "psd", "clip", "pdf"))
        }
    }
    if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return emptyList()
    return if (directory) listOf(chooser.selectedFile) else chooser.selectedFiles.toList()
}

private class Notice(val message: String, val warnings: List<String> = emptyList())

@Composable
internal fun LibraryApp(owner: AwtWindow?, root: File = libraryDirectory()) {
    val scope = rememberCoroutineScope()
    var store by remember { mutableStateOf<LibraryStore?>(null) }
    var posts by remember { mutableStateOf<List<LibraryPost>>(emptyList()) }
    var busy by remember { mutableStateOf("보관함을 여는 중…") }
    var startupError by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<Notice?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var importFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var editing by remember { mutableStateOf<LibraryPost?>(null) }
    var browse by remember { mutableStateOf(false) }
    var deviceShare by remember { mutableStateOf(false) }
    var lastWebUrl by remember { mutableStateOf("") }
    var showWarnings by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var creator by remember { mutableStateOf<String?>(null) }
    var year by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(attempt) {
        busy = "보관함을 여는 중…"
        startupError = ""
        try {
            val loaded = withContext(Dispatchers.IO) {
                val opened = LibraryStore(root)
                opened to opened.posts()
            }
            store = loaded.first
            posts = loaded.second
        } catch (e: Exception) {
            startupError = "보관함을 열 수 없습니다. " + (e.localizedMessage ?: e.javaClass.simpleName)
        } finally { busy = "" }
    }

    fun update(label: String, task: (LibraryStore) -> ImportReport) {
        val loaded = store ?: return
        if (busy.isNotEmpty()) return
        busy = label
        scope.launch {
            try {
                val (result, latest) = withContext(Dispatchers.IO) { task(loaded) to loaded.posts() }
                posts = latest
                if (selected != null && latest.none { it.id == selected }) selected = null
                notice = Notice("추가 ${result.added}개 · 기존 파일 연결 ${result.reused}개 · 건너뜀 ${result.skipped}개", result.warnings)
            } catch (e: Exception) {
                notice = Notice("작업을 완료할 수 없습니다. " + (e.localizedMessage ?: e.javaClass.simpleName))
            } finally { busy = "" }
        }
    }

    fun launchExternal(completed: String? = null, action: () -> Unit) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
                if (completed != null) notice = Notice(completed)
            }
            catch (e: Exception) { notice = Notice("열 수 없습니다. " + (e.localizedMessage ?: e.javaClass.simpleName)) }
        }
    }
    fun openUrl(url: String) {
        launchExternal {
            require(FanboxLinks.isOfficialUrl(url)) { "FANBOX 공식 주소를 입력하세요." }
            Desktop.getDesktop().browse(URI(url))
        }
    }
    val currentPost = posts.firstOrNull { it.id == selected }
    val ready = store != null && busy.isEmpty()
    Column(Modifier.fillMaxSize().background(paper)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("FANBOX 보관함", style = MaterialTheme.typography.h6, color = ink)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { browse = true }) { Text("공식 사이트 열기") }
            TextButton(enabled = ready, onClick = { deviceShare = true }) { Text("기기 연결") }
            OutlinedButton(enabled = ready, onClick = {
                chooseFiles(owner, true).firstOrNull()?.let { folder ->
                    update("작가 폴더를 색인하는 중…") { it.indexFolder(folder) }
                }
            }) { Text("작가 폴더 연결") }
            Button(enabled = ready, onClick = { importFiles = chooseFiles(owner, false) }) { Text("파일 가져오기") }
        }
        Divider(color = line)
        if (busy.isNotEmpty()) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = violet)
            Text(busy, Modifier.padding(horizontal = 22.dp, vertical = 4.dp), style = MaterialTheme.typography.caption)
        }
        notice?.let { n ->
            Row(Modifier.fillMaxWidth().background(Color(0xFFEDE6F1)).padding(horizontal = 20.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(n.message, Modifier.weight(1f), style = MaterialTheme.typography.body2)
                if (n.warnings.isNotEmpty()) TextButton(onClick = { showWarnings = true }) { Text("상세 ${n.warnings.size}건") }
                TextButton(onClick = { notice = null }) { Text("닫기") }
            }
        }
        when {
            startupError.isNotEmpty() -> Column(Modifier.padding(22.dp)) {
                Text(startupError, color = MaterialTheme.colors.error)
                TextButton(onClick = { attempt++ }) { Text("다시 열기") }
            }
            currentPost == null -> Shelf(posts, query, { query = it }, creator, { creator = it; year = null },
                year, { year = it }, { selected = it }, { browse = true })
            else -> key(currentPost.id) {
                PostReader(currentPost, ready, { selected = null }, { editing = currentPost }, ::openUrl, { asset ->
                    launchExternal { openLibraryFile(asset) }
                }, { asset ->
                    val picker = JFileChooser().apply { selectedFile = File(displayName(asset)) }
                    if (picker.showSaveDialog(owner) == JFileChooser.APPROVE_OPTION) {
                        val dest = picker.selectedFile
                        launchExternal("파일을 저장했습니다. " + dest.name) { exportLibraryFile(asset, dest) }
                    }
                })
            }
        }
    }
    if (showWarnings) NoticeDialog(notice?.warnings.orEmpty()) { showWarnings = false }
    if (deviceShare) store?.let { activeStore ->
        DeviceShareDialog(activeStore) {
            deviceShare = false
            attempt++
        }
    }
    if (browse) BrowserDialog(lastWebUrl, { browse = false }) { url ->
        lastWebUrl = url; browse = false; openUrl(url)
    }
    if (importFiles.isNotEmpty()) {
        PostEditDialog(posts, currentPost, null, importFiles, lastWebUrl, { importFiles = emptyList() }) { draft, _, files ->
            importFiles = emptyList()
            update("파일을 보관함에 복사하는 중…") { db ->
                db.importManaged(draft, files.map { f -> ImportSource(f.name, f.path) { f.inputStream() } })
            }
        }
    }
    editing?.let { from ->
        PostEditDialog(posts, null, from, emptyList(), "", { editing = null }) { draft, ids, _ ->
            editing = null
            update("선택한 파일을 묶는 중…") { it.regroup(from.id, ids, draft) }
        }
    }
}

private fun displayName(asset: LibraryAsset): String =
    asset.name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("""[<>:"/\\|?*\p{Cntrl}]"""), "_")
        .trimEnd('.', ' ').takeIf { it.isNotBlank() } ?: "attachment"

internal fun canOpen(asset: LibraryAsset): Boolean =
    displayName(asset).substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp",
        "tif", "tiff", "avif", "heic", "zip", "pdf", "psd", "psb", "clip", "mp4", "webm", "mp3", "wav", "flac")

internal fun exportLibraryFile(asset: LibraryAsset, destination: File) {
    val source = File(asset.path)
    require(source.isFile) { "원본 파일의 위치를 확인하세요." }
    require(!destination.exists()) { "같은 이름의 파일이 있습니다. 다른 이름을 선택하세요." }
    val tmp = Files.createTempFile(destination.absoluteFile.parentFile.toPath(), ".fanbox-", ".part")
    try {
        source.inputStream().use { input -> Files.newOutputStream(tmp).use { output -> input.copyTo(output) } }
        Files.move(tmp, destination.toPath())
    } finally { Files.deleteIfExists(tmp) }
}

private fun openLibraryFile(asset: LibraryAsset) {
    require(canOpen(asset)) { "이 파일은 ‘다른 이름으로 저장’으로 내보내세요." }
    val source = File(asset.path)
    require(source.isFile) { "원본 파일의 위치를 확인하세요." }
    if (source.extension.equals(File(asset.name).extension, ignoreCase = true)) {
        Desktop.getDesktop().open(source)
        return
    }
    // A managed blob has no extension. External editors get a working copy so saving cannot change the indexed content.
    val directory = Files.createTempDirectory("fanbox-open-").toFile()
    directory.deleteOnExit()
    val copy = File(directory, "view-" + displayName(asset))
    copy.deleteOnExit()
    exportLibraryFile(asset, copy)
    Desktop.getDesktop().open(copy)
}
