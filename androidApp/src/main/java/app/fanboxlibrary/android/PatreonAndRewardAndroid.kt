package app.fanboxlibrary.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.webkit.CookieManager
import app.fanboxlibrary.shared.ArchiveUnpacker
import app.fanboxlibrary.shared.ImportReport
import app.fanboxlibrary.shared.ImportSource
import app.fanboxlibrary.shared.LibraryStore
import app.fanboxlibrary.shared.MegaDownloader
import app.fanboxlibrary.shared.PostDraft
import app.fanboxlibrary.shared.UnpackedEntryFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.Files
import java.util.Locale

internal enum class MobileFeedService(val label: String) {
    FANBOX("FANBOX"),
    PATREON("Patreon"),
}

internal enum class MobileExternalLinkKind(val badge: String) {
    DROPBOX("Dropbox"),
    GOOGLE_DRIVE("Google Drive"),
    MEGA("MEGA"),
    DIRECT_FILE("직접 파일"),
    PATREON_GATE("보상 인증 페이지"),
}

internal data class MobileExternalLink(
    val label: String,
    val url: String,
    val kind: MobileExternalLinkKind = AndroidRewardTools.classifyUrl(url),
)

internal data class MobileResolvedReward(
    val label: String,
    val originalUrl: String,
    val directDownloadUrl: String,
    val kind: MobileExternalLinkKind,
)

internal sealed interface MobileGateCheckResult {
    data class Ready(val rewards: List<MobileResolvedReward>) : MobileGateCheckResult
    data class NeedsWebUnlock(val startUrl: String, val gateHost: String) : MobileGateCheckResult
    data class Failed(val message: String) : MobileGateCheckResult
}

internal object PatreonAndroidSession {
    const val WEB_REFERER = "https://www.patreon.com/"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    fun cookieHeader(): String {
        val cm = runCatching { CookieManager.getInstance() }.getOrNull() ?: return ""
        val merged = linkedMapOf<String, String>()
        listOf("https://www.patreon.com/", "https://patreon.com/").forEach { u ->
            val raw = runCatching { cm.getCookie(u) }.getOrNull().orEmpty()
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

    fun hasLoginCookie(): Boolean = cookieHeader().contains("session_id=")
    fun userAgent(): String = UA
}

internal object PatreonAndroidClient {
    private const val POST_FIELDS =
        "title,content,content_json_string,teaser_text,teaser_text_json_string,embed,published_at,url,patreon_url,post_type,post_metadata,post_file,current_user_can_view,image,min_cents_pledged_to_view,is_paid,like_count,is_nsfw"

    suspend fun fetchStream(nextUrl: String? = null): FanboxResult<FanboxFeedPage> = withContext(Dispatchers.IO) {
        val base = "https://www.patreon.com/api/stream?include=user,campaign,images,attachments&fields[post]=$POST_FIELDS&fields[campaign]=creation_name,avatar_photo_url,url,vanity&fields[user]=full_name,image_url&page[count]=25&json-api-version=1.0"
        val url = nextUrl?.takeIf { isAllowedPatreonUrl(it) } ?: base
        requestJson(url, requireAuth = true) { parseStreamPage(it, base) }
    }

    suspend fun fetchCampaignPosts(campaignId: String, nextUrl: String? = null): FanboxResult<FanboxFeedPage> = withContext(Dispatchers.IO) {
        val cid = URLEncoder.encode(campaignId, "UTF-8")
        val base = "https://www.patreon.com/api/posts?filter[campaign_id]=$cid&filter[contains_exclusive_posts]=true&sort=-published_at&include=user,campaign,images,attachments&fields[post]=$POST_FIELDS&fields[campaign]=creation_name,avatar_photo_url,url,vanity&fields[user]=full_name,image_url&page[count]=30&json-api-version=1.0"
        val url = nextUrl?.takeIf { isAllowedPatreonUrl(it) } ?: base
        requestJson(url, requireAuth = false) { parseStreamPage(it, base) }
    }

    suspend fun fetchSupportingCreators(): FanboxResult<List<FanboxCreator>> = withContext(Dispatchers.IO) {
        val url = "https://www.patreon.com/api/current_user?include=pledges.creator.campaign,memberships.campaign&fields[campaign]=creation_name,avatar_photo_url,cover_photo_url,summary,url,vanity&fields[member]=patron_status,currently_entitled_amount_cents&json-api-version=1.0"
        requestJson(url, requireAuth = true) { root ->
            val included = root.optJSONArray("included") ?: JSONArray()
            val memberByCamp = HashMap<String, Pair<Boolean, Int>>()
            for (i in 0 until included.length()) {
                val m = included.optJSONObject(i) ?: continue
                val t = m.optString("type")
                if (t == "member" || t == "pledge") {
                    val attrs = m.optJSONObject("attributes")
                    val campId = m.optJSONObject("relationships")?.optJSONObject("campaign")?.optJSONObject("data")?.optString("id").orEmpty()
                    if (campId.isNotEmpty()) {
                        val status = attrs?.optString("patron_status").orEmpty()
                        val cents = attrs?.optInt("currently_entitled_amount_cents", 0)?.takeIf { it > 0 }
                            ?: attrs?.optInt("amount_cents", 0) ?: 0
                        memberByCamp[campId] = (status == "active_patron" || cents > 0) to (cents / 100)
                    }
                }
            }
            val list = mutableListOf<FanboxCreator>()
            for (i in 0 until included.length()) {
                val c = included.optJSONObject(i) ?: continue
                if (c.optString("type") != "campaign") continue
                val id = c.optString("id").ifBlank { continue }
                val attrs = c.optJSONObject("attributes") ?: continue
                val name = attrs.optString("creation_name").ifBlank { attrs.optString("vanity").ifBlank { "Patreon #$id" } }
                val (active, dollars) = memberByCamp[id] ?: (true to 0)
                list.add(
                    FanboxCreator(
                        creatorId = id,
                        userId = id,
                        name = name,
                        iconUrl = attrs.optString("avatar_photo_url").takeIf { it.isNotBlank() && it != "null" },
                        coverUrl = attrs.optString("cover_photo_url").takeIf { it.isNotBlank() && it != "null" },
                        description = AndroidRewardTools.stripHtml(attrs.optString("summary")),
                        isSupporting = active,
                        isFollowed = true,
                        planFee = dollars,
                        service = MobileFeedService.PATREON,
                        campaignUrl = attrs.optString("url").ifBlank { "https://www.patreon.com/" },
                    ),
                )
            }
            list.distinctBy { it.creatorId }
        }
    }

    suspend fun searchCreators(query: String): FanboxResult<List<FanboxCreator>> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        requestJson("https://www.patreon.com/api/search?q=$q", requireAuth = false) { root ->
            val data = root.optJSONArray("data") ?: JSONArray()
            buildList {
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val id = item.optString("id").ifBlank { continue }
                    val attrs = item.optJSONObject("attributes") ?: continue
                    val name = attrs.optString("creator_name").ifBlank { attrs.optString("campaign_name").ifBlank { continue } }
                    add(
                        FanboxCreator(
                            creatorId = id,
                            userId = id,
                            name = name,
                            iconUrl = attrs.optString("avatar_photo_url").takeIf { it.isNotBlank() && it != "null" },
                            coverUrl = attrs.optString("cover_photo_url").takeIf { it.isNotBlank() && it != "null" },
                            description = attrs.optString("creation_name"),
                            service = MobileFeedService.PATREON,
                            campaignUrl = attrs.optString("url").ifBlank { "https://www.patreon.com/" },
                        ),
                    )
                }
            }
        }
    }

