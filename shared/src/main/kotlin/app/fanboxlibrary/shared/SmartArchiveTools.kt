package app.fanboxlibrary.shared

import net.lingala.zip4j.ZipFile as Zip4jFile
import net.lingala.zip4j.exception.ZipException as Zip4jException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Extracts archive password candidates written by creators inside FANBOX / Patreon post descriptions.
 * Supports Korean, Japanese, and English conventions (e.g. Pass: xxxx, PW: xxxx, 비번: xxxx, パスワード：xxxx).
 */
object PasswordExtractor {
    private val labeledPatterns = listOf(
        Regex("""(?i)(?:dl\s*)?(?:pass(?:word)?|pw|p/w|암호|비밀번호|비번|패스워드|パスワード|パス|合言葉)\s*(?:은|는|is)?\s*[:：=▶>\-\s]\s*["'「『【\[]?([A-Za-z0-9!@#$%^&*_+\-.~?]{2,48})["'」』】\]]?"""),
        Regex("""(?i)\b(?:pass|pw)\s+([A-Za-z0-9!@#$%^&*_+\-.]{3,32})\b"""),
    )

    private val stopWords = setOf(
        "http", "https", "www", "none", "null", "here", "link", "download", "dropbox", "patreon", "fanbox",
    )

    fun extractCandidates(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val found = LinkedHashSet<String>()
        for (regex in labeledPatterns) {
            for (m in regex.findAll(text)) {
                val cand = m.groupValues[1].trim()
                if (cand.length >= 2 && cand.lowercase(Locale.ROOT) !in stopWords) {
                    found.add(cand)
                }
            }
        }
        return found.toList()
    }
}

data class UnpackedEntryFile(
    val file: File,
    val displayPath: String,
    val leafName: String,
)

/**
 * Unified unpacker for plain & password-protected .zip (ZipCrypto + AES-256 via Zip4j, with Shift-JIS/UTF-8 support)
 * and plain & password-protected .7z archives (via Apache Commons Compress + XZ).
 */
