package app.fanboxlibrary.android

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.fanboxlibrary.shared.FanboxLinks
import app.fanboxlibrary.shared.ImportReport
import app.fanboxlibrary.shared.ImportSource
import app.fanboxlibrary.shared.LibraryAsset
import app.fanboxlibrary.shared.LibraryPost
import app.fanboxlibrary.shared.LibraryStore
import app.fanboxlibrary.shared.PostDraft
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val model by viewModels<ArchiveModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) receive(intent)
        setContent { ArchiveApp(model) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    private fun receive(intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val postUrl = Regex("""https://[^\s<>"']+""").findAll(text)
            .map { it.value.trimEnd(')', ']', '.', ',') }
            .firstOrNull { FanboxLinks.postId(it) != null }.orEmpty()
        val streams = buildList {
            if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                addAll(IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty())
            } else {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::add)
            }
            val clip = intent.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(::add)
            }
        }.filter { it.scheme == ContentResolver.SCHEME_CONTENT && it.authority != "${packageName}.files" }
        if (postUrl.isEmpty() && streams.isEmpty()) {
            model.notice = "공식 FANBOX 게시물 링크 또는 저장할 파일을 공유해 주세요."
            return
        }
        if (model.busy || model.readingSelection || model.moveIds.isNotEmpty()) {
            model.notice = "가져오기가 끝나면 다시 공유해 주세요."
            return
        }
        if (!model.importing) model.beginImport(PostDraft(sourceUrl = postUrl))
        else if (postUrl.isNotEmpty()) {
            val current = model.draft
            if (current.sourceUrl.isNotBlank() && FanboxLinks.postId(current.sourceUrl) != FanboxLinks.postId(postUrl)) {
                model.notice = "현재 작품 가져오기를 마친 뒤 다른 게시물을 공유해 주세요."
                return
            }
            if (current.existingId == null) model.draft = current.copy(sourceUrl = postUrl)
        }
        model.addDocuments(streams)
    }
}

internal data class Document(val uri: Uri, val name: String)