    suspend fun fetchPostDetail(postId: String): FanboxResult<FanboxPostDetail> = withContext(Dispatchers.IO) {
        val pid = URLEncoder.encode(postId, "UTF-8")
        val url = "https://www.patreon.com/api/posts/$pid?include=attachments,images,media,user,campaign&fields[post]=$POST_FIELDS&fields[media]=image_urls,download_url,file_name,size_bytes,mimetype&fields[attachment]=name,url&fields[campaign]=creation_name,avatar_photo_url,url&fields[user]=full_name,image_url&json-api-version=1.0"
        requestJson(url, requireAuth = false) { root ->
            val data = root.optJSONObject("data") ?: throw IOException("Patreon 게시글을 찾을 수 없습니다.")
            val included = indexIncluded(root.optJSONArray("included"))
            val summary = parsePost(data, included) ?: throw IOException("Patreon 게시글이 비어 있습니다.")
            val attrs = data.optJSONObject("attributes")
            val (bodyText, extLinks, inlineImages) = parsePatreonBody(attrs)

            val rawByMediaId = LinkedHashMap<String, FanboxImage>()
            val images = mutableListOf<FanboxImage>()
            val files = mutableListOf<FanboxFile>()
            val seenUrls = hashSetOf<String>()
            val rels = data.optJSONObject("relationships")

            fun collectRel(relKey: String) {
                val arr = rels?.optJSONObject(relKey)?.optJSONArray("data") ?: return
                for (i in 0 until arr.length()) {
                    val r = arr.optJSONObject(i) ?: continue
                    val mid = r.optString("id")
                    val obj = included["${r.optString("type")}:$mid"] ?: continue
                    val ma = obj.optJSONObject("attributes") ?: continue
                    val mime = ma.optString("mimetype")
                    val imgUrls = ma.optJSONObject("image_urls")
                    val origUrl = imgUrls?.optNonNull("original")
                        ?: ma.optNonNull("download_url")
                        ?: imgUrls?.optNonNull("default")
                        ?: ma.optNonNull("url")
                    val thumbUrl = imgUrls?.optNonNull("default_small") ?: imgUrls?.optNonNull("default") ?: origUrl
                    val fileName = ma.optString("file_name").ifBlank { ma.optString("name").ifBlank { "patreon_$mid" } }
                    val size = ma.optLong("size_bytes", 0L)
                    if (origUrl != null && seenUrls.add(origUrl)) {
                        val isImg = mime.startsWith("image/", true) || imgUrls != null ||
                            fileName.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("jpg", "jpeg", "png", "webp", "gif")
                        if (isImg) {
                            val ext = fileName.substringAfterLast('.', "png").substringBefore('?').takeIf { it.length in 2..5 } ?: "png"
                            val fi = FanboxImage("${mid}_$i", ext, 0, 0, origUrl, thumbUrl ?: origUrl)
                            rawByMediaId[mid] = fi
                            images.add(fi)
                        } else {
                            val ext = fileName.substringAfterLast('.', "").takeIf { it.length in 1..6 }.orEmpty()
                            files.add(FanboxFile(mid, fileName, ext, size, origUrl))
                        }
                    }
                }
            }
            collectRel("images")
            collectRel("media")
            collectRel("attachments")

            val orderArr = attrs?.optJSONObject("post_metadata")?.optJSONArray("image_order")
            if (orderArr != null && orderArr.length() > 0 && rawByMediaId.isNotEmpty()) {
                val ordered = mutableListOf<FanboxImage>()
                val used = hashSetOf<FanboxImage>()
                for (i in 0 until orderArr.length()) {
                    val found = rawByMediaId[orderArr.optString(i)] ?: continue
                    if (used.add(found)) ordered.add(found)
                }
                images.forEach { if (used.add(it)) ordered.add(it) }
                images.clear()
                images.addAll(ordered)
            }

            for (inl in inlineImages) {
                val mid = inl.id.removePrefix("inline_")
                if (mid !in rawByMediaId && seenUrls.add(inl.originalUrl)) images.add(inl)
            }

            if (images.isEmpty()) {
                val cover = attrs?.optJSONObject("post_file")?.optNonNull("url")
                    ?: attrs?.optJSONObject("image")?.optNonNull("large_url")
                    ?: attrs?.optJSONObject("image")?.optNonNull("url")
                if (cover != null && seenUrls.add(cover)) {
                    images.add(FanboxImage("cover_${summary.id}", "jpg", 0, 0, cover, cover))
                }
            }

            FanboxPostDetail(summary, bodyText, images, files, summary.tags, extLinks)
        }
    }

