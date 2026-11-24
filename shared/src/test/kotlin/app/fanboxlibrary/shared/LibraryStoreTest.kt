package app.fanboxlibrary.shared

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.concurrent.Executors
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryStoreTest {
    private fun fixture(body: (File, LibraryStore) -> Unit) {
        val base = Files.createTempDirectory("fanbox-core-test-").toFile()
        try { body(base, LibraryStore(File(base, "private"))) }
        finally { base.deleteRecursively() }
    }
    private fun src(name: String, data: String = name) = ImportSource(name) { data.byteInputStream() }
    private fun draft(id: Int) = PostDraft("sample", "제목", 2025, "https://sample.fanbox.cc/posts/$id")

    @Test fun links() {
        assertEquals("123", FanboxLinks.postId("https://www.fanbox.cc/@sample/posts/123?x=y#part"))
        assertEquals("123", FanboxLinks.postId("https://sample.fanbox.cc/posts/123/"))
        for (url in listOf("http://a.fanbox.cc/posts/123", "https://fanbox.cc@other.test/posts/123",
            "https://a.fanbox.cc.evil.test/posts/123", "data:text/plain,123","https://a.fanbox.cc:444/posts/123")) {
            assertFalse(FanboxLinks.isOfficialUrl(url))
        }
        assertNull(FanboxLinks.postId("https://a.fanbox.cc/posts/123/extra"))
    }

    @Test fun managedDedupViewsAndReopen() = fixture { base, store ->
        val sources = listOf(src("p1.png", "same"), src("p2.png", "other"))
        assertEquals(2, store.importManaged(draft(1), sources).added)
        assertEquals(2, store.importManaged(draft(1), sources).reused)
        assertEquals(1, store.importManaged(draft(2), listOf(src("renamed.png", "same"))).added)
        val posts = LibraryStore(File(base, "private")).posts()
        assertEquals(2, posts.size)
        assertEquals(listOf("p1.png", "p2.png"), posts[0].assets.map { it.name })
        assertEquals(posts[0].assets.first().path, posts[1].assets.single().path)
        assertEquals("same", File(posts[0].assets.first().path).readText())
        assertEquals(2, File(base, "private/files").walk().count { it.isFile })
    }

    @Test fun partialReadsAndTruncatedZipNeverAppearComplete() = fixture { base, store ->
        val zip = File(base, "attachment.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("data.txt")); out.write("ok".toByteArray()); out.closeEntry()
        }
        val failed = ImportSource("bad.jpg") { throw IOException("disconnected") }
        val result = store.importManaged(draft(12), listOf(ImportSource("layers.zip", zip::inputStream), src("cut.zip","PK"), failed))
        assertEquals(1, result.added)
        assertEquals(2, result.skipped)
        val saved = store.posts().single().assets.single()
        assertFalse(saved.isImage)
        assertTrue(File(saved.path).readBytes().contentEquals(zip.readBytes()))
        assertEquals(0, File(base, "private/staging").listFiles().orEmpty().size)
    }

    @Test fun importFolderRecognizesYearVariantsAndSortsWithoutChangingFiles() = fixture { base, store ->
        val artist = File(base, "Sample Artist")
        val dir = File(artist, "수집 이미지/2019/05-31 테스트 [F123456]").apply { mkdirs() }
        val plain = File(dir, "page2.png").apply { writeText("first") }
        File(dir,"page10.png").writeText("second")
        File(dir,"textless").mkdir()
        File(dir,"textless/page2.png").writeText("first")
        File(dir,"SAMPLE").mkdir()
        File(dir,"SAMPLE/page2.png").writeText("preview")
        File(dir, "notes.json").writeText("[]")
        val originalTime = plain.lastModified()
        assertEquals(4, store.indexFolder(artist).added)
        assertEquals(4, store.indexFolder(artist).reused)
        val post = store.posts().single()
        assertEquals("fanbox:123456", post.id)
        assertEquals(2019, post.year)
        assertEquals(listOf("page2.png","page10.png"), post.assets.filter { it.variant == "원본 미확인" }.map { it.name })
        assertEquals(setOf("원본 미확인","textless","SAMPLE"), post.assets.map { it.variant }.toSet())
        assertEquals(originalTime, plain.lastModified())
        assertEquals(0, File(base, "private/files").walk().count { it.isFile })
    }

    @Test fun replacedAndMissingExternalReferencesDoNotOpenWrongBytes() = fixture { base, store ->
        val dir = File(base, "artist").apply { mkdir() }
        val file = File(dir, "x.png").apply { writeText("ab") }
        val oldTimestamp = file.lastModified()
        store.indexFiles(draft(1), listOf(file))
        val old = store.posts().single().assets.single()
        file.writeText("cd")
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(oldTimestamp))
        store.indexFiles(draft(1), listOf(file))
        val assets = store.posts().single().assets
        assertEquals(2, assets.size)
        assertFalse(File(assets.first { it.sha256 == old.sha256 }.path).exists())
        assertEquals("cd", File(assets.first { it.sha256 != old.sha256 }.path).readText())
        assertTrue(file.delete())
        assertTrue(store.posts().single().assets.none { File(it.path).exists() })
    }

    @Test fun explicitFilesCanBeRegroupedAndFolderScanPreservesDecision() = fixture { base, store ->
        val artist = File(base,"artist").apply { mkdir() }
        File(artist, "a.png").writeText("hello")
        File(artist, "b.png").writeText("world")
        store.indexFolder(artist)
        val initial = store.posts().single()
        assertEquals("미분류", initial.title)
        store.regroup(initial.id, setOf(initial.assets[0].id), draft(10))
        store.indexFolder(artist)
        assertEquals(listOf(1,1), store.posts().map { it.assets.size })
    }

    @Test fun conflictingIdsDoNotMergeUnrelatedPosts() = fixture { _, store ->
        store.importManaged(draft(1),listOf(src("a.png")))
        assertFails { store.importManaged(draft(2).copy(existingId = "fanbox:1"), listOf(src("b.png"))) }
        assertEquals(1, store.posts().single().assets.size)
    }

    @Test fun concurrentInstancesKeepBothImports() = fixture { base, store ->
        val second = LibraryStore(File(base, "private"))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit { store.importManaged(draft(1),listOf(src("a.png"))) }
            val b = pool.submit { second.importManaged(draft(2),listOf(src("b.png"))) }
            a.get(); b.get()
        } finally { pool.shutdown() }
        assertEquals(2, store.posts().size)
    }

    @Test fun peerUsesVerifiedBytesAndKeepsStableIdAndVariants() = fixture { _, store ->
        store.importManaged(draft(1), listOf(src("ref.png","same")))
        val source = store.posts().single().assets.single()
        val peer = PeerAssetSource("else.png",source.sha256,source.size,"textless") { error("must reuse local bytes") }
        val id = "local:" + "a".repeat(64)
        val meta = PostDraft("작가","작품",2019)
        assertEquals(1, store.importPeerPost(id,meta,listOf(peer)).added)
        assertEquals(1, store.importPeerPost(id,meta,listOf(peer)).reused)
        val imported = store.posts().single { it.id == id }.assets.single()
        assertEquals("textless", imported.variant)
        assertEquals(source.path, imported.path)
    }

    @Test fun peerDigestMismatchDoesNotPublishPartialPost() = fixture { _, store ->
        val first = PeerAssetSource("a.png",digestText("a"),1,"원본") { "a".byteInputStream() }
        val broken = PeerAssetSource("b.png",digestText("b"),1,"원본") { "x".byteInputStream() }
        assertFails { store.importPeerPost("fanbox:3",draft(3),listOf(first,broken)) }
        assertTrue(store.posts().isEmpty())
        val good = broken.copy(open = { "b".byteInputStream() })
        assertEquals(2, store.importPeerPost("fanbox:3",draft(3),listOf(first,good)).added)
        assertNotEquals(store.posts().single().assets[0].path, store.posts().single().assets[1].path)
        assertFails { store.importPeerPost("fanbox:4",draft(5),listOf(good)) }
    }

    @Test fun peerRejectsPathNamesAndExistingNonArchivesRelabelledAsZip() = fixture { base, store ->
        store.importManaged(draft(1), listOf(src("ref.png", "not a zip")))
        val existing = store.posts().single().assets.single()
        val renamed = PeerAssetSource("ref.zip", existing.sha256, existing.size, "원본") { error("should use local bytes") }
        assertFails { store.importPeerPost("fanbox:2", draft(2), listOf(renamed)) }
        for (bad in listOf("../outside.jpg", "nested\\outside.jpg", "/absolute.jpg")) {
            assertFails { store.importPeerPost("fanbox:2", draft(2), listOf(renamed.copy(name = bad))) }
        }
        assertEquals(1, store.posts().size)
        assertFalse(File(base, "outside.jpg").exists())
    }

    @Test fun archiveWithCorruptedStoredEntryIsNotImported() = fixture { base, store ->
        val payload = "0123456789unicode".toByteArray()
        val archive = File(base, "corrupt.zip")
        ZipOutputStream(archive.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("part").also {
                it.method = ZipEntry.STORED
                it.size = payload.size.toLong()
                it.compressedSize = it.size
                it.crc = CRC32().apply { update(payload) }.value
            })
            out.write(payload); out.closeEntry()
        }
        val bytes = archive.readBytes()
        val offset = bytes.indices.first { bytes[it] == payload[0] && bytes.copyOfRange(it, it + payload.size).contentEquals(payload) }
        bytes[offset] = 100
        archive.writeBytes(bytes)
        assertEquals(1, store.indexFiles(draft(1), listOf(archive)).skipped)
        assertTrue(store.posts().isEmpty())
    }

    @Test fun peerCanRepairDamagedManagedBlobWithoutAlteringExternalFiles() = fixture { _, store ->
        store.importManaged(draft(1), listOf(src("ref.png", "good")))
        val item = store.posts().single().assets.single()
        File(item.path).writeText("oops")
        var opened = 0
        val source = PeerAssetSource("restored.png", item.sha256, item.size, item.variant) {
            opened++
            "good".byteInputStream()
        }
        store.importPeerPost("fanbox:2", draft(2), listOf(source))
        assertEquals(1, opened)
        for (post in store.posts()) assertEquals("good", File(post.assets.single().path).readText())
    }

    @Test fun provenanceUsesExplicitHintsAndPreservesPeerLabels() = fixture { _, store ->
        val sources = listOf(
            src("page.png", "same"),
            ImportSource("original.png") { "same".byteInputStream() },
            ImportSource("page.png","작품/원본") { "second".byteInputStream() },
            ImportSource("page.png","originals/textless") { "third".byteInputStream() },
            src("originality.png", "fourth")
        )
        store.importManaged(draft(1), sources)
        val assets = store.posts().single().assets
        assertEquals(listOf("원본 미확인","원본","원본","textless","원본 미확인"), assets.map { it.variant })
        val peer = assets.map { PeerAssetSource(it.name,it.sha256,it.size,it.variant) { error("reuse bytes") } }
        assertEquals(5, store.importPeerPost("fanbox:2", draft(2), peer).added)
        assertEquals(assets.map { it.variant }, store.posts().single { it.id == "fanbox:2" }.assets.map { it.variant })
    }
}