internal class ArchiveModel(application: Application) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()
    val store by lazy { LibraryStore(File(context.filesDir, "library")) }
    var posts by mutableStateOf<List<LibraryPost>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var readingSelection by mutableStateOf(false)
        private set
    var notice by mutableStateOf("")
    var importing by mutableStateOf(false)
        private set
    var draft by mutableStateOf(PostDraft())
    var documents by mutableStateOf<List<Document>>(emptyList())
        private set
    var moveFrom by mutableStateOf("")
        private set
    var moveIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var savingPostId by mutableStateOf<String?>(null)
        private set
    var savingProgress by mutableStateOf("")
        private set
    var lastImportTick by mutableStateOf(0)
        private set

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            try { posts = withContext(Dispatchers.IO) { store.posts() } }
            catch (e: Exception) { reportError(e) }
        }
    }

    fun beginImport(initial: PostDraft = PostDraft(), sourceId: String = "", selected: Set<String> = emptySet()) {
        if (busy || readingSelection) return
        draft = initial
        documents = emptyList()
        moveFrom = sourceId
        moveIds = selected
        importing = true
    }

    fun cancelImport() {
        if (busy || readingSelection) return
        importing = false
        documents = emptyList()
        moveIds = emptySet()
    }

    fun addDocuments(uris: List<Uri>) {
        if (busy || readingSelection || uris.isEmpty()) return
        readingSelection = true
        viewModelScope.launch {
            try {
                val added = withContext(Dispatchers.IO) {
                    uris.distinct().filter { it.scheme == ContentResolver.SCHEME_CONTENT }.map { uri ->
                        val resolver = context.contentResolver
                        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
                        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
                        Document(uri, name)
                    }
                }
                documents = (documents + added).distinctBy { it.uri }
            } catch (e: Exception) { reportError(e) }
            finally { readingSelection = false }
        }
    }

    fun shift(index: Int, delta: Int) {
        val next = index + delta
        if (busy || next !in documents.indices) return
        documents = documents.toMutableList().apply { add(next, removeAt(index)) }
    }

    fun removeAt(index: Int) {
        if (!busy) documents = documents.filterIndexed { i, _ -> i != index }
    }

    fun commit() {
        if (busy || readingSelection || (documents.isEmpty() && moveIds.isEmpty())) return
        if (draft.sourceUrl.isNotBlank() && FanboxLinks.postId(draft.sourceUrl.trim()) == null) {
            notice = "공식 FANBOX 게시물 주소를 입력해 주세요."
            return
        }
        val target = draft.copy(sourceUrl = draft.sourceUrl.trim())
        val selection = documents
        val oldPost = moveFrom
        val oldIds = moveIds
        busy = true
        viewModelScope.launch {
            try {
                val (result, updated) = withContext(Dispatchers.IO) {
                    val report = if (oldIds.isNotEmpty()) {
                        store.regroup(oldPost, oldIds, target)
                    } else {
                        val sources = selection.map { doc ->
                            ImportSource(doc.name) {
                                context.contentResolver.openInputStream(doc.uri)
                                    ?: throw IOException("파일을 열 수 없습니다: ${doc.name}")
                            }
                        }
                        store.importManaged(target, sources)
                    }
                    report to store.posts()
                }
                posts = updated
                notice = result.summary()
                if (result.warnings.isEmpty()) {
                    importing = false
                    documents = emptyList()
                    moveIds = emptySet()
                    lastImportTick++
                }
            } catch (e: Exception) { reportError(e) }
            finally { busy = false }
        }
    }

    fun saveFanboxPost(detail: FanboxPostDetail, includeFiles: Boolean = true) {
        if (busy || savingPostId != null) return
        val targets = buildList<Pair<String, String>> {
            detail.images.forEachIndexed { idx, img ->
                val prefix = "%02d_".format(idx + 1)
                add("$prefix${img.fileName}" to img.originalUrl)
            }
            if (includeFiles) {
                detail.files.forEach { f -> add(f.fileName to f.url) }
            }
        }
        if (targets.isEmpty()) {
            notice = "이 게시글에 저장할 이미지나 파일이 없습니다."
            return
        }
        savingPostId = detail.summary.id
        savingProgress = "저장 준비 중 (0/${targets.size})…"
        viewModelScope.launch {
            var added = 0
            var reused = 0
            var skipped = 0
            var firstWarning = ""
            try {
                val draft = PostDraft(
                    creator = detail.summary.creatorName,
                    title = detail.summary.title,
                    year = detail.summary.year,
                    sourceUrl = detail.summary.postUrl,
                )
                for ((idx, pair) in targets.withIndex()) {
                    val (fileName, url) = pair
                    savingProgress = "보관함에 저장 중 (${idx + 1}/${targets.size}) · $fileName"
                    val report = withContext(Dispatchers.IO) {
                        val source = ImportSource(fileName, "원본/$fileName") {
                            FanboxClient.openDownloadStream(context, url)
                        }
                        store.importManaged(draft, listOf(source))
                    }
                    added += report.added
                    reused += report.reused
                    skipped += report.skipped
                    if (firstWarning.isEmpty() && report.warnings.isNotEmpty()) {
                        firstWarning = report.warnings.first()
                    }
                }
                posts = withContext(Dispatchers.IO) { store.posts() }
                notice = buildString {
                    append("내 보관함에 저장 완료 · 추가 ${added}개")
                    if (reused > 0) append(" · 기존 ${reused}개")
                    if (skipped > 0) append(" · 건너뜀 ${skipped}개")
                    if (firstWarning.isNotEmpty()) append("\n$firstWarning")
                }
            } catch (e: Exception) {
                reportError(e)
            } finally {
                savingPostId = null
                savingProgress = ""
            }
        }
    }

    fun saveWebDownload(url: String, fileName: String, pageUrl: String, pageTitle: String) {
        if (busy || savingPostId != null) {
            notice = "다른 저장 작업이 진행 중입니다."
            return
        }
        val cleanName = fileName.replace(Regex("""[\\/\p{Cntrl}]"""), "_").ifBlank { "fanbox_file" }
        val canonicalPostUrl = pageUrl.takeIf { FanboxLinks.postId(it) != null }.orEmpty()
        savingPostId = "web"
        savingProgress = "다운로드 저장 중 · $cleanName"
        viewModelScope.launch {
            try {
                val (report, updated) = withContext(Dispatchers.IO) {
                    val draft = PostDraft(
                        title = pageTitle.removeSuffix("｜pixivFANBOX").removeSuffix(" - pixivFANBOX").trim(),
                        sourceUrl = canonicalPostUrl,
                    )
                    val source = ImportSource(cleanName, "원본/$cleanName") {
                        FanboxClient.openDownloadStream(context, url)
                    }
                    store.importManaged(draft, listOf(source)) to store.posts()
                }
                posts = updated
                notice = "보관함에 저장했습니다 · $cleanName (${report.summary()})"
            } catch (e: Exception) {
                reportError(e)
            } finally {
                savingPostId = null
                savingProgress = ""
            }
        }
    }

    private fun reportError(e: Exception) {
        if (e is CancellationException) throw e
        notice = e.localizedMessage ?: "보관함을 열 수 없습니다."
    }

    private fun ImportReport.summary(): String = buildString {
        append("추가 ${added}개 · 기존 파일 ${reused}개")
        if (skipped > 0) append(" · 건너뜀 ${skipped}개")
        if (warnings.isNotEmpty()) append("\n").append(warnings.first())
    }
}

internal const val FANBOX_HOME = "https://www.fanbox.cc/"

internal fun openOfficial(context: Context, url: String, error: (String) -> Unit) {
    if (!FanboxLinks.isOfficialUrl(url)) {
        error("공식 FANBOX 주소를 확인해 주세요.")
        return
    }
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url))
    } catch (_: ActivityNotFoundException) { error("이 링크를 열 브라우저를 찾을 수 없습니다.") }
}

internal fun openAttachment(context: Context, asset: LibraryAsset, error: (String) -> Unit) {
    try {
        val file = File(asset.path)
        if (!file.isFile) throw IOException("파일이 보관함에 없습니다.")
        val name = asset.name.replace(Regex("""[\\/\p{Cntrl}]"""), "_").take(180).ifBlank { "attachment" }
        val extension = name.substringAfterLast('.', "").lowercase()
        val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file, name)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        view.clipData = ClipData.newRawUri(name, uri)
        context.startActivity(view)
    } catch (_: ActivityNotFoundException) { error("이 형식의 파일을 열 앱이 없습니다.") }
    catch (e: Exception) { error(e.localizedMessage ?: "첨부를 열 수 없습니다.") }
}
