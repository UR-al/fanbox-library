package app.fanboxlibrary.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.URI
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.max

internal data class DesktopFanboxCreator(
    val creatorId: String,
    val userId: String = "",
    val name: String,
    val iconUrl: String?,
    val coverUrl: String?,
    val description: String,
    val isSupporting: Boolean = false,
    val isFollowed: Boolean = false,
    val planFee: Int = 0,
)

internal data class DesktopFanboxPlan(
    val id: String,
    val title: String,
    val fee: Int,
    val description: String,
)

internal data class DesktopFanboxImage(
    val id: String,
    val extension: String,
    val width: Int,
    val height: Int,
    val originalUrl: String,
    val thumbnailUrl: String,
) {
    val fileName: String get() = "$id.${extension.ifBlank { "png" }}"
}

internal data class DesktopFanboxFile(
    val id: String,
    val name: String,
    val extension: String,
    val size: Long,
    val url: String,
) {
    val fileName: String
        get() {
            val clean = name.ifBlank { id }
            return if (extension.isNotBlank() && !clean.endsWith(".$extension", ignoreCase = true)) {
                "$clean.$extension"
            } else clean
        }
}

internal data class DesktopFanboxPostSummary(
    val id: String,
    val title: String,
    val creatorId: String,
    val creatorName: String,
    val creatorIconUrl: String?,
    val coverImageUrl: String?,
    val excerpt: String,
    val publishedDatetime: String,
    val feeRequired: Int,
    val isRestricted: Boolean,
    val hasAdultContent: Boolean,
    val likeCount: Int,
    val type: String,
) {
    val year: Int? get() = publishedDatetime.take(4).toIntOrNull()
    val postUrl: String get() = "https://${creatorId.ifBlank { "www" }}.fanbox.cc/posts/$id"
    val displayDate: String get() = publishedDatetime.take(10)
}

internal data class DesktopFanboxPostDetail(
    val summary: DesktopFanboxPostSummary,
    val text: String,
    val images: List<DesktopFanboxImage>,
    val files: List<DesktopFanboxFile>,
    val tags: List<String>,
)

internal data class DesktopFanboxFeedPage(
    val items: List<DesktopFanboxPostSummary>,
    val nextUrl: String?,
)

internal sealed interface DesktopFanboxResult<out T> {
    data class Success<T>(val data: T) : DesktopFanboxResult<T>
    data class AuthRequired(val message: String) : DesktopFanboxResult<Nothing>
    data class Error(val message: String) : DesktopFanboxResult<Nothing>
}

internal object DesktopFanboxSession {
    private val prefs: Preferences = Preferences.userRoot().node("app/fanboxlibrary/desktop/fanbox")
    const val WEB_ORIGIN = "https://www.fanbox.cc"
    const val WEB_REFERER = "https://www.fanbox.cc/"
    const val API_BASE = "https://api.fanbox.cc"
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    @Volatile private var cachedCsrf: String = ""

    fun rawCookie(): String = prefs.get("cookie", "").trim()

    fun hasLogin(): Boolean = rawCookie().contains("FANBOXSESSID=")

    fun saveSessionInput(input: String) {
        val trimmed = input.trim().removeSurrounding("\"").removeSurrounding("'")
        val normalized = when {
            trimmed.isEmpty() -> ""
            trimmed.contains("FANBOXSESSID=") -> trimmed
            else -> "FANBOXSESSID=$trimmed"
        }
        prefs.put("cookie", normalized)
        cachedCsrf = ""
    }

    fun clearSession() {
        prefs.remove("cookie")
        cachedCsrf = ""
    }

    fun csrfToken(): String = cachedCsrf
    fun updateCsrfToken(token: String) {
        if (token.isNotBlank()) cachedCsrf = token
    }
}

