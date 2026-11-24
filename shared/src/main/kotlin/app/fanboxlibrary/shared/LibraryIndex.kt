package app.fanboxlibrary.shared

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

private val hexDigits = "0123456789abcdef".toCharArray()

internal fun ByteArray.hexString(): String = buildString(size * 2) {
    for (byte in this@hexString) {
        append(hexDigits[(byte.toInt() ushr 4) and 15])
        append(hexDigits[byte.toInt() and 15])
    }
}

internal fun digestText(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).hexString()

internal data class Location(val path: String, val modified: Long)
internal data class Blob(val size: Long, val locations: MutableList<Location> = mutableListOf())
internal data class Entry(val hash: String, val name: String, val variant: String) {
    val id get() = "$hash:$variant"
}
internal data class PostRecord(val id: String, var creator: String, var title: String, var year: Int?,
                               var url: String, val entries: LinkedHashMap<String, Entry> = linkedMapOf())

internal class LibraryIndex {
    val blobs = linkedMapOf<String, Blob>()
    val posts = linkedMapOf<String, PostRecord>()
    val relocated = linkedSetOf<Pair<String, String>>()
    private val pathHashes = HashMap<String, String>()

    fun recordLocation(hash: String, size: Long, loc: Location) {
        val blob = blobs.getOrPut(hash) { Blob(size) }
        require(blob.size == size) { "같은 지문의 파일 크기가 다릅니다." }
        val previous = pathHashes.put(loc.path, hash)
        if (previous != null && previous != hash) blobs[previous]?.locations?.removeAll { it.path == loc.path }
        val known = blob.locations.indexOfFirst { it.path == loc.path }
        if (known == -1) blob.locations.add(loc) else blob.locations[known] = loc
    }

    fun loadFrom(file: File) {
        if (!file.exists()) return
        DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == VERSION) { "지원하지 않는 보관함 형식입니다." }
            repeat(input.count()) {
                val hash = input.string()
                val blob = Blob(input.readLong())
                repeat(input.count()) {
                    val loc = Location(input.string(), input.readLong())
                    blob.locations.add(loc)
                    pathHashes[loc.path] = hash
                }
                blobs[hash] = blob
            }
            repeat(input.count()) {
                val post = PostRecord(input.string(), input.string(), input.string(), input.readInt().takeIf { it != 0 }, input.string())
                repeat(input.count()) {
                    val entry = Entry(input.string(), input.string(), input.string())
                    if (entry.hash !in blobs) throw IOException("보관함의 파일 목록이 손상되었습니다.")
                    post.entries[entry.id] = entry
                }
                posts[post.id] = post
            }
            repeat(input.count()) { relocated.add(input.string() to input.string()) }
            if (input.readInt() != MAGIC || input.read() != -1) throw IOException("보관함 색인이 손상되었습니다.")
        }
    }

    fun saveTo(file: File) {
        val scratch = File.createTempFile("index-", ".part", file.parentFile)
        try {
            FileOutputStream(scratch).use { stream ->
                val out = DataOutputStream(BufferedOutputStream(stream))
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeInt(blobs.size)
                for ((hash, blob) in blobs) {
                    out.string(hash)
                    out.writeLong(blob.size)
                    out.writeInt(blob.locations.size)
                    for (loc in blob.locations) { out.string(loc.path); out.writeLong(loc.modified) }
                }
                out.writeInt(posts.size)
                for (post in posts.values) {
                    out.string(post.id)
                    out.string(post.creator)
                    out.string(post.title)
                    out.writeInt(post.year ?: 0)
                    out.string(post.url)
                    out.writeInt(post.entries.size)
                    for (item in post.entries.values) {
                        out.string(item.hash)
                        out.string(item.name)
                        out.string(item.variant)
                    }
                }
                out.writeInt(relocated.size)
                for ((post, item) in relocated) {
                    out.string(post)
                    out.string(item)
                }
                out.writeInt(MAGIC)
                out.flush()
                stream.fd.sync()
            }
            // The private data root is on one volume. Fail safely if it cannot provide atomic replacement.
            Files.move(scratch.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            scratch.delete()
        }
    }

    companion object {
        private const val MAGIC = 0x46424C49
        private const val VERSION = 1

        private fun DataInputStream.count(): Int = readInt().also { require(it in 0..5_000_000) { "잘못된 색인입니다." } }

        private fun DataInputStream.string(): String {
            val len = readInt()
            require(len in 0..(1024 * 1024)) { "잘못된 색인 문자열입니다." }
            val bytes = ByteArray(len)
            readFully(bytes)
            return bytes.toString(Charsets.UTF_8)
        }

        private fun DataOutputStream.string(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            require(bytes.size <= 1024 * 1024) { "문자열이 너무 깁니다." }
            writeInt(bytes.size)
            write(bytes)
        }
    }
}
