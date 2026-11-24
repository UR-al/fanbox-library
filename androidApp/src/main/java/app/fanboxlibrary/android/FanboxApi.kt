package app.fanboxlibrary.android

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import coil.request.ImageRequest
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

internal data class FanboxCreator(
    val creatorId: String,
    val name: String,
    val iconUrl: String?,
    val coverUrl: String?,
    val description: String,
    val isSupporting: Boolean,
    val planFee: Int = 0,
)

internal data class FanboxImage(
    val id: String,
    val extension: String,
    val width: Int,
    val height: Int,
    val originalUrl: String,
    val thumbnailUrl: String,
) {
    val fileName: String get() = "${id}.${extension.ifBlank { "png" }}"
}

internal data class FanboxFile(
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
            } else {
                clean
            }
        }
}

internal data class FanboxPostSummary(
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

internal data class FanboxPostDetail(
    val summary: FanboxPostSummary,
    val text: String,
    val images: List<FanboxImage>,
    val files: List<FanboxFile>,
    val tags: List<String>,
)

internal data class FanboxFeedPage(
    val items: List<FanboxPostSummary>,
    val nextUrl: String?,
)

internal sealed interface FanboxResult<out T> {
    data class Success<T>(val data: T) : FanboxResult<T>
    data class AuthRequired(val message: String) : FanboxResult<Nothing>
    data class CloudflareBlocked(val message: String) : FanboxResult<Nothing>
    data class Error(val message: String) : FanboxResult<Nothing>
}

internal object FanboxSession {
    const val WEB_ORIGIN = "https://www.fanbox.cc"
    const val WEB_REFERER = "https://www.fanbox.cc/"
    const val API_BASE = "https://api.fanbox.cc"

    fun cookieHeader(): String {
        val cm = runCatching { CookieManager.getInstance() }.getOrNull() ?: return ""
        val merged = linkedMapOf<String, String>()
        listOf("https://www.fanbox.cc/", "https://fanbox.cc/", "https://api.fanbox.cc/").forEach { url ->
            val raw = runCatching { cm.getCookie(url) }.getOrNull().orEmpty()
            raw.split(';').forEach { part ->
                val idx = part.indexOf('=')
                if (idx > 0) {
                    val k = part.substring(0, idx).trim()
                    val v = part.substring(idx + 1).trim()
                    if (k.isNotEmpty()) merged[k] = v
                }
            }
        }
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun hasLoginCookie(): Boolean = cookieHeader().contains("FANBOXSESSID=")

    fun userAgent(context: Context): String =
        runCatching { WebSettings.getDefaultUserAgent(context) }
            .getOrDefault("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")

    fun flushCookies() {
        runCatching { CookieManager.getInstance().flush() }
    }

    fun clearSession(onDone: () -> Unit = {}) {
        runCatching {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies {
                cm.flush()
                onDone()
            }
        }.onFailure { onDone() }
    }

    fun imageRequest(context: Context, url: String): ImageRequest {
        val cookie = cookieHeader()
        val builder = ImageRequest.Builder(context)
            .data(url)
            .addHeader("Referer", WEB_REFERER)
            .addHeader("User-Agent", userAgent(context))
            .crossfade(true)
        if (cookie.isNotBlank()) builder.addHeader("Cookie", cookie)
        return builder.build()
    }
}

internal object FanboxClient {
    fun fetchHomeFeed(context: Context, nextUrl: String? = null): FanboxResult<FanboxFeedPage> {
        val url = nextUrl?.takeIf { isAllowedApiUrl(it) } ?: "${FanboxSession.API_BASE}/post.listHome?limit=20"
        return requestJson(context, url) { root -> parseFeedPage(root) }
    }

    fun fetchSupportingFeed(context: Context, nextUrl: String? = null): FanboxResult<FanboxFeedPage> {
        val url = nextUrl?.takeIf { isAllowedApiUrl(it) } ?: "${FanboxSession.API_BASE}/post.listSupporting?limit=20"
        return requestJson(context, url) { root -> parseFeedPage(root) }
    }

    fun fetchCreatorPosts(context: Context, creatorId: String, nextUrl: String? = null): FanboxResult<FanboxFeedPage> {
        val encoded = URLEncoder.encode(creatorId, "UTF-8")
        val url = nextUrl?.takeIf { isAllowedApiUrl(it) }
            ?: "${FanboxSession.API_BASE}/post.listCreator?creatorId=$encoded&limit=20"
        return requestJson(context, url) { root -> parseFeedPage(root) }
    }

    fun fetchCreators(context: Context): FanboxResult<List<FanboxCreator>> {
        val supportingRes = requestJson(context, "${FanboxSession.API_BASE}/plan.listSupporting") { root ->
            val arr = root.optJSONArray("body") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val creatorId = obj.optString("creatorId").ifBlank { continue }
                    val user = obj.optJSONObject("user")
                    add(
                        FanboxCreator(
                            creatorId = creatorId,
                            name = user?.optString("name")?.ifBlank { creatorId } ?: creatorId,
                            iconUrl = user?.optNullableString("iconUrl"),
                            coverUrl = obj.optNullableString("coverImageUrl"),
                            description = obj.optString("title").ifBlank { obj.optString("description") },
                            isSupporting = true,
                            planFee = obj.optInt("fee", 0),
                        ),
                    )
                }
            }
        }
        if (supportingRes is FanboxResult.AuthRequired || supportingRes is FanboxResult.CloudflareBlocked) {
            return supportingRes
        }

        val followingRes = requestJson(context, "${FanboxSession.API_BASE}/creator.listFollowing") { root ->
            val arr = root.optJSONArray("body") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val creatorId = obj.optString("creatorId").ifBlank { continue }
                    val user = obj.optJSONObject("user")
                    add(
                        FanboxCreator(
                            creatorId = creatorId,
                            name = user?.optString("name")?.ifBlank { creatorId } ?: creatorId,
                            iconUrl = user?.optNullableString("iconUrl"),
                            coverUrl = obj.optNullableString("coverImageUrl"),
                            description = obj.optString("description"),
                            isSupporting = false,
                        ),
                    )
                }
            }
        }
        if (supportingRes is FanboxResult.Error && followingRes !is FanboxResult.Success) {
            return supportingRes
        }
        if (followingRes is FanboxResult.AuthRequired || followingRes is FanboxResult.CloudflareBlocked) {
            if (supportingRes !is FanboxResult.Success) return followingRes
        }

        val merged = linkedMapOf<String, FanboxCreator>()
        (supportingRes as? FanboxResult.Success)?.data.orEmpty().forEach { merged[it.creatorId] = it }
        (followingRes as? FanboxResult.Success)?.data.orEmpty().forEach { creator ->
            if (creator.creatorId !in merged) merged[creator.creatorId] = creator
        }
        return FanboxResult.Success(merged.values.toList())
    }

    fun fetchPostDetail(context: Context, postId: String): FanboxResult<FanboxPostDetail> {
        val encoded = URLEncoder.encode(postId, "UTF-8")
        val url = "${FanboxSession.API_BASE}/post.info?postId=$encoded"
        return requestJson(context, url) { root ->
            val bodyObj = root.optJSONObject("body")
                ?: throw IOException("게시글 정보를 불러올 수 없습니다.")
            parsePostDetail(bodyObj)
        }
    }

    fun openDownloadStream(context: Context, url: String): InputStream {
        require(isAllowedDownloadUrl(url)) { "허용되지 않은 FANBOX 다운로드 주소입니다." }
        var currentUrl = url
        var redirects = 0
        val cookie = FanboxSession.cookieHeader()
        val ua = FanboxSession.userAgent(context)
        while (true) {
            val conn = (URI(currentUrl).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 45_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", ua)
                setRequestProperty("Referer", FanboxSession.WEB_REFERER)
                if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
            }
            val code = conn.responseCode
            if (code in 301..308) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank() || ++redirects > 6) {
                    throw IOException("다운로드 리다이렉트 오류 (HTTP $code)")
                }
                currentUrl = URI(currentUrl).resolve(loc).toString()
                continue
            }
            if (code != 200) {
                conn.disconnect()
                throw IOException("파일 다운로드 실패 (HTTP $code)")
            }
            return conn.inputStream
        }
    }

    private fun isAllowedApiUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme.equals("https", true) && uri.host.equals("api.fanbox.cc", true)
    }

    private fun isAllowedDownloadUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", true)) return false
        val host = uri.host?.lowercase() ?: return false
        return host == "fanbox.cc" ||
            host.endsWith(".fanbox.cc") ||
            host == "pximg.net" ||
            host.endsWith(".pximg.net") ||
            host.endsWith(".pixiv.net")
    }

    private fun <T> requestJson(
        context: Context,
        url: String,
        parse: (JSONObject) -> T,
    ): FanboxResult<T> {
        val cookie = FanboxSession.cookieHeader()
        if (!cookie.contains("FANBOXSESSID=")) {
            return FanboxResult.AuthRequired("FANBOX 로그인이 필요합니다.")
        }
        return try {
            val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("Origin", FanboxSession.WEB_ORIGIN)
                setRequestProperty("Referer", FanboxSession.WEB_REFERER)
                setRequestProperty("User-Agent", FanboxSession.userAgent(context))
                setRequestProperty("Cookie", cookie)
            }
            try {
                val code = conn.responseCode
                val contentType = conn.contentType.orEmpty()
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    .orEmpty()

                when {
                    code == 401 -> FanboxResult.AuthRequired("로그인 세션이 만료되었습니다. 웹뷰에서 다시 로그인해 주세요.")
                    code == 403 && (contentType.contains("text/html", true) || text.contains("cloudflare", true) || text.contains("Just a moment", true)) ->
                        FanboxResult.CloudflareBlocked("Cloudflare 보안 확인이 필요합니다. 내장 웹뷰에서 한 번 인증해 주세요.")
                    code == 403 -> FanboxResult.AuthRequired("접근 권한이 없거나 로그인이 필요합니다. (HTTP 403)")
                    code !in 200..299 -> FanboxResult.Error("FANBOX 응답 오류 (HTTP $code)")
                    !text.trimStart().startsWith("{") ->
                        FanboxResult.CloudflareBlocked("FANBOX 보안 페이지가 반환되었습니다. 웹뷰 탭에서 확인해 주세요.")
                    else -> {
                        val json = JSONObject(text)
                        FanboxResult.Success(parse(json))
                    }
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            FanboxResult.Error("네트워크 오류: ${e.localizedMessage ?: e.javaClass.simpleName}")
        }
    }

    private fun parseFeedPage(root: JSONObject): FanboxFeedPage {
        val bodyObj = root.optJSONObject("body")
        val itemsArr = bodyObj?.optJSONArray("items")
            ?: root.optJSONArray("body")
            ?: JSONArray()
        val nextUrl = bodyObj?.optNullableString("nextUrl")
        val items = buildList {
            for (i in 0 until itemsArr.length()) {
                val obj = itemsArr.optJSONObject(i) ?: continue
                parsePostSummary(obj)?.let(::add)
            }
        }
        return FanboxFeedPage(items = items, nextUrl = nextUrl)
    }

    private fun parsePostSummary(obj: JSONObject): FanboxPostSummary? {
        val id = obj.optString("id").ifBlank { return null }
        val user = obj.optJSONObject("user")
        val creatorId = obj.optString("creatorId").ifBlank { user?.optString("userId").orEmpty() }
        val cover = obj.optNullableString("coverImageUrl")
            ?: obj.optJSONObject("cover")?.optNullableString("url")
        return FanboxPostSummary(
            id = id,
            title = obj.optString("title").ifBlank { "제목 없는 게시글" },
            creatorId = creatorId,
            creatorName = user?.optString("name")?.ifBlank { creatorId } ?: creatorId.ifBlank { "작가" },
            creatorIconUrl = user?.optNullableString("iconUrl"),
            coverImageUrl = cover,
            excerpt = obj.optString("excerpt").trim(),
            publishedDatetime = obj.optString("publishedDatetime"),
            feeRequired = obj.optInt("feeRequired", 0),
            isRestricted = obj.optBoolean("isRestricted", false),
            hasAdultContent = obj.optBoolean("hasAdultContent", false),
            likeCount = obj.optInt("likeCount", 0),
            type = obj.optString("type", "image"),
        )
    }

    private fun parsePostDetail(obj: JSONObject): FanboxPostDetail {
        val summary = parsePostSummary(obj) ?: throw IOException("게시글 메타데이터가 비어 있습니다.")
        val tagsArr = obj.optJSONArray("tags") ?: JSONArray()
        val tags = buildList {
            for (i in 0 until tagsArr.length()) {
                val t = tagsArr.optString(i).trim()
                if (t.isNotEmpty()) add(t)
            }
        }

        val content = obj.optJSONObject("body")
        val images = mutableListOf<FanboxImage>()
        val files = mutableListOf<FanboxFile>()
        val textParts = mutableListOf<String>()

        if (content != null) {
            content.optNullableString("text")?.trim()?.takeIf { it.isNotEmpty() }?.let { textParts.add(it) }

            // Direct images array (type == "image")
            content.optJSONArray("images")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.toFanboxImage()?.let(images::add)
                }
            }

            // Direct files array (type == "file")
            content.optJSONArray("files")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.toFanboxFile()?.let(files::add)
                }
            }

            // Article blocks + imageMap + fileMap (type == "article")
            val imageMap = content.optJSONObject("imageMap")
            val fileMap = content.optJSONObject("fileMap")
            val seenImages = images.map { it.id }.toMutableSet()
            val seenFiles = files.map { it.id }.toMutableSet()

            content.optJSONArray("blocks")?.let { blocks ->
                for (i in 0 until blocks.length()) {
                    val b = blocks.optJSONObject(i) ?: continue
                    when (b.optString("type")) {
                        "p", "header" -> {
                            val line = b.optString("text").trim()
                            if (line.isNotEmpty()) textParts.add(line)
                        }
                        "image" -> {
                            val imgId = b.optString("imageId")
                            if (imgId.isNotEmpty() && seenImages.add(imgId)) {
                                imageMap?.optJSONObject(imgId)?.toFanboxImage()?.let(images::add)
                            }
                        }
                        "file" -> {
                            val fileId = b.optString("fileId")
                            if (fileId.isNotEmpty() && seenFiles.add(fileId)) {
                                fileMap?.optJSONObject(fileId)?.toFanboxFile()?.let(files::add)
                            }
                        }
                    }
                }
            }

            // Any remaining images/files in maps not referenced in blocks
            imageMap?.keys()?.forEach { key ->
                if (seenImages.add(key)) imageMap.optJSONObject(key)?.toFanboxImage()?.let(images::add)
            }
            fileMap?.keys()?.forEach { key ->
                if (seenFiles.add(key)) fileMap.optJSONObject(key)?.toFanboxFile()?.let(files::add)
            }
        }

        return FanboxPostDetail(
            summary = summary,
            text = textParts.joinToString("\n\n"),
            images = images,
            files = files,
            tags = tags,
        )
    }

    private fun JSONObject.toFanboxImage(): FanboxImage? {
        val original = optNullableString("originalUrl") ?: return null
        val thumb = optNullableString("thumbnailUrl") ?: original
        return FanboxImage(
            id = optString("id").ifBlank { original.substringAfterLast('/').substringBefore('.') },
            extension = optString("extension").ifBlank { original.substringAfterLast('.', "png").substringBefore('?') },
            width = optInt("width", 0),
            height = optInt("height", 0),
            originalUrl = original,
            thumbnailUrl = thumb,
        )
    }

    private fun JSONObject.toFanboxFile(): FanboxFile? {
        val url = optNullableString("url") ?: return null
        return FanboxFile(
            id = optString("id").ifBlank { url.substringAfterLast('/') },
            name = optString("name").ifBlank { "attachment" },
            extension = optString("extension"),
            size = optLong("size", 0L),
            url = url,
        )
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).trim().takeIf { it.isNotEmpty() && !it.equals("null", true) }
    }
}
