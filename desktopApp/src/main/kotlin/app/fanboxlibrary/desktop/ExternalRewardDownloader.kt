package app.fanboxlibrary.desktop

import app.fanboxlibrary.shared.ImportReport
import app.fanboxlibrary.shared.ImportSource
import app.fanboxlibrary.shared.LibraryAsset
import app.fanboxlibrary.shared.LibraryPost
import app.fanboxlibrary.shared.LibraryStore
import app.fanboxlibrary.shared.PostDraft
import app.fanboxlibrary.shared.ZipExplorer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.prefs.Preferences
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

internal data class ResolvedRewardItem(
    val label: String,
    val originalUrl: String,
    val directDownloadUrl: String,
    val kind: ExternalLinkKind,
)

internal data class GateHtmlAnalysis(
    val rewards: List<ResolvedRewardItem>,
    val patreonUnlockUrl: String?,
)

internal object ExternalRewardDownloader {
    private val gateCookiePrefs: Preferences =
        Preferences.userRoot().node("app/fanboxlibrary/desktop/gate_cookies")

    private val downloadHttp = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val localCdpHttp = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val naturalPieces = Regex("[0-9]+|[^0-9]+")
    private val naturalComparator = Comparator<String> { a, b ->
        val ax = naturalPieces.findAll(a.lowercase(Locale.ROOT)).iterator()
        val bx = naturalPieces.findAll(b.lowercase(Locale.ROOT)).iterator()
        while (ax.hasNext() && bx.hasNext()) {
            val x = ax.next().value
            val y = bx.next().value
            val cmp = if (x[0] in '0'..'9' && y[0] in '0'..'9') {
                val nx = x.trimStart('0')
                val ny = y.trimStart('0')
                nx.length.compareTo(ny.length).takeIf { it != 0 } ?: nx.compareTo(ny)
            } else x.compareTo(y)
            if (cmp != 0) return@Comparator cmp
        }
        ax.hasNext().compareTo(bx.hasNext()).takeIf { it != 0 } ?: a.compareTo(b)
    }

    private val directFileExtensions = setOf(
        "zip", "7z", "rar", "psd", "psb", "clip", "pdf",
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "avif",
        "mp4", "webm", "mov",
    )

    private val ignoredSocialHosts = setOf(
        "twitter.com", "www.twitter.com", "x.com", "www.x.com",
        "instagram.com", "www.instagram.com",
        "facebook.com", "www.facebook.com",
        "tiktok.com", "www.tiktok.com",
        "bsky.app", "discord.gg", "discord.com",
        "pixiv.net", "www.pixiv.net",
    )

    fun cleanExtractedUrl(raw: String): String? {
        val unescaped = raw.trim()
            .replace("&#038;", "&")
            .replace("&amp;", "&")
            .trimEnd('.', ',', ')', ']', '>', ';')
        val uri = runCatching { URI(unescaped) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.host.isNullOrBlank()) return null
        return unescaped
    }

    fun isInterestingExternalPostLink(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        if (host in ignoredSocialHosts) return false
        if (host == "patreon.com" || host.endsWith(".patreon.com")) {
            val path = uri.path.orEmpty().lowercase(Locale.ROOT)
            // Ignore self/profile/checkout links on Patreon
            return path.contains("/file")
        }
        if (host == "fanbox.cc" || host.endsWith(".fanbox.cc")) return false
        return true
    }

    fun classifyUrl(url: String): ExternalLinkKind {
        val uri = runCatching { URI(url) }.getOrNull()
        val host = uri?.host?.lowercase(Locale.ROOT).orEmpty()
        val path = uri?.path.orEmpty().lowercase(Locale.ROOT)
        val ext = path.substringAfterLast('.', "")
        return when {
            host == "dropbox.com" || host.endsWith(".dropbox.com") || host.endsWith(".dropboxusercontent.com") ->
                ExternalLinkKind.DROPBOX
            host == "drive.google.com" || host == "drive.usercontent.google.com" || host == "docs.google.com" ->
                ExternalLinkKind.GOOGLE_DRIVE
            host == "mega.nz" || host.endsWith(".mega.nz") || host == "mega.co.nz" ->
                ExternalLinkKind.MEGA
            ext in directFileExtensions ->
                ExternalLinkKind.DIRECT_FILE
            else ->
                ExternalLinkKind.PATREON_GATE
        }
    }

    fun defaultLabelForUrl(url: String): String = when (classifyUrl(url)) {
        ExternalLinkKind.DROPBOX -> "Dropbox 보상 다운로드"
        ExternalLinkKind.GOOGLE_DRIVE -> "Google Drive 보상 다운로드"
        ExternalLinkKind.MEGA -> "MEGA 보상 링크"
        ExternalLinkKind.DIRECT_FILE -> url.substringBefore('?').substringAfterLast('/').ifBlank { "첨부 파일 다운로드" }
        ExternalLinkKind.PATREON_GATE -> {
            val uri = runCatching { URI(url) }.getOrNull()
            val slug = uri?.path?.trim('/')?.substringAfterLast('/')?.replace('-', ' ')?.trim().orEmpty()
            if (slug.isNotEmpty()) "${uri?.host.orEmpty()} · $slug" else "${uri?.host ?: "외부"} 보상 페이지"
        }
    }

