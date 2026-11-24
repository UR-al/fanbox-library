package app.fanboxlibrary.shared

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** A library root is private app data; in-place indexing never writes to the indexed artist directory. */
class LibraryStore(root: File) {
    private val root = root.canonicalFile
    private val indexFile = File(this.root, "library.index")
    private val managed = File(this.root, "files")
    private val staging = File(this.root, "staging")

    private fun <T> locked(action: (LibraryIndex) -> T): T =
        synchronized(locks.computeIfAbsent(root.path) { Any() }) {
            Files.createDirectories(root.toPath())
            RandomAccessFile(File(root, ".lock"), "rw").use { lockFile ->
                lockFile.channel.lock().use {
                    val index = LibraryIndex()
                    index.loadFrom(indexFile)
                    action(index)
                }
            }
        }

    /** Only the immutable snapshot is exposed to the caller. Missing external files remain visible. */
    fun posts(): List<LibraryPost> = locked { index ->
        val available = HashMap<String, String>()
        index.posts.values.map { record ->
            val assets = record.entries.values.map { entry ->
                val blob = index.blobs.getValue(entry.hash)
                val path = available.getOrPut(entry.hash) {
                    val loc = blob.locations.firstOrNull {
                        val f = File(it.path)
                        f.isFile && f.length() == blob.size && f.lastModified() == it.modified
                    }
                    // A replaced in-place file must not be opened as the old version of that file.
                    loc?.path ?: File(root, "unavailable/${entry.hash}").absolutePath
                }
                LibraryAsset(entry.id, entry.name, entry.hash, blob.size, path, entry.variant, Media.isImage(entry.name))
            }
            LibraryPost(record.id, record.creator, record.title, record.year, record.url, assets)
        }.sortedWith(compareByDescending<LibraryPost> { it.year ?: 0 }.thenBy { it.creator }.thenBy { it.title }.thenBy { it.id })
    }

    /** Copy streams to private content-addressed storage. Staging files are never visible in the index. */
    fun importManaged(draft: PostDraft, inputs: List<ImportSource>): ImportReport = locked { index ->
        validateDraft(index, draft)
        Files.createDirectories(staging.toPath())
        // This root is exclusively locked. Leftovers may exist if Android terminated an earlier import.
        staging.listFiles()?.filter { it.name.startsWith("media-") && it.name.endsWith(".part") }?.forEach { it.delete() }
        val counter = Counters()
        val items = mutableListOf<Prepared>()
        for (source in inputs) {
            counter.tryFile(source.name) {
                val name = safeName(source.name)
                val tmp = File.createTempFile("media-", ".part", staging)
                try {
                    val fingerprint = source.open().use { input ->
                        FileOutputStream(tmp).use { output ->
                            fingerprint(input, output).also { output.fd.sync() }
                        }
                    }
                    checkMedia(tmp, name)
                    val dest = publish(tmp, fingerprint)
                    val hints = listOfNotNull(source.pathHint, name)
                    items.add(Prepared(fingerprint, name, Media.variant(hints), Location(dest.path, dest.lastModified())))
                } finally {
                    tmp.delete()
                }
            }
        }
        commitItems(index, draft, items, counter)
    }

