package app.fanboxlibrary.transfer

import app.fanboxlibrary.shared.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import kotlin.test.*

class PreflightTest {
    private fun source(name: String, data: String) = ImportSource(name) { data.byteInputStream() }
    private fun work(id: Int) = PostDraft("artist", "work $id", 2025, "https://artist.fanbox.cc/posts/$id")
    private fun LibraryPost.inventory() = assets.map { FileSummary(it.id, it.name, it.variant, it.sha256, it.size) }

    @Test fun matchesRevisionsRenamesMissingFilesAndMissingWorks() {
        val dir = Files.createTempDirectory("folio-delta").toFile()
        try {
            val phone = LibraryStore(File(dir, "phone"))
            val pc = LibraryStore(File(dir, "pc"))
            phone.importManaged(work(1), listOf(source("cover.png", "Cover"), source("page.png", "before"),
                source("gone.png", "Gone"), source("corrupt.png", "real")))
            phone.importManaged(work(9), listOf(source("other-name.png", "Reuse")))
            pc.importManaged(work(1), listOf(source("cover.png", "Cover"), source("page.png", "after"),
                source("gone.png", "Gone"), source("renamed.png", "Reuse"), source("corrupt.png", "real")))
            pc.importManaged(work(2), listOf(source("new.png", "Reuse")))
            val local = phone.posts()
            val first = local.first { it.id == "fanbox:1" }
            File(first.assets.first { it.name == "gone.png" }.path).delete()
            val damaged = File(first.assets.first { it.name == "corrupt.png" }.path)
            val stamp = damaged.lastModified()
            damaged.writeText("fake"); damaged.setLastModified(stamp)

            val remote = pc.posts().associateBy { it.id }
            val index = LocalInventory(local)
            val changed = index.compare(first.id, remote.getValue(first.id).inventory())
            assertEquals(PeerPostState.CHANGED, changed.state)
            assertEquals(mapOf("cover.png" to LocalFileState.PRESENT, "page.png" to LocalFileState.RECEIVE_BYTES,
                "gone.png" to LocalFileState.RECEIVE_BYTES, "renamed.png" to LocalFileState.LINK_LOCAL_BYTES,
                "corrupt.png" to LocalFileState.RECEIVE_BYTES), changed.files.associate { it.remote.name to it.state })
            assertEquals(4, changed.missingIds.size)
            val new = index.compare("fanbox:2", remote.getValue("fanbox:2").inventory())
            assertEquals(PeerPostState.NEW, new.state)
            assertEquals(LocalFileState.LINK_LOCAL_BYTES, new.files.single().state)
            assertEquals(PeerPostState.COMPLETE, index.compare(first.id, changed.files.filter {
                it.state == LocalFileState.PRESENT }.map { it.remote }).state)
        } finally { dir.deleteRecursively() }
    }

    @Test fun artistAndSearchTogetherLimitComparedPosts() {
        val posts = listOf(PostSummary("1","A","New cover",1), PostSummary("2","B","New cover",1), PostSummary("3","A","Study",1))
        assertEquals(listOf("1"), filterSharedWorks(posts, "A", " COVER ").map { it.id })
        assertEquals(listOf("1", "2"), filterSharedWorks(posts, null, "new").map { it.id })
    }

    @Test fun samePostSameVariantRenameIsHeldButOtherVariantNeedsLocalReference() {
        val dir = Files.createTempDirectory("folio-alias").toFile()
        try {
            val phone = LibraryStore(File(dir, "phone"))
            val pc = LibraryStore(File(dir, "pc"))
            phone.importManaged(work(4), listOf(source("old-name.png", "page")))
            pc.importManaged(work(4), listOf(source("new-name.png", "page"),
                ImportSource("version.png", "textless/version.png") { "page".byteInputStream() }))
            val remote = pc.posts().single()
            val diff = LocalInventory(phone.posts()).compare(remote.id, remote.inventory())
            assertEquals(LocalFileState.PRESENT_OTHER_NAME, diff.files.first { it.remote.name == "new-name.png" }.state)
            assertEquals(LocalFileState.LINK_LOCAL_BYTES, diff.files.first { it.remote.name == "version.png" }.state)
            assertEquals(setOf(remote.assets.first { it.name == "version.png" }.id), diff.missingIds)
            val completed = diff.received(diff.missingIds)
            assertEquals(PeerPostState.COMPLETE, completed.state)
            assertTrue(completed.missingIds.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun tlsDigestAndSelectiveTransferReusesLocalBytesAndKeepsPreviousRevision() {
        val dir = Files.createTempDirectory("folio-delta-tls").toFile()
        try {
            val phone = LibraryStore(File(dir, "phone")); val pc = LibraryStore(File(dir, "pc"))
            val external = File(dir, "renamed.png").apply { writeText("same bytes") }
            pc.indexFiles(work(3), listOf(external))
            pc.importManaged(work(3), listOf(source("revision.png", "second"), source("sample.png", "sample")))
            phone.importManaged(work(3), listOf(source("revision.png", "first"), source("sample.png", "sample")))
            phone.importManaged(work(4), listOf(source("different-name.png", "same bytes")))
            val offered = pc.posts().single()
            val identity = HeldCertificate.Builder().commonName("Delta test").build()
            val context = HandshakeCertificates.Builder().heldCertificate(identity).build().sslContext()
            PairHost(InetAddress.getByName("127.0.0.1"), context, identity.certificate, pc, listOf(offered), false).use { host ->
                PairClient(host.pairing).use { client ->
                    val remote = client.inventory(offered.id)
                    assertEquals(offered.inventory(), remote)
                    val diff = LocalInventory(phone.posts()).compare(offered.id, remote)
                    assertEquals(2, diff.missingIds.size)
                    // The PC cannot serve this file any more. Pull must link the renamed reference using the phone's copy.
                    assertTrue(external.delete())
                    client.pull(offered.id, phone, diff.missingIds)
                    val saved = phone.posts().first { it.id == offered.id }.assets
                    assertEquals(setOf("first", "second"), saved.filter { it.name == "revision.png" }.map { File(it.path).readText() }.toSet())
                    assertEquals("same bytes", File(saved.single { it.name == "renamed.png" }.path).readText())
                    assertEquals(PeerPostState.COMPLETE, LocalInventory(phone.posts()).compare(offered.id, remote).state)
                }
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun senderRejectsChangedSourceEvenWithIdenticalSizeAndTimestamp() {
        val dir = Files.createTempDirectory("folio-source-changed").toFile()
        try {
            val pc = LibraryStore(File(dir, "pc")); val phone = LibraryStore(File(dir, "phone"))
            val source = File(dir, "image.png").apply { writeText("abcd") }
            pc.indexFiles(work(8), listOf(source))
            val offered = pc.posts().single()
            val stamp = source.lastModified()
            source.writeText("wxyz"); source.setLastModified(stamp)
            val identity = HeldCertificate.Builder().commonName("Source test").build()
            val context = HandshakeCertificates.Builder().heldCertificate(identity).build().sslContext()
            PairHost(InetAddress.getByName("127.0.0.1"), context, identity.certificate, pc, listOf(offered), false).use { host ->
                PairClient(host.pairing).use { peer -> assertFails { peer.pull(offered.id, phone) } }
            }
            assertTrue(phone.posts().isEmpty())
        } finally { dir.deleteRecursively() }
    }
}