    fun extractBareUrlsFromText(text: String): List<DesktopPostExternalLink> {
        if (text.isBlank()) return emptyList()
        val regex = Regex("""https://[^\s<>"'()]+""")
        val found = linkedMapOf<String, DesktopPostExternalLink>()
        for (m in regex.findAll(text)) {
            val clean = cleanExtractedUrl(m.value) ?: continue
            if (!isInterestingExternalPostLink(clean)) continue
            found.putIfAbsent(clean, DesktopPostExternalLink(defaultLabelForUrl(clean), clean))
        }
        return found.values.toList()
    }

    /**
     * Converts Dropbox shared file/folder URLs (?dl=0 or no dl param) and Google Drive file URLs
     * into direct binary/ZIP stream URLs without losing rlkey / st tokens!
     */
    fun normalizeDirectDownloadUrl(rawUrl: String): String {
        val clean = cleanExtractedUrl(rawUrl) ?: rawUrl.trim()
        val uri = runCatching { URI(clean) }.getOrNull() ?: return clean
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()

        // 1. Dropbox shared file or folder -> force dl=1 while preserving rlkey, st, etc.
        if (host == "dropbox.com" || host.endsWith(".dropbox.com")) {
            val base = clean.substringBefore('?').substringBefore('#')
            val rawQuery = clean.substringAfter('?', "").substringBefore('#')
            val keptParams = rawQuery.split('&')
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("dl=", ignoreCase = true) }
                .toMutableList()
            keptParams.add("dl=1")
            return "$base?${keptParams.joinToString("&")}"
        }

        // 2. Google Drive /file/d/{id}/... or /open?id={id}
        if (host == "drive.google.com") {
            val fileId = Regex("""/file/d/([A-Za-z0-9_-]+)""").find(uri.path.orEmpty())?.groupValues?.get(1)
                ?: Regex("""(?:^|&)id=([A-Za-z0-9_-]+)""").find(uri.rawQuery.orEmpty())?.groupValues?.get(1)
            if (!fileId.isNullOrBlank()) {
                return "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
            }
        }

