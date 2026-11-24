package app.fanboxlibrary.desktop

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences

internal data class ParsedPatreonContent(
    val text: String,
    val links: List<DesktopPostExternalLink>,
    val inlineImages: List<DesktopFanboxImage>,
)

internal object DesktopPatreonSession {
    private val prefs: Preferences = Preferences.userRoot().node("app/fanboxlibrary/desktop/patreon")
    const val WEB_ORIGIN = "https://www.patreon.com"
    const val WEB_REFERER = "https://www.patreon.com/"
    const val USER_AGENT = DesktopFanboxSession.USER_AGENT

    fun rawCookie(): String = prefs.get("cookie", "").trim()
    fun hasLogin(): Boolean = rawCookie().contains("session_id=")

    fun saveCookie(input: String) {
        val trimmed = input.trim().removeSurrounding("\"").removeSurrounding("'")
        val normalized = when {
            trimmed.isEmpty() -> ""
            trimmed.contains("session_id=") -> trimmed
            else -> "session_id=$trimmed"
        }
        prefs.put("cookie", normalized)
    }

    fun clearSession() {
        prefs.remove("cookie")
    }
}

internal object DesktopPatreonClient {
    private const val POST_FIELDS =
        "title,content,content_json_string,teaser_text,teaser_text_json_string,embed,published_at,url,patreon_url,post_type,post_metadata,post_file,current_user_can_view,image,min_cents_pledged_to_view,is_paid,like_count,is_nsfw"

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun fetchStream(nextUrl: String? = null): DesktopFanboxResult<DesktopFanboxFeedPage> {
        val baseUrl = "https://www.patreon.com/api/stream?include=user,campaign,images,attachments&fields[post]=$POST_FIELDS&fields[campaign]=creation_name,avatar_photo_url,url,vanity&fields[user]=full_name,image_url&page[count]=25&json-api-version=1.0"
        val url = nextUrl?.takeIf { isAllowedPatreonUrl(it) } ?: baseUrl
        return requestJson(url, requireAuth = true) { parseStreamPage(it, baseUrl) }
    }

    fun fetchCampaignPosts(campaignId: String, nextUrl: String? = null): DesktopFanboxResult<DesktopFanboxFeedPage> {
        val cid = URLEncoder.encode(campaignId, "UTF-8")
        val baseUrl = "https://www.patreon.com/api/posts?filter[campaign_id]=$cid&filter[contains_exclusive_posts]=true&sort=-published_at&include=user,campaign,images,attachments&fields[post]=$POST_FIELDS&fields[campaign]=creation_name,avatar_photo_url,url,vanity&fields[user]=full_name,image_url&page[count]=30&json-api-version=1.0"
        val url = nextUrl?.takeIf { isAllowedPatreonUrl(it) } ?: baseUrl
        return requestJson(url, requireAuth = false) { parseStreamPage(it, baseUrl) }
    }

