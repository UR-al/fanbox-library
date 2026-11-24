package app.fanboxlibrary.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.fanboxlibrary.shared.ImportSource
import app.fanboxlibrary.shared.LibraryStore
import app.fanboxlibrary.shared.PostDraft
import app.fanboxlibrary.transfer.PairClient
import app.fanboxlibrary.transfer.PairingAddress
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairedWindowsTest {
    @Test fun pullThenPushAndReuseOverPinnedTls() {
        val argument = InstrumentationRegistry.getArguments().getString("fixtureCode")
        assumeTrue("Requires the loopback PairingFixture host", !argument.isNullOrEmpty())
        val pairing = PairingAddress.parse(String(Base64.getUrlDecoder().decode(argument), Charsets.UTF_8))
        require(pairing.address.isLoopbackAddress)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "peer-test-").toFile()
        try {
            val store = LibraryStore(root)
            PairClient(pairing).use { peer ->
                val post = peer.available().single()
                val listed = peer.inventory(post.id).single()
                assertEquals("windows.zip", listed.name)
                assertEquals(1, peer.pull(post.id, store, setOf(listed.id)).added)
                val imported = store.posts().single().assets.single()
                assertEquals(listed.sha256, imported.sha256)
                ZipFile(File(imported.path)).use { zip ->
                    val text = zip.getInputStream(zip.getEntry("windows.txt")).use { it.readBytes() }
                    assertArrayEquals("fixture-from-windows".repeat(10000).toByteArray(), text)
                }
                val phone = PostDraft(title = "폰 테스트", sourceUrl = "https://fixture.fanbox.cc/posts/880000002")
                val bytes = ByteArrayOutputStream()
                ZipOutputStream(bytes).use {
                    it.putNextEntry(ZipEntry("phone.txt")); it.write("fixture-from-android".toByteArray())
                }
                assertEquals(1, store.importManaged(phone,
                    listOf(ImportSource("phone.zip") { bytes.toByteArray().inputStream() })).added)
                peer.push(store.posts().single { it.sourceUrl == phone.sourceUrl })
                // The fixture puts its original file aside after the first transfer.
                // A second successful pull therefore cannot have fetched the remote bytes again.
                val duplicate = peer.pull(post.id, store, setOf(listed.id))
                assertEquals(1, duplicate.reused)
                assertEquals(0, duplicate.added)
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
