package app.fanboxlibrary.shared

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val size: Long,
)

data class ReleaseInfo(
    val tagName: String,
    val version: String,
    val title: String,
    val notes: String,
    val htmlUrl: String,
    val publishedAt: String,
    val assets: List<ReleaseAsset>,
) {
    fun findWindowsInstaller(): ReleaseAsset? =
        assets.firstOrNull { it.name.endsWith(".exe", ignoreCase = true) }
            ?: assets.firstOrNull { it.name.endsWith(".msi", ignoreCase = true) }

    fun findAndroidApk(): ReleaseAsset? =
        assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
}

sealed interface UpdateCheckResult {
    data class UpToDate(val currentVersion: String, val latestRelease: ReleaseInfo?) : UpdateCheckResult
    data class UpdateAvailable(val currentVersion: String, val release: ReleaseInfo) : UpdateCheckResult
    data class NoReleaseYet(val currentVersion: String) : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

object GitHubUpdater {
    const val REPO_OWNER = "UR-al"
    const val REPO_NAME = "fanbox-library"
    const val REPO_URL = "https://github.com/$REPO_OWNER/$REPO_NAME"
    const val RELEASES_PAGE_URL = "$REPO_URL/releases"
    const val LATEST_RELEASE_API_URL = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"

    fun normalizeVersion(raw: String): String = raw.trim().removePrefix("v").removePrefix("V")

