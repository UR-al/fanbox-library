package app.fanboxlibrary.android

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import coil.request.ImageRequest
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

internal data class FanboxCreator(
    val creatorId: String,
    val userId: String = "",
    val name: String,
    val iconUrl: String?,
    val coverUrl: String?,
    val description: String,
    val isSupporting: Boolean = false,
    val isFollowed: Boolean = false,
    val planFee: Int = 0,
    val service: MobileFeedService = MobileFeedService.FANBOX,
    val campaignUrl: String = "",
)

internal data class FanboxPlan(
    val id: String,
    val title: String,
    val fee: Int,
    val description: String,
    val coverImageUrl: String?,
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
    val tags: List<String> = emptyList(),
    val service: MobileFeedService = MobileFeedService.FANBOX,
    val customPostUrl: String = "",
) {
    val year: Int? get() = publishedDatetime.take(4).toIntOrNull()
    val postUrl: String
        get() = customPostUrl.ifBlank {
            if (service == MobileFeedService.PATREON) "https://www.patreon.com/posts/$id"
            else "https://${creatorId.ifBlank { "www" }}.fanbox.cc/posts/$id"
        }
    val displayDate: String get() = publishedDatetime.take(10)
    val isTextOnly: Boolean get() = type.equals("text", true) || (coverImageUrl.isNullOrBlank() && type != "image" && type != "file")
    val uniqueKey: String get() = "${service.name}:$id"
}

