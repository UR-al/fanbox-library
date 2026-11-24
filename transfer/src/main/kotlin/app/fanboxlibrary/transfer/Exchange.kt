package app.fanboxlibrary.transfer

import app.fanboxlibrary.shared.*
import java.io.*
import java.net.Socket
import java.security.MessageDigest
import java.security.DigestOutputStream
import java.nio.file.Files

data class PostSummary(val id: String, val creator: String, val title: String, val count: Int)
data class FileSummary(val id: String, val name: String, val variant: String, val sha256: String = "", val size: Long = -1)
private const val MAX_ITEMS = 65_536
private const val NEXT = 1
private const val DONE = 2
internal const val LIST = 1
internal const val DOWNLOAD = 2
internal const val UPLOAD = 3
internal const val DETAILS = 4
internal const val DIGEST = 5

internal class Channel(socket: Socket) {
    val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

    fun list(posts: Collection<LibraryPost>) = with(output) {
        require(posts.size <= MAX_ITEMS)
        writeInt(posts.size)
        for (p in posts) {
            writeUTF(p.id); writeUTF(p.creator); writeUTF(p.title); writeInt(p.assets.size)
        }
        flush()
    }
    fun readList(): List<PostSummary> = with(input) {
        List(count()) { PostSummary(readUTF(), readUTF(), readUTF(), count()) }
    }
    fun files(post: LibraryPost, withDigests: Boolean = false) = with(output) {
        require(post.assets.size <= MAX_ITEMS)
        writeInt(post.assets.size)
        for (a in post.assets) {
            writeUTF(a.id); writeUTF(a.name); writeUTF(a.variant)
            if (withDigests) { writeUTF(a.sha256); writeLong(a.size) }
        }
        flush()
    }
    fun readFiles(withDigests: Boolean = false): List<FileSummary> = with(input) {
        List(count()) {
            val file = FileSummary(readUTF(), readUTF(), readUTF())
            if (withDigests) {
                val hash = readUTF()
                val size = readLong()
                require(hash.length == 64 && hash.all { it in '0'..'9' || it in 'a'..'f' } && size > 0)
                file.copy(sha256 = hash, size = size)
            } else file
        }.also { items -> require(items.distinctBy { it.id }.size == items.size) }
    }
    fun writeSelection(ids: Set<String>?) = with(output) {
        writeBoolean(ids != null)
        if (ids != null) {
            require(ids.size in 1..MAX_ITEMS)
            writeInt(ids.size)
            ids.forEach(::writeUTF)
        }
        flush()
    }
    fun selected(post: LibraryPost): LibraryPost {
        if (!input.readBoolean()) return post
        val ids = List(input.count()) { input.readUTF() }.toSet()
        val chosen = post.assets.filter { it.id in ids }
        require(chosen.isNotEmpty() && chosen.size == ids.size)
        return post.copy(assets = chosen)
    }
    private fun DataInputStream.count() = readInt().also { require(it in 0..MAX_ITEMS) }

    fun send(post: LibraryPost) {
        val files = post.assets.associateBy { it.sha256 }
        with(output) {
            writeUTF(post.id); writeUTF(post.creator); writeUTF(post.title)
            writeInt(post.year ?: 0); writeUTF(post.sourceUrl)
            require(post.assets.size <= MAX_ITEMS)
            writeInt(post.assets.size)
            for (a in post.assets) {
                writeUTF(a.name); writeUTF(a.sha256); writeLong(a.size); writeUTF(a.variant)
            }
            flush()
        }
        while (true) {
            when (input.readUnsignedByte()) {
                DONE -> return
                NEXT -> {
                    val asset = files[input.readUTF()] ?: throw IOException("작품에 없는 파일입니다.")
                    val file = File(asset.path)
                    require(file.isFile && file.length() == asset.size) { "원본 파일이 변경되었습니다." }
                    sendVerifiedFile(file, asset)
                    output.flush()
                }
                else -> throw IOException("알 수 없는 전송 요청입니다.")
            }
        }
    }

    private fun sendVerifiedFile(file: File, asset: LibraryAsset) {
        // External folders may change while this dialog is open. Pin the exact bytes offered by the owner.
        val tmp = Files.createTempFile("folio-send-", ".part").toFile()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val length = file.inputStream().use { src ->
                DigestOutputStream(tmp.outputStream(), digest).use { out -> Slice(src, asset.size).copyTo(out) }
            }
            val expected = asset.sha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            require(length == asset.size && MessageDigest.isEqual(expected, digest.digest())) { "공유할 원본이 변경되었습니다." }
            tmp.inputStream().use { it.copyTo(output) }
        } finally { tmp.delete() }
    }

    fun receive(store: LibraryStore, expectedId: String? = null): ImportReport {
        val id = input.readUTF()
        require(expectedId == null || id == expectedId)
        val draft = PostDraft(input.readUTF(), input.readUTF(), input.readInt().takeIf { it != 0 }, input.readUTF())
        val assets = List(input.count()) {
            val name = input.readUTF()
            val sha = input.readUTF()
            val size = input.readLong()
            require(size >= 0)
            val variant = input.readUTF()
            PeerAssetSource(name, sha, size, variant) {
                output.writeByte(NEXT); output.writeUTF(sha); output.flush()
                Slice(input, size)
            }
        }
        val report = store.importPeerPost(id, draft, assets)
        output.writeByte(DONE)
        output.flush()
        return report
    }
}

/** A file shares a connection with subsequent frames. Reaching its boundary is EOF for this view only. */
private class Slice(private val source: InputStream, private var remaining: Long) : InputStream() {
    override fun read(): Int {
        if (remaining == 0L) return -1
        val byte = source.read()
        if (byte < 0) throw EOFException("전송이 중단되었습니다.")
        remaining--
        return byte
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (remaining == 0L) return -1
        val n = source.read(b, off, minOf(remaining, len.toLong()).toInt())
        if (n < 0) throw EOFException("전송이 중단되었습니다.")
        remaining -= n
        return n
    }
}

class PairClient(private val pairing: PairingAddress) : Closeable {
    private var active: Socket? = null
    @Volatile private var closed = false
    override fun close() = synchronized(this) { closed = true; active?.close(); Unit }
    private fun <T> request(op: Int, block: (Channel) -> T): T = pairing.connect().use { socket ->
        synchronized(this) { check(!closed && active == null); active = socket }
        try {
            val c = Channel(socket)
            c.output.writeUTF("FBL1")
            c.output.write(pairing.secret)
            c.output.writeByte(op)
            c.output.flush()
            check(c.input.readBoolean()) {
                if (op == DIGEST) "PC에서 누락분 비교를 사용할 수 없습니다. PC 앱과 연결 코드를 확인하거나 파일을 직접 선택하세요."
                else "PC가 요청을 허용하지 않았습니다. 연결 코드와 받기 설정을 확인하세요."
            }
            block(c)
        } finally { synchronized(this) { active = null } }
    }
    fun available(): List<PostSummary> = request(LIST) { it.readList() }
    fun files(id: String): List<FileSummary> = request(DETAILS) {
        it.output.writeUTF(id); it.output.flush()
        it.readFiles()
    }
    fun inventory(id: String): List<FileSummary> = request(DIGEST) {
        it.output.writeUTF(id); it.output.flush()
        it.readFiles(withDigests = true)
    }
    fun pull(id: String, store: LibraryStore, ids: Set<String>? = null): ImportReport = request(DOWNLOAD) {
        it.output.writeUTF(id); it.writeSelection(ids)
        it.receive(store, id)
    }
    fun push(post: LibraryPost) = request(UPLOAD) { it.send(post) }
}