    /** A peer never supplies a local path. Its manifest is committed only after every blob verifies. */
    fun importPeerPost(peerId: String, draft: PostDraft, sources: List<PeerAssetSource>): ImportReport = locked { index ->
        require(peerId.matches(Regex("""(?:(?:fanbox|patreon):[1-9][0-9]{0,18}|(?:local|folder):[a-f0-9]{64})"""))) { "알 수 없는 작품 ID입니다." }
        require(draft.existingId == null) { "동기화 데이터에 로컬 대상이 포함되어 있습니다." }
        validateDraft(index, draft)
        val sourceId = FanboxLinks.sourceKey(draft.sourceUrl)
        require(!(peerId.startsWith("fanbox:") || peerId.startsWith("patreon:")) || sourceId == null || sourceId == peerId) { "게시물 주소와 작품 ID가 다릅니다." }
        if (sources.isEmpty()) return@locked ImportReport()
        val key = sourceId ?: peerId
        val post = index.posts.getOrPut(key) { newRecord(key, draft.copy(sourceUrl = FanboxLinks.canonicalPost(draft.sourceUrl))) }
        Files.createDirectories(staging.toPath())
        val counter = Counters()
        val verified = HashMap<String, Location>()
        val testedArchives = hashSetOf<String>()
        for (src in sources) {
            require(src.sha256.matches(Regex("[a-f0-9]{64}")) && src.size > 0) { "잘못된 파일 지문입니다." }
            require(src.name == safeName(src.name) && src.name.length <= 1024 && src.variant in variants) { "잘못된 파일 정보입니다." }
            val expected = Fingerprint(src.sha256, src.size)
            val location = verified.getOrPut(src.sha256) {
                val files = listOf(managedFile(src.sha256)) + index.blobs[src.sha256]?.locations.orEmpty().map { File(it.path) }
                val existing = files.distinct().firstNotNullOfOrNull { file -> verifiedLocation(file, expected) }
                if (existing != null) existing else {
                    val tmp = File.createTempFile("media-", ".part", staging)
                    try {
                        val actual = src.open().use { input ->
                            FileOutputStream(tmp).use { out ->
                                fingerprint(input, out, src.size).also { out.fd.sync() }
                            }
                        }
                        if (actual != expected) throw IOException("전송 파일의 내용이 일치하지 않습니다: ${src.name}")
                        checkMedia(tmp, src.name)
                        if (src.name.endsWith(".zip", true)) testedArchives.add(src.sha256)
                        val dest = publish(tmp, actual)
                        Location(dest.path, dest.lastModified())
                    } finally { tmp.delete() }
                }
            }
            // A second logical name can assign an archive extension to bytes already present as an image.
            if (src.name.endsWith(".zip", true) && testedArchives.add(src.sha256)) checkMedia(File(location.path), src.name)
            attach(index, post, Prepared(expected, src.name, src.variant, location), counter)
        }
        index.saveTo(indexFile)
        counter.report()
    }

    private fun managedFile(hash: String) = File(managed, "${hash.take(2)}/$hash")

    private fun verifiedLocation(file: File, expected: Fingerprint): Location? {
        return try {
            if (!file.isFile || file.length() != expected.size) return null
            val stamp = file.lastModified()
            val actual = file.inputStream().use { fingerprint(it) }
            if (actual == expected && file.lastModified() == stamp && file.length() == expected.size)
                Location(file.path, stamp) else null
        } catch (_: IOException) {
            null // An offline external drive can be recovered from the paired device.
        } catch (_: SecurityException) {
            null
        }
    }

