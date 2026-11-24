package app.fanboxlibrary.shared

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

data class ZipItem(
    val entryName: String,
    val displayName: String,
    val size: Long,
    val isImage: Boolean,
    val isPsd: Boolean,
)

object ZipExplorer {
    private val japaneseCharset: Charset? = runCatching { Charset.forName("MS932") }.getOrNull()
    private val koreanCharset: Charset? = runCatching { Charset.forName("EUC-KR") }.getOrNull()

    fun isZipFile(name: String): Boolean = name.substringAfterLast('.', "").equals("zip", ignoreCase = true)

    fun isPsdFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext == "psd" || ext == "psb"
    }

    /**
     * Opens a ZIP file with automatic fallback to MS932 (Shift-JIS) / EUC-KR
     * for Japanese/Korean FANBOX archives that don't set the UTF-8 flag.
     */
    private fun openZip(file: File): ZipFile {
        val candidates = listOfNotNull(StandardCharsets.UTF_8, japaneseCharset, koreanCharset)
        var lastError: Exception? = null
        for (cs in candidates) {
            try {
                val zf = ZipFile(file, cs)
                // Force enumeration of entries to verify charset doesn't throw IllegalArgumentException (MALFORMED)
                val entries = zf.entries()
                var hasReplacement = false
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement().name
                    if ('\uFFFD' in name) {
                        hasReplacement = true
                        break
                    }
                }
                if (!hasReplacement || cs == candidates.last()) return zf
                zf.close()
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IOException("ZIP 파일을 열 수 없습니다.", lastError)
    }

    fun listEntries(zipFile: File): List<ZipItem> {
        if (!zipFile.isFile) return emptyList()
        openZip(zipFile).use { zf ->
            val items = mutableListOf<ZipItem>()
            val en = zf.entries()
            while (en.hasMoreElements()) {
                val e = en.nextElement()
                if (e.isDirectory) continue
                val clean = normalizeEntryPath(e.name) ?: continue
                if (clean.startsWith("__MACOSX/") || clean.substringAfterLast('/').startsWith("._")) continue
                val ext = clean.substringAfterLast('.', "").lowercase(Locale.ROOT)
                val isImg = Media.isImage(clean)
                val isPsd = ext == "psd" || ext == "psb"
                items.add(
                    ZipItem(
                        entryName = e.name,
                        displayName = clean,
                        size = e.size.coerceAtLeast(0L),
                        isImage = isImg,
                        isPsd = isPsd,
                    ),
                )
            }
            return items.sortedWith(
                compareByDescending<ZipItem> { it.isPsd }
                    .thenByDescending { it.isImage }
                    .thenBy { it.displayName.lowercase(Locale.ROOT) },
            )
        }
    }

    fun readEntryBytes(zipFile: File, entryName: String, maxBytes: Long = 32L * 1024 * 1024): ByteArray {
        openZip(zipFile).use { zf ->
            val entry = zf.getEntry(entryName) ?: throw IOException("ZIP 내부 항목을 찾을 수 없습니다: $entryName")
            if (entry.size > maxBytes) throw IOException("미리보기 크기 제한을 초과했습니다.")
            zf.getInputStream(entry).use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buf)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) throw IOException("미리보기 크기 제한을 초과했습니다.")
                    out.write(buf, 0, read)
                }
                return out.toByteArray()
            }
        }
    }

    fun extractEntryToFile(zipFile: File, entryName: String, destination: File): File {
        destination.absoluteFile.parentFile?.mkdirs()
        openZip(zipFile).use { zf ->
            val entry = zf.getEntry(entryName) ?: throw IOException("ZIP 내부 항목을 찾을 수 없습니다: $entryName")
            zf.getInputStream(entry).use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 32 * 1024)
                }
            }
        }
        return destination
    }

    /**
     * Extracts selected entries (or all supported media/PSD entries when [entryNames] is null)
     * directly into [store] under [targetPost].
     */
    fun extractIntoLibrary(
        zipFile: File,
        store: LibraryStore,
        targetPost: LibraryPost,
        entryNames: Set<String>? = null,
    ): ImportReport {
        val entries = listEntries(zipFile).filter { item ->
            val chosen = entryNames == null || item.entryName in entryNames
            chosen && (item.isImage || item.isPsd || Media.supported(item.displayName))
        }
        if (entries.isEmpty()) return ImportReport(skipped = 0, warnings = listOf("압축 해제할 지원 파일이 없습니다."))

        val draft = PostDraft(existingId = targetPost.id)
        openZip(zipFile).use { zf ->
            val sources = entries.mapNotNull { item ->
                val entry: ZipEntry = zf.getEntry(item.entryName) ?: return@mapNotNull null
                val fileName = item.displayName.substringAfterLast('/')
                ImportSource(
                    name = fileName,
                    pathHint = item.displayName,
                    open = { zf.getInputStream(entry) },
                )
            }
            return store.importManaged(draft, sources)
        }
    }

    private fun normalizeEntryPath(raw: String): String? {
        val norm = raw.replace('\\', '/').trimStart('/')
        if (norm.isBlank() || norm.contains("..")) return null
        return norm
    }
}
