package app.fanboxlibrary.transfer

import app.fanboxlibrary.shared.LibraryPost
import java.io.File
import java.io.IOException
import java.security.MessageDigest

enum class LocalFileState(val held: Boolean) {
    PRESENT(true), PRESENT_OTHER_NAME(true), LINK_LOCAL_BYTES(false), RECEIVE_BYTES(false)
}
enum class PeerPostState { NEW, CHANGED, COMPLETE }

data class ComparedFile(val remote: FileSummary, val state: LocalFileState)

data class PostComparison(val state: PeerPostState, val files: List<ComparedFile>) {
    val missingIds: Set<String> = files.filterNot { it.state.held }.mapTo(linkedSetOf()) { it.remote.id }

    fun received(ids: Set<String>?): PostComparison {
        val updated = files.map { item ->
            if (!item.state.held && (ids == null || item.remote.id in ids)) item.copy(state = LocalFileState.PRESENT) else item
        }
        return PostComparison(if (updated.all { it.state.held }) PeerPostState.COMPLETE else PeerPostState.CHANGED, updated)
    }
}

fun filterSharedWorks(posts: List<PostSummary>, artist: String?, search: String): List<PostSummary> {
    val term = search.trim()
    return posts.filter { post ->
        (artist == null || artist == post.creator) && (post.creator.contains(term, true) || post.title.contains(term, true))
    }
}

/** One local snapshot per preflight. Identical files in different works are read at most once. */
class LocalInventory(posts: List<LibraryPost>) {
    private val entries = posts.associate { post -> post.id to post.assets.associateBy { it.id } }
    private val copies = posts.flatMap { it.assets }.groupBy { it.sha256 }
    private val verified = mutableMapOf<Pair<String, Long>, Boolean>()

    fun compare(postId: String, remote: List<FileSummary>): PostComparison {
        val local = entries[postId]
        val files = remote.map { file ->
            require(file.sha256.length == 64 && file.size > 0) { "비교에 필요한 PC 파일 정보가 없습니다." }
            val stored = local?.get(file.id)
            val state = when {
                !available(file) -> LocalFileState.RECEIVE_BYTES
                stored?.sha256 == file.sha256 && stored.size == file.size ->
                    if (stored.name == file.name) LocalFileState.PRESENT else LocalFileState.PRESENT_OTHER_NAME
                else -> LocalFileState.LINK_LOCAL_BYTES
            }
            ComparedFile(file, state)
        }
        val state = when {
            local == null -> PeerPostState.NEW
            files.any { !it.state.held } -> PeerPostState.CHANGED
            else -> PeerPostState.COMPLETE
        }
        return PostComparison(state, files)
    }

    private fun available(expected: FileSummary) = verified.getOrPut(expected.sha256 to expected.size) {
        copies[expected.sha256].orEmpty().asSequence().filter { it.size == expected.size }.distinctBy { it.path }.any {
            val file = File(it.path)
            if (!file.isFile || file.length() != expected.size) false else try {
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                file.inputStream().buffered().use { input ->
                    var amount = input.read(buffer)
                    while (amount != -1) {
                        digest.update(buffer, 0, amount)
                        amount = input.read(buffer)
                    }
                }
                val target = expected.sha256.chunked(2).map { pair -> pair.toInt(16).toByte() }.toByteArray()
                MessageDigest.isEqual(target, digest.digest())
            } catch (_: IOException) { false }
        }
    }
}
