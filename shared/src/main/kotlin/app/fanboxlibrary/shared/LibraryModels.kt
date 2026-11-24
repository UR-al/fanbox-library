package app.fanboxlibrary.shared

import java.io.File
import java.io.InputStream
import java.net.URI
import java.util.Locale

data class PostDraft(
    val creator: String = "",
    val title: String = "",
    val year: Int? = null,
    val sourceUrl: String = "",
    val existingId: String? = null,
)

data class ImportSource(val name: String, val pathHint: String? = null, val open: () -> InputStream) {
    constructor(name: String, open: () -> InputStream) : this(name, null, open)
}

data class PeerAssetSource(val name: String, val sha256: String, val size: Long, val variant: String,
                           val open: () -> InputStream)

data class LibraryPost(
    val id: String,
    val creator: String,
    val title: String,
    val year: Int?,
    val sourceUrl: String,
    val assets: List<LibraryAsset>,
)

data class LibraryAsset(
    val id: String,
    val name: String,
    val sha256: String,
    val size: Long,
    val path: String,
    val variant: String,
    val isImage: Boolean,
)

/** added = new post item, reused = already linked item; skipped entries are explained in warnings. */
data class ImportReport(
    val added: Int = 0,
    val reused: Int = 0,
    val skipped: Int = 0,
    val warnings: List<String> = emptyList(),
)

object FanboxLinks {
    private val host = Regex("""(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)?fanbox\.cc""")
    private val postPath = Regex("""(?:/@[A-Za-z0-9_-]+)?/posts/([1-9][0-9]{0,18})/?""")
    private val patreonHost = Regex("""(?:www\.)?patreon\.com""")
    private val patreonPostPath = Regex("""(?:/(?:c/)?[A-Za-z0-9_-]+)?/posts/(?:[^/]*-)?([1-9][0-9]{0,18})/?""")

    private fun official(raw: String): URI? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        return uri.takeIf {
            it.scheme.equals("https", true) && it.rawUserInfo == null && it.port in listOf(-1, 443) &&
                it.host?.lowercase(Locale.ROOT)?.matches(host) == true
        }
    }

    private fun officialPatreon(raw: String): URI? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        return uri.takeIf {
            it.scheme.equals("https", true) && it.rawUserInfo == null && it.port in listOf(-1, 443) &&
                it.host?.lowercase(Locale.ROOT)?.matches(patreonHost) == true
        }
    }

    fun isOfficialUrl(url: String): Boolean = official(url) != null

    fun isPatreonUrl(url: String): Boolean = officialPatreon(url) != null

    fun postId(url: String): String? =
        official(url)?.rawPath?.let { postPath.matchEntire(it)?.groupValues?.get(1) }

    fun patreonPostId(url: String): String? =
        officialPatreon(url)?.rawPath?.let { patreonPostPath.matchEntire(it)?.groupValues?.get(1) }

    internal fun sourceKey(url: String): String? =
        postId(url)?.let { "fanbox:$it" } ?: patreonPostId(url)?.let { "patreon:$it" }

    internal fun canonicalPost(url: String): String {
        if (url.isBlank()) return ""
        val fanboxUri = official(url)
        if (fanboxUri != null && postId(url) != null) {
            return "https://${fanboxUri.host.lowercase(Locale.ROOT)}${fanboxUri.rawPath.trimEnd('/')}"
        }
        val patreonUri = officialPatreon(url)
        if (patreonUri != null && patreonPostId(url) != null) {
            return "https://www.patreon.com${patreonUri.rawPath.trimEnd('/')}"
        }
        throw IllegalArgumentException("공식 FANBOX 또는 Patreon 게시물 주소를 입력하세요.")
    }

    internal fun creator(url: String): String? {
        val uri = official(url) ?: return null
        val sub = uri.host.lowercase(Locale.ROOT).removeSuffix(".fanbox.cc")
        if (sub != "www" && sub != "fanbox.cc") return sub
        return uri.rawPath.takeIf { it.startsWith("/@") }?.removePrefix("/@")?.substringBefore('/')
    }
}

internal object Media {
    private val originalHint = Regex("""(?<![\p{L}\p{N}])(?:originals?|원본(?:이미지)?|原寸|原本|オリジナル)(?![\p{L}\p{N}])""")
    private val images = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic", "heif")
    private val attachments = setOf("zip", "7z", "rar", "psd", "psb", "clip", "pdf", "mp4", "webm",
        "mov", "mp3", "m4a", "wav", "ogg", "flac", "apng", "tif", "tiff", "kra", "ai", "eps")

    fun isImage(name: String): Boolean = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in images
    fun supported(name: String): Boolean = isImage(name) || name.substringAfterLast('.', "").lowercase(Locale.ROOT) in attachments

    fun variant(parts: List<String>): String {
        val text = parts.joinToString("/").lowercase(Locale.ROOT)
        return when {
            Regex("""(?<![a-z])sample(?![a-z])|샘플|サンプル""").containsMatchIn(text) -> "SAMPLE"
            "textless" in text || "텍스트 제거" in text || "무자막" in text -> "textless"
            "저해상도" in text || "lowres" in text || "low-res" in text || "thumbnail" in text || "썸네일" in text -> "저해상도"
            "원본 미확인" in text -> "원본 미확인"
            originalHint.containsMatchIn(text) -> "원본"
            else -> "원본 미확인"
        }
    }

    private val marker = Regex("""\[F([1-9][0-9]{0,18})]""", RegexOption.IGNORE_CASE)
    private val year = Regex("""(?:19|20)\d{2}""")
    data class FolderPost(val key: String, val draft: PostDraft, val variant: String)

    fun classify(root: File, file: File): FolderPost {
        val parts = file.relativeTo(root).invariantSeparatorsPath.split('/')
        val folders = parts.dropLast(1)
        val markedFolder = folders.lastOrNull { marker.containsMatchIn(it) }
        val id = marker.find(markedFolder ?: file.name)?.groupValues?.get(1)
        val title = markedFolder?.replace(marker, "")?.trim()?.ifEmpty { null }
        val creator = root.name
        val publishedYear = folders.lastOrNull { year.matches(it) }?.toInt()
        val fallback = listOf(root.absolutePath, folders.joinToString("/")).joinToString("\u0000")
        return FolderPost(
            id?.let { "fanbox:$it" } ?: "folder:" + digestText(fallback),
            PostDraft(creator, title ?: if (id != null) "FANBOX #$id" else "미분류", publishedYear),
            variant(parts),
        )
    }
}