internal object DesktopFanboxClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val remoteImageCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean = size > 90
        },
    )

    private val remoteImageThreads = Dispatchers.IO.limitedParallelism(4)

    fun decodeRemoteImage(url: String, maxEdge: Int): ImageBitmap? {
        val cacheKey = "$url@$maxEdge"
        remoteImageCache[cacheKey]?.let { return it }
        if (!isAllowedMediaUrl(url)) return null
        val req = Request.Builder()
            .url(url)
            .header("Referer", DesktopFanboxSession.WEB_REFERER)
            .header("User-Agent", DesktopFanboxSession.USER_AGENT)
            .apply {
                val c = DesktopFanboxSession.rawCookie()
                if (c.isNotBlank()) header("Cookie", c)
            }
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val bytes = resp.body?.bytes() ?: return null
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes))?.use { stream ->
                val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: return null
                try {
                    reader.setInput(stream, true, true)
                    val w = reader.getWidth(0)
                    val h = reader.getHeight(0)
                    val sample = ceil(max(w, h).toDouble() / maxEdge).toInt().coerceAtLeast(1)
                    val param = reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) }
                    val bmp = reader.read(0, param).toComposeImageBitmap()
                    remoteImageCache[cacheKey] = bmp
                    return bmp
                } finally {
                    reader.dispose()
                }
            }
        }
        return null
    }

    fun fetchHomeFeed(nextUrl: String? = null): DesktopFanboxResult<DesktopFanboxFeedPage> {
        val url = nextUrl?.takeIf { isAllowedApiUrl(it) } ?: "${DesktopFanboxSession.API_BASE}/post.listHome?limit=24"
        return requestJson(url, requireAuth = true) { parseFeedPage(it) }
    }

    fun fetchSupportingFeed(nextUrl: String? = null): DesktopFanboxResult<DesktopFanboxFeedPage> {
        val url = nextUrl?.takeIf { isAllowedApiUrl(it) } ?: "${DesktopFanboxSession.API_BASE}/post.listSupporting?limit=24"
        return requestJson(url, requireAuth = true) { parseFeedPage(it) }
    }

    fun fetchCreatorPosts(creatorId: String, nextUrl: String? = null): DesktopFanboxResult<DesktopFanboxFeedPage> {
        val encoded = URLEncoder.encode(creatorId, "UTF-8")
        val url = nextUrl?.takeIf { isAllowedApiUrl(it) }
            ?: "${DesktopFanboxSession.API_BASE}/post.listCreator?creatorId=$encoded&limit=24"
        return requestJson(url, requireAuth = false) { parseFeedPage(it) }
    }

    fun fetchCreatorProfile(creatorId: String): DesktopFanboxResult<DesktopFanboxCreator> {
        val encoded = URLEncoder.encode(creatorId, "UTF-8")
        return requestJson("${DesktopFanboxSession.API_BASE}/creator.get?creatorId=$encoded", requireAuth = false) { root ->
            val obj = root["body"] as? Map<*, *> ?: throw IOException("작가 정보를 찾을 수 없습니다.")
            parseCreator(obj) ?: throw IOException("작가 정보가 비어 있습니다.")
        }
    }

    fun fetchCreatorPlans(creatorId: String): DesktopFanboxResult<List<DesktopFanboxPlan>> {
        val encoded = URLEncoder.encode(creatorId, "UTF-8")
        return requestJson("${DesktopFanboxSession.API_BASE}/plan.listCreator?creatorId=$encoded", requireAuth = false) { root ->
            val arr = when (val b = root["body"]) {
                is List<*> -> b
                is Map<*, *> -> (b["plans"] as? List<*>) ?: (b["items"] as? List<*>) ?: emptyList<Any?>()
                else -> emptyList<Any?>()
            }
            arr.mapNotNull { item ->
                val obj = item as? Map<*, *> ?: return@mapNotNull null
                val id = obj.str("id").ifBlank { return@mapNotNull null }
                DesktopFanboxPlan(
                    id = id,
                    title = obj.str("title").ifBlank { "플랜" },
                    fee = obj.intVal("fee"),
                    description = obj.str("description").trim(),
                )
            }.sortedBy { it.fee }
        }
    }

    fun fetchCreators(): DesktopFanboxResult<List<DesktopFanboxCreator>> {
        val sup = requestJson("${DesktopFanboxSession.API_BASE}/plan.listSupporting", requireAuth = true) { root ->
            val arr = when (val b = root["body"]) {
                is List<*> -> b
                is Map<*, *> -> (b["plans"] as? List<*>) ?: (b["items"] as? List<*>) ?: emptyList<Any?>()
                else -> emptyList<Any?>()
            }
            arr.mapNotNull { item ->
                val obj = item as? Map<*, *> ?: return@mapNotNull null
                val cid = obj.str("creatorId").ifBlank { return@mapNotNull null }
                val user = obj["user"] as? Map<*, *>
                DesktopFanboxCreator(
                    creatorId = cid,
                    userId = user?.str("userId").orEmpty(),
                    name = user?.str("name")?.ifBlank { cid } ?: cid,
                    iconUrl = user?.optStr("iconUrl"),
                    coverUrl = obj.optStr("coverImageUrl"),
                    description = obj.str("title").ifBlank { obj.str("description") },
                    isSupporting = true,
                    isFollowed = true,
                    planFee = obj.intVal("fee"),
                )
            }
        }
        if (sup is DesktopFanboxResult.AuthRequired) return sup

        val fol = requestJson("${DesktopFanboxSession.API_BASE}/creator.listFollowing", requireAuth = true) { root ->
            val arr = when (val b = root["body"]) {
                is List<*> -> b
                is Map<*, *> -> (b["creators"] as? List<*>) ?: (b["items"] as? List<*>) ?: emptyList<Any?>()
                else -> emptyList<Any?>()
            }
            arr.mapNotNull { (it as? Map<*, *>)?.let { m -> parseCreator(m, defaultFollowed = true) } }
        }
        if (sup is DesktopFanboxResult.Error && fol !is DesktopFanboxResult.Success) return sup

        val merged = linkedMapOf<String, DesktopFanboxCreator>()
        (sup as? DesktopFanboxResult.Success)?.data.orEmpty().forEach { merged[it.creatorId] = it }
        (fol as? DesktopFanboxResult.Success)?.data.orEmpty().forEach { merged.putIfAbsent(it.creatorId, it) }
        return DesktopFanboxResult.Success(merged.values.toList())
    }

    fun searchCreators(rawQuery: String): DesktopFanboxResult<List<DesktopFanboxCreator>> {
        val q = rawQuery.trim()
        if (q.isEmpty()) return DesktopFanboxResult.Success(emptyList())
        val out = linkedMapOf<String, DesktopFanboxCreator>()

        extractCreatorIdCandidate(q)?.let { cid ->
            val direct = fetchCreatorProfile(cid)
            if (direct is DesktopFanboxResult.Success) out[direct.data.creatorId] = direct.data
        }

        val encoded = URLEncoder.encode(q, "UTF-8")
        val search = requestJson("${DesktopFanboxSession.API_BASE}/creator.search?q=$encoded&page=1", requireAuth = false) { root ->
            val body = root["body"]
            val list = when (body) {
                is Map<*, *> -> (body["creators"] as? List<*>) ?: (body["items"] as? List<*>) ?: emptyList<Any?>()
                is List<*> -> body
                else -> emptyList<Any?>()
            }
            list.mapNotNull { (it as? Map<*, *>)?.let { m -> parseCreator(m) } }
        }
        when (search) {
            is DesktopFanboxResult.Success -> search.data.forEach { out.putIfAbsent(it.creatorId, it) }
            is DesktopFanboxResult.AuthRequired -> if (out.isEmpty()) return search
            is DesktopFanboxResult.Error -> if (out.isEmpty()) return search
        }
        return DesktopFanboxResult.Success(out.values.toList())
    }

    fun fetchPostDetail(postId: String): DesktopFanboxResult<DesktopFanboxPostDetail> {
        val encoded = URLEncoder.encode(postId, "UTF-8")
        return requestJson("${DesktopFanboxSession.API_BASE}/post.info?postId=$encoded", requireAuth = false) { root ->
            val body = root["body"] as? Map<*, *> ?: throw IOException("게시글 정보를 불러올 수 없습니다.")
            val postObj = (body["post"] as? Map<*, *>) ?: body
            parsePostDetail(postObj)
        }
    }

    fun setFollowCreator(creatorUserId: String, follow: Boolean): DesktopFanboxResult<Boolean> {
        if (!DesktopFanboxSession.hasLogin()) return DesktopFanboxResult.AuthRequired("팔로우하려면 먼저 FANBOX에 로그인하세요.")
        if (creatorUserId.isBlank()) return DesktopFanboxResult.Error("작가 사용자 ID가 없습니다.")
        val csrf = ensureCsrfToken()
            ?: return DesktopFanboxResult.Error("보안 토큰(CSRF)을 가져오지 못했습니다. 로그인 쿠키를 확인해 주세요.")
        val endpoint = if (follow) "creator.follow" else "creator.unfollow"
        val jsonBody = """{"creatorUserId":"${creatorUserId.replace("\"", "")}"}"""
        return requestJson(
            url = "${DesktopFanboxSession.API_BASE}/$endpoint",
            requireAuth = true,
            method = "POST",
            jsonBody = jsonBody,
            csrfToken = csrf,
        ) { follow }
    }

    fun openDownloadStream(url: String): InputStream {
        require(isAllowedMediaUrl(url)) { "허용되지 않은 FANBOX 다운로드 주소입니다." }
        val req = Request.Builder()
            .url(url)
            .header("Referer", DesktopFanboxSession.WEB_REFERER)
            .header("User-Agent", DesktopFanboxSession.USER_AGENT)
            .apply {
                val c = DesktopFanboxSession.rawCookie()
                if (c.isNotBlank()) header("Cookie", c)
            }
            .build()
        val resp = http.newCall(req).execute()
        if (!resp.isSuccessful) {
            resp.close()
            throw IOException("파일 다운로드 실패 (HTTP ${resp.code})")
        }
        return resp.body?.byteStream() ?: run {
            resp.close()
            throw IOException("빈 응답입니다.")
        }
    }

    private fun ensureCsrfToken(): String? {
        DesktopFanboxSession.csrfToken().takeIf { it.isNotBlank() }?.let { return it }
        val req = Request.Builder()
            .url(DesktopFanboxSession.WEB_REFERER)
            .header("User-Agent", DesktopFanboxSession.USER_AGENT)
            .header("Cookie", DesktopFanboxSession.rawCookie())
            .build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                val html = resp.body?.string().orEmpty()
                val m = Regex("""csrfToken&quot;:&quot;([^&]+)&quot;|"csrfToken"\s*:\s*"([^"]+)"""").find(html)
                val token = m?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }.orEmpty()
                if (token.isNotBlank()) {
                    DesktopFanboxSession.updateCsrfToken(token)
                    token
                } else null
            }
        }.getOrNull()
    }

    private fun <T> requestJson(
        url: String,
        requireAuth: Boolean,
        method: String = "GET",
        jsonBody: String? = null,
        csrfToken: String? = null,
        parse: (Map<*, *>) -> T,
    ): DesktopFanboxResult<T> {
        val cookie = DesktopFanboxSession.rawCookie()
        if (requireAuth && !cookie.contains("FANBOXSESSID=")) {
            return DesktopFanboxResult.AuthRequired("FANBOX 로그인이 필요합니다.")
        }
        return try {
            val builder = Request.Builder()
                .url(url)
                .header("Accept", "application/json, text/plain, */*")
                .header("Origin", DesktopFanboxSession.WEB_ORIGIN)
                .header("Referer", DesktopFanboxSession.WEB_REFERER)
                .header("User-Agent", DesktopFanboxSession.USER_AGENT)
            if (cookie.isNotBlank()) builder.header("Cookie", cookie)
            if (!csrfToken.isNullOrBlank()) builder.header("x-csrf-token", csrfToken)
            if (method == "POST") {
                val mediaType = "application/json; charset=utf-8".toMediaType()
                builder.post((jsonBody ?: "{}").toRequestBody(mediaType))
            }
            http.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.code == 401 -> DesktopFanboxResult.AuthRequired("로그인 세션이 만료되었습니다. 다시 로그인해 주세요.")
                    resp.code == 403 -> DesktopFanboxResult.AuthRequired("로그인이 필요하거나 접근 권한이 없습니다. (HTTP 403)")
                    !resp.isSuccessful -> DesktopFanboxResult.Error("FANBOX 응답 오류 (HTTP ${resp.code})")
                    text.isBlank() && method == "POST" -> DesktopFanboxResult.Success(parse(emptyMap<String, Any?>()))
                    else -> {
                        val root = SimpleJson.parse(text) as? Map<*, *>
                            ?: return DesktopFanboxResult.Error("JSON 응답을 해석할 수 없습니다.")
                        if (root.containsKey("error")) {
                            return DesktopFanboxResult.Error(root["error"]?.toString() ?: "FANBOX 오류")
                        }
                        DesktopFanboxResult.Success(parse(root))
                    }
                }
            }
        } catch (e: Exception) {
            DesktopFanboxResult.Error("네트워크 오류: ${e.localizedMessage ?: e.javaClass.simpleName}")
        }
    }

    private fun parseFeedPage(root: Map<*, *>): DesktopFanboxFeedPage {
        val body = root["body"]
        val (list, next) = when (body) {
            is Map<*, *> -> ((body["items"] as? List<*>) ?: (body["posts"] as? List<*>) ?: emptyList<Any?>()) to body.optStr("nextUrl")
            is List<*> -> body to null
            else -> emptyList<Any?>() to null
        }
        val items = list.mapNotNull { (it as? Map<*, *>)?.let(::parsePostSummary) }
        return DesktopFanboxFeedPage(items, next)
    }

    private fun parseCreator(obj: Map<*, *>, defaultFollowed: Boolean = false): DesktopFanboxCreator? {
        val cid = obj.str("creatorId").ifBlank { return null }
        val user = obj["user"] as? Map<*, *>
        return DesktopFanboxCreator(
            creatorId = cid,
            userId = user?.str("userId").orEmpty(),
            name = user?.str("name")?.ifBlank { cid } ?: cid,
            iconUrl = user?.optStr("iconUrl"),
            coverUrl = obj.optStr("coverImageUrl"),
            description = obj.str("description").trim(),
            isSupporting = obj.boolVal("isSupported"),
            isFollowed = obj.boolVal("isFollowed", defaultFollowed),
        )
    }

    private fun parsePostSummary(obj: Map<*, *>): DesktopFanboxPostSummary? {
        val id = obj.str("id").ifBlank { return null }
        val user = obj["user"] as? Map<*, *>
        val cid = obj.str("creatorId").ifBlank { user?.str("userId").orEmpty() }
        val cover = obj.optStr("coverImageUrl") ?: (obj["cover"] as? Map<*, *>)?.optStr("url")
        return DesktopFanboxPostSummary(
            id = id,
            title = obj.str("title").ifBlank { "제목 없는 게시글" },
            creatorId = cid,
            creatorName = user?.str("name")?.ifBlank { cid } ?: cid.ifBlank { "작가" },
            creatorIconUrl = user?.optStr("iconUrl"),
            coverImageUrl = cover,
            excerpt = obj.str("excerpt").trim(),
            publishedDatetime = obj.str("publishedDatetime"),
            feeRequired = obj.intVal("feeRequired"),
            isRestricted = obj.boolVal("isRestricted"),
            hasAdultContent = obj.boolVal("hasAdultContent"),
            likeCount = obj.intVal("likeCount"),
            type = obj.str("type").ifBlank { "image" },
        )
    }

    private fun parsePostDetail(obj: Map<*, *>): DesktopFanboxPostDetail {
        val summary = parsePostSummary(obj) ?: throw IOException("게시글 정보가 비어 있습니다.")
        val tags = (obj["tags"] as? List<*>).orEmpty().mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }
        val content = obj["body"] as? Map<*, *>
        val images = mutableListOf<DesktopFanboxImage>()
        val files = mutableListOf<DesktopFanboxFile>()
        val textParts = mutableListOf<String>()

        if (content != null) {
            content.optStr("text")?.trim()?.takeIf(String::isNotEmpty)?.let(textParts::add)
            (content["images"] as? List<*>)?.forEach { (it as? Map<*, *>)?.toImage()?.let(images::add) }
            (content["files"] as? List<*>)?.forEach { (it as? Map<*, *>)?.toFile()?.let(files::add) }

            val imageMap = content["imageMap"] as? Map<*, *>
            val fileMap = content["fileMap"] as? Map<*, *>
            val seenImg = images.map { it.id }.toMutableSet()
            val seenFile = files.map { it.id }.toMutableSet()

            (content["blocks"] as? List<*>)?.forEach { item ->
                val b = item as? Map<*, *> ?: return@forEach
                when (b.str("type")) {
                    "p", "header" -> b.str("text").trim().takeIf(String::isNotEmpty)?.let(textParts::add)
                    "image" -> {
                        val imgId = b.str("imageId")
                        if (imgId.isNotEmpty() && seenImg.add(imgId)) {
                            (imageMap?.get(imgId) as? Map<*, *>)?.toImage()?.let(images::add)
                        }
                    }
                    "file" -> {
                        val fileId = b.str("fileId")
                        if (fileId.isNotEmpty() && seenFile.add(fileId)) {
                            (fileMap?.get(fileId) as? Map<*, *>)?.toFile()?.let(files::add)
                        }
                    }
                }
            }
            imageMap?.forEach { (k, v) ->
                val keyStr = k?.toString().orEmpty()
                if (keyStr.isNotEmpty() && seenImg.add(keyStr)) (v as? Map<*, *>)?.toImage()?.let(images::add)
            }
            fileMap?.forEach { (k, v) ->
                val keyStr = k?.toString().orEmpty()
                if (keyStr.isNotEmpty() && seenFile.add(keyStr)) (v as? Map<*, *>)?.toFile()?.let(files::add)
            }
        }
        return DesktopFanboxPostDetail(summary, textParts.joinToString("\n\n"), images, files, tags)
    }

    private fun Map<*, *>.toImage(): DesktopFanboxImage? {
        val orig = optStr("originalUrl") ?: return null
        val thumb = optStr("thumbnailUrl") ?: orig
        return DesktopFanboxImage(
            id = str("id").ifBlank { orig.substringAfterLast('/').substringBefore('.') },
            extension = str("extension").ifBlank { orig.substringAfterLast('.', "png").substringBefore('?') },
            width = intVal("width"),
            height = intVal("height"),
            originalUrl = orig,
            thumbnailUrl = thumb,
        )
    }

    private fun Map<*, *>.toFile(): DesktopFanboxFile? {
        val url = optStr("url") ?: return null
        return DesktopFanboxFile(
            id = str("id").ifBlank { url.substringAfterLast('/') },
            name = str("name").ifBlank { "attachment" },
            extension = str("extension"),
            size = longVal("size"),
            url = url,
        )
    }

    private fun extractCreatorIdCandidate(input: String): String? {
        val trimmed = input.trim().removePrefix("@")
        Regex("""https?://([a-zA-Z0-9_-]+)\.fanbox\.cc""").find(trimmed)?.groupValues?.get(1)?.let { sub ->
            if (!sub.equals("www", true)) return sub
        }
        Regex("""fanbox\.cc/@([a-zA-Z0-9_-]+)""").find(trimmed)?.groupValues?.get(1)?.let { return it }
        if (Regex("""^[a-zA-Z0-9_-]{2,32}$""").matches(trimmed)) return trimmed
        return null
    }

    private fun isAllowedApiUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme.equals("https", true) && uri.host.equals("api.fanbox.cc", true)
    }

    private fun isAllowedMediaUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", true)) return false
        val host = uri.host?.lowercase() ?: return false
        return host == "fanbox.cc" || host.endsWith(".fanbox.cc") ||
            host == "pximg.net" || host.endsWith(".pximg.net") ||
            host.endsWith(".pixiv.net")
    }

    private fun Map<*, *>.str(k: String): String = (this[k] as? String).orEmpty()
    private fun Map<*, *>.optStr(k: String): String? = (this[k] as? String)?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", true) }
    private fun Map<*, *>.intVal(k: String): Int = (this[k] as? Number)?.toInt() ?: 0
    private fun Map<*, *>.longVal(k: String): Long = (this[k] as? Number)?.toLong() ?: 0L
    private fun Map<*, *>.boolVal(k: String, default: Boolean = false): Boolean = (this[k] as? Boolean) ?: default

    @Composable
    fun rememberRemoteImage(url: String?, maxEdge: Int = 540): ImageBitmap? {
        if (url.isNullOrBlank()) return null
        return key(url, maxEdge) {
            val bmp by produceState<ImageBitmap?>(remoteImageCache["$url@$maxEdge"]) {
                if (value == null) {
                    value = try {
                        withContext(remoteImageThreads) { decodeRemoteImage(url, maxEdge) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            bmp
        }
    }
}

private object SimpleJson {
    fun parse(text: String): Any? = Parser(text).parseValue()

    private class Parser(private val s: String) {
        private var i = 0
        fun parseValue(): Any? {
            skipWs()
            if (i >= s.length) return null
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> parseNumber()
            }
        }
        private fun parseObject(): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (i < s.length) {
                skipWs()
                val k = parseString()
                skipWs()
                if (i < s.length && s[i] == ':') i++
                m[k] = parseValue()
                skipWs()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == '}') { i++; break }
                break
            }
            return m
        }
        private fun parseArray(): List<Any?> {
            i++
            val list = ArrayList<Any?>()
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return list }
            while (i < s.length) {
                list.add(parseValue())
                skipWs()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == ']') { i++; break }
                break
            }
            return list
        }
        private fun parseString(): String {
            if (i >= s.length || s[i] != '"') return ""
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> if (i < s.length) when (val e = s[i++]) {
                        '"', '\\', '/' -> sb.append(e)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> if (i + 4 <= s.length) {
                            sb.append(s.substring(i, i + 4).toInt(16).toChar())
                            i += 4
                        }
                        else -> sb.append(e)
                    }
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }
        private fun parseNumber(): Number {
            val start = i
            if (i < s.length && s[i] == '-') i++
            while (i < s.length && s[i].isDigit()) i++
            var dbl = false
            if (i < s.length && s[i] == '.') { dbl = true; i++; while (i < s.length && s[i].isDigit()) i++ }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                dbl = true; i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i].isDigit()) i++
            }
            val tok = s.substring(start, i)
            return if (dbl) tok.toDoubleOrNull() ?: 0.0 else tok.toLongOrNull() ?: 0L
        }
        private fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
    }
}

