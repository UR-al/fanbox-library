package app.fanboxlibrary.shared

import net.lingala.zip4j.ZipFile as Zip4jFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64
import java.util.zip.ZipFile
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SmartArchiveAndMetaTest {
    @Test
    fun passwordExtractionAndEncryptedZipUnpackWorks() {
        val text = """
            Thank you for supporting!
            DL Pass: kotaro2026
            비번: backup99
            パスワード：sakura77
        """.trimIndent()
        val cands = PasswordExtractor.extractCandidates(text)
        assertTrue("kotaro2026" in cands)
        assertTrue("backup99" in cands)
        assertTrue("sakura77" in cands)

        val dir = Files.createTempDirectory("pwzip-test-").toFile()
        try {
            val plainImg = File(dir, "01.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)) }
            val encZip = File(dir, "secret.zip")
            val z4 = Zip4jFile(encZip, "kotaro2026".toCharArray())
            val params = ZipParameters().apply {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            }
            z4.addFile(plainImg, params)
            assertTrue(ArchiveUnpacker.isZipEncrypted(encZip))

            val outDir = File(dir, "out")
            val unpacked = ArchiveUnpacker.extractZipWithPasswords(encZip, outDir, listOf("wrong1", "kotaro2026"))
            assertEquals(1, unpacked.size)
            assertEquals("01.png", unpacked.single().leafName)
            assertEquals(8L, unpacked.single().file.length())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun megaKeyDerivationAndAttrDecryptionWorks() {
        val raw32 = ByteArray(32) { (it + 1).toByte() }
        val keyB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(raw32)
        val handle = MegaDownloader.parseMegaFileUrl("https://mega.nz/file/AbCdEf12#$keyB64")
        assertNotNull(handle)
        assertEquals("AbCdEf12", handle.publicId)
        assertEquals(16, handle.aesKey.size)
        assertEquals(16, handle.ctrIv.size)

        // Encrypt a MEGA{"n":"comic_pack.zip"} attribute block with AES-CBC zero IV
        val attrPlain = """MEGA{"n":"comic_pack.zip"}""".toByteArray(StandardCharsets.UTF_8)
        val padded = attrPlain.copyOf(((attrPlain.size + 15) / 16) * 16)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(handle.aesKey, "AES"), IvParameterSpec(ByteArray(16)))
        val encAt = Base64.getUrlEncoder().withoutPadding().encodeToString(cipher.doFinal(padded))

        assertEquals("comic_pack.zip", MegaDownloader.decryptAttributesFileName(encAt, handle.aesKey))
    }

    @Test
    fun libraryMetaCoverOrderFavoritesResumeExportAndDeleteWorks() {
        val dir = Files.createTempDirectory("meta-lib-test-").toFile()
        try {
            val store = LibraryStore(File(dir, "lib"))
            store.importManaged(
                PostDraft(creator = "Robokeh", title = "Kotaro Ep1", year = 2026),
                listOf(
                    ImportSource("10.png") { byteArrayOf(10, 20, 30).inputStream() },
                    ImportSource("02.png") { byteArrayOf(2, 3, 4).inputStream() },
                    ImportSource("01.png") { byteArrayOf(1, 2, 3).inputStream() },
                ),
            )
            var post = store.posts().single()
            assertFalse(post.isFavorite)

            // Natural sort fixes 10, 02, 01 -> 01, 02, 10
            store.sortPostAssetsNaturally(post.id)
            post = store.posts().single()
            assertEquals(listOf("01.png", "02.png", "10.png"), post.assets.map { it.name })

            // Move 02.png forward to index 0
            val secondId = post.assets[1].id
            store.moveAsset(post.id, secondId, -1)
            post = store.posts().single()
            assertEquals("02.png", post.assets.first().name)

            // Custom cover, favorite, bookmark, lastReadPage
            val thirdId = post.assets[2].id
            store.setCoverAsset(post.id, thirdId)
            assertTrue(store.toggleFavoritePost(post.id))
            assertTrue(store.toggleBookmarkAsset(post.id, thirdId))
            store.saveLastReadPage(post.id, 2)

            post = store.posts().single()
            assertTrue(post.isFavorite)
            assertEquals(2, post.lastReadPage)
            assertEquals(thirdId, post.coverImage()?.id)
            assertTrue(thirdId in post.bookmarkedAssetIds)

            // Export to clean folder tree & .cbz
            val folderOut = File(dir, "backup-folders")
            val count = LibraryExporter.exportPostsToCleanFolderTree(listOf(post), folderOut)
            assertEquals(3, count)

            val cbzFile = File(dir, "kotaro.cbz")
            val cbzPages = LibraryExporter.exportPostToCbz(post, cbzFile)
            assertEquals(3, cbzPages)
            ZipFile(cbzFile).use { zf ->
                assertNotNull(zf.getEntry("ComicInfo.xml"))
                assertNotNull(zf.getEntry("0001.png"))
            }

            // Delete whole post
            assertTrue(store.deletePost(post.id))
            assertTrue(store.posts().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}