    /**
     * Returns negative if [a] < [b], 0 if equal, positive if [a] > [b].
     * Pre-release versions (e.g., 1.0.0-preview) sort lower than their stable counterpart (1.0.0).
     */
    fun compareVersions(a: String, b: String): Int {
        val left = parseVersion(a)
        val right = parseVersion(b)
        val maxLen = maxOf(left.numbers.size, right.numbers.size)
        for (i in 0 until maxLen) {
            val l = left.numbers.getOrElse(i) { 0 }
            val r = right.numbers.getOrElse(i) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return when {
            left.preRelease == null && right.preRelease == null -> 0
            left.preRelease == null -> 1
            right.preRelease == null -> -1
            else -> left.preRelease.compareTo(right.preRelease, ignoreCase = true)
        }
    }

    private data class ParsedVersion(val numbers: List<Int>, val preRelease: String?)

    private fun parseVersion(raw: String): ParsedVersion {
        val clean = normalizeVersion(raw).substringBefore('+')
        val dash = clean.indexOf('-')
        val core = if (dash >= 0) clean.substring(0, dash) else clean
        val pre = if (dash >= 0) clean.substring(dash + 1).ifBlank { null } else null
        val nums = core.split('.').mapNotNull { part -> part.toIntOrNull() }
        return ParsedVersion(nums.ifEmpty { listOf(0) }, pre)
    }

    fun checkLatest(currentVersion: String, apiUrl: String = LATEST_RELEASE_API_URL): UpdateCheckResult {
        return try {
            val conn = (URI(apiUrl).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "FanboxLibrary/$currentVersion")
            }
            try {
                val code = conn.responseCode
                when (code) {
                    200 -> {
                        val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        val release = parseReleaseJson(body)
                            ?: return UpdateCheckResult.Error("GitHub 릴리즈 정보를 해석할 수 없습니다.")
                        if (compareVersions(currentVersion, release.version) < 0) {
                            UpdateCheckResult.UpdateAvailable(currentVersion, release)
                        } else {
                            UpdateCheckResult.UpToDate(currentVersion, release)
                        }
                    }
                    404 -> UpdateCheckResult.NoReleaseYet(currentVersion)
                    403, 429 -> UpdateCheckResult.Error("GitHub API 요청 한도에 도달했습니다. 잠시 후 다시 시도하거나 릴리즈 페이지를 직접 열어주세요.")
                    else -> UpdateCheckResult.Error("업데이트 서버 응답 오류 (HTTP $code)")
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            UpdateCheckResult.Error("네트워크 연결을 확인하세요. (${e.localizedMessage ?: e.javaClass.simpleName})")
        }
    }

    fun isTrustedDownloadUrl(url: String): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        return host == "github.com" ||
            host.endsWith(".github.com") ||
            host == "githubusercontent.com" ||
            host.endsWith(".githubusercontent.com")
    }

    /**
     * Desktop-only helper to download a release installer asset safely to [destination].
     * Never used on Android so Google Play builds remain fully policy-compliant.
     */
    fun downloadAsset(
        asset: ReleaseAsset,
        destination: File,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): File {
        require(isTrustedDownloadUrl(asset.downloadUrl)) { "허용되지 않은 다운로드 주소입니다." }
        destination.absoluteFile.parentFile?.mkdirs()
        var currentUrl = asset.downloadUrl
        var redirects = 0
        while (true) {
            require(isTrustedDownloadUrl(currentUrl)) { "허용되지 않은 리다이렉트 주소입니다." }
            val conn = (URI(currentUrl).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "FanboxLibrary-Updater")
                setRequestProperty("Accept", "application/octet-stream")
            }
            try {
                val code = conn.responseCode
                if (code in 301..308) {
                    val loc = conn.getHeaderField("Location")
                        ?: throw IllegalStateException("리다이렉트 위치가 비어 있습니다.")
                    if (++redirects > 6) throw IllegalStateException("리다이렉트가 너무 많습니다.")
                    currentUrl = URI(currentUrl).resolve(loc).toString()
                    continue
                }
                if (code != 200) {
                    throw IllegalStateException("다운로드에 실패했습니다 (HTTP $code).")
                }
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: asset.size
                val tmp = Files.createTempFile(destination.absoluteFile.parentFile.toPath(), ".fanbox-update-", ".part")
                try {
                    conn.inputStream.use { input ->
                        Files.newOutputStream(tmp).use { output ->
                            val buf = ByteArray(32 * 1024)
                            var downloaded = 0L
                            while (true) {
                                val read = input.read(buf)
                                if (read < 0) break
                                output.write(buf, 0, read)
                                downloaded += read
                                onProgress(downloaded, total)
                            }
                        }
                    }
                    Files.move(tmp, destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    Files.deleteIfExists(tmp)
                }
                return destination
            } finally {
                conn.disconnect()
            }
        }
    }

    fun parseReleaseJson(json: String): ReleaseInfo? {
        val root = runCatching { MiniJsonParser(json).parseValue() }.getOrNull() as? Map<*, *> ?: return null
        val tag = (root["tag_name"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val version = normalizeVersion(tag)
        val title = (root["name"] as? String)?.trim()?.ifEmpty { tag } ?: tag
        val notes = (root["body"] as? String)?.trim().orEmpty()
        val htmlUrl = (root["html_url"] as? String)?.trim()?.ifEmpty { RELEASES_PAGE_URL } ?: RELEASES_PAGE_URL
        val publishedAt = (root["published_at"] as? String)?.trim().orEmpty()
        val rawAssets = root["assets"] as? List<*> ?: emptyList<Any?>()
        val assets = rawAssets.mapNotNull { item ->
            val obj = item as? Map<*, *> ?: return@mapNotNull null
            val name = (obj["name"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = (obj["browser_download_url"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val size = (obj["size"] as? Number)?.toLong() ?: 0L
            ReleaseAsset(name = name, downloadUrl = url, size = size)
        }
        return ReleaseInfo(
            tagName = tag,
            version = version,
            title = title,
            notes = notes,
            htmlUrl = htmlUrl,
            publishedAt = publishedAt,
            assets = assets,
        )
    }

    private class MiniJsonParser(private val text: String) {
        private var pos = 0

        fun parseValue(): Any? {
            skipWhitespace()
            if (pos >= text.length) return null
            return when (text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            pos++ // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (pos < text.length && text[pos] == '}') {
                pos++
                return map
            }
            while (pos < text.length) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                if (pos >= text.length || text[pos] != ':') throw IllegalArgumentException("Expected ':' at $pos")
                pos++
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                if (pos < text.length && text[pos] == ',') {
                    pos++
                    continue
                }
                if (pos < text.length && text[pos] == '}') {
                    pos++
                    return map
                }
                throw IllegalArgumentException("Expected ',' or '}' at $pos")
            }
            return map
        }

        private fun parseArray(): List<Any?> {
            pos++ // '['
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (pos < text.length && text[pos] == ']') {
                pos++
                return list
            }
            while (pos < text.length) {
                list.add(parseValue())
                skipWhitespace()
                if (pos < text.length && text[pos] == ',') {
                    pos++
                    continue
                }
                if (pos < text.length && text[pos] == ']') {
                    pos++
                    return list
                }
                throw IllegalArgumentException("Expected ',' or ']' at $pos")
            }
            return list
        }

        private fun parseString(): String {
            if (text[pos] != '"') throw IllegalArgumentException("Expected '\"' at $pos")
            pos++
            val sb = StringBuilder()
            while (pos < text.length) {
                val c = text[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= text.length) break
                        when (val esc = text[pos++]) {
                            '"', '\\', '/' -> sb.append(esc)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 <= text.length) {
                                    val hex = text.substring(pos, pos + 4)
                                    sb.append(hex.toInt(16).toChar())
                                    pos += 4
                                }
                            }
                            else -> sb.append(esc)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        private fun parseNumber(): Number {
            val start = pos
            if (pos < text.length && text[pos] == '-') pos++
            while (pos < text.length && text[pos].isDigit()) pos++
            var isFloat = false
            if (pos < text.length && text[pos] == '.') {
                isFloat = true
                pos++
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                isFloat = true
                pos++
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            val token = text.substring(start, pos)
            return if (isFloat) token.toDouble() else token.toLong()
        }

        private fun expect(literal: String) {
            if (!text.startsWith(literal, pos)) throw IllegalArgumentException("Expected $literal at $pos")
            pos += literal.length
        }

        private fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }
    }
}