/**
 * One-click Windows popup web login using installed Chrome or Edge in standalone --app mode.
 * Automatically captures FANBOXSESSID from the isolated login profile via local CDP WebSocket
 * and closes the popup window as soon as login succeeds—no DevTools or manual cookie copy needed!
 */
internal object DesktopWebLogin {
    private val localHttp = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun findBrowser(): File? {
        val pf = System.getenv("ProgramFiles") ?: "C:\\Program Files"
        val pf86 = System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)"
        val localApp = System.getenv("LOCALAPPDATA") ?: ""
        val candidates = listOf(
            File(pf, "Google\\Chrome\\Application\\chrome.exe"),
            File(pf86, "Google\\Chrome\\Application\\chrome.exe"),
            File(localApp, "Google\\Chrome\\Application\\chrome.exe"),
            File(pf86, "Microsoft\\Edge\\Application\\msedge.exe"),
            File(pf, "Microsoft\\Edge\\Application\\msedge.exe"),
            File(pf, "BraveSoftware\\Brave-Browser\\Application\\brave.exe"),
            File(pf, "Naver\\Naver Whale\\Application\\whale.exe"),
        )
        return candidates.firstOrNull { it.isFile }
    }

    suspend fun runPopupLogin(onStatus: (String) -> Unit): Result<String> = withContext(Dispatchers.IO) {
        val browser = findBrowser()
            ?: return@withContext Result.failure(IllegalStateException("Chrome 또는 Edge 브라우저를 찾을 수 없습니다. 아래 쿠키 직접 입력을 이용해 주세요."))

        val port = ServerSocket(0).use { it.localPort }
        val baseDir = System.getenv("LOCALAPPDATA")?.let(::File) ?: File(System.getProperty("user.home"), ".local/share")
        val profileDir = File(File(baseDir, "FanboxLibrary"), "web-login-profile").apply { mkdirs() }

        onStatus("${browser.nameWithoutExtension} 로그인 창을 여는 중…")
        val process = ProcessBuilder(
            browser.absolutePath,
            "--app=https://www.fanbox.cc/login",
            "--window-size=540,780",
            "--no-first-run",
            "--no-default-browser-check",
            "--remote-debugging-port=$port",
            "--user-data-dir=${profileDir.absolutePath}",
        ).redirectErrorStream(true).start()

        var ws: WebSocket? = null
        try {
            // Wait up to 15s for browser debugging endpoint to come up
            var wsUrl: String? = null
            for (attempt in 0 until 30) {
                if (!isActive) throw CancellationException()
                if (!process.isAlive) {
                    return@withContext Result.failure(IllegalStateException("로그인 창이 닫혔습니다."))
                }
                wsUrl = fetchBrowserWsUrl(port)
                if (wsUrl != null) break
                delay(500)
            }
            if (wsUrl == null) {
                return@withContext Result.failure(IllegalStateException("로그인 창 연결에 실패했습니다. 기존 로그인 창이 열려 있다면 닫고 다시 시도해 주세요."))
            }

            onStatus("팝업 창에서 pixiv / FANBOX에 로그인하세요. 완료되면 자동으로 연결됩니다.")

            val responses = ConcurrentHashMap<Int, CompletableDeferred<String>>()
            val opened = CompletableDeferred<Boolean>()
            val closed = CompletableDeferred<Boolean>()

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.complete(true)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val map = SimpleJson.parse(text) as? Map<*, *> ?: return
                    val id = (map["id"] as? Number)?.toInt() ?: return
                    responses.remove(id)?.complete(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    opened.complete(false)
                    closed.complete(true)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closed.complete(true)
                }
            }

            ws = localHttp.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
            if (withTimeoutOrNull(8_000L) { opened.await() } != true) {
                return@withContext Result.failure(IllegalStateException("브라우저 세션 감시 연결에 실패했습니다."))
            }

            val idGen = AtomicInteger(1)
            // Poll cookies every 1 second for up to 5 minutes until FANBOXSESSID appears
            repeat(300) {
                if (!isActive) throw CancellationException()
                if (!process.isAlive || closed.isCompleted) {
                    return@withContext Result.failure(IllegalStateException("로그인이 완료되기 전에 창이 닫혔습니다."))
                }

                val reqId = idGen.getAndIncrement()
                val def = CompletableDeferred<String>()
                responses[reqId] = def
                ws.send("""{"id":$reqId,"method":"Storage.getCookies"}""")

                val reply = withTimeoutOrNull(3_000L) { def.await() }
                responses.remove(reqId)

                val cookieHeader = reply?.let(::extractFanboxCookiesFromCdp)
                if (!cookieHeader.isNullOrBlank()) {
                    onStatus("로그인 완료! 창을 닫고 피드를 불러오는 중…")
                    DesktopFanboxSession.saveSessionInput(cookieHeader)
                    runCatching { ws.send("""{"id":${idGen.getAndIncrement()},"method":"Browser.close"}""") }
                    delay(300)
                    return@withContext Result.success(cookieHeader)
                }
                delay(1000)
            }
            Result.failure(IllegalStateException("로그인 대기 시간이 초과되었습니다."))
        } finally {
            runCatching { ws?.close(1000, "done") }
            runCatching { if (process.isAlive) process.destroy() }
        }
    }

    private fun fetchBrowserWsUrl(port: Int): String? = runCatching {
        val req = Request.Builder().url("http://127.0.0.1:$port/json/version").build()
        localHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            val obj = SimpleJson.parse(resp.body?.string().orEmpty()) as? Map<*, *>
            obj?.get("webSocketDebuggerUrl") as? String
        }
    }.getOrNull()

    private fun extractFanboxCookiesFromCdp(rawJson: String): String? {
        val root = SimpleJson.parse(rawJson) as? Map<*, *> ?: return null
        val result = root["result"] as? Map<*, *> ?: return null
        val cookies = result["cookies"] as? List<*> ?: return null
        val matched = linkedMapOf<String, String>()
        for (item in cookies) {
            val c = item as? Map<*, *> ?: continue
            val domain = (c["domain"] as? String).orEmpty().lowercase()
            val name = (c["name"] as? String).orEmpty()
            val value = (c["value"] as? String).orEmpty()
            if (domain.endsWith("fanbox.cc") && name.isNotBlank() && value.isNotBlank()) {
                matched[name] = value
            }
        }
        val sess = matched["FANBOXSESSID"]
        if (sess.isNullOrBlank()) return null
        return matched.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
}