    fun downloadToInputStream(url: String): InputStream {
        val safe = url.replace("[", "%5B").replace("]", "%5D")
        val conn = (URL(safe).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Referer", PatreonAndroidSession.WEB_REFERER)
            setRequestProperty("User-Agent", PatreonAndroidSession.userAgent())
            val c = PatreonAndroidSession.cookieHeader()
            if (c.isNotBlank()) setRequestProperty("Cookie", c)
        }
        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            throw IOException("Patreon 다운로드 실패 (HTTP ${conn.responseCode})")
        }
        return BufferedInputStream(conn.inputStream)
    }

    private fun <T> requestJson(url: String, requireAuth: Boolean, parse: (JSONObject) -> T): FanboxResult<T> {
        val cookie = PatreonAndroidSession.cookieHeader()
        if (requireAuth && !cookie.contains("session_id=")) {
            return FanboxResult.AuthRequired("Patreon 로그인이 필요합니다.")
        }
        val safeUrl = url.replace("[", "%5B").replace("]", "%5D")
        return try {
            val conn = (URL(safeUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Referer", PatreonAndroidSession.WEB_REFERER)
                setRequestProperty("User-Agent", PatreonAndroidSession.userAgent())
                if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            when {
                code == 401 || code == 403 -> FanboxResult.AuthRequired("Patreon 로그인이 필요합니다. (HTTP $code)")
                code !in 200..299 -> FanboxResult.Error("Patreon 오류 (HTTP $code)")
                else -> FanboxResult.Success(parse(JSONObject(body)))
            }
        } catch (e: Exception) {
            FanboxResult.Error("Patreon 네트워크 오류: ${e.localizedMessage ?: e.javaClass.simpleName}")
        }
    }

    private fun parseStreamPage(root: JSONObject, baseUrl: String): FanboxFeedPage {
        val data = root.optJSONArray("data") ?: JSONArray()
        val included = indexIncluded(root.optJSONArray("included"))
        val items = mutableListOf<FanboxPostSummary>()
        for (i in 0 until data.length()) {
            data.optJSONObject(i)?.let { parsePost(it, included) }?.let(items::add)
        }
        val nextCursor = root.optJSONObject("meta")?.optJSONObject("pagination")?.optJSONObject("cursors")?.optNonNull("next")
        val linksNext = root.optJSONObject("links")?.optNonNull("next")
        val next = when {
            items.isEmpty() -> null
            !nextCursor.isNullOrBlank() -> "$baseUrl&page[cursor]=${URLEncoder.encode(nextCursor, "UTF-8")}"
            !linksNext.isNullOrBlank() && isAllowedPatreonUrl(linksNext) -> linksNext
            else -> null
        }
        return FanboxFeedPage(items, next)
    }

    private fun indexIncluded(arr: JSONArray?): Map<String, JSONObject> {
        if (arr == null) return emptyMap()
        val map = HashMap<String, JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            map["${o.optString("type")}:${o.optString("id")}"] = o
        }
        return map
    }

    private fun parsePost(obj: JSONObject, included: Map<String, JSONObject>): FanboxPostSummary? {
        val id = obj.optString("id").ifBlank { return null }
        val attrs = obj.optJSONObject("attributes") ?: return null
        val rels = obj.optJSONObject("relationships")
        val campRef = rels?.optJSONObject("campaign")?.optJSONObject("data")
        val userRef = rels?.optJSONObject("user")?.optJSONObject("data")
        val campAttrs = campRef?.let { included["${it.optString("type")}:${it.optString("id")}"] }?.optJSONObject("attributes")
        val userAttrs = userRef?.let { included["${it.optString("type")}:${it.optString("id")}"] }?.optJSONObject("attributes")

        val creatorId = campRef?.optString("id")?.ifBlank { userRef?.optString("id").orEmpty() } ?: "patreon"
        val creatorName = campAttrs?.optNonNull("creation_name") ?: userAttrs?.optNonNull("full_name") ?: "Patreon 작가"
        val creatorIcon = campAttrs?.optNonNull("avatar_photo_url") ?: userAttrs?.optNonNull("image_url")
        val imgObj = attrs.optJSONObject("image")
        val cover = imgObj?.optNonNull("large_url") ?: imgObj?.optNonNull("thumb_url") ?: imgObj?.optNonNull("url")
        val canView = attrs.optBoolean("current_user_can_view", true)
        val (bodyText, extLinks, _) = parsePatreonBody(attrs)
        val postType = attrs.optString("post_type").ifBlank { if (cover != null) "image_file" else "text_only" }
        val normalizedType = if (postType.contains("text", true) && cover == null && extLinks.isEmpty()) "text" else "image"
        val rawCandidate = attrs.optNonNull("url") ?: attrs.optNonNull("patreon_url") ?: ""
        val customUrl = when {
            rawCandidate.startsWith("https://") -> rawCandidate
            rawCandidate.startsWith("/") -> "https://www.patreon.com$rawCandidate"
            else -> "https://www.patreon.com/posts/$id"
        }

        return FanboxPostSummary(
            id = id,
            title = attrs.optNonNull("title") ?: "제목 없는 Patreon 글",
            creatorId = creatorId,
            creatorName = creatorName,
            creatorIconUrl = creatorIcon,
            coverImageUrl = cover,
            excerpt = bodyText.replace(Regex("\\s+"), " ").take(150),
            publishedDatetime = attrs.optString("published_at"),
            feeRequired = attrs.optInt("min_cents_pledged_to_view", 0) / 100,
            isRestricted = !canView,
            hasAdultContent = attrs.optBoolean("is_nsfw", false),
            likeCount = attrs.optInt("like_count", 0),
            type = normalizedType,
            service = MobileFeedService.PATREON,
            customPostUrl = customUrl,
        )
    }

    private fun parsePatreonBody(attrs: JSONObject?): Triple<String, List<MobileExternalLink>, List<FanboxImage>> {
        if (attrs == null) return Triple("", emptyList(), emptyList())
        val links = linkedMapOf<String, MobileExternalLink>()
        val inlineImages = mutableListOf<FanboxImage>()

        fun addLink(label: String, rawHref: String) {
            val clean = AndroidRewardTools.cleanUrl(rawHref) ?: return
            if (!AndroidRewardTools.isInterestingLink(clean)) return
            val nice = label.trim().takeIf { it.isNotEmpty() && !it.startsWith("http", true) }
                ?: AndroidRewardTools.defaultLabel(clean)
            links.putIfAbsent(clean, MobileExternalLink(nice, clean))
        }

        var text = ""
        val cjs = attrs.optNonNull("content_json_string") ?: attrs.optNonNull("teaser_text_json_string")
        if (!cjs.isNullOrBlank()) {
            text = parseProseMirror(cjs, ::addLink, inlineImages)
        }
        val html = attrs.optNonNull("content").orEmpty()
        if (html.isNotBlank()) {
            val anchorRegex = Regex("""<a\b[^>]*\bhref\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            anchorRegex.findAll(html).forEach { m -> addLink(AndroidRewardTools.stripHtml(m.groupValues[2]), m.groupValues[1]) }
            if (text.isBlank()) text = AndroidRewardTools.stripHtml(html)
        }
        if (text.isBlank()) text = AndroidRewardTools.stripHtml(attrs.optNonNull("teaser_text").orEmpty())

        attrs.optJSONObject("embed")?.let { emb ->
            emb.optNonNull("url")?.let { u -> addLink(emb.optString("subject"), u) }
        }
        Regex("""https://[^\s<>"'()]+""").findAll(text).forEach { m ->
            AndroidRewardTools.cleanUrl(m.value)?.let { addLink("", it) }
        }

        return Triple(text.trim(), links.values.toList(), inlineImages)
    }

    private fun parseProseMirror(
        jsonStr: String,
        onLink: (String, String) -> Unit,
        inlineImages: MutableList<FanboxImage>,
    ): String = runCatching {
        val root = JSONObject(jsonStr)
        val blocks = mutableListOf<String>()
        var lastHeading = ""

        fun inlineText(arr: JSONArray?): String {
            if (arr == null) return ""
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val n = arr.optJSONObject(i) ?: continue
                when (n.optString("type")) {
                    "text" -> {
                        val t = n.optString("text")
                        sb.append(t)
                        val marks = n.optJSONArray("marks")
                        if (marks != null) {
                            for (mIdx in 0 until marks.length()) {
                                val mk = marks.optJSONObject(mIdx) ?: continue
                                if (mk.optString("type") == "link") {
                                    val href = mk.optJSONObject("attrs")?.optNonNull("href")
                                    if (!href.isNullOrBlank()) {
                                        val lbl = t.trim().takeIf { !it.startsWith("http", true) && !it.equals("link", true) }
                                            ?: lastHeading
                                        onLink(lbl, href)
                                    }
                                }
                            }
                        }
                    }
                    "hardBreak" -> sb.append('\n')
                    else -> sb.append(inlineText(n.optJSONArray("content")))
                }
            }
            return sb.toString()
        }

        fun visit(node: JSONObject) {
            when (node.optString("type")) {
                "doc" -> {
                    val c = node.optJSONArray("content") ?: return
                    for (i in 0 until c.length()) c.optJSONObject(i)?.let(::visit)
                }
                "heading" -> {
                    val line = inlineText(node.optJSONArray("content")).trim()
                    if (line.isNotEmpty()) {
                        if (!line.startsWith("http", true)) lastHeading = line.trimEnd(':')
                        blocks.add(line)
                    }
                }
                "paragraph" -> {
                    val line = inlineText(node.optJSONArray("content")).trim()
                    if (line.isNotEmpty()) blocks.add(line)
                }
                "bulletList" -> {
                    val c = node.optJSONArray("content") ?: return
                    val items = mutableListOf<String>()
                    for (i in 0 until c.length()) {
                        val li = c.optJSONObject(i)?.optJSONArray("content") ?: continue
                        val parts = mutableListOf<String>()
                        for (j in 0 until li.length()) {
                            val txt = inlineText(li.optJSONObject(j)?.optJSONArray("content")).trim()
                            if (txt.isNotEmpty()) parts.add(txt)
                        }
                        if (parts.isNotEmpty()) items.add("• " + parts.joinToString("\n  "))
                    }
                    if (items.isNotEmpty()) blocks.add(items.joinToString("\n"))
                }
                "horizontalRule" -> blocks.add("───────────────")
                "image" -> {
                    val ia = node.optJSONObject("attrs")
                    val src = ia?.optNonNull("src")
                    val mid = ia?.optString("media_id").orEmpty()
                    if (!src.isNullOrBlank()) {
                        inlineImages.add(FanboxImage(if (mid.isNotEmpty()) "inline_$mid" else "inline_${inlineImages.size}", "png", 0, 0, src, src))
                    }
                }
                else -> {
                    val line = inlineText(node.optJSONArray("content")).trim()
                    if (line.isNotEmpty()) blocks.add(line)
                }
            }
        }
        visit(root)
        blocks.joinToString("\n\n").replace("\u00A0", " ")
    }.getOrDefault("")

    private fun isAllowedPatreonUrl(url: String): Boolean {
        val safe = url.trim().replace("[", "%5B").replace("]", "%5D")
        val uri = runCatching { URI(safe) }.getOrNull() ?: return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        return uri.scheme.equals("https", true) &&
            (host == "patreon.com" || host.endsWith(".patreon.com") || host == "patreonusercontent.com" || host.endsWith(".patreonusercontent.com"))
    }

    private fun JSONObject.optNonNull(key: String): String? =
        if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() && !it.equals("null", true) }
}