    private fun publish(tmp: File, expected: Fingerprint): File {
        val dest = managedFile(expected.hash)
        Files.createDirectories(dest.parentFile.toPath())
        if (!dest.exists()) {
            Files.move(tmp.toPath(), dest.toPath(), ATOMIC_MOVE)
        } else {
            require(dest.isFile && !Files.isSymbolicLink(dest.toPath())) { "보관 파일 경로가 유효하지 않습니다." }
            if (dest.length() != expected.size || dest.inputStream().use { fingerprint(it) } != expected) {
                // Only an app-owned blob named for this exact checksum can be repaired here.
                Files.move(tmp.toPath(), dest.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            }
        }
        return dest
    }

    private fun checkMedia(file: File, name: String) {
        if (file.length() == 0L) throw IOException("빈 파일입니다.")
        if (!name.endsWith(".zip", ignoreCase = true)) return
        // Older Japanese archives may use CP932 names. A lossless byte mapping suffices for CRC checks;
        // entries carrying the UTF-8 flag are decoded by ZipFile itself.
        ZipFile(file, Charsets.ISO_8859_1).use { archive ->
            // Always check the central directory. Also verify member CRCs for ordinary-sized archives.
            // A large PSD may expand far beyond its download size; never unpack unbounded data.
            var budget = 512L * 1024 * 1024
            for (entry in archive.entries()) {
                if (entry.size !in 0..budget) return@use
                budget -= entry.size
            }
            val buffer = ByteArray(65536)
            for (entry in archive.entries()) {
                if (entry.isDirectory) continue
                val crc = CRC32()
                var size = 0L
                archive.getInputStream(entry).use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        if (size > entry.size) throw IOException("ZIP 내부 파일의 크기가 올바르지 않습니다.")
                        crc.update(buffer, 0, count)
                    }
                }
                if (size != entry.size || crc.value != entry.crc) throw IOException("ZIP 내부 파일이 손상되었습니다.")
            }
        }
    }

    /** Index user-selected individual files without moving, copying, or editing those files. */
    fun indexFiles(draft: PostDraft, files: List<File>): ImportReport = locked { index ->
        validateDraft(index, draft)
        val counter = Counters()
        val items = mutableListOf<Prepared>()
        for (file in files) counter.tryFile(file.name) {
            val hints = listOfNotNull(file.parentFile?.name, file.name)
            items.add(prepareExisting(file, Media.variant(hints)))
        }
        commitItems(index, draft, items, counter)
    }

    private fun commitItems(index: LibraryIndex, draft: PostDraft, items: List<Prepared>, counter: Counters): ImportReport {
        if (items.isNotEmpty()) {
            val post = destination(index, draft, items.map { it.entry.id })
            for (item in items) attach(index, post, item, counter)
            index.saveTo(indexFile)
        }
        return counter.report()
    }

    fun indexFolder(artistFolder: File): ImportReport = locked { index ->
        val folder = artistFolder.toPath().toRealPath().toFile()
        require(folder.isDirectory && !folder.toPath().startsWith(root.toPath())) { "보관함 외부의 작가 폴더를 선택하세요." }
        val counter = Counters()
        val files = mutableListOf<File>()
        Files.walkFileTree(folder.toPath(), object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir == root.toPath() || Files.isSymbolicLink(dir) || !dir.toRealPath().startsWith(folder.toPath()))
                    FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile && Media.supported(file.fileName.toString())) files.add(file.toFile())
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                counter.skip(file.fileName?.toString() ?: file.toString(), exc)
                return FileVisitResult.CONTINUE
            }
        })
        // Sorting paths as text would place page 10 ahead of page 2.
        files.sortWith { a, b -> naturalCompare(a.path, b.path) }
        for (file in files) counter.tryFile(file.name) {
            val info = Media.classify(folder, file)
            val item = prepareExisting(file, info.variant)
            if (info.key to item.entry.id in index.relocated) {
                index.recordLocation(item.fingerprint.hash, item.fingerprint.size, item.location)
                counter.reused++
            } else {
                val record = index.posts.getOrPut(info.key) { newRecord(info.key, info.draft) }
                attach(index, record, item, counter)
            }
        }
        if (files.isNotEmpty()) index.saveTo(indexFile)
        counter.report()
    }

    /** Moves the selected logical items to their new post. Blob bytes and external files never move. */
    fun regroup(sourcePostId: String, assetIds: Set<String>, destination: PostDraft): ImportReport = locked { index ->
        validateDraft(index, destination)
        val source = requireNotNull(index.posts[sourcePostId]) { "원본 작품을 찾을 수 없습니다." }
        val items = assetIds.map { id -> requireNotNull(source.entries[id]) { "선택한 파일을 찾을 수 없습니다." } }
        if (items.isEmpty()) return@locked ImportReport()
        val target = destination(index, destination, assetIds.toList())
        val counter = Counters()
        if (target.id != source.id) {
            for (entry in source.entries.values.filter { it.id in assetIds }) {
                if (target.entries.putIfAbsent(entry.id, entry) == null) counter.added++ else counter.reused++
                source.entries.remove(entry.id)
                index.relocated.add(source.id to entry.id)
                index.relocated.remove(target.id to entry.id)
            }
            if (source.entries.isEmpty()) index.posts.remove(source.id)
        } else {
            counter.reused = items.size
        }
        index.saveTo(indexFile)
        counter.report()
    }

    /**
     * Merges multiple posts (e.g., episodes of a series) into a single target post.
     * Optionally updates the merged series title and creator.
     */
    fun mergePosts(
        targetPostId: String,
        sourcePostIds: Set<String>,
        newTitle: String? = null,
        newCreator: String? = null,
    ): ImportReport = locked { index ->
        val target = requireNotNull(index.posts[targetPostId]) { "기준 작품을 찾을 수 없습니다." }
        if (!newTitle.isNullOrBlank()) target.title = newTitle.trim()
        if (!newCreator.isNullOrBlank()) target.creator = newCreator.trim()
        val counter = Counters()
        for (srcId in sourcePostIds) {
            if (srcId == target.id) continue
            val source = index.posts[srcId] ?: continue
            val entries = source.entries.values.toList()
            for (entry in entries) {
                if (target.entries.putIfAbsent(entry.id, entry) == null) counter.added++ else counter.reused++
                source.entries.remove(entry.id)
                index.relocated.add(source.id to entry.id)
                index.relocated.remove(target.id to entry.id)
            }
            if (source.entries.isEmpty()) index.posts.remove(source.id)
        }
        index.saveTo(indexFile)
        counter.report()
    }

    /**
     * Removes selected images/attachments from a post in the library index.
     * Cleans up unreferenced app-managed blobs inside [managed], while NEVER deleting external linked user files.
     */
    fun deleteAssets(postId: String, assetIds: Set<String>): Int = locked { index ->
        val post = index.posts[postId] ?: return@locked 0
        if (assetIds.isEmpty()) return@locked 0
        var removed = 0
        val touchedHashes = hashSetOf<String>()
        for (id in assetIds) {
            val entry = post.entries.remove(id) ?: continue
            index.relocated.add(post.id to entry.id)
            touchedHashes.add(entry.hash)
            removed++
        }
        if (post.entries.isEmpty()) {
            index.posts.remove(post.id)
        }
        if (removed > 0) {
            val stillReferenced = hashSetOf<String>()
            index.posts.values.forEach { p -> p.entries.values.forEach { stillReferenced.add(it.hash) } }
            for (hash in touchedHashes) {
                if (hash !in stillReferenced) {
                    index.blobs.remove(hash)
                    val blobFile = managedFile(hash)
                    if (blobFile.isFile && blobFile.canonicalFile.toPath().startsWith(managed.canonicalFile.toPath())) {
                        runCatching { blobFile.delete() }
                    }
                }
            }
            index.saveTo(indexFile)
        }
        removed
    }

    fun renamePost(postId: String, newTitle: String, newCreator: String? = null) = locked { index ->
        val post = requireNotNull(index.posts[postId]) { "작품을 찾을 수 없습니다." }
        if (newTitle.isNotBlank()) post.title = newTitle.trim()
        if (!newCreator.isNullOrBlank()) post.creator = newCreator.trim()
        index.saveTo(indexFile)
    }

    private fun prepareExisting(raw: File, variant: String): Prepared {
        val file = raw.canonicalFile
        require(file.isFile && !file.toPath().startsWith(root.toPath())) { "참조할 수 있는 외부 파일을 선택하세요." }
        val modified = file.lastModified()
        val size = file.length()
        checkMedia(file, file.name)
        val fingerprint = file.inputStream().use { fingerprint(it) }
        if (!file.isFile || file.lastModified() != modified || size != fingerprint.size || size != file.length()) {
            throw IOException("색인하는 동안 파일이 변경되었습니다. 다시 시도하세요.")
        }
        return Prepared(fingerprint, safeName(file.name), variant, Location(file.path, modified))
    }

    private fun validateDraft(index: LibraryIndex, draft: PostDraft) {
        val normalized = FanboxLinks.canonicalPost(draft.sourceUrl)
        require(draft.year == null || draft.year in 1..9999) { "올바른 연도를 입력하세요." }
        if (draft.existingId != null) {
            val existing = requireNotNull(index.posts[draft.existingId]) { "지정한 작품을 찾을 수 없습니다." }
            val incomingKey = FanboxLinks.sourceKey(normalized)
            if (incomingKey != null && (existing.id.startsWith("fanbox:") || existing.id.startsWith("patreon:"))) {
                require(existing.id == incomingKey) { "서로 다른 게시물입니다." }
            }
        }
    }

    private fun destination(index: LibraryIndex, draft: PostDraft, ids: List<String>): PostRecord {
        val normalized = FanboxLinks.canonicalPost(draft.sourceUrl)
        val key = FanboxLinks.sourceKey(normalized)
            ?: draft.existingId
            ?: "local:" + digestText((listOf(draft.creator.trim(), draft.title.trim(), draft.year?.toString().orEmpty()) +
                ids.distinct().sorted()).joinToString("\u0000"))
        val post = index.posts.getOrPut(key) { newRecord(key, draft.copy(sourceUrl = normalized)) }
        if (draft.creator.isNotBlank()) post.creator = draft.creator.trim()
        if (draft.title.isNotBlank()) post.title = draft.title.trim()
        if (draft.year != null) post.year = draft.year
        if (normalized.isNotEmpty()) post.url = normalized
        return post
    }

    private fun newRecord(key: String, draft: PostDraft) = PostRecord(key,
        draft.creator.trim().ifEmpty { FanboxLinks.creator(draft.sourceUrl) ?: "미분류" },
        draft.title.trim().ifEmpty {
            FanboxLinks.postId(draft.sourceUrl)?.let { "FANBOX #$it" }
                ?: FanboxLinks.patreonPostId(draft.sourceUrl)?.let { "Patreon #$it" }
                ?: "미분류"
        },
        draft.year, draft.sourceUrl)

    private fun attach(index: LibraryIndex, post: PostRecord, item: Prepared, counter: Counters) {
        index.recordLocation(item.fingerprint.hash, item.fingerprint.size, item.location)
        index.relocated.remove(post.id to item.entry.id)
        if (post.entries.putIfAbsent(item.entry.id, item.entry) == null) counter.added++ else counter.reused++
    }

    private class Counters {
        var added = 0
        var reused = 0
        private var skipped = 0
        private val warnings = mutableListOf<String>()

        fun skip(name: String, ex: Exception) {
            skipped++
            if (warnings.size < 50) warnings.add("$name · ${ex.localizedMessage ?: "파일 읽기 실패"}")
        }
        fun tryFile(name: String, fn: () -> Unit) {
            try { fn() }
            catch (ex: IOException) { skip(name, ex) }
            catch (ex: SecurityException) { skip(name, ex) }
            catch (ex: IllegalArgumentException) { skip(name, ex) }
        }
        fun report() = ImportReport(added, reused, skipped, warnings.toList())
    }

    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
        private val variants = setOf("원본 미확인", "원본", "textless", "SAMPLE", "저해상도")
        private val pieces = Regex("[0-9]+|[^0-9]+")
        private val ordering = Comparator<String> { a, b ->
            val ax = pieces.findAll(a.lowercase(Locale.ROOT)).iterator()
            val bx = pieces.findAll(b.lowercase(Locale.ROOT)).iterator()
            while (ax.hasNext() && bx.hasNext()) {
                val x = ax.next().value
                val y = bx.next().value
                val cmp = if (x[0] in '0'..'9' && y[0] in '0'..'9') {
                    val nx = x.trimStart('0'); val ny = y.trimStart('0')
                    nx.length.compareTo(ny.length).takeIf { it != 0 } ?: nx.compareTo(ny)
                } else x.compareTo(y)
                if (cmp != 0) return@Comparator cmp
            }
            ax.hasNext().compareTo(bx.hasNext()).takeIf { it != 0 } ?: a.compareTo(b)
        }
        private fun naturalCompare(a: String, b: String) = ordering.compare(a, b)
        private fun safeName(raw: String): String {
            val leaf = raw.substringAfterLast('/').substringAfterLast('\\').trim()
            require(leaf.isNotEmpty() && leaf != "." && leaf != ".." && leaf.none { it.isISOControl() }) { "파일 이름이 유효하지 않습니다." }
            return leaf
        }
        private data class Fingerprint(val hash: String, val size: Long)
        private data class Prepared(val fingerprint: Fingerprint, val name: String, val variant: String, val location: Location) {
            val entry = Entry(fingerprint.hash, name, variant)
        }
        private fun fingerprint(stream: InputStream, copyTo: OutputStream? = null, limit: Long = Long.MAX_VALUE): Fingerprint {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            val buffer = ByteArray(65536)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (count > limit - size) throw IOException("전송 파일이 표시된 크기를 초과합니다.")
                digest.update(buffer, 0, count)
                copyTo?.write(buffer, 0, count)
                size += count
            }
            return Fingerprint(digest.digest().hexString(), size)
        }
    }
}
