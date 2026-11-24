package app.fanboxlibrary.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.prefs.Preferences
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

private val prefs: Preferences = Preferences.userRoot().node("app/fanboxlibrary/desktop")

fun main() = application {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    var darkTheme by remember { mutableStateOf(prefs.getBoolean("darkTheme", true)) }
    Window(
        onCloseRequest = ::exitApplication,
        title = "FANBOX 보관함",
        state = rememberWindowState(width = 1280.dp, height = 840.dp),
    ) {
        LaunchedEffect(window) { window.minimumSize = Dimension(820, 600) }
        FanboxLibraryTheme(darkTheme = darkTheme) {
            LibraryApp(
                owner = window,
                darkTheme = darkTheme,
                onToggleTheme = {
                    darkTheme = !darkTheme
                    prefs.putBoolean("darkTheme", darkTheme)
                },
            )
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
            addChoosableFileFilter(
                FileNameExtensionFilter(
                    "이미지·작업 파일",
                    "jpg", "jpeg", "png", "webp", "gif", "bmp", "tif", "tiff", "zip", "psd", "psb", "clip", "pdf",
                ),
            )
        }
    }
    if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return emptyList()
    return if (directory) listOf(chooser.selectedFile) else chooser.selectedFiles.toList()
}

private class Notice(val message: String, val warnings: List<String> = emptyList())
private enum class DesktopSection { FANBOX, LIBRARY }