        return clean
    }

    /**
     * Parses HTML of a creator's reward gate page (e.g. robokeh.org WordPress + patreon-connect).
     * Associates each downloadable button with its nearest preceding heading for multi-pack pages!
     */
    internal fun parseGateHtml(baseUrl: String, html: String): GateHtmlAnalysis {
        val baseUri = runCatching { URI(baseUrl) }.getOrNull()
        var patreonUnlockUrl: String? = null
        val rewards = linkedMapOf<String, ResolvedRewardItem>()
        var currentHeading = ""

        val tokenRegex = Regex(
            """<(h[1-6]|strong)\b[^>]*>(.*?)</\1>|<a\b[^>]*\bhref\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        for (match in tokenRegex.findAll(html)) {
            val headingTag = match.groupValues[1]
            if (headingTag.isNotEmpty()) {
                val hText = DesktopPatreonClient.stripHtml(match.groupValues[2])
                    .replace(Regex("\\s+"), " ")
                    .trim()
                if (hText.length in 2..100 &&
                    !hText.equals("link", ignoreCase = true) &&
                    !hText.equals("link:", ignoreCase = true) &&
                    !hText.equals("download", ignoreCase = true)
                ) {
                    currentHeading = hText.trimEnd(':')
                }
                continue
            }

            val rawHref = match.groupValues[3]
                .replace("&#038;", "&")
                .replace("&amp;", "&")
                .trim()
            val resolvedHref = runCatching {
                if (rawHref.startsWith("https://", ignoreCase = true)) rawHref
                else baseUri?.resolve(rawHref)?.toString()
            }.getOrNull()?.let(::cleanExtractedUrl) ?: continue

            val anchorText = DesktopPatreonClient.stripHtml(match.groupValues[4])
                .replace(Regex("\\s+"), " ")
                .trim()

            // Detect Patreon WordPress unlock flow URL
            val lowerHref = resolvedHref.lowercase(Locale.ROOT)
            if (lowerHref.contains("patreon-unlock-post=") ||
                lowerHref.contains("/patreon-flow/") ||
                lowerHref.contains("patreon.com/oauth2/authorize") ||
                lowerHref.contains("patreon.com/oauth2/become-patron")
            ) {
                if (patreonUnlockUrl == null || lowerHref.contains("patreon-unlock-post=")) {
                    patreonUnlockUrl = resolvedHref
                }
                continue
            }

            val kind = classifyUrl(resolvedHref)
            if (kind == ExternalLinkKind.DROPBOX ||
                kind == ExternalLinkKind.GOOGLE_DRIVE ||
                kind == ExternalLinkKind.MEGA ||
                kind == ExternalLinkKind.DIRECT_FILE
            ) {
                val genericAnchor = anchorText.isEmpty() ||
                    anchorText.startsWith("http", ignoreCase = true) ||
                    anchorText.lowercase(Locale.ROOT) in setOf("download", "dropbox", "link", "click here", "here", "download here")
                val label = when {
                    !genericAnchor && currentHeading.isNotEmpty() && !anchorText.contains(currentHeading, ignoreCase = true) ->
                        "$currentHeading · $anchorText"
                    !genericAnchor -> anchorText
                    currentHeading.isNotEmpty() -> "$currentHeading (${kind.badge})"
                    else -> defaultLabelForUrl(resolvedHref)
                }
                val direct = normalizeDirectDownloadUrl(resolvedHref)
                rewards.putIfAbsent(direct, ResolvedRewardItem(label, resolvedHref, direct, kind))
            }
        }

        return GateHtmlAnalysis(rewards.values.toList(), patreonUnlockUrl)
    }

    private fun loadGateCookie(host: String): String =
        gateCookiePrefs.get(host.lowercase(Locale.ROOT), "").trim()

    private fun saveGateCookie(host: String, cookieHeader: String) {
        val key = host.lowercase(Locale.ROOT)
        if (cookieHeader.isBlank()) return
        // Java Preferences value limit is 8192 chars
        val safe = if (cookieHeader.length <= 7600) cookieHeader else {
            cookieHeader.split(';')
                .map { it.trim() }
                .filter {
                    val low = it.lowercase(Locale.ROOT)
                    low.contains("wordpress") || low.contains("patreon") || low.contains("sess") ||
                        low.contains("auth") || low.contains("token") || low.contains("wp")
                }
                .joinToString("; ")
                .take(7600)
        }
        if (safe.isNotBlank()) gateCookiePrefs.put(key, safe)
    }

    /**
     * Resolves an external link inside a post into 1+ direct downloadable rewards:
     * - Direct Dropbox / Google Drive / Direct File -> immediate 0ms resolution!
     * - External Patreon-gated site (e.g. robokeh.org):
     *   1) Silent HTTP fetch with cached site cookies (0 popups after first unlock!)
     *   2) If locked ("Unlock with Patreon"), opens the persistent Patreon browser profile directly on
     *      the OAuth unlock URL, waits for Patreon redirect + unlocked DOM, saves site cookies, and auto-closes!
     */
    suspend fun resolveRewardLink(
        link: DesktopPostExternalLink,
        onStatus: (String) -> Unit,
    ): Result<List<ResolvedRewardItem>> = withContext(Dispatchers.IO) {
        val kind = classifyUrl(link.url)
        if (kind != ExternalLinkKind.PATREON_GATE) {
            val direct = normalizeDirectDownloadUrl(link.url)
            return@withContext Result.success(
                listOf(ResolvedRewardItem(link.label, link.url, direct, kind)),
            )
        }

        val gateUri = runCatching { URI(link.url) }.getOrNull()
            ?: return@withContext Result.failure(IllegalArgumentException("올바르지 않은 주소입니다: ${link.url}"))
        val gateHost = gateUri.host?.lowercase(Locale.ROOT).orEmpty()

        // Tier 1: Fast background check using saved gate cookies (never sending Patreon cookies!)
        onStatus("${gateHost} 보상 페이지 확인 중…")
        val initialCheck = runCatching {
            val savedCookie = loadGateCookie(gateHost)
            val req = Request.Builder()
                .url(link.url)
                .header("User-Agent", DesktopFanboxSession.USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .apply {
                    if (savedCookie.isNotBlank()) header("Cookie", savedCookie)
                }
                .build()
            downloadHttp.newCall(req).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                val finalKind = classifyUrl(finalUrl)
                if (finalKind == ExternalLinkKind.DROPBOX ||
                    finalKind == ExternalLinkKind.GOOGLE_DRIVE ||
                    finalKind == ExternalLinkKind.DIRECT_FILE
                ) {
                    return@withContext Result.success(
                        listOf(
                            ResolvedRewardItem(
                                label = link.label,
                                originalUrl = finalUrl,
                                directDownloadUrl = normalizeDirectDownloadUrl(finalUrl),
                                kind = finalKind,
                            ),
                        ),
                    )
                }
                val html = resp.body?.string().orEmpty()
                parseGateHtml(finalUrl, html)
            }
        }.getOrNull()

        if (initialCheck != null && initialCheck.rewards.isNotEmpty()) {
            return@withContext Result.success(initialCheck.rewards)
        }

        // Tier 2: Launch automatic Patreon OAuth popup in persistent patreon-login-profile!
        val startUrl = initialCheck?.patreonUnlockUrl ?: link.url
        unlockGateWithPopup(
            startUrl = startUrl,
            originalGateUrl = link.url,
            gateHost = gateHost,
            fallbackLabel = link.label,
            onStatus = onStatus,
        )
    }

    private suspend fun unlockGateWithPopup(
        startUrl: String,
        originalGateUrl: String,
        gateHost: String,
        fallbackLabel: String,
        onStatus: (String) -> Unit,
    ): Result<List<ResolvedRewardItem>> = withContext(Dispatchers.IO) {
        val browser = DesktopWebLogin.findBrowser()
            ?: return@withContext Result.failure(IllegalStateException("Chrome 또는 Edge 브라우저를 찾을 수 없습니다."))

        val port = ServerSocket(0).use { it.localPort }
        val baseDir = System.getenv("LOCALAPPDATA")?.let(::File) ?: File(System.getProperty("user.home"), ".local/share")
        val profileDir = File(File(baseDir, "FanboxLibrary"), "patreon-login-profile").apply { mkdirs() }

        onStatus("Patreon 인증 창을 열어 보상을 잠금 해제하는 중…")
        val process = ProcessBuilder(
            browser.absolutePath,
            "--app=$startUrl",
            "--window-size=580,800",
            "--no-first-run",
            "--no-default-browser-check",
            "--remote-debugging-port=$port",
            "--user-data-dir=${profileDir.absolutePath}",
        ).redirectErrorStream(true).start()

        try {
            var browserWsUrl: String? = null
            for (attempt in 0 until 30) {
                if (!isActive) throw CancellationException()
                if (!process.isAlive) {
                    return@withContext Result.failure(IllegalStateException("인증 창이 닫혔습니다."))
                }
                browserWsUrl = fetchBrowserWsUrl(port)
                if (browserWsUrl != null) break
                delay(400)
            }
            if (browserWsUrl == null) {
                return@withContext Result.failure(IllegalStateException("인증 창 연결에 실패했습니다. 열려 있는 Patreon 로그인 팝업이 있다면 닫고 다시 시도해 주세요."))
            }

            repeat(220) {
                if (!isActive) throw CancellationException()
                if (!process.isAlive) {
                    return@withContext Result.failure(IllegalStateException("보상 링크를 확인하기 전에 창이 닫혔습니다."))
                }

                val pageTarget = fetchPrimaryPageTarget(port)
                if (pageTarget != null) {
                    val tabUrl = pageTarget.first
                    val pageWsUrl = pageTarget.second
                    val tabUri = runCatching { URI(tabUrl) }.getOrNull()
                    val tabHost = tabUri?.host?.lowercase(Locale.ROOT).orEmpty()
                    val tabPath = tabUri?.path.orEmpty().lowercase(Locale.ROOT)

                    // Case A: Redirected directly to Dropbox / Google Drive / MEGA!
                    val tabKind = classifyUrl(tabUrl)
                    if (tabKind == ExternalLinkKind.DROPBOX ||
                        tabKind == ExternalLinkKind.GOOGLE_DRIVE ||
                        tabKind == ExternalLinkKind.MEGA ||
                        tabKind == ExternalLinkKind.DIRECT_FILE
                    ) {
                        captureAndSaveHostCookies(browserWsUrl, gateHost)
                        closeBrowserViaCdp(browserWsUrl)
                        return@withContext Result.success(
                            listOf(
                                ResolvedRewardItem(
                                    label = fallbackLabel,
                                    originalUrl = tabUrl,
                                    directDownloadUrl = normalizeDirectDownloadUrl(tabUrl),
                                    kind = tabKind,
                                ),
                            ),
                        )
                    }

                    // Case B: Currently on Patreon OAuth / login page
                    if (tabHost.endsWith("patreon.com")) {
                        onStatus("Patreon 연동 승인 대기 중… (팝업 창에 '허용 / Allow' 버튼이 보이면 한 번 클릭해 주세요)")
                    } else if (tabHost == gateHost || tabHost.endsWith(".$gateHost") || gateHost.endsWith(".$tabHost")) {
                        val inOAuthCallback = tabPath.contains("/patreon-flow") || tabPath.contains("/patreon-authorization")
                        if (!inOAuthCallback && pageWsUrl.isNotBlank()) {
                            delay(500)
                            val html = evaluateOuterHtml(pageWsUrl)
                            if (!html.isNullOrBlank()) {
                                val parsed = parseGateHtml(tabUrl, html)
                                if (parsed.rewards.isNotEmpty()) {
                                    onStatus("보상 링크 ${parsed.rewards.size}개 확인 완료! 인증 세션을 저장하고 창을 닫습니다…")
                                    captureAndSaveHostCookies(browserWsUrl, gateHost)
                                    closeBrowserViaCdp(browserWsUrl)
                                    delay(250)
                                    return@withContext Result.success(parsed.rewards)
                                }
                            }
                            onStatus("${gateHost} 페이지에서 보상 링크를 찾는 중… (잠금 해제 버튼이 있으면 클릭해 주세요)")
                        }
                    }
                }
                delay(900)
            }

            Result.failure(IllegalStateException("인증 대기 시간이 초과되었습니다. (${originalGateUrl})"))
        } finally {
            runCatching { if (process.isAlive) process.destroy() }
        }
    }

    private fun fetchBrowserWsUrl(port: Int): String? = runCatching {
        val req = Request.Builder().url("http://127.0.0.1:$port/json/version").build()
        localCdpHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            val obj = SimpleJson.parse(resp.body?.string().orEmpty()) as? Map<*, *>
            obj?.get("webSocketDebuggerUrl") as? String
        }
    }.getOrNull()

    private fun fetchPrimaryPageTarget(port: Int): Pair<String, String>? = runCatching {
        val req = Request.Builder().url("http://127.0.0.1:$port/json/list").build()
        localCdpHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            val arr = SimpleJson.parse(resp.body?.string().orEmpty()) as? List<*> ?: return@runCatching null
            val page = arr.mapNotNull { it as? Map<*, *> }.firstOrNull {
                (it["type"] as? String) == "page" && ((it["url"] as? String)?.startsWith("http") == true)
            } ?: return@runCatching null
            val u = (page["url"] as? String).orEmpty()
            val ws = (page["webSocketDebuggerUrl"] as? String).orEmpty()
            u to ws
        }
    }.getOrNull()

    private suspend fun evaluateOuterHtml(pageWsUrl: String): String? =
        cdpSingleCall(pageWsUrl, """{"id":1,"method":"Runtime.evaluate","params":{"expression":"document.documentElement ? document.documentElement.outerHTML : ''","returnByValue":true}}""")
            ?.let { raw ->
                val root = SimpleJson.parse(raw) as? Map<*, *>
                val res = root?.get("result") as? Map<*, *>
                val inner = res?.get("result") as? Map<*, *>
                inner?.get("value") as? String
            }

    private suspend fun captureAndSaveHostCookies(browserWsUrl: String, gateHost: String) {
        val raw = cdpSingleCall(browserWsUrl, """{"id":1,"method":"Storage.getCookies"}""") ?: return
        val root = SimpleJson.parse(raw) as? Map<*, *> ?: return
        val result = root["result"] as? Map<*, *> ?: return
        val cookies = result["cookies"] as? List<*> ?: return
        val baseDomain = gateHost.removePrefix("www.")
        val matched = linkedMapOf<String, String>()
        for (item in cookies) {
            val c = item as? Map<*, *> ?: continue
            val domain = (c["domain"] as? String).orEmpty().lowercase(Locale.ROOT).trimStart('.')
            val name = (c["name"] as? String).orEmpty()
            val value = (c["value"] as? String).orEmpty()
            if ((domain == baseDomain || domain.endsWith(".$baseDomain")) && name.isNotBlank() && value.isNotBlank()) {
                matched[name] = value
            }
        }
        if (matched.isNotEmpty()) {
            saveGateCookie(gateHost, matched.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }
    }

    private suspend fun closeBrowserViaCdp(browserWsUrl: String) {
        cdpSingleCall(browserWsUrl, """{"id":99,"method":"Browser.close"}""", timeoutMs = 1_500L)
    }

    private suspend fun cdpSingleCall(wsUrl: String, payloadJson: String, timeoutMs: Long = 4_000L): String? {
        val opened = CompletableDeferred<Boolean>()
        val reply = CompletableDeferred<String?>()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(true)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val map = SimpleJson.parse(text) as? Map<*, *> ?: return
                if (map.containsKey("id")) reply.complete(text)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                opened.complete(false)
                reply.complete(null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                reply.complete(null)
            }
        }
        val ws = localCdpHttp.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
        return try {
            if (withTimeoutOrNull(3_000L) { opened.await() } != true) return null
            ws.send(payloadJson)
            withTimeoutOrNull(timeoutMs) { reply.await() }
        } finally {
            runCatching { ws.close(1000, "done") }
        }
    }

    /**
     * Streams one or more resolved Dropbox / Drive / Direct File rewards to temp disk,
     * auto-unpacks ZIP folders (preserving natural page order & Textless variants) and
     * auto-renders PDF comic pages at 180 DPI into `LibraryStore`!
     */
    suspend fun downloadAndImportRewards(
        store: LibraryStore,
        draft: PostDraft,
        rewards: List<ResolvedRewardItem>,
        onProgress: (String) -> Unit,
    ): ImportReport = withContext(Dispatchers.IO) {
        var totalAdded = 0
        var totalReused = 0
        var totalSkipped = 0
        val warnings = mutableListOf<String>()

        val tempDir = Files.createTempDirectory("fanboxlib-reward-").toFile()
        try {
            for ((idx, item) in rewards.withIndex()) {
                if (!isActive) throw CancellationException()
                if (item.kind == ExternalLinkKind.MEGA) {
                    warnings.add("${item.label}: MEGA 링크는 브라우저 암호 복호화가 필요하여 건너뛰었습니다.")
                    totalSkipped++
                    continue
                }

                val prefix = if (rewards.size > 1) "(${idx + 1}/${rewards.size}) " else ""
                onProgress("${prefix}${item.label} 연결 중…")

                val dlFile = File(tempDir, "download_${idx + 1}.bin")
                var headerFileName = ""
                var contentType = ""

                val req = Request.Builder()
                    .url(item.directDownloadUrl)
                    .header("User-Agent", DesktopFanboxSession.USER_AGENT)
                    .header("Accept", "*/*")
                    .build()

                downloadHttp.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("${item.label} 다운로드 실패 (HTTP ${resp.code})")
                    }
                    contentType = resp.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
                    if (contentType.contains("text/html")) {
                        throw IOException("${item.label}: 직접 파일 대신 웹 페이지가 반환되었습니다. 링크 권한이나 비밀번호를 확인해 주세요.")
                    }
                    headerFileName = parseContentDispositionFileName(resp.header("Content-Disposition"))
                        ?: resp.request.url.pathSegments.lastOrNull()?.takeIf { it.contains('.') }.orEmpty()

                    val totalBytes = resp.body?.contentLength() ?: -1L
                    val input = resp.body?.byteStream() ?: throw IOException("빈 응답입니다.")
                    FileOutputStream(dlFile).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastUiTick = 0L
                        while (true) {
                            if (!isActive) throw CancellationException()
                            val n = input.read(buf)
                            if (n < 0) break
                            if (n == 0) continue
                            out.write(buf, 0, n)
                            downloaded += n
                            val now = System.currentTimeMillis()
                            if (now - lastUiTick >= 220L) {
                                lastUiTick = now
                                val mb = downloaded / 1048576.0
                                val pct = if (totalBytes > 0) " (${(downloaded * 100 / totalBytes).coerceIn(0, 100)}%)" else ""
                                onProgress("${prefix}${item.label} 다운로드 중 · %.1f MB%s".format(mb, pct))
                            }
                        }
                    }
                }

                val rep = ingestDownloadedFile(
                    store = store,
                    draft = draft,
                    file = dlFile,
                    suggestedName = headerFileName.ifBlank { sanitizeFileStem(item.label) },
                    tempWorkDir = File(tempDir, "unpack_${idx + 1}").apply { mkdirs() },
                    onProgress = { msg -> onProgress("$prefix$msg") },
                )
                totalAdded += rep.added
                totalReused += rep.reused
                totalSkipped += rep.skipped
                warnings.addAll(rep.warnings)
                dlFile.delete()
            }
        } finally {
            tempDir.deleteRecursively()
        }

        ImportReport(totalAdded, totalReused, totalSkipped, warnings)
    }

    private fun ingestDownloadedFile(
        store: LibraryStore,
        draft: PostDraft,
        file: File,
        suggestedName: String,
        tempWorkDir: File,
        onProgress: (String) -> Unit,
    ): ImportReport {
        val magic = ByteArray(12)
        val readLen = file.inputStream().use { it.read(magic) }.coerceAtLeast(0)
        val isZip = readLen >= 4 && magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() &&
            magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte()
        val isPdf = readLen >= 4 && magic[0] == '%'.code.toByte() && magic[1] == 'P'.code.toByte() &&
            magic[2] == 'D'.code.toByte() && magic[3] == 'F'.code.toByte()

        if (isZip) {
            return ingestZipArchive(store, draft, file, tempWorkDir, depth = 0, onProgress = onProgress)
        }
        if (isPdf) {
            val cleanPdfName = ensureExtension(suggestedName, "pdf")
            return ingestPdfWithRenderedPages(store, draft, file, cleanPdfName, tempWorkDir, onProgress)
        }

        val inferredExt = inferExtensionFromMagic(magic, readLen)
            ?: suggestedName.substringAfterLast('.', "").takeIf { it.isNotEmpty() }
            ?: "bin"
        val finalName = ensureExtension(suggestedName, inferredExt)
        return store.importManaged(
            draft,
            listOf(ImportSource(finalName, "원본/$finalName") { file.inputStream() }),
        )
    }

    private fun ingestZipArchive(
        store: LibraryStore,
        draft: PostDraft,
        zipFile: File,
        tempWorkDir: File,
        depth: Int,
        onProgress: (String) -> Unit,
    ): ImportReport {
        val entries = runCatching { ZipExplorer.listEntries(zipFile) }.getOrDefault(emptyList())
            .sortedWith { a, b -> naturalComparator.compare(a.displayName, b.displayName) }

        if (entries.isEmpty()) {
            return store.importManaged(draft, listOf(ImportSource("reward.zip", "원본/reward.zip") { zipFile.inputStream() }))
        }

        var added = 0
        var reused = 0
        var skipped = 0
        val warnings = mutableListOf<String>()
        val directBatch = mutableListOf<File>()
        val directHints = mutableListOf<String>()
        val directNames = mutableListOf<String>()
        val usedNames = hashSetOf<String>()

        for ((idx, entry) in entries.withIndex()) {
            val leaf = entry.displayName.substringAfterLast('/')
            if (leaf.startsWith(".") || leaf.equals("Thumbs.db", ignoreCase = true) || leaf.equals("desktop.ini", ignoreCase = true)) {
                continue
            }
            val ext = leaf.substringAfterLast('.', "").lowercase(Locale.ROOT)
            onProgress("압축 해제 중 (${idx + 1}/${entries.size}) · $leaf")
            val extracted = File(tempWorkDir, "e_${depth}_${idx}_$leaf")
            runCatching { ZipExplorer.extractEntryToFile(zipFile, entry.entryName, extracted) }.onFailure { continue }

            when {
                ext == "pdf" -> {
                    val pdfRep = ingestPdfWithRenderedPages(
                        store = store,
                        draft = draft,
                        pdfFile = extracted,
                        pdfDisplayName = uniqueLeafName(leaf, usedNames),
                        tempWorkDir = File(tempWorkDir, "pdf_$idx").apply { mkdirs() },
                        onProgress = onProgress,
                    )
                    added += pdfRep.added
                    reused += pdfRep.reused
                    skipped += pdfRep.skipped
                    warnings.addAll(pdfRep.warnings)
                    extracted.delete()
                }
                ext == "zip" && depth < 1 -> {
                    val subRep = ingestZipArchive(
                        store = store,
                        draft = draft,
                        zipFile = extracted,
                        tempWorkDir = File(tempWorkDir, "subzip_$idx").apply { mkdirs() },
                        depth = depth + 1,
                        onProgress = onProgress,
                    )
                    added += subRep.added
                    reused += subRep.reused
                    skipped += subRep.skipped
                    warnings.addAll(subRep.warnings)
                    extracted.delete()
                }
                ext in directFileExtensions -> {
                    val uniqueName = uniqueLeafName(
                        if (entry.isImage) "%03d_%s".format(idx + 1, leaf) else leaf,
                        usedNames,
                    )
                    val pathHint = if (entry.displayName.contains("textless", ignoreCase = true) ||
                        entry.displayName.contains("무자막")
                    ) {
                        "textless/${entry.displayName}"
                    } else {
                        "원본/${entry.displayName}"
                    }
                    directBatch.add(extracted)
                    directNames.add(uniqueName)
                    directHints.add(pathHint)
                }
                else -> extracted.delete()
            }
        }

        if (directBatch.isNotEmpty()) {
            onProgress("내 보관함에 파일 ${directBatch.size}개 등록 중…")
            val sources = directBatch.indices.map { i ->
                val f = directBatch[i]
                ImportSource(directNames[i], directHints[i]) { f.inputStream() }
            }
            val rep = store.importManaged(draft, sources)
            added += rep.added
            reused += rep.reused
            skipped += rep.skipped
            warnings.addAll(rep.warnings)
            directBatch.forEach { it.delete() }
        }

        return ImportReport(added, reused, skipped, warnings)
    }

    private fun ingestPdfWithRenderedPages(
        store: LibraryStore,
        draft: PostDraft,
        pdfFile: File,
        pdfDisplayName: String,
        tempWorkDir: File,
        onProgress: (String) -> Unit,
    ): ImportReport {
        val pageFiles = renderPdfPagesToJpeg(pdfFile, pdfDisplayName.substringBeforeLast('.'), tempWorkDir, onProgress)
        val sources = buildList {
            pageFiles.forEach { pf ->
                add(ImportSource(pf.name, "원본/${pf.name}") { pf.inputStream() })
            }
            add(ImportSource(pdfDisplayName, "원본/$pdfDisplayName") { pdfFile.inputStream() })
        }
        val rep = store.importManaged(draft, sources)
        pageFiles.forEach { it.delete() }
        return rep
    }

    /**
     * Renders every page of a PDF comic into crisp 180 DPI RGB JPEGs so it can be read
     * smoothly in Single-Page or Webtoon mode inside FanboxLibrary's native reader!
     */
    fun renderPdfPagesToJpeg(
        pdfFile: File,
        stem: String,
        outDir: File,
        onProgress: (String) -> Unit,
    ): List<File> {
        outDir.mkdirs()
        val safeStem = sanitizeFileStem(stem).take(40).ifBlank { "comic" }
        val rendered = mutableListOf<File>()
        runCatching {
            Loader.loadPDF(pdfFile).use { doc ->
                val total = doc.numberOfPages
                val renderer = PDFRenderer(doc)
                for (pageIdx in 0 until total) {
                    onProgress("PDF 만화 페이지 변환 중 (${pageIdx + 1}/$total)…")
                    val bim = renderer.renderImageWithDPI(pageIdx, 180f, ImageType.RGB)
                    val outFile = File(outDir, "%03d_%s_p%03d.jpg".format(pageIdx + 1, safeStem, pageIdx + 1))
                    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
                    try {
                        ImageIO.createImageOutputStream(outFile).use { ios ->
                            writer.output = ios
                            val param = writer.defaultWriteParam.apply {
                                compressionMode = ImageWriteParam.MODE_EXPLICIT
                                compressionQuality = 0.92f
                            }
                            writer.write(null, IIOImage(bim, null, null), param)
                        }
                    } finally {
                        writer.dispose()
                        bim.flush()
                    }
                    rendered.add(outFile)
                }
            }
        }
        return rendered
    }

    suspend fun unpackExistingPdfAsset(
        store: LibraryStore,
        post: LibraryPost,
        pdfAsset: LibraryAsset,
        onProgress: (String) -> Unit,
    ): ImportReport = withContext(Dispatchers.IO) {
        val pdfFile = File(pdfAsset.path)
        require(pdfFile.isFile) { "원본 PDF 파일을 찾을 수 없습니다." }
        val tempDir = Files.createTempDirectory("fanboxlib-pdf-").toFile()
        try {
            val pages = renderPdfPagesToJpeg(pdfFile, pdfAsset.name.substringBeforeLast('.'), tempDir, onProgress)
            if (pages.isEmpty()) throw IOException("PDF에서 변환할 수 있는 페이지가 없습니다.")
            val draft = PostDraft(
                creator = post.creator,
                title = post.title,
                year = post.year,
                sourceUrl = post.sourceUrl,
                existingId = post.id,
            )
            store.importManaged(
                draft,
                pages.map { pf -> ImportSource(pf.name, "원본/${pf.name}") { pf.inputStream() } },
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun parseContentDispositionFileName(header: String?): String? {
        if (header.isNullOrBlank()) return null
        Regex("""filename\*\s*=\s*UTF-8''([^;\r\n]+)""", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)?.let { enc ->
            val dec = runCatching { URLDecoder.decode(enc.trim().removeSurrounding("\""), "UTF-8") }.getOrNull()
            if (!dec.isNullOrBlank()) return sanitizeFileName(dec)
        }
        Regex("""filename\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)?.let { fn ->
            if (fn.isNotBlank()) return sanitizeFileName(fn)
        }
        Regex("""filename\s*=\s*([^;\s]+)""", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)?.let { fn ->
            val clean = fn.trim().removeSurrounding("\"")
            if (clean.isNotBlank()) return sanitizeFileName(clean)
        }
        return null
    }

    private fun inferExtensionFromMagic(magic: ByteArray, len: Int): String? {
        if (len >= 4 && magic[0] == '8'.code.toByte() && magic[1] == 'B'.code.toByte() &&
            magic[2] == 'P'.code.toByte() && magic[3] == 'S'.code.toByte()
        ) return "psd"
        if (len >= 3 && magic[0] == 0xFF.toByte() && magic[1] == 0xD8.toByte() && magic[2] == 0xFF.toByte()) return "jpg"
        if (len >= 8 && magic[0] == 0x89.toByte() && magic[1] == 0x50.toByte() &&
            magic[2] == 0x4E.toByte() && magic[3] == 0x47.toByte()
        ) return "png"
        if (len >= 12 && magic[0] == 'R'.code.toByte() && magic[1] == 'I'.code.toByte() &&
            magic[8] == 'W'.code.toByte() && magic[9] == 'E'.code.toByte()
        ) return "webp"
        if (len >= 4 && magic[0] == 'G'.code.toByte() && magic[1] == 'I'.code.toByte() && magic[2] == 'F'.code.toByte()) return "gif"
        return null
    }

    private fun uniqueLeafName(candidate: String, used: MutableSet<String>): String {
        val clean = sanitizeFileName(candidate)
        if (used.add(clean.lowercase(Locale.ROOT))) return clean
        val stem = clean.substringBeforeLast('.', clean)
        val ext = clean.substringAfterLast('.', "")
        var n = 2
        while (true) {
            val next = if (ext.isNotEmpty()) "${stem}_$n.$ext" else "${stem}_$n"
            if (used.add(next.lowercase(Locale.ROOT))) return next
            n++
        }
    }

    private fun ensureExtension(name: String, ext: String): String {
        val clean = sanitizeFileName(name).ifBlank { "reward.$ext" }
        return if (clean.endsWith(".$ext", ignoreCase = true)) clean else "$clean.$ext"
    }

    private fun sanitizeFileStem(raw: String): String =
        raw.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]+"""), "_").trim().trim('.').ifBlank { "reward" }

    private fun sanitizeFileName(raw: String): String =
        raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]+"""), "_")
            .trim()
            .trim('.')
            .ifBlank { "reward" }
}