internal object AndroidRewardTools {
    private val directExts = setOf(
        "zip", "7z", "rar", "psd", "psb", "clip", "pdf",
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "mp4",
    )
    private val ignoredHosts = setOf(
        "twitter.com", "www.twitter.com", "x.com", "www.x.com",
        "instagram.com", "www.instagram.com", "facebook.com", "www.facebook.com",
        "tiktok.com", "www.tiktok.com", "discord.gg", "discord.com",
    )

    fun cleanUrl(raw: String): String? {
        val u = raw.trim().replace("&#038;", "&").replace("&amp;", "&").trimEnd('.', ',', ')', ']', '>')
        val uri = runCatching { URI(u) }.getOrNull() ?: return null
        return if (uri.scheme.equals("https", true) && !uri.host.isNullOrBlank()) u else null
    }

    fun isInterestingLink(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        if (host in ignoredHosts) return false
        if (host == "patreon.com" || host.endsWith(".patreon.com")) return uri.path.orEmpty().contains("/file")
        if (host == "fanbox.cc" || host.endsWith(".fanbox.cc")) return false
        return true
    }

    fun classifyUrl(url: String): MobileExternalLinkKind {
        val uri = runCatching { URI(url) }.getOrNull()
        val host = uri?.host?.lowercase(Locale.ROOT).orEmpty()
        val ext = uri?.path.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            host == "dropbox.com" || host.endsWith(".dropbox.com") || host.endsWith(".dropboxusercontent.com") -> MobileExternalLinkKind.DROPBOX
            host == "drive.google.com" || host == "drive.usercontent.google.com" -> MobileExternalLinkKind.GOOGLE_DRIVE
            host == "mega.nz" || host.endsWith(".mega.nz") || host == "mega.co.nz" -> MobileExternalLinkKind.MEGA
            ext in directExts -> MobileExternalLinkKind.DIRECT_FILE
            else -> MobileExternalLinkKind.PATREON_GATE
        }
    }

    fun defaultLabel(url: String): String = when (classifyUrl(url)) {
        MobileExternalLinkKind.DROPBOX -> "Dropbox 보상 다운로드"
        MobileExternalLinkKind.GOOGLE_DRIVE -> "Google Drive 보상 다운로드"
        MobileExternalLinkKind.MEGA -> "MEGA 보상 다운로드"
        MobileExternalLinkKind.DIRECT_FILE -> url.substringBefore('?').substringAfterLast('/').ifBlank { "파일 다운로드" }
        MobileExternalLinkKind.PATREON_GATE -> "${runCatching { URI(url).host }.getOrNull() ?: "외부"} 보상 페이지"
    }

    fun normalizeDirectUrl(rawUrl: String): String {
        val clean = cleanUrl(rawUrl) ?: rawUrl.trim()
        val uri = runCatching { URI(clean) }.getOrNull() ?: return clean
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        if (host == "dropbox.com" || host.endsWith(".dropbox.com")) {
            val base = clean.substringBefore('?').substringBefore('#')
            val q = clean.substringAfter('?', "").substringBefore('#')
            val kept = q.split('&').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("dl=", true) }.toMutableList()
            kept.add("dl=1")
            return "$base?${kept.joinToString("&")}"
        }
        if (host == "drive.google.com") {
            val fid = Regex("""/file/d/([A-Za-z0-9_-]+)""").find(uri.path.orEmpty())?.groupValues?.get(1)
                ?: Regex("""(?:^|&)id=([A-Za-z0-9_-]+)""").find(uri.rawQuery.orEmpty())?.groupValues?.get(1)
            if (!fid.isNullOrBlank()) return "https://drive.usercontent.google.com/download?id=$fid&export=download&confirm=t"
        }
        return clean
    }

    fun parseGateHtml(baseUrl: String, html: String): Pair<List<MobileResolvedReward>, String?> {
        val baseUri = runCatching { URI(baseUrl) }.getOrNull()
        var unlockUrl: String? = null
        val rewards = linkedMapOf<String, MobileResolvedReward>()
        var heading = ""
        val regex = Regex(
            """<(h[1-6]|strong)\b[^>]*>(.*?)</\1>|<a\b[^>]*\bhref\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        for (m in regex.findAll(html)) {
            if (m.groupValues[1].isNotEmpty()) {
                val h = stripHtml(m.groupValues[2]).replace(Regex("\\s+"), " ").trim()
                if (h.length in 2..90 && !h.equals("link", true) && !h.equals("link:", true)) heading = h.trimEnd(':')
                continue
            }
            val hrefRaw = m.groupValues[3].replace("&#038;", "&").replace("&amp;", "&").trim()
            val resolved = runCatching {
                if (hrefRaw.startsWith("https://", true)) hrefRaw else baseUri?.resolve(hrefRaw)?.toString()
            }.getOrNull()?.let(::cleanUrl) ?: continue
            val low = resolved.lowercase(Locale.ROOT)
            if (low.contains("patreon-unlock-post=") || low.contains("/patreon-flow/") || low.contains("patreon.com/oauth2/")) {
                if (unlockUrl == null || low.contains("patreon-unlock-post=")) unlockUrl = resolved
                continue
            }
            val kind = classifyUrl(resolved)
            if (kind != MobileExternalLinkKind.PATREON_GATE) {
                val anchor = stripHtml(m.groupValues[4]).replace(Regex("\\s+"), " ").trim()
                val generic = anchor.isEmpty() || anchor.startsWith("http", true) || anchor.lowercase(Locale.ROOT) in setOf("download", "dropbox", "link", "here")
                val label = when {
                    !generic && heading.isNotEmpty() -> "$heading · $anchor"
                    !generic -> anchor
                    heading.isNotEmpty() -> "$heading (${kind.badge})"
                    else -> defaultLabel(resolved)
                }
                val direct = normalizeDirectUrl(resolved)
                rewards.putIfAbsent(direct, MobileResolvedReward(label, resolved, direct, kind))
            }
        }
        return rewards.values.toList() to unlockUrl
    }

    suspend fun checkExternalLink(link: MobileExternalLink): MobileGateCheckResult = withContext(Dispatchers.IO) {
        if (link.kind != MobileExternalLinkKind.PATREON_GATE) {
            return@withContext MobileGateCheckResult.Ready(
                listOf(MobileResolvedReward(link.label, link.url, normalizeDirectUrl(link.url), link.kind)),
            )
        }
        val uri = runCatching { URI(link.url) }.getOrNull()
            ?: return@withContext MobileGateCheckResult.Failed("잘못된 주소입니다.")
        val gateHost = uri.host?.lowercase(Locale.ROOT).orEmpty()
        try {
            val savedCookie = runCatching { CookieManager.getInstance().getCookie(link.url) }.getOrNull().orEmpty()
            val conn = (URL(link.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", PatreonAndroidSession.userAgent())
                if (savedCookie.isNotBlank()) setRequestProperty("Cookie", savedCookie)
            }
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            val finalUrl = conn.url.toString()
            conn.disconnect()
            val (rewards, unlockUrl) = parseGateHtml(finalUrl, html)
            if (rewards.isNotEmpty()) MobileGateCheckResult.Ready(rewards)
            else MobileGateCheckResult.NeedsWebUnlock(unlockUrl ?: link.url, gateHost)
        } catch (_: Exception) {
            MobileGateCheckResult.NeedsWebUnlock(link.url, gateHost)
        }
    }

    suspend fun downloadAndImportRewards(
        store: LibraryStore,
        draft: PostDraft,
        rewards: List<MobileResolvedReward>,
        passwordCandidates: List<String>,
        onProgress: (String) -> Unit,
    ): ImportReport = withContext(Dispatchers.IO) {
        var added = 0
        var reused = 0
        var skipped = 0
        val warnings = mutableListOf<String>()
        val tempDir = Files.createTempDirectory("fanbox-mob-reward-").toFile()
        try {
            for ((idx, item) in rewards.withIndex()) {
                if (!isActive) throw CancellationException()
                val dlFile = File(tempDir, "dl_$idx.bin")
                var fileName = ""
                if (item.kind == MobileExternalLinkKind.MEGA) {
                    fileName = MegaDownloader.downloadDecryptedToFile(item.originalUrl, dlFile) { dl, tot, fn ->
                        val mb = dl / 1048576.0
                        val pct = if (tot > 0) " (${(dl * 100 / tot).coerceIn(0, 100)}%)" else ""
                        onProgress("MEGA 복호화 중 · $fn · %.1f MB%s".format(mb, pct))
                    }
                } else {
                    val conn = (URL(item.directDownloadUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 20_000
                        readTimeout = 300_000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", PatreonAndroidSession.userAgent())
                    }
                    if (conn.responseCode !in 200..299) {
                        conn.disconnect()
                        throw IOException("다운로드 실패 (HTTP ${conn.responseCode})")
                    }
                    val ct = conn.contentType.orEmpty().lowercase(Locale.ROOT)
                    if (ct.contains("text/html")) {
                        conn.disconnect()
                        throw IOException("직접 파일 대신 웹 페이지가 반환되었습니다.")
                    }
                    val cd = conn.getHeaderField("Content-Disposition").orEmpty()
                    fileName = Regex("""filename\*=UTF-8''([^;\r\n]+)""", RegexOption.IGNORE_CASE).find(cd)?.groupValues?.get(1)
                        ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
                        ?: Regex("""filename="?([^";\r\n]+)"?""", RegexOption.IGNORE_CASE).find(cd)?.groupValues?.get(1)
                        ?: "reward"
                    val total = conn.contentLengthLong
                    conn.inputStream.use { inp ->
                        FileOutputStream(dlFile).use { out ->
                            val buf = ByteArray(64 * 1024)
                            var dl = 0L
                            var tick = 0L
                            while (true) {
                                if (!isActive) throw CancellationException()
                                val r = inp.read(buf)
                                if (r < 0) break
                                out.write(buf, 0, r)
                                dl += r
                                val now = System.currentTimeMillis()
                                if (now - tick >= 250L) {
                                    tick = now
                                    val pct = if (total > 0) " (${(dl * 100 / total).coerceIn(0, 100)}%)" else ""
                                    onProgress("${item.label} 다운로드 중 · %.1f MB%s".format(dl / 1048576.0, pct))
                                }
                            }
                        }
                    }
                    conn.disconnect()
                }

                val rep = ingestMobileFile(store, draft, dlFile, fileName, File(tempDir, "w_$idx"), passwordCandidates, onProgress)
                added += rep.added
                reused += rep.reused
                skipped += rep.skipped
                warnings.addAll(rep.warnings)
                dlFile.delete()
            }
        } finally {
            tempDir.deleteRecursively()
        }
        ImportReport(added, reused, skipped, warnings)
    }

    private fun ingestMobileFile(
        store: LibraryStore,
        draft: PostDraft,
        file: File,
        suggestedName: String,
        workDir: File,
        passwords: List<String>,
        onProgress: (String) -> Unit,
    ): ImportReport {
        val magic = ByteArray(12)
        val n = file.inputStream().use { it.read(magic) }.coerceAtLeast(0)
        val isZip = n >= 4 && magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte()
        val is7z = ArchiveUnpacker.isSevenZipMagic(magic, n)
        val isPdf = n >= 4 && magic[0] == '%'.code.toByte() && magic[1] == 'P'.code.toByte() && magic[2] == 'D'.code.toByte()

        if (isZip || is7z) {
            val unpacked: List<UnpackedEntryFile> = if (is7z) {
                ArchiveUnpacker.extractSevenZip(file, workDir, passwords, onProgress)
            } else {
                ArchiveUnpacker.extractZipWithPasswords(file, workDir, passwords, onProgress)
            }
            var added = 0
            var reused = 0
            val batch = mutableListOf<Triple<File, String, String>>()
            for ((i, u) in unpacked.withIndex()) {
                val ext = u.leafName.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (ext == "pdf") {
                    val pr = renderAndroidPdfAndImport(store, draft, u.file, u.leafName, File(workDir, "pdf_$i"), onProgress)
                    added += pr.added; reused += pr.reused
                    u.file.delete()
                } else if (ext in directExts) {
                    val isImg = ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif")
                    val name = if (isImg) "%03d_%s".format(i + 1, u.leafName) else u.leafName
                    val hint = if (u.displayPath.contains("textless", true)) "textless/${u.displayPath}" else "원본/${u.displayPath}"
                    batch.add(Triple(u.file, name, hint))
                }
            }
            if (batch.isNotEmpty()) {
                val rep = store.importManaged(draft, batch.map { (f, nm, h) -> ImportSource(nm, h) { f.inputStream() } })
                added += rep.added; reused += rep.reused
            }
            return ImportReport(added, reused)
        }

        if (isPdf) {
            val nm = if (suggestedName.endsWith(".pdf", true)) suggestedName else "$suggestedName.pdf"
            return renderAndroidPdfAndImport(store, draft, file, nm, workDir, onProgress)
        }

        return store.importManaged(draft, listOf(ImportSource(suggestedName, "원본/$suggestedName") { file.inputStream() }))
    }

    private fun renderAndroidPdfAndImport(
        store: LibraryStore,
        draft: PostDraft,
        pdfFile: File,
        pdfName: String,
        outDir: File,
        onProgress: (String) -> Unit,
    ): ImportReport {
        outDir.mkdirs()
        val pages = mutableListOf<File>()
        runCatching {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val count = renderer.pageCount
                    for (i in 0 until count) {
                        renderer.openPage(i).use { page ->
                            onProgress("PDF 페이지 변환 중 (${i + 1}/$count)…")
                            val w = (page.width * 2.4f).toInt().coerceIn(600, 3200)
                            val h = (page.height * 2.4f).toInt().coerceIn(600, 4600)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            Canvas(bmp).drawColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val outFile = File(outDir, "%03d_page_%03d.jpg".format(i + 1, i + 1))
                            FileOutputStream(outFile).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                            bmp.recycle()
                            pages.add(outFile)
                        }
                    }
                }
            }
        }
        val sources = pages.map { pf -> ImportSource(pf.name, "원본/${pf.name}") { pf.inputStream() } } +
            ImportSource(pdfName, "원본/$pdfName") { pdfFile.inputStream() }
        return store.importManaged(draft, sources)
    }

    fun stripHtml(html: String): String =
        html.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .trim()
}
