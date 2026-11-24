package app.fanboxlibrary.transfer

import app.fanboxlibrary.shared.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import kotlin.test.*

class ExchangeTest {
    @Test fun pairedSelectiveSync() {
        val dir = Files.createTempDirectory("folio-transfer").toFile()
        try {
            val pc = LibraryStore(File(dir, "pc"))
            val phone = LibraryStore(File(dir, "phone"))
            fun src(name: String, content: String) = ImportSource(name) { content.byteInputStream() }
            val draft = PostDraft("sample", "Offline", 2025, "https://sample.fanbox.cc/posts/100")
            pc.importManaged(draft, listOf(src("1.png","p1"), src("2.png","p2")))
            pc.importManaged(PostDraft(title = "Private", sourceUrl = "https://sample.fanbox.cc/posts/101"),
                listOf(src("x.png","x")))
            val offered = pc.posts().first { it.sourceUrl.endsWith("/100") }
            val identity = HeldCertificate.Builder().commonName("local test").build()
            val context = HandshakeCertificates.Builder().heldCertificate(identity).build().sslContext()
            PairHost(InetAddress.getByName("127.0.0.1"), context, identity.certificate, pc, listOf(offered), true).use { host ->
                val code = host.pairing.encode()
                PairClient(PairingAddress.parse(code)).use { client ->
                    assertEquals(listOf(offered.id), client.available().map { it.id })
                    val file = client.files(offered.id).first()
                    assertEquals(1, client.pull(offered.id, phone, setOf(file.id)).added)
                    assertEquals(file.name, phone.posts().single().assets.single().name)
                    assertEquals(1, client.pull(offered.id, phone).added)
                    assertEquals(2, client.pull(offered.id, phone).reused)
                    assertFails { client.pull("fanbox:101", phone) }
                    phone.importManaged(PostDraft(title = "From phone", sourceUrl = "https://sample.fanbox.cc/posts/200"),
                        listOf(src("mobile.png", "test push")))
                    client.push(phone.posts().first { it.sourceUrl.endsWith("/200") })
                    assertEquals("test push", File(pc.posts().first { it.sourceUrl.endsWith("/200") }.assets.single().path).readText())
                }
                // A different session secret or certificate pin cannot open the catalogue.
                for (field in listOf(3, 4)) {
                    val parts = code.split(" ").toMutableList()
                    parts[field] = (if (parts[field].first() == 'A') "B" else "A") + parts[field].drop(1)
                    assertFails { PairClient(PairingAddress.parse(parts.joinToString(" "))).use { it.available() } }
                }
            }
            PairHost(InetAddress.getByName("127.0.0.1"), context, identity.certificate, pc, emptyList(), false).use { host ->
                PairClient(host.pairing).use { client ->
                    assertTrue(client.available().isEmpty())
                    assertFails { client.push(offered) }
                }
            }
        } finally { dir.deleteRecursively() }
    }
}