@Composable
internal fun LibraryApp(
    owner: AwtWindow?,
    root: File = libraryDirectory(),
    darkTheme: Boolean = true,
    onToggleTheme: () -> Unit = {},
) {
    val colors = LocalGalleryColors.current
    val scope = rememberCoroutineScope()
    var activeSection by remember {
        mutableStateOf(
            if (owner == null) DesktopSection.LIBRARY
            else if (prefs.get("lastSection", "FANBOX") == "LIBRARY") DesktopSection.LIBRARY
            else DesktopSection.FANBOX,
        )
    }
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
    var activeZipAsset by remember { mutableStateOf<LibraryAsset?>(null) }
    var activePsdTarget by remember { mutableStateOf<Pair<File, String>?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateCheckResult?>(null) }
    var lastWebUrl by remember { mutableStateOf("") }
    var showWarnings by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    var isDraggingOver by remember { mutableStateOf(false) }

    // Shelf filters & sort state
    var smartFilter by remember { mutableStateOf(SmartFilter.ALL) }
    var query by remember { mutableStateOf("") }
    var creator by remember { mutableStateOf<String?>(null) }
    var year by remember { mutableStateOf<String?>(null) }
    var sortMode by remember { mutableStateOf(SortMode.YEAR_DESC) }
    var largeGrid by remember { mutableStateOf(false) }

    val visiblePosts = remember(posts, smartFilter, creator, year, query, sortMode) {
        computeVisiblePosts(posts, smartFilter, creator, year, query, sortMode)
    }

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
        } finally {
            busy = ""
        }
    }

    // Quiet background update check when running in a real desktop window (skipped in headless UI tests)
    LaunchedEffect(owner) {
        if (owner != null) {
            val checked = withContext(Dispatchers.IO) { GitHubUpdater.checkLatest(APP_VERSION) }
            updateResult = checked
        }
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
            } finally {
                busy = ""
            }
        }
    }

    // Window Drag & Drop support for folders and files
    DisposableEffect(owner, store) {
        val win = owner
        if (win == null) return@DisposableEffect onDispose {}
        val previousTarget = win.dropTarget
        val target = DropTarget(
            win,
            DnDConstants.ACTION_COPY,
            object : DropTargetAdapter() {
                override fun dragEnter(dtde: DropTargetDragEvent) {
                    if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        dtde.acceptDrag(DnDConstants.ACTION_COPY)
                        SwingUtilities.invokeLater { isDraggingOver = true }
                    } else {
                        dtde.rejectDrag()
                    }
                }

                override fun dragExit(dte: DropTargetEvent) {
                    SwingUtilities.invokeLater { isDraggingOver = false }
                }

                override fun drop(dtde: DropTargetDropEvent) {
                    SwingUtilities.invokeLater { isDraggingOver = false }
                    if (!dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        dtde.rejectDrop()
                        return
                    }
                    dtde.acceptDrop(DnDConstants.ACTION_COPY)
                    val dropped = runCatching {
                        @Suppress("UNCHECKED_CAST")
                        (dtde.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>).orEmpty()
                    }.getOrDefault(emptyList())
                    dtde.dropComplete(dropped.isNotEmpty())
                    if (dropped.isNotEmpty()) {
                        SwingUtilities.invokeLater {
                            if (dropped.size == 1 && dropped.first().isDirectory) {
                                update("드래그한 작가 폴더를 색인하는 중…") { it.indexFolder(dropped.first()) }
                            } else {
                                val files = dropped.filter { it.isFile }
                                if (files.isNotEmpty()) importFiles = files
                            }
                        }
                    }
                }
            },
            true,
        )
        win.dropTarget = target
        onDispose { win.dropTarget = previousTarget }
    }

    fun launchExternal(completed: String? = null, action: () -> Unit) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
                if (completed != null) notice = Notice(completed)
            } catch (e: Exception) {
                notice = Notice("열 수 없습니다. " + (e.localizedMessage ?: e.javaClass.simpleName))
            }
        }
    }

    fun openUrl(url: String) {
        launchExternal {
            require(FanboxLinks.isOfficialUrl(url)) { "FANBOX 공식 주소를 입력하세요." }
            Desktop.getDesktop().browse(URI(url))
        }
    }

    fun linkArtistFolder() {
        chooseFiles(owner, true).firstOrNull()?.let { folder ->
            update("작가 폴더를 색인하는 중…") { it.indexFolder(folder) }
        }
    }

    val currentPost = posts.firstOrNull { it.id == selected }
    val ready = store != null && busy.isEmpty()
    val hasUpdate = updateResult is UpdateCheckResult.UpdateAvailable

    Box(Modifier.fillMaxSize().background(colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            // Top App Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.sidebar)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Brand Emblem
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(colors.accent, colors.secondary),
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("F", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                    }
                    Column {
                        Text("FANBOX 보관함", style = MaterialTheme.typography.titleMedium, color = colors.ink)
                        Text("v$APP_VERSION", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                    }
                }

                Spacer(Modifier.width(10.dp))

                // Top Section Switcher [FANBOX 피드 | 내 보관함]
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val isFanbox = activeSection == DesktopSection.FANBOX
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (isFanbox) colors.accent else Color.Transparent)
                            .clickable {
                                activeSection = DesktopSection.FANBOX
                                if (owner != null) prefs.put("lastSection", "FANBOX")
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            "FANBOX 피드",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isFanbox) Color.White else colors.muted,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (!isFanbox) colors.accent else Color.Transparent)
                            .clickable {
                                activeSection = DesktopSection.LIBRARY
                                if (owner != null) prefs.put("lastSection", "LIBRARY")
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            "내 보관함 (${posts.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (!isFanbox) Color.White else colors.muted,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                // GitHub Update Check Button
                OutlinedButton(
                    onClick = { showUpdateDialog = true },
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, if (hasUpdate) colors.accent else colors.border),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (hasUpdate) colors.accentSoft else Color.Transparent,
                    ),
                    contentPadding = PaddingValues(horizontal = 11.dp, vertical = 7.dp),
                ) {
                    Box {
                        Icon(
                            Icons.Rounded.SystemUpdateAlt,
                            contentDescription = "GitHub 업데이트",
                            tint = if (hasUpdate) colors.accent else colors.muted,
                            modifier = Modifier.size(16.dp),
                        )
                        if (hasUpdate) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .align(Alignment.TopEnd)
                                    .offset(x = 3.dp, y = (-2).dp)
                                    .clip(CircleShape)
                                    .background(colors.accent),
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (hasUpdate) "업데이트 있음" else "업데이트",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (hasUpdate) colors.accent else colors.ink,
                    )
                }

                // Theme Toggle
                IconButton(
                    onClick = onToggleTheme,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(10.dp)),
                ) {
                    Icon(
                        if (darkTheme) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                        contentDescription = if (darkTheme) "라이트 모드" else "다크 모드",
                        tint = colors.muted,
                        modifier = Modifier.size(18.dp),
                    )
                }

                TextButton(
                    onClick = { browse = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.ink),
                ) {
                    Icon(Icons.Rounded.Language, null, Modifier.size(16.dp), tint = colors.muted)
                    Spacer(Modifier.width(5.dp))
                    Text("공식 사이트 열기")
                }

                TextButton(
                    enabled = ready,
                    onClick = { deviceShare = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.ink),
                ) {
                    Icon(Icons.Rounded.Devices, null, Modifier.size(16.dp), tint = colors.muted)
                    Spacer(Modifier.width(5.dp))
                    Text("기기 연결")
                }

                OutlinedButton(
                    enabled = ready,
                    onClick = ::linkArtistFolder,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, colors.border),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.ink),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Icon(Icons.Rounded.CreateNewFolder, null, Modifier.size(16.dp), tint = colors.muted)
                    Spacer(Modifier.width(5.dp))
                    Text("작가 폴더 연결")
                }

                Button(
                    enabled = ready,
                    onClick = { importFiles = chooseFiles(owner, false) },
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 13.dp, vertical = 7.dp),
                ) {
                    Icon(Icons.Rounded.AddPhotoAlternate, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("파일 가져오기")
                }
            }

            HorizontalDivider(color = colors.border)

            if (busy.isNotEmpty()) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = colors.accent)
                Text(
                    busy,
                    Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
            }

            notice?.let { n ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.noticeBg)
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(n.message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.ink)
                    if (n.warnings.isNotEmpty()) {
                        TextButton(onClick = { showWarnings = true }) { Text("상세 ${n.warnings.size}건") }
                    }
                    TextButton(onClick = { notice = null }) { Text("닫기") }
                }
            }

            when {
                startupError.isNotEmpty() -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(startupError, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { attempt++ }) { Text("다시 열기") }
                }

                activeSection == DesktopSection.FANBOX -> DesktopFanboxHub(
                    store = store,
                    libraryPosts = posts,
                    onLibraryUpdated = { msg ->
                        notice = Notice(msg)
                        store?.let { activeStore ->
                            scope.launch {
                                posts = withContext(Dispatchers.IO) { activeStore.posts() }
                            }
                        }
                    },
                    onOpenArchivedPost = { localId ->
                        selected = localId
                        activeSection = DesktopSection.LIBRARY
                        if (owner != null) prefs.put("lastSection", "LIBRARY")
                    },
                )

                currentPost == null -> Shelf(
                    posts = posts,
                    visiblePosts = visiblePosts,
                    query = query,
                    onQuery = { query = it },
                    smartFilter = smartFilter,
                    onSmartFilter = { smartFilter = it },
                    creator = creator,
                    onCreator = { creator = it; year = null },
                    year = year,
                    onYear = { year = it },
                    sortMode = sortMode,
                    onSortMode = { sortMode = it },
                    largeGrid = largeGrid,
                    onToggleGrid = { largeGrid = !largeGrid },
                    onPost = { selected = it },
                    onBrowse = { browse = true },
                    onLinkFolder = ::linkArtistFolder,
                    onMergeSeries = { targetId, sourceIds, seriesTitle ->
                        update("선택한 작품을 시리즈로 합치는 중…") {
                            it.mergePosts(targetId, sourceIds, newTitle = seriesTitle)
                        }
                    },
                )

                else -> key(currentPost.id) {
                    PostReader(
                        post = currentPost,
                        visiblePosts = visiblePosts,
                        onSelectPost = { selected = it },
                        enabled = ready,
                        back = { selected = null },
                        edit = { editing = currentPost },
                        browse = ::openUrl,
                        open = { asset -> launchExternal { openLibraryFile(asset) } },
                        export = { asset ->
                            val picker = JFileChooser().apply { selectedFile = File(displayName(asset)) }
                            if (picker.showSaveDialog(owner) == JFileChooser.APPROVE_OPTION) {
                                val dest = picker.selectedFile
                                launchExternal("파일을 저장했습니다. " + dest.name) { exportLibraryFile(asset, dest) }
                            }
                        },
                        openZip = { asset -> activeZipAsset = asset },
                        openPsd = { asset -> activePsdTarget = File(asset.path) to asset.name },
                        onDeleteAsset = { asset ->
                            val loaded = store ?: return@PostReader
                            scope.launch {
                                try {
                                    val (removed, latest) = withContext(Dispatchers.IO) {
                                        loaded.deleteAssets(currentPost.id, setOf(asset.id)) to loaded.posts()
                                    }
                                    posts = latest
                                    if (latest.none { it.id == currentPost.id }) selected = null
                                    if (removed > 0) notice = Notice("보관함에서 ‘${asset.name}’ 항목을 삭제했습니다.")
                                } catch (e: Exception) {
                                    notice = Notice("삭제 실패: " + (e.localizedMessage ?: e.javaClass.simpleName))
                                }
                            }
                        },
                    )
                }
            }
        }

        // Drag & Drop Overlay
        if (isDraggingOver) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.62f))
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(colors.surface)
                        .border(2.dp, colors.accent, RoundedCornerShape(22.dp))
                        .padding(horizontal = 48.dp, vertical = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Rounded.AddPhotoAlternate, null, tint = colors.accent, modifier = Modifier.size(44.dp))
                    Text("여기에 폴더나 파일을 놓아 추가하세요", style = MaterialTheme.typography.titleLarge, color = colors.ink)
                    Text(
                        "작가 폴더 1개를 놓으면 자동 색인되고, 파일을 놓으면 가져오기 창이 열립니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                    )
                }
            }
        }
    }

    if (showWarnings) NoticeDialog(notice?.warnings.orEmpty()) { showWarnings = false }
    activeZipAsset?.let { zipAsset ->
        val post = currentPost
        val activeStore = store
        if (post != null && activeStore != null) {
            DesktopZipExplorerDialog(
                darkTheme = darkTheme,
                post = post,
                zipAsset = zipAsset,
                store = activeStore,
                onLibraryChanged = { msg ->
                    notice = Notice(msg)
                    scope.launch {
                        posts = withContext(Dispatchers.IO) { activeStore.posts() }
                    }
                },
                onOpenPsd = { file, name ->
                    activePsdTarget = file to name
                },
                onDismiss = { activeZipAsset = null },
            )
        }
    }
    activePsdTarget?.let { (file, name) ->
        val post = currentPost
        val activeStore = store
        if (post != null && activeStore != null) {
            DesktopPsdStudioDialog(
                darkTheme = darkTheme,
                post = post,
                psdFile = file,
                displayName = name,
                store = activeStore,
                onLibraryChanged = { msg ->
                    notice = Notice(msg)
                    scope.launch {
                        posts = withContext(Dispatchers.IO) { activeStore.posts() }
                    }
                },
                onDismiss = { activePsdTarget = null },
            )
        }
    }
    if (showUpdateDialog) {
        UpdateDialog(
            currentVersion = APP_VERSION,
            initialResult = updateResult,
            onResultUpdated = { updateResult = it },
            onDismiss = { showUpdateDialog = false },
        )
    }
    if (deviceShare) store?.let { activeStore ->
        DeviceShareDialog(darkTheme = darkTheme, store = activeStore) {
            deviceShare = false
            attempt++
        }
    }
    if (browse) BrowserDialog(lastWebUrl, { browse = false }) { url ->
        lastWebUrl = url; browse = false; openUrl(url)
    }
    if (importFiles.isNotEmpty()) {
        PostEditDialog(
            darkTheme = darkTheme,
            posts = posts,
            initial = currentPost,
            source = null,
            inputs = importFiles,
            previousUrl = lastWebUrl,
            close = { importFiles = emptyList() },
        ) { draft, _, files ->
            importFiles = emptyList()
            update("파일을 보관함에 복사하는 중…") { db ->
                db.importManaged(draft, files.map { f -> ImportSource(f.name, f.path) { f.inputStream() } })
            }
        }
    }
    editing?.let { from ->
        PostEditDialog(
            darkTheme = darkTheme,
            posts = posts,
            initial = null,
            source = from,
            inputs = emptyList(),
            previousUrl = "",
            close = { editing = null },
        ) { draft, ids, _ ->
            editing = null
            update("선택한 파일을 묶는 중…") { it.regroup(from.id, ids, draft) }
        }
    }
}

private fun displayName(asset: LibraryAsset): String =
    asset.name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("""[<>:"/\\|?*\p{Cntrl}]"""), "_")
        .trimEnd('.', ' ').takeIf { it.isNotBlank() } ?: "attachment"

internal fun canOpen(asset: LibraryAsset): Boolean =
    displayName(asset).substringAfterLast('.', "").lowercase() in setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "tif", "tiff", "avif", "heic",
        "zip", "pdf", "psd", "psb", "clip", "mp4", "webm", "mp3", "wav", "flac",
    )

internal fun exportLibraryFile(asset: LibraryAsset, destination: File) {
    val source = File(asset.path)
    require(source.isFile) { "원본 파일의 위치를 확인하세요." }
    require(!destination.exists()) { "같은 이름의 파일이 있습니다. 다른 이름을 선택하세요." }
    val tmp = Files.createTempFile(destination.absoluteFile.parentFile.toPath(), ".fanbox-", ".part")
    try {
        source.inputStream().use { input -> Files.newOutputStream(tmp).use { output -> input.copyTo(output) } }
        Files.move(tmp, destination.toPath())
    } finally {
        Files.deleteIfExists(tmp)
    }
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