    fun fetchSupportingCreators(): DesktopFanboxResult<List<DesktopFanboxCreator>> {
        val url = "https://www.patreon.com/api/current_user?include=pledges.creator.campaign,memberships.campaign&fields[campaign]=creation_name,avatar_photo_url,cover_photo_url,summary,url,vanity&fields[member]=patron_status,currently_entitled_amount_cents&json-api-version=1.0"
        return requestJson(url, requireAuth = true) { root ->
            val included = (root["included"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
            val memberByCampaign = hashMapOf<String, Pair<Boolean, Int>>()
            included.filter { it.str("type") == "member" || it.str("type") == "pledge" }.forEach { m ->
                val attrs = m["attributes"] as? Map<*, *>
                val rels = m["relationships"] as? Map<*, *>
                val campId = ((rels?.get("campaign") as? Map<*, *>)?.get("data") as? Map<*, *>)?.str("id").orEmpty()
                if (campId.isNotEmpty()) {
                    val status = attrs?.str("patron_status").orEmpty()
                    val cents = attrs?.intVal("currently_entitled_amount_cents") ?: attrs?.intVal("amount_cents") ?: 0
                    val active = status == "active_patron" || cents > 0
                    memberByCampaign[campId] = active to (cents / 100)
                }
            }
            included.filter { it.str("type") == "campaign" }.mapNotNull { c ->
                val id = c.str("id").ifBlank { return@mapNotNull null }
                val attrs = c["attributes"] as? Map<*, *> ?: return@mapNotNull null
                val name = attrs.str("creation_name").ifBlank { attrs.str("vanity").ifBlank { "Patreon #$id" } }
                val (active, dollars) = memberByCampaign[id] ?: (true to 0)
                DesktopFanboxCreator(
                    creatorId = id,
                    userId = id,
                    name = name,
                    iconUrl = attrs.optStr("avatar_photo_url"),
                    coverUrl = attrs.optStr("cover_photo_url"),
                    description = stripHtml(attrs.str("summary")),
                    isSupporting = active,
                    isFollowed = true,
                    planFee = dollars,
                    service = FeedService.PATREON,
                    campaignUrl = attrs.str("url").ifBlank { "https://www.patreon.com/c/${attrs.str("vanity")}" },
                )
            }.distinctBy { it.creatorId }
        }
    }

    fun searchCreators(query: String): DesktopFanboxResult<List<DesktopFanboxCreator>> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "https://www.patreon.com/api/search?q=$q"
        return requestJson(url, requireAuth = false) { root ->
            val data = (root["data"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
            data.mapNotNull { item ->
                val id = item.str("id").ifBlank { return@mapNotNull null }
                val attrs = item["attributes"] as? Map<*, *> ?: return@mapNotNull null
                val name = attrs.str("creator_name").ifBlank { attrs.str("campaign_name").ifBlank { return@mapNotNull null } }
                DesktopFanboxCreator(
                    creatorId = id,
                    userId = id,
                    name = name,
                    iconUrl = attrs.optStr("avatar_photo_url") ?: attrs.optStr("image_url"),
                    coverUrl = attrs.optStr("cover_photo_url"),
                    description = attrs.str("creation_name"),
                    isSupporting = false,
                    isFollowed = false,
                    service = FeedService.PATREON,
                    campaignUrl = attrs.str("url").ifBlank { "https://www.patreon.com/" },
                )
            }
        }
    }

    fun fetchPostDetail(postId: String): DesktopFanboxResult<DesktopFanboxPostDetail> {
        val pid = URLEncoder.encode(postId, "UTF-8")
        val url = "https://www.patreon.com/api/posts/$pid?include=attachments,images,media,user,campaign&fields[post]=$POST_FIELDS&fields[media]=image_urls,download_url,file_name,size_bytes,mimetype&fields[attachment]=name,url&fields[campaign]=creation_name,avatar_photo_url,url&fields[user]=full_name,image_url&json-api-version=1.0"
        return requestJson(url, requireAuth = false) { root ->
            val data = root["data"] as? Map<*, *> ?: throw IOException("Patreon 게시글 정보를 찾을 수 없습니다.")
            val included = indexIncluded(root["included"] as? List<*>)
            val summary = parsePost(data, included) ?: throw IOException("Patreon 게시글 데이터가 비어 있습니다.")
            val attrs = data["attributes"] as? Map<*, *>
            val parsedBody = parsePatreonAttributes(attrs)

            val rawImagesByMediaId = linkedMapOf<String, DesktopFanboxImage>()
            val images = mutableListOf<DesktopFanboxImage>()
            val files = mutableListOf<DesktopFanboxFile>()
            val seenUrls = hashSetOf<String>()

            // 1. Collect media / images / attachments from included
            val rels = data["relationships"] as? Map<*, *>
            fun collectMediaRel(relKey: String) {
                val relList = ((rels?.get(relKey) as? Map<*, *>)?.get("data") as? List<*>).orEmpty()
                relList.forEachIndexed { idx, ref ->
                    val r = ref as? Map<*, *> ?: return@forEachIndexed
                    val mediaId = r.str("id")
                    val obj = included["${r.str("type")}:$mediaId"] ?: return@forEachIndexed
                    val ma = obj["attributes"] as? Map<*, *> ?: return@forEachIndexed
                    val mime = ma.str("mimetype")
                    val imgUrls = ma["image_urls"] as? Map<*, *>
                    val origUrl = imgUrls?.optStr("original")
                        ?: ma.optStr("download_url")
                        ?: imgUrls?.optStr("default")
                        ?: ma.optStr("url")
                    val thumbUrl = imgUrls?.optStr("default_small") ?: imgUrls?.optStr("default") ?: origUrl
                    val fileName = ma.str("file_name").ifBlank { ma.str("name").ifBlank { "patreon_$mediaId" } }
                    val size = ma.longVal("size_bytes")
                    if (origUrl != null && seenUrls.add(origUrl)) {
                        val isImage = mime.startsWith("image/", true) || imgUrls != null ||
                            fileName.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif")
                        if (isImage) {
                            val ext = fileName.substringAfterLast('.', "png").substringBefore('?').takeIf { it.length in 2..5 } ?: "png"
                            val img = DesktopFanboxImage("${mediaId}_$idx", ext, 0, 0, origUrl, thumbUrl ?: origUrl)
                            rawImagesByMediaId[mediaId] = img
                            images.add(img)
                        } else {
                            val ext = fileName.substringAfterLast('.', "").takeIf { it.length in 1..6 }.orEmpty()
                            files.add(DesktopFanboxFile(mediaId, fileName, ext, size, origUrl))
                        }
                    }
                }
            }
            collectMediaRel("images")
            collectMediaRel("media")
            collectMediaRel("attachments")

            // Honor post_metadata.image_order if Patreon specifies exact page order
            val imageOrder = ((attrs?.get("post_metadata") as? Map<*, *>)?.get("image_order") as? List<*>)
                ?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
                .orEmpty()
            if (imageOrder.isNotEmpty() && rawImagesByMediaId.isNotEmpty()) {
                val ordered = mutableListOf<DesktopFanboxImage>()
                val used = hashSetOf<DesktopFanboxImage>()
                for (mid in imageOrder) {
                    val found = rawImagesByMediaId[mid] ?: continue
                    if (used.add(found)) ordered.add(found)
                }
                for (rem in images) {
                    if (used.add(rem)) ordered.add(rem)
                }
                images.clear()
                images.addAll(ordered)
            }

            // Include inline ProseMirror images if not already present
            for (inlineImg in parsedBody.inlineImages) {
                val mid = inlineImg.id.removePrefix("inline_")
                if (mid !in rawImagesByMediaId && seenUrls.add(inlineImg.originalUrl)) {
                    images.add(inlineImg)
                }
            }

            // 2. Cover / post_file fallback if images list is still empty
            if (images.isEmpty()) {
                val postFileObj = attrs?.get("post_file") as? Map<*, *>
                val coverObj = attrs?.get("image") as? Map<*, *>
                val coverUrl = postFileObj?.optStr("url") ?: coverObj?.optStr("large_url") ?: coverObj?.optStr("url")
                if (coverUrl != null && seenUrls.add(coverUrl)) {
                    images.add(DesktopFanboxImage("cover_${summary.id}", "jpg", 0, 0, coverUrl, coverUrl))
                }
            }

            DesktopFanboxPostDetail(
                summary = summary,
                text = parsedBody.text,
                images = images,
                files = files,
                tags = summary.tags,
                externalLinks = parsedBody.links,
            )
        }
    }

    internal fun parsePatreonAttributes(attrs: Map<*, *>?): ParsedPatreonContent {
        if (attrs == null) return ParsedPatreonContent("", emptyList(), emptyList())
        val links = linkedMapOf<String, DesktopPostExternalLink>()
        val inlineImages = mutableListOf<DesktopFanboxImage>()

        fun recordLink(label: String, rawHref: String) {
            val cleaned = ExternalRewardDownloader.cleanExtractedUrl(rawHref) ?: return
            if (!ExternalRewardDownloader.isInterestingExternalPostLink(cleaned)) return
            val niceLabel = label.trim()
                .takeIf { it.isNotEmpty() && !it.equals(cleaned, ignoreCase = true) && !it.startsWith("http", ignoreCase = true) }
                ?: ExternalRewardDownloader.defaultLabelForUrl(cleaned)
            val existing = links[cleaned]
            if (existing == null || existing.label == ExternalRewardDownloader.defaultLabelForUrl(cleaned)) {
                links[cleaned] = DesktopPostExternalLink(niceLabel, cleaned)
            }
        }

        // 1. Modern Patreon ProseMirror content_json_string (used by 2025-2026 Patreon editor)
        val cjs = attrs.optStr("content_json_string") ?: attrs.optStr("teaser_text_json_string")
        var text = ""
        if (!cjs.isNullOrBlank()) {
            val pm = parseProseMirrorJson(cjs, ::recordLink, inlineImages)
            if (pm.isNotBlank()) text = pm
        }

        // 2. Legacy HTML content fallback
        val html = attrs.optStr("content").orEmpty()
        if (html.isNotBlank()) {
            extractHtmlAnchors(html, ::recordLink)
            if (text.isBlank()) text = stripHtml(html)
        }

        // 3. Teaser text fallback
        if (text.isBlank()) {
            text = stripHtml(attrs.str("teaser_text"))
        }

        // 4. Embed link (if post is a link/embed post)
        val embed = attrs["embed"] as? Map<*, *>
        if (embed != null) {
            val embedUrl = embed.optStr("url")
            val embedSubj = embed.str("subject").ifBlank { embed.str("description") }
            if (embedUrl != null) recordLink(embedSubj, embedUrl)
        }

        // 5. Also pick up any bare URLs inside the text
        ExternalRewardDownloader.extractBareUrlsFromText(text).forEach { bare ->
            recordLink(bare.label, bare.url)
        }

        return ParsedPatreonContent(text.trim(), links.values.toList(), inlineImages)
    }

    internal fun parseProseMirrorJson(
        jsonString: String,
        onLink: (label: String, href: String) -> Unit = { _, _ -> },
        inlineImages: MutableList<DesktopFanboxImage> = mutableListOf(),
    ): String {
        val root = runCatching { SimpleJson.parse(jsonString) as? Map<*, *> }.getOrNull() ?: return ""
        val blocks = mutableListOf<String>()
        var lastHeading = ""

        fun renderInline(nodes: List<*>?): String {
            if (nodes.isNullOrEmpty()) return ""
            val sb = StringBuilder()
            for (item in nodes) {
                val n = item as? Map<*, *> ?: continue
                when (n.str("type")) {
                    "text" -> {
                        val t = n.str("text")
                        sb.append(t)
                        val marks = n["marks"] as? List<*>
                        marks?.forEach { m ->
                            val mark = m as? Map<*, *> ?: return@forEach
                            if (mark.str("type") == "link") {
                                val href = (mark["attrs"] as? Map<*, *>)?.optStr("href")
                                if (!href.isNullOrBlank()) {
                                    val candidateLabel = t.trim().takeIf {
                                        it.isNotEmpty() && !it.startsWith("http", ignoreCase = true) &&
                                            !it.equals("link", ignoreCase = true) && !it.equals("link:", ignoreCase = true)
                                    } ?: lastHeading.takeIf {
                                        it.isNotEmpty() && !it.equals("link", ignoreCase = true) && !it.equals("link:", ignoreCase = true)
                                    }.orEmpty()
                                    onLink(candidateLabel, href)
                                    if (!t.contains("http", ignoreCase = true)) {
                                        sb.append(" ($href)")
                                    }
                                }
                            }
                        }
                    }
                    "hardBreak" -> sb.append('\n')
                    else -> {
                        val nested = n["content"] as? List<*>
                        if (nested != null) sb.append(renderInline(nested))
                    }
                }
            }
            return sb.toString()
        }

        fun visitBlock(node: Map<*, *>, listPrefix: String = "") {
            when (node.str("type")) {
                "doc" -> (node["content"] as? List<*>)?.forEach { (it as? Map<*, *>)?.let { child -> visitBlock(child) } }
                "heading" -> {
                    val line = renderInline(node["content"] as? List<*>).trim()
                    if (line.isNotEmpty()) {
                        if (!line.startsWith("http", ignoreCase = true)) lastHeading = line.trimEnd(':').trim()
                        blocks.add(line)
                    }
                }
                "paragraph" -> {
                    val line = renderInline(node["content"] as? List<*>).trim()
                    if (line.isNotEmpty()) {
                        blocks.add(if (listPrefix.isNotEmpty()) "$listPrefix$line" else line)
                    }
                }
                "blockquote" -> {
                    val before = blocks.size
                    (node["content"] as? List<*>)?.forEach { (it as? Map<*, *>)?.let { child -> visitBlock(child, "│ ") } }
                    if (blocks.size == before) {
                        val line = renderInline(node["content"] as? List<*>).trim()
                        if (line.isNotEmpty()) blocks.add("│ $line")
                    }
                }
                "bulletList" -> {
                    val items = mutableListOf<String>()
                    (node["content"] as? List<*>)?.forEach { li ->
                        val liMap = li as? Map<*, *> ?: return@forEach
                        val subLines = mutableListOf<String>()
                        (liMap["content"] as? List<*>)?.forEach { p ->
                            val pMap = p as? Map<*, *> ?: return@forEach
                            val txt = renderInline(pMap["content"] as? List<*>).trim()
                            if (txt.isNotEmpty()) subLines.add(txt)
                        }
                        if (subLines.isNotEmpty()) items.add("• " + subLines.joinToString("\n  "))
                    }
                    if (items.isNotEmpty()) blocks.add(items.joinToString("\n"))
                }
                "orderedList" -> {
                    val items = mutableListOf<String>()
                    var idx = 1
                    (node["content"] as? List<*>)?.forEach { li ->
                        val liMap = li as? Map<*, *> ?: return@forEach
                        val subLines = mutableListOf<String>()
                        (liMap["content"] as? List<*>)?.forEach { p ->
                            val pMap = p as? Map<*, *> ?: return@forEach
                            val txt = renderInline(pMap["content"] as? List<*>).trim()
                            if (txt.isNotEmpty()) subLines.add(txt)
                        }
                        if (subLines.isNotEmpty()) items.add("${idx++}. " + subLines.joinToString("\n   "))
                    }
                    if (items.isNotEmpty()) blocks.add(items.joinToString("\n"))
                }
                "horizontalRule" -> blocks.add("───────────────")
                "image" -> {
                    val ia = node["attrs"] as? Map<*, *>
                    val src = ia?.optStr("src")
                    val mediaId = ia?.optStr("media_id").orEmpty()
                    if (!src.isNullOrBlank()) {
                        val ext = src.substringBefore('?').substringAfterLast('.', "png").takeIf { it.length in 2..5 } ?: "png"
                        inlineImages.add(
                            DesktopFanboxImage(
                                id = if (mediaId.isNotEmpty()) "inline_$mediaId" else "inline_${inlineImages.size + 1}",
                                extension = ext,
                                width = ia.intVal("media_width"),
                                height = ia.intVal("media_height"),
                                originalUrl = src,
                                thumbnailUrl = src,
                            ),
                        )
                    }
                    val linkHref = ia?.optStr("link")
                    if (!linkHref.isNullOrBlank()) onLink(ia.str("caption").ifBlank { lastHeading }, linkHref)
                }
                else -> {
                    val children = node["content"] as? List<*>
                    if (children != null) {
                        val inline = renderInline(children).trim()
                        if (inline.isNotEmpty()) blocks.add(inline)
                    }
                }
            }
        }

        visitBlock(root)
        return blocks.joinToString("\n\n").replace("\u00A0", " ").trim()
    }

    private fun extractHtmlAnchors(html: String, onLink: (String, String) -> Unit) {
        val regex = Regex("""<a\b[^>]*\bhref\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        for (m in regex.findAll(html)) {
            val href = m.groupValues[1]
            val inner = stripHtml(m.groupValues[2])
            onLink(inner, href)
        }
    }

    fun openDownloadStream(url: String): InputStream {
        require(isAllowedPatreonUrl(url)) { "허용되지 않은 Patreon 다운로드 주소입니다." }
        val req = Request.Builder()
            .url(url)
            .header("Referer", DesktopPatreonSession.WEB_REFERER)
            .header("User-Agent", DesktopPatreonSession.USER_AGENT)
            .apply {
                val c = DesktopPatreonSession.rawCookie()
                if (c.isNotBlank()) header("Cookie", c)
            }
            .build()
        val resp = http.newCall(req).execute()
        if (!resp.isSuccessful) {
            resp.close()
            throw IOException("Patreon 파일 다운로드 실패 (HTTP ${resp.code})")
        }
        return resp.body?.byteStream() ?: run {
            resp.close()
            throw IOException("빈 응답입니다.")
        }
    }

    private fun <T> requestJson(url: String, requireAuth: Boolean, parse: (Map<*, *>) -> T): DesktopFanboxResult<T> {
        val cookie = DesktopPatreonSession.rawCookie()
        if (requireAuth && !cookie.contains("session_id=")) {
            return DesktopFanboxResult.AuthRequired("Patreon 로그인이 필요합니다.")
        }
        return try {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Referer", DesktopPatreonSession.WEB_REFERER)
                .header("User-Agent", DesktopPatreonSession.USER_AGENT)
                .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.code == 401 || resp.code == 403 ->
                        DesktopFanboxResult.AuthRequired("Patreon 로그인이 필요하거나 보안 확인이 필요합니다. (HTTP ${resp.code})")
                    !resp.isSuccessful -> DesktopFanboxResult.Error("Patreon 응답 오류 (HTTP ${resp.code})")
                    else -> {
                        val root = SimpleJson.parse(text) as? Map<*, *>
                            ?: return DesktopFanboxResult.Error("Patreon JSON 응답을 해석할 수 없습니다.")
                        DesktopFanboxResult.Success(parse(root))
                    }
                }
            }
        } catch (e: Exception) {
            DesktopFanboxResult.Error("Patreon 네트워크 오류: ${e.localizedMessage ?: e.javaClass.simpleName}")
        }
    }

    private fun parseStreamPage(root: Map<*, *>, baseUrlForCursor: String): DesktopFanboxFeedPage {
        val data = (root["data"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        val included = indexIncluded(root["included"] as? List<*>)
        val items = data.mapNotNull { parsePost(it, included) }

        val nextCursor = (((root["meta"] as? Map<*, *>)?.get("pagination") as? Map<*, *>)
            ?.get("cursors") as? Map<*, *>)?.optStr("next")
        val linksNext = (root["links"] as? Map<*, *>)?.optStr("next")?.let { raw ->
            if (raw.startsWith("/")) "https://www.patreon.com$raw" else raw
        }
        val next = when {
            items.isEmpty() -> null
            !nextCursor.isNullOrBlank() -> "$baseUrlForCursor&page[cursor]=${URLEncoder.encode(nextCursor, "UTF-8")}"
            !linksNext.isNullOrBlank() && isAllowedPatreonUrl(linksNext) -> linksNext
            else -> null
        }
        return DesktopFanboxFeedPage(items, next)
    }

    private fun indexIncluded(list: List<*>?): Map<String, Map<*, *>> {
        val map = HashMap<String, Map<*, *>>()
        list.orEmpty().forEach { item ->
            val obj = item as? Map<*, *> ?: return@forEach
            val key = "${obj.str("type")}:${obj.str("id")}"
            if (key != ":") map[key] = obj
        }
        return map
    }

    private fun parsePost(obj: Map<*, *>, included: Map<String, Map<*, *>>): DesktopFanboxPostSummary? {
        val id = obj.str("id").ifBlank { return null }
        val attrs = obj["attributes"] as? Map<*, *> ?: return null
        val rels = obj["relationships"] as? Map<*, *>
        val campRef = (rels?.get("campaign") as? Map<*, *>)?.get("data") as? Map<*, *>
        val userRef = (rels?.get("user") as? Map<*, *>)?.get("data") as? Map<*, *>
        val camp = campRef?.let { included["${it.str("type")}:${it.str("id")}"] }
        val user = userRef?.let { included["${it.str("type")}:${it.str("id")}"] }
        val campAttrs = camp?.get("attributes") as? Map<*, *>
        val userAttrs = user?.get("attributes") as? Map<*, *>

        val creatorId = campRef?.str("id")?.ifBlank { userRef?.str("id").orEmpty() } ?: "patreon"
        val creatorName = campAttrs?.str("creation_name")?.ifBlank { null }
            ?: userAttrs?.str("full_name")?.ifBlank { null }
            ?: "Patreon 작가"
        val creatorIcon = campAttrs?.optStr("avatar_photo_url") ?: userAttrs?.optStr("image_url")
        val imgObj = attrs["image"] as? Map<*, *>
        val cover = imgObj?.optStr("large_url") ?: imgObj?.optStr("thumb_url") ?: imgObj?.optStr("url")
        val canView = (attrs["current_user_can_view"] as? Boolean) ?: true
        val parsed = parsePatreonAttributes(attrs)
        val postType = attrs.str("post_type").ifBlank { if (cover != null) "image_file" else "text_only" }
        val hasExternalReward = parsed.links.isNotEmpty()
        val normalizedType = if (postType.contains("text", true) && cover == null && !hasExternalReward) "text" else "image"
        val urlCandidate = attrs.str("url").ifBlank { attrs.str("patreon_url") }
        val rawUrl = when {
            urlCandidate.startsWith("https://") -> urlCandidate
            urlCandidate.startsWith("/") -> "https://www.patreon.com$urlCandidate"
            else -> "https://www.patreon.com/posts/$id"
        }
        val minCents = attrs.intVal("min_cents_pledged_to_view")

        return DesktopFanboxPostSummary(
            id = id,
            title = attrs.str("title").ifBlank { "제목 없는 Patreon 글" },
            creatorId = creatorId,
            creatorName = creatorName,
            creatorIconUrl = creatorIcon,
            coverImageUrl = cover,
            excerpt = parsed.text.replace(Regex("\\s+"), " ").take(160),
            publishedDatetime = attrs.str("published_at"),
            feeRequired = minCents / 100,
            isRestricted = !canView,
            hasAdultContent = (attrs["is_nsfw"] as? Boolean) ?: false,
            likeCount = attrs.intVal("like_count"),
            type = normalizedType,
            service = FeedService.PATREON,
            customPostUrl = rawUrl,
        )
    }

    internal fun stripHtml(html: String): String =
        html.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("</h[1-6]>", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("<li[^>]*>", RegexOption.IGNORE_CASE), "• ")
            .replace(Regex("</li>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#8217;", "’")
            .replace("&#8220;", "“")
            .replace("&#8221;", "”")
            .replace("&nbsp;", " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    internal fun isAllowedPatreonUrl(url: String): Boolean {
        val parsed = url.trim().toHttpUrlOrNull() ?: return false
        if (!parsed.isHttps) return false
        val host = parsed.host.lowercase()
        return host == "patreon.com" || host.endsWith(".patreon.com") ||
            host == "patreonusercontent.com" || host.endsWith(".patreonusercontent.com")
    }

    private fun Map<*, *>.str(k: String): String = (this[k] as? String).orEmpty()
    private fun Map<*, *>.optStr(k: String): String? = (this[k] as? String)?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", true) }
    private fun Map<*, *>.intVal(k: String): Int = (this[k] as? Number)?.toInt() ?: 0
    private fun Map<*, *>.longVal(k: String): Long = (this[k] as? Number)?.toLong() ?: 0L
}