internal data class FanboxPostDetail(
    val summary: FanboxPostSummary,
    val text: String,
    val images: List<FanboxImage>,
    val files: List<FanboxFile>,
    val tags: List<String>,
    val externalLinks: List<MobileExternalLink> = emptyList(),
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

internal data class BridgeHttpResponse(
    val status: Int,
    val contentType: String,
    val body: String,
)

/**
 * Executes FANBOX API requests inside Android's real Chromium WebView parked on https://www.fanbox.cc/.
 * This shares the exact browser TLS fingerprint, HTTP/2 stack, CookieManager session (FANBOXSESSID),
 * Origin header, and live CSRF token—eliminating false Cloudflare 403 blocks from Java HttpURLConnection.
 */
internal object FanboxWebBridge {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<String, CompletableDeferred<BridgeHttpResponse>>()

    @Volatile private var webView: WebView? = null
    @Volatile private var readySignal = CompletableDeferred<Boolean>()
    @Volatile private var loadingOrigin = false

    @SuppressLint("SetJavaScriptEnabled")
    fun attachOrCreate(context: Context): WebView {
        webView?.let { return it }
        val wv = WebView(context.applicationContext).apply {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(this, true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.blockNetworkImage = true // API bridge never needs to render images
            addJavascriptInterface(JsCallback(), "FanboxBridge")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    FanboxSession.flushCookies()
                    if (!url.isNullOrBlank() && url.startsWith("https://")) {
                        loadingOrigin = false
                        if (!readySignal.isCompleted) readySignal.complete(true)
                    }
                }
            }
        }
        webView = wv
        reloadOrigin()
        return wv
    }

    fun reloadOrigin() {
        mainHandler.post {
            val wv = webView ?: return@post
            if (loadingOrigin) return@post
            loadingOrigin = true
            readySignal = CompletableDeferred()
            wv.loadUrl(FanboxSession.WEB_REFERER)
        }
    }

    private suspend fun ensureReady(context: Context): WebView {
        var existing = webView
        if (existing == null) {
            val created = CompletableDeferred<WebView>()
            mainHandler.post {
                created.complete(attachOrCreate(context))
            }
            existing = created.await()
        }
        runCatching {
            withTimeout(12_000L) { readySignal.await() }
        }
        return existing
    }

    suspend fun fetchText(
        context: Context,
        url: String,
        method: String = "GET",
        jsonBody: String? = null,
    ): BridgeHttpResponse {
        val wv = ensureReady(context)
        val reqId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<BridgeHttpResponse>()
        pending[reqId] = deferred

        val jsUrl = JSONObject.quote(url)
        val jsMethod = JSONObject.quote(method)
        val jsBody = if (jsonBody != null) JSONObject.quote(jsonBody) else "null"
        val jsReqId = JSONObject.quote(reqId)

        val script = """
            (async function() {
              try {
                let csrf = '';
                try {
                  const meta = document.querySelector('meta[name="metadata"]');
                  if (meta && meta.content) {
                    const parsed = JSON.parse(meta.content);
                    if (parsed && parsed.csrfToken) csrf = parsed.csrfToken;
                  }
                } catch (_) {}
                const headers = { 'Accept': 'application/json, text/plain, */*' };
                if ($jsMethod !== 'GET') {
                  headers['Content-Type'] = 'application/json';
                  if (csrf) headers['x-csrf-token'] = csrf;
                }
                const opts = { method: $jsMethod, credentials: 'include', headers: headers };
                if ($jsBody !== null) opts.body = $jsBody;
                const resp = await fetch($jsUrl, opts);
                const txt = await resp.text();
                window.FanboxBridge.onResult($jsReqId, resp.status, resp.headers.get('content-type') || '', txt);
              } catch (err) {
                window.FanboxBridge.onResult($jsReqId, -1, '', String(err));
              }
            })();
        """.trimIndent()

        mainHandler.post { wv.evaluateJavascript(script, null) }

        return try {
            withTimeout(20_000L) { deferred.await() }
        } catch (e: Exception) {
            pending.remove(reqId)
            BridgeHttpResponse(-1, "", "요청 시간 초과: ${e.localizedMessage ?: "timeout"}")
        }
    }

    suspend fun fetchBinaryViaBrowser(context: Context, url: String): ByteArray {
        val wv = ensureReady(context)
        val reqId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<BridgeHttpResponse>()
        pending[reqId] = deferred

        val jsUrl = JSONObject.quote(url)
        val jsReqId = JSONObject.quote(reqId)
        val script = """
            (async function() {
              try {
                const resp = await fetch($jsUrl, { credentials: 'include' });
                if (!resp.ok) {
                  window.FanboxBridge.onResult($jsReqId, resp.status, '', 'HTTP ' + resp.status);
                  return;
                }
                const blob = await resp.blob();
                const reader = new FileReader();
                reader.onloadend = function() {
                  const res = String(reader.result || '');
                  const idx = res.indexOf(',');
                  window.FanboxBridge.onResult($jsReqId, resp.status, blob.type || '', idx >= 0 ? res.slice(idx + 1) : res);
                };
                reader.onerror = function() {
                  window.FanboxBridge.onResult($jsReqId, -1, '', 'FileReader error');
                };
                reader.readAsDataURL(blob);
              } catch (err) {
                window.FanboxBridge.onResult($jsReqId, -1, '', String(err));
              }
            })();
        """.trimIndent()

        mainHandler.post { wv.evaluateJavascript(script, null) }
        val res = withTimeout(45_000L) { deferred.await() }
        if (res.status !in 200..299) {
            throw IOException("브라우저 다운로드 실패 (HTTP ${res.status}): ${res.body}")
        }
        return Base64.decode(res.body, Base64.DEFAULT)
    }

    private class JsCallback {
        @JavascriptInterface
        fun onResult(id: String, status: Int, contentType: String, body: String) {
            pending.remove(id)?.complete(BridgeHttpResponse(status, contentType, body))
        }
    }
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
                FanboxWebBridge.reloadOrigin()
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
    suspend fun fetchHomeFeed(context: Context, nextUrl: String? = null): FanboxResult<FanboxFeedPage> {
        val url = nextUrl?.takeIf { isAllowedApiUrl(it) } ?: "${FanboxSession.API_BASE}/post.listHome?limit=20"
        return requestJson(context, url) { root -> parseFeedPage(root) }
    }

    suspend fun fetchSupportingFeed(context: Context, nextUrl: String? = null): FanboxResult<FanboxFeedPage> {
        val url = nextUrl?.takeIf { isAllowedApiUrl(it) } ?: "${FanboxSession.API_BASE}/post.listSupporting?limit=20"
        return requestJson(context, url) { root -> parseFeedPage(root) }
    }

    private val creatorPageUrlsCache = ConcurrentHashMap<String, List<String>>()

    private suspend fun fetchCreatorPageUrls(context: Context, creatorId: String): List<String> {
        val encoded = URLEncoder.encode(creatorId, "UTF-8")
        val res = requestJson(context, "${FanboxSession.API_BASE}/post.paginateCreator?creatorId=$encoded") { root ->
            val arr = root.optJSONArray("body")
                ?: root.optJSONObject("body")?.optJSONArray("pageUrls")
                ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    val u = arr.optString(i).trim()
                    if (isAllowedApiUrl(u)) add(u)
                }
            }
        }
        val urls = (res as? FanboxResult.Success)?.data.orEmpty()
        if (urls.isNotEmpty()) creatorPageUrlsCache[creatorId] = urls
        return urls
    }

    suspend fun fetchCreatorPosts(context: Context, creatorId: String, nextUrl: String? = null): FanboxResult<FanboxFeedPage> {
        val urls = if (nextUrl == null) {
            fetchCreatorPageUrls(context, creatorId)
        } else {
            creatorPageUrlsCache[creatorId] ?: fetchCreatorPageUrls(context, creatorId)
        }
        if (urls.isEmpty()) {
            val encoded = URLEncoder.encode(creatorId, "UTF-8")
            val url = nextUrl?.takeIf { isAllowedApiUrl(it) }
                ?: "${FanboxSession.API_BASE}/post.listCreator?creatorId=$encoded&limit=20"
            return requestJson(context, url) { root -> parseFeedPage(root) }
        }
        val startIdx = if (nextUrl == null) 0 else urls.indexOf(nextUrl).takeIf { it >= 0 }
        if (startIdx == null) {
            val safe = nextUrl?.takeIf { isAllowedApiUrl(it) } ?: return FanboxResult.Success(FanboxFeedPage(emptyList(), null))
            return requestJson(context, safe) { root -> parseFeedPage(root) }
        }
        val combined = mutableListOf<FanboxPostSummary>()
        val endExclusive = (startIdx + 2).coerceAtMost(urls.size)
        for (i in startIdx until endExclusive) {
            when (val r = requestJson(context, urls[i]) { root -> parseFeedPage(root) }) {
                is FanboxResult.Success -> combined.addAll(r.data.items)
                is FanboxResult.AuthRequired -> if (combined.isEmpty()) return r
                is FanboxResult.CloudflareBlocked -> if (combined.isEmpty()) return r
                is FanboxResult.Error -> if (combined.isEmpty()) return r
            }
        }
        return FanboxResult.Success(FanboxFeedPage(combined.distinctBy { it.id }, urls.getOrNull(endExclusive)))
    }

    suspend fun fetchCreatorProfile(context: Context, creatorId: String): FanboxResult<FanboxCreator> {
        val encoded = URLEncoder.encode(creatorId, "UTF-8")
        return requestJson(context, "${FanboxSession.API_BASE}/creator.get?creatorId=$encoded") { root ->
            val obj = root.optJSONObject("body") ?: throw IOException("작가 정보를 찾을 수 없습니다.")
            parseCreatorObject(obj) ?: throw IOException("작가 정보가 비어 있습니다.")
        }
    }

    suspend fun fetchCreatorPlans(context: Context, creatorId: String): FanboxResult<List<FanboxPlan>> {
        val encoded = URLEncoder.encode(creatorId, "UTF-8")
        return requestJson(context, "${FanboxSession.API_BASE}/plan.listCreator?creatorId=$encoded") { root ->
            val arr = root.optJSONArray("body")
                ?: root.optJSONObject("body")?.optJSONArray("plans")
                ?: root.optJSONObject("body")?.optJSONArray("items")
                ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id").ifBlank { continue }
                    add(
                        FanboxPlan(
                            id = id,
                            title = obj.optString("title").ifBlank { "플랜" },
                            fee = obj.optInt("fee", 0),
                            description = obj.optString("description").trim(),
                            coverImageUrl = obj.optNullableString("coverImageUrl"),
                        ),
                    )
                }
            }.sortedBy { it.fee }
        }
    }

    suspend fun setFollowCreator(context: Context, creatorUserId: String, follow: Boolean): FanboxResult<Boolean> {
        if (creatorUserId.isBlank()) return FanboxResult.Error("작가 ID 정보를 불러오지 못했습니다.")
        val endpoint = if (follow) "follow.create" else "follow.delete"
        val payload = JSONObject().put("creatorUserId", creatorUserId).toString()
        return requestJson(
            context = context,
            url = "${FanboxSession.API_BASE}/$endpoint",
            method = "POST",
            jsonBody = payload,
        ) { follow }
    }

    suspend fun fetchRecommendedCreators(context: Context): FanboxResult<List<FanboxCreator>> {
        return requestJson(context, "${FanboxSession.API_BASE}/creator.listRecommended?limit=20") { root ->
            val arr = root.optJSONObject("body")?.optJSONArray("creators")
                ?: root.optJSONArray("body")
                ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let(::parseCreatorObject)?.let(::add)
                }
            }
        }
    }

    /**
     * Searches creators by keyword via creator.search, and also resolves direct creatorId or FANBOX URLs.
     */
    suspend fun searchCreators(context: Context, rawQuery: String): FanboxResult<List<FanboxCreator>> {
        val q = rawQuery.trim()
        if (q.isEmpty()) return FanboxResult.Success(emptyList())

        val results = linkedMapOf<String, FanboxCreator>()

        // 1. If input looks like a FANBOX URL or ASCII creatorId, try creator.get directly first!
        val candidateId = extractCreatorIdCandidate(q)
        if (candidateId != null) {
            val direct = fetchCreatorProfile(context, candidateId)
            if (direct is FanboxResult.Success) {
                results[direct.data.creatorId] = direct.data
            }
        }

        // 2. Keyword search via creator.search?q=...&page=1
        val encoded = URLEncoder.encode(q, "UTF-8")
        val searchRes = requestJson(context, "${FanboxSession.API_BASE}/creator.search?q=$encoded&page=1") { root ->
            val bodyObj = root.optJSONObject("body")
            val arr = bodyObj?.optJSONArray("creators")
                ?: bodyObj?.optJSONArray("items")
                ?: root.optJSONArray("body")
                ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let(::parseCreatorObject)?.let(::add)
                }
            }
        }
        when (searchRes) {
            is FanboxResult.Success -> searchRes.data.forEach { c -> results.putIfAbsent(c.creatorId, c) }
            is FanboxResult.AuthRequired -> if (results.isEmpty()) return searchRes
            is FanboxResult.CloudflareBlocked -> if (results.isEmpty()) return searchRes
            is FanboxResult.Error -> if (results.isEmpty()) return searchRes
        }

        return FanboxResult.Success(results.values.toList())
    }

    suspend fun fetchCreators(context: Context): FanboxResult<List<FanboxCreator>> {
        val supportingRes = requestJson(context, "${FanboxSession.API_BASE}/plan.listSupporting") { root ->
            val arr = root.optJSONArray("body")
                ?: root.optJSONObject("body")?.optJSONArray("plans")
                ?: root.optJSONObject("body")?.optJSONArray("items")
                ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val creatorId = obj.optString("creatorId").ifBlank { continue }
                    val user = obj.optJSONObject("user")
                    add(
                        FanboxCreator(
                            creatorId = creatorId,
                            userId = user?.optString("userId").orEmpty(),
                            name = user?.optString("name")?.ifBlank { creatorId } ?: creatorId,
                            iconUrl = user?.optNullableString("iconUrl"),
                            coverUrl = obj.optNullableString("coverImageUrl"),
                            description = obj.optString("title").ifBlank { obj.optString("description") },
                            isSupporting = true,
                            isFollowed = true,
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
            val arr = root.optJSONArray("body")
                ?: root.optJSONObject("body")?.optJSONArray("creators")
                ?: root.optJSONObject("body")?.optJSONArray("items")
                ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val parsed = parseCreatorObject(obj, defaultFollowed = true) ?: continue
                    add(parsed)
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

    suspend fun fetchPostDetail(context: Context, postId: String): FanboxResult<FanboxPostDetail> {
        val encoded = URLEncoder.encode(postId, "UTF-8")
        val url = "${FanboxSession.API_BASE}/post.info?postId=$encoded"
        return requestJson(context, url) { root ->
            val bodyObj = root.optJSONObject("body")
                ?: throw IOException("게시글 정보를 불러올 수 없습니다.")
            val postObj = bodyObj.optJSONObject("post") ?: bodyObj
            parsePostDetail(postObj)
        }
    }

    suspend fun downloadToInputStream(context: Context, url: String): InputStream {
        require(isAllowedDownloadUrl(url)) { "허용되지 않은 FANBOX 다운로드 주소입니다." }
        return try {
            openDirectDownloadStream(context, url)
        } catch (directError: IOException) {
            // Fallback through Chromium WebView if Cloudflare blocks direct HttpURLConnection
            val bytes = FanboxWebBridge.fetchBinaryViaBrowser(context, url)
            ByteArrayInputStream(bytes)
        }
    }

    private fun openDirectDownloadStream(context: Context, url: String): InputStream {
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

    private fun extractCreatorIdCandidate(input: String): String? {
        val trimmed = input.trim().removePrefix("@")
        val subMatch = Regex("""https?://([a-zA-Z0-9_-]+)\.fanbox\.cc""").find(trimmed)
        if (subMatch != null) {
            val sub = subMatch.groupValues[1]
            if (!sub.equals("www", true)) return sub
        }
        val pathMatch = Regex("""fanbox\.cc/@([a-zA-Z0-9_-]+)""").find(trimmed)
        if (pathMatch != null) return pathMatch.groupValues[1]
        if (Regex("""^[a-zA-Z0-9_-]{2,32}$""").matches(trimmed)) return trimmed
        return null
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

    private suspend fun <T> requestJson(
        context: Context,
        url: String,
        method: String = "GET",
        jsonBody: String? = null,
        parse: (JSONObject) -> T,
    ): FanboxResult<T> {
        if (!FanboxSession.hasLoginCookie() && method == "POST") {
            return FanboxResult.AuthRequired("이 기능을 사용하려면 FANBOX 로그인이 필요합니다.")
        }
        val res = FanboxWebBridge.fetchText(context, url, method, jsonBody)
        val text = res.body
        return when {
            res.status == 401 -> FanboxResult.AuthRequired("FANBOX 로그인이 필요합니다.")
            res.status == 403 && (res.contentType.contains("text/html", true) || text.contains("Just a moment", true)) ->
                FanboxResult.CloudflareBlocked("FANBOX 보안 확인이 필요합니다.")
            res.status == 403 -> FanboxResult.AuthRequired("로그인이 필요하거나 접근 권한이 없습니다. (HTTP 403)")
            res.status !in 200..299 -> FanboxResult.Error("요청 실패 (HTTP ${res.status}): ${text.take(120)}")
            !text.trimStart().startsWith("{") -> {
                if (method == "POST" && text.isBlank()) {
                    runCatching { parse(JSONObject()) }.fold(
                        onSuccess = { FanboxResult.Success(it) },
                        onFailure = { FanboxResult.Error(it.localizedMessage ?: "응답 해석 오류") },
                    )
                } else {
                    FanboxResult.Error("예상치 못한 응답 형식입니다.")
                }
            }
            else -> runCatching {
                val json = JSONObject(text)
                if (json.has("error")) {
                    throw IOException(json.optString("error", "FANBOX 오류"))
                }
                parse(json)
            }.fold(
                onSuccess = { FanboxResult.Success(it) },
                onFailure = { FanboxResult.Error(it.localizedMessage ?: "응답 파싱 실패") },
            )
        }
    }

    private fun parseCreatorObject(obj: JSONObject, defaultFollowed: Boolean = false): FanboxCreator? {
        val creatorId = obj.optString("creatorId").ifBlank { return null }
        val user = obj.optJSONObject("user")
        return FanboxCreator(
            creatorId = creatorId,
            userId = user?.optString("userId").orEmpty(),
            name = user?.optString("name")?.ifBlank { creatorId } ?: creatorId,
            iconUrl = user?.optNullableString("iconUrl"),
            coverUrl = obj.optNullableString("coverImageUrl"),
            description = obj.optString("description").trim(),
            isSupporting = obj.optBoolean("isSupported", false),
            isFollowed = obj.optBoolean("isFollowed", defaultFollowed),
        )
    }

    private fun parseFeedPage(root: JSONObject): FanboxFeedPage {
        val bodyObj = root.optJSONObject("body")
        val itemsArr = bodyObj?.optJSONArray("items")
            ?: bodyObj?.optJSONArray("posts")
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

            content.optJSONArray("images")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.toFanboxImage()?.let(images::add)
                }
            }

            content.optJSONArray("files")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.toFanboxFile()?.let(files::add)
                }
            }

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