object ArchiveUnpacker {
    private val naturalPieces = Regex("[0-9]+|[^0-9]+")
    val naturalOrder: Comparator<String> = Comparator { a, b ->
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

    fun isSevenZipMagic(magic: ByteArray, len: Int): Boolean =
        len >= 6 &&
            magic[0] == 0x37.toByte() && magic[1] == 0x7A.toByte() &&
            magic[2] == 0xBC.toByte() && magic[3] == 0xAF.toByte() &&
            magic[4] == 0x27.toByte() && magic[5] == 0x1C.toByte()

    fun isZipEncrypted(zipFile: File): Boolean =
        runCatching { Zip4jFile(zipFile).isEncrypted }.getOrDefault(false)

    fun extractZipWithPasswords(
        zipFile: File,
        outDir: File,
        passwordCandidates: List<String>,
        onProgress: (String) -> Unit = {},
    ): List<UnpackedEntryFile> {
        outDir.mkdirs()
        val z4 = Zip4jFile(zipFile)
        if (!z4.isEncrypted) {
            // Use our charset-aware ZipExplorer first for non-encrypted Japanese/Korean archives
            val plain = runCatching {
                val entries = ZipExplorer.listEntries(zipFile)
                    .sortedWith { a, b -> naturalOrder.compare(a.displayName, b.displayName) }
                val out = mutableListOf<UnpackedEntryFile>()
                for ((idx, item) in entries.withIndex()) {
                    val leaf = sanitizeLeaf(item.displayName)
                    if (isIgnoredSystemFile(item.displayName, leaf)) continue
                    onProgress("ZIP 압축 해제 중 (${idx + 1}/${entries.size}) · $leaf")
                    val target = File(outDir, "%04d_%s".format(idx + 1, leaf))
                    ZipExplorer.extractEntryToFile(zipFile, item.entryName, target)
                    out.add(UnpackedEntryFile(target, item.displayName, leaf))
                }
                out
            }.getOrNull()
            if (plain != null) return plain
        }

        val passwordsToTry = if (z4.isEncrypted) {
            passwordCandidates.distinct().ifEmpty {
                throw IOException("암호가 걸린 ZIP 파일입니다. 게시글 본문에서 비밀번호를 찾지 못했습니다.")
            }
        } else {
            listOf("")
        }

        var lastError: Exception? = null
        for (pw in passwordsToTry) {
            val attemptDir = File(outDir, "z4_${pw.hashCode().toUInt()}").apply { mkdirs() }
            try {
                val zf = Zip4jFile(zipFile)
                if (pw.isNotEmpty()) zf.setPassword(pw.toCharArray())
                val headers = zf.fileHeaders.filterNot { it.isDirectory }
                val results = mutableListOf<UnpackedEntryFile>()
                for ((idx, fh) in headers.withIndex()) {
                    val cleanRel = fh.fileName.replace('\\', '/').trimStart('/')
                    val leaf = sanitizeLeaf(cleanRel)
                    if (isIgnoredSystemFile(cleanRel, leaf)) continue
                    onProgress("암호 ZIP 해제 중 (${idx + 1}/${headers.size}) · $leaf")
                    val targetName = "%04d_%s".format(idx + 1, leaf)
                    zf.extractFile(fh, attemptDir.absolutePath, targetName)
                    val extracted = File(attemptDir, targetName)
                    if (extracted.isFile && extracted.length() > 0L) {
                        results.add(UnpackedEntryFile(extracted, cleanRel, leaf))
                    }
                }
                return results.sortedWith { a, b -> naturalOrder.compare(a.displayPath, b.displayPath) }
            } catch (e: Zip4jException) {
                attemptDir.deleteRecursively()
                lastError = e
            } catch (e: Exception) {
                attemptDir.deleteRecursively()
                lastError = e
            }
        }
        throw IOException("암호 ZIP 압축 해제 실패: 올바른 비밀번호를 찾지 못했습니다.", lastError)
    }

    fun extractSevenZip(
        sevenZFile: File,
        outDir: File,
        passwordCandidates: List<String>,
        onProgress: (String) -> Unit = {},
    ): List<UnpackedEntryFile> {
        outDir.mkdirs()
        val candidates = listOf<CharArray?>(null) + passwordCandidates.distinct().map { it.toCharArray() }
        var lastError: Exception? = null

        for ((cIdx, pwChars) in candidates.withIndex()) {
            val attemptDir = File(outDir, "7z_$cIdx").apply { mkdirs() }
            try {
                val builder = SevenZFile.builder().setFile(sevenZFile)
                if (pwChars != null) builder.setPassword(pwChars)
                builder.get().use { archive ->
                    val results = mutableListOf<UnpackedEntryFile>()
                    val buf = ByteArray(64 * 1024)
                    var idx = 0
                    while (true) {
                        val entry = archive.nextEntry ?: break
                        if (entry.isDirectory) continue
                        idx++
                        val cleanRel = (entry.name ?: "item_$idx").replace('\\', '/').trimStart('/')
                        val leaf = sanitizeLeaf(cleanRel)
                        if (isIgnoredSystemFile(cleanRel, leaf)) continue
                        onProgress("7z 압축 해제 중 ($idx) · $leaf")
                        val outFile = File(attemptDir, "%04d_%s".format(idx, leaf))
                        FileOutputStream(outFile).use { out ->
                            while (true) {
                                val r = archive.read(buf)
                                if (r <= 0) break
                                out.write(buf, 0, r)
                            }
                        }
                        if (outFile.isFile && outFile.length() > 0L) {
                            results.add(UnpackedEntryFile(outFile, cleanRel, leaf))
                        }
                    }
                    return results.sortedWith { a, b -> naturalOrder.compare(a.displayPath, b.displayPath) }
                }
            } catch (e: Exception) {
                attemptDir.deleteRecursively()
                lastError = e
            }
        }
        throw IOException("7z 압축 해제 실패: 암호가 다르거나 손상된 아카이브입니다.", lastError)
    }

    private fun isIgnoredSystemFile(relPath: String, leaf: String): Boolean =
        relPath.startsWith("__MACOSX/") ||
            leaf.startsWith("._") ||
            leaf.equals(".DS_Store", ignoreCase = true) ||
            leaf.equals("Thumbs.db", ignoreCase = true) ||
            leaf.equals("desktop.ini", ignoreCase = true)

    private fun sanitizeLeaf(path: String): String =
        path.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]+"""), "_")
            .trim()
            .trim('.')
            .ifBlank { "file" }
}

data class MegaFileHandle(
    val publicId: String,
    val aesKey: ByteArray,
    val ctrIv: ByteArray,
)

/**
 * Browserless pure-Kotlin MEGA (mega.nz/file/{id}#{key} & mega.nz/#!{id}!{key}) client-side AES-128-CTR downloader!
 * Works identically on Desktop JVM and Android without WebView or external SDKs.
 */
object MegaDownloader {
    private val modernRegex = Regex("""mega\.(?:nz|co\.nz)/file/([A-Za-z0-9_-]+)#([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
    private val legacyRegex = Regex("""mega\.(?:nz|co\.nz)/#!([A-Za-z0-9_-]+)!([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)

    fun parseMegaFileUrl(url: String): MegaFileHandle? {
        val m = modernRegex.find(url) ?: legacyRegex.find(url) ?: return null
        val id = m.groupValues[1]
        val keyB64 = m.groupValues[2]
        val rawKey = b64UrlDecode(keyB64)
        if (rawKey.size < 32) return null

        val bb = ByteBuffer.wrap(rawKey)
        val k0 = bb.int
        val k1 = bb.int
        val k2 = bb.int
        val k3 = bb.int
        val k4 = bb.int
        val k5 = bb.int
        val k6 = bb.int
        val k7 = bb.int

        val aesKey = ByteBuffer.allocate(16)
            .putInt(k0 xor k4)
            .putInt(k1 xor k5)
            .putInt(k2 xor k6)
            .putInt(k3 xor k7)
            .array()

        val ctrIv = ByteBuffer.allocate(16)
            .putInt(k4)
            .putInt(k5)
            .putLong(0L)
            .array()

        return MegaFileHandle(id, aesKey, ctrIv)
    }

    fun decryptAttributesFileName(encryptedAtB64: String, aesKey: ByteArray): String? = runCatching {
        val cipherBytes = b64UrlDecode(encryptedAtB64)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(ByteArray(16)))
        val plain = cipher.doFinal(cipherBytes)
        val end = plain.indexOfFirst { it == 0.toByte() }.let { if (it >= 0) it else plain.size }
        val str = String(plain, 0, end, StandardCharsets.UTF_8)
        if (!str.startsWith("MEGA{")) return null
        Regex(""""n"\s*:\s*"((?:\\.|[^"\\])*)"""").find(str)?.groupValues?.get(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
    }.getOrNull()

    fun downloadDecryptedToFile(
        megaUrl: String,
        destFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long, fileName: String) -> Unit = { _, _, _ -> },
    ): String {
        val handle = parseMegaFileUrl(megaUrl)
            ?: throw IOException("지원하지 않는 MEGA 링크 형식입니다. (개별 파일 링크 mega.nz/file/ID#KEY 필요)")

        val apiConn = (URI.create("https://g.api.mega.co.nz/cs?id=1").toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        val reqBody = """[{"a":"g","g":1,"p":"${handle.publicId}"}]""".toByteArray(StandardCharsets.UTF_8)
        apiConn.outputStream.use { it.write(reqBody) }
        val respText = apiConn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        apiConn.disconnect()

        if (respText.trim().startsWith("[-")) {
            throw IOException("MEGA API 오류 응답: $respText")
        }

        val dlUrl = Regex(""""g"\s*:\s*"([^"]+)"""").find(respText)?.groupValues?.get(1)
            ?: throw IOException("MEGA 다운로드 스트림 주소를 받지 못했습니다.")
        val atB64 = Regex(""""at"\s*:\s*"([^"]+)"""").find(respText)?.groupValues?.get(1).orEmpty()
        val totalSize = Regex(""""s"\s*:\s*(\d+)""").find(respText)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
        val fileName = decryptAttributesFileName(atB64, handle.aesKey)?.takeIf { it.isNotBlank() } ?: "mega_${handle.publicId}.bin"

        val streamConn = (URL(dlUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 300_000
        }
        try {
            if (streamConn.responseCode !in 200..299) {
                throw IOException("MEGA 파일 스트림 오류 (HTTP ${streamConn.responseCode})")
            }
            val ctrCipher = Cipher.getInstance("AES/CTR/NoPadding")
            ctrCipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(handle.aesKey, "AES"), IvParameterSpec(handle.ctrIv))

            CipherInputStream(BufferedInputStream(streamConn.inputStream), ctrCipher).use { decIn ->
                FileOutputStream(destFile).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var lastTick = 0L
                    while (true) {
                        val n = decIn.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        out.write(buf, 0, n)
                        downloaded += n
                        val now = System.currentTimeMillis()
                        if (now - lastTick >= 220L) {
                            lastTick = now
                            onProgress(downloaded, totalSize, fileName)
                        }
                    }
                }
            }
        } finally {
            streamConn.disconnect()
        }
        return fileName
    }

    internal fun b64UrlDecode(input: String): ByteArray {
        var padded = input.trim()
        val rem = padded.length % 4
        if (rem == 2) padded += "=="
        else if (rem == 3) padded += "="
        return Base64.getUrlDecoder().decode(padded)
    }
}

/**
 * Clean human-readable folder backup (`Creator / [Year] Title / 001_img.jpg`)
 * and standard ComicBook Archive (`.cbz` with `ComicInfo.xml`) exporter!
 */
object LibraryExporter {
    fun exportPostsToCleanFolderTree(
        posts: List<LibraryPost>,
        targetRootDir: File,
        onProgress: (current: Int, total: Int, title: String) -> Unit = { _, _, _ -> },
    ): Int {
        targetRootDir.mkdirs()
        var exportedFiles = 0
        for ((idx, post) in posts.withIndex()) {
            onProgress(idx + 1, posts.size, post.title)
            val creatorFolder = File(targetRootDir, sanitizeSegment(post.creator.ifBlank { "작가 미분류" }))
            val yearPrefix = post.year?.let { "[$it] " }.orEmpty()
            val postFolderName = sanitizeSegment("$yearPrefix${post.title.ifBlank { post.id }}")
            val postDir = uniqueDirectory(File(creatorFolder, postFolderName)).apply { mkdirs() }

            var imgIndex = 0
            for (asset in post.assets) {
                val src = File(asset.path)
                if (!src.isFile) continue
                val subDir = if (asset.variant.equals("textless", ignoreCase = true)) {
                    File(postDir, "Textless").apply { mkdirs() }
                } else {
                    postDir
                }
                val outName = if (asset.isImage) {
                    imgIndex++
                    val ext = asset.name.substringAfterLast('.', "png")
                    "%03d.%s".format(imgIndex, ext)
                } else {
                    sanitizeSegment(asset.name)
                }
                val dest = uniqueFile(File(subDir, outName))
                src.inputStream().use { input ->
                    dest.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
                exportedFiles++
            }
        }
        return exportedFiles
    }

    fun exportPostToCbz(post: LibraryPost, targetCbzFile: File): Int {
        val images = post.assets.filter { it.isImage && File(it.path).isFile }
        require(images.isNotEmpty()) { "CBZ로 내보낼 수 있는 이미지가 없습니다." }
        targetCbzFile.absoluteFile.parentFile?.mkdirs()

        val finalTarget = if (targetCbzFile.name.endsWith(".cbz", ignoreCase = true)) {
            targetCbzFile
        } else {
            File(targetCbzFile.parentFile, "${targetCbzFile.name}.cbz")
        }

        ZipOutputStream(FileOutputStream(finalTarget), StandardCharsets.UTF_8).use { zos ->
            // 1. Standard ComicInfo.xml for Kavita / Komga / Honeyview / Mihon
            zos.putNextEntry(ZipEntry("ComicInfo.xml"))
            val xml = buildString {
                appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
                appendLine("""<ComicInfo>""")
                appendLine("""  <Title>${escapeXml(post.title)}</Title>""")
                appendLine("""  <Writer>${escapeXml(post.creator)}</Writer>""")
                appendLine("""  <Penciller>${escapeXml(post.creator)}</Penciller>""")
                if (post.year != null) appendLine("""  <Year>${post.year}</Year>""")
                if (post.sourceUrl.isNotBlank()) appendLine("""  <Web>${escapeXml(post.sourceUrl)}</Web>""")
                appendLine("""  <PageCount>${images.size}</PageCount>""")
                appendLine("""</ComicInfo>""")
            }
            zos.write(xml.toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()

            // 2. Ordered comic pages (STORED / fast level since JPEGs/PNGs are already compressed)
            for ((i, img) in images.withIndex()) {
                val ext = img.name.substringAfterLast('.', "jpg").lowercase(Locale.ROOT)
                val prefix = if (img.variant.equals("textless", ignoreCase = true)) "textless_" else ""
                val entryName = "%s%04d.%s".format(prefix, i + 1, ext)
                zos.putNextEntry(ZipEntry(entryName))
                File(img.path).inputStream().use { it.copyTo(zos, 64 * 1024) }
                zos.closeEntry()
            }
        }
        return images.size
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun sanitizeSegment(raw: String): String =
        raw.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]+"""), "_")
            .trim()
            .trim('.')
            .take(110)
            .ifBlank { "untitled" }

    private fun uniqueDirectory(base: File): File {
        if (!base.exists()) return base
        var n = 2
        while (true) {
            val cand = File(base.parentFile, "${base.name} ($n)")
            if (!cand.exists()) return cand
            n++
        }
    }

    private fun uniqueFile(base: File): File {
        if (!base.exists()) return base
        val stem = base.nameWithoutExtension
        val ext = base.extension
        var n = 2
        while (true) {
            val cand = File(base.parentFile, if (ext.isNotEmpty()) "${stem}_$n.$ext" else "${stem}_$n")
            if (!cand.exists()) return cand
            n++
        }
    }
}
