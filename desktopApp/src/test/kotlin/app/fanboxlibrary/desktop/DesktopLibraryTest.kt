package app.fanboxlibrary.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import app.fanboxlibrary.shared.*
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

class DesktopLibraryTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun picture(color: Color): ByteArray {
        val image = BufferedImage(580, 740, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = color
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.color = color.brighter()
            graphics.fillOval(80, 80, 400, 400)
        } finally {
            graphics.dispose()
        }
        return ByteArrayOutputStream().use { out -> ImageIO.write(image, "png", out); out.toByteArray() }
    }

    private fun archive(): ByteArray = ByteArrayOutputStream().let { out ->
        ZipOutputStream(out).use {
            it.putNextEntry(ZipEntry("example.txt"))
            it.write("fixture".toByteArray()); it.closeEntry()
        }
        out.toByteArray()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun openIndexedWorkReadPagesAndFindAttachment() {
        val home = temporary.newFolder("library")
        val artist = temporary.newFolder("테스트 작가")
        val folder = File(artist, "2025/05-12 둥근 정원 [F98765]").apply { mkdirs() }
        File(folder, "01.png").writeBytes(picture(Color(92, 134, 111)))
        File(folder, "02.png").writeBytes(picture(Color(145, 115, 163)))
        File(folder, "자료.zip").writeBytes(archive())
        val storage = LibraryStore(home)
        storage.indexFolder(artist)
        val post = storage.posts().single()
        runComposeUiTest {
            setContent {
                FanboxLibraryTheme(darkTheme = true) {
                    LibraryApp(owner = null, root = home)
                }
            }
            waitUntil(timeoutMillis = 15_000) { onAllNodesWithText(post.title).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(post.title).assertIsDisplayed()
            capture("shelf")
            onNodeWithText(post.title).performClick()
            onNodeWithText("1 / 2").assertIsDisplayed()
            waitUntil(timeoutMillis = 15_000) { onAllNodesWithContentDescription("01.png").fetchSemanticsNodes().isNotEmpty() }
            capture("reader")
            onNodeWithText("다음").performClick()
            onNodeWithText("2 / 2").assertIsDisplayed()
            onNodeWithText("첨부 1").performClick()
            onNodeWithText("자료.zip").assertIsDisplayed()
            onNodeWithText("다른 이름으로 저장").assertIsDisplayed()
            onNodeWithText("‹ 보관함").performClick()
            onNodeWithText(post.title).assertIsDisplayed()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun arrowKeysSwitchWorksInReader() {
        val home = temporary.newFolder("library-nav")
        val artist = temporary.newFolder("작가A")
        val first = File(artist, "2025/작품 B [F200]").apply { mkdirs() }
        val second = File(artist, "2024/작품 A [F100]").apply { mkdirs() }
        File(first, "01.png").writeBytes(picture(Color(90, 120, 170)))
        File(second, "01.png").writeBytes(picture(Color(180, 95, 110)))
        val storage = LibraryStore(home)
        storage.indexFolder(artist)
        runComposeUiTest {
            setContent {
                FanboxLibraryTheme(darkTheme = true) {
                    LibraryApp(owner = null, root = home)
                }
            }
            waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("작품 B").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("작품 B").performClick()
            onNodeWithText("작품 1/2").assertIsDisplayed()
            // Plain DirectionRight at the last image must NOT skip to the next work
            onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            onNodeWithText("작품 1/2").assertIsDisplayed()
            // PageDown / PageUp switches between works intentionally
            onRoot().performKeyInput { pressKey(Key.PageDown) }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("작품 2/2").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("작품 A").assertIsDisplayed()
            onRoot().performKeyInput { pressKey(Key.PageUp) }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("작품 1/2").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("작품 B").assertIsDisplayed()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.capture(name: String) {
        val directory = File("build/reports/ui-smoke").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    @Test fun managedAttachmentExportsUnderItsOwnNameAndCannotOverwrite() {
        val home = temporary.newFolder("library")
        val bytes = archive()
        val library = LibraryStore(home)
        library.importManaged(PostDraft(title = "부록"), listOf(ImportSource("부록.zip") { bytes.inputStream() }))
        val asset = library.posts().single().assets.single()
        assertTrue(canOpen(asset))
        val target = File(temporary.root, "export.zip")
        exportLibraryFile(asset, target)
        assertArrayEquals(bytes, target.readBytes())
        target.writeText("keep")
        assertThrows(IllegalArgumentException::class.java) { exportLibraryFile(asset, target) }
        assertEquals("keep", target.readText())
    }

    @Test fun patreonProseMirrorAndExternalDropboxGateParsingWorks() {
        // 1. Creator-prefixed Patreon URLs must parse post ID and import cleanly into LibraryStore
        assertEquals("174853749", FanboxLinks.patreonPostId("https://www.patreon.com/robokeh/posts/my-affair-with-3-174853749"))
        assertEquals("174853749", FanboxLinks.patreonPostId("https://www.patreon.com/c/robokeh/posts/174853749"))
        assertEquals("174853749", FanboxLinks.patreonPostId("https://www.patreon.com/posts/my-affair-with-3-174853749"))

        val lib = LibraryStore(temporary.newFolder("patreon-lib"))
        lib.importManaged(
            PostDraft(
                creator = "Robokeh",
                title = "My Affair With Kotaro",
                year = 2026,
                sourceUrl = "https://www.patreon.com/robokeh/posts/my-affair-with-3-174853749",
            ),
            listOf(ImportSource("01.png") { picture(Color.CYAN).inputStream() }),
        )
        assertEquals("patreon:174853749", lib.posts().single().id)

        // 2. ProseMirror content_json_string extracts readable text, bullet list, and external gate link
        val proseMirrorJson = """
            {"type":"doc","content":[
              {"type":"paragraph","content":[{"type":"text","text":"Hello dear Patrons!"}]},
              {"type":"bulletList","content":[
                {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Zoro x Laxus"}]}]},
                {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Mash x Natsu"}]}]}
              ]},
              {"type":"heading","attrs":{"level":3},"content":[{"type":"text","text":"HD Comic Reward"}]},
              {"type":"heading","attrs":{"level":1},"content":[
                {"type":"text","marks":[{"type":"link","attrs":{"href":"https://robokeh.org/my-affair-with-kotaro-comic-hd-link/"}}],"text":"https://robokeh.org/my-affair-with-kotaro-comic-hd-link/"}
              ]}
            ]}
        """.trimIndent()
        val parsed = DesktopPatreonClient.parsePatreonAttributes(
            mapOf("content" to null, "content_json_string" to proseMirrorJson),
        )
        assertTrue(parsed.text.contains("Hello dear Patrons!"))
        assertTrue(parsed.text.contains("• Zoro x Laxus"))
        assertEquals(1, parsed.links.size)
        assertEquals("https://robokeh.org/my-affair-with-kotaro-comic-hd-link/", parsed.links.single().url)
        assertEquals(ExternalLinkKind.PATREON_GATE, parsed.links.single().kind)

        // 3. Dropbox dl=1 normalization preserves rlkey & st tokens and decodes HTML &#038;
        val normalized = ExternalRewardDownloader.normalizeDirectDownloadUrl(
            "https://www.dropbox.com/scl/fo/abc123xyz/h?rlkey=mySecretKey&#038;st=tok99&amp;dl=0",
        )
        assertEquals("https://www.dropbox.com/scl/fo/abc123xyz/h?rlkey=mySecretKey&st=tok99&dl=1", normalized)

        // 4. WordPress patreon-connect locked & unlocked HTML parsing
        val lockedHtml = """
            <div>
              <a href="https://robokeh.org/patreon-flow/?patreon-unlock-post=148">Unlock with Patreon</a>
            </div>
        """.trimIndent()
        val lockedAnalysis = ExternalRewardDownloader.parseGateHtml("https://robokeh.org/my-affair/", lockedHtml)
        assertTrue(lockedAnalysis.rewards.isEmpty())
        assertEquals("https://robokeh.org/patreon-flow/?patreon-unlock-post=148", lockedAnalysis.patreonUnlockUrl)

        val unlockedHtml = """
            <h3>September Sketch Pack</h3>
            <p><a href="https://www.dropbox.com/scl/fo/sketch111/h?rlkey=k1&#038;dl=0">Download</a></p>
            <h3>Kotaro Part 1 PSD + Textless</h3>
            <p><a href="https://www.dropbox.com/scl/fo/psd222/h?rlkey=k2&amp;dl=0">Dropbox</a></p>
        """.trimIndent()
        val unlockedAnalysis = ExternalRewardDownloader.parseGateHtml("https://robokeh.org/deluxe/", unlockedHtml)
        assertEquals(2, unlockedAnalysis.rewards.size)
        assertTrue(unlockedAnalysis.rewards[0].label.contains("September Sketch Pack"))
        assertEquals("https://www.dropbox.com/scl/fo/sketch111/h?rlkey=k1&dl=1", unlockedAnalysis.rewards[0].directDownloadUrl)
        assertTrue(unlockedAnalysis.rewards[1].label.contains("Kotaro Part 1 PSD"))
        assertEquals("https://www.dropbox.com/scl/fo/psd222/h?rlkey=k2&dl=1", unlockedAnalysis.rewards[1].directDownloadUrl)

        // 5. Multi-page PDF renders cleanly into comic page JPEGs
        val pdfFile = File(temporary.root, "sample_comic.pdf")
        PDDocument().use { doc ->
            repeat(2) { idx ->
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.setNonStrokingColor(if (idx == 0) Color.BLUE else Color.RED)
                    cs.addRect(50f, 50f, 300f, 400f)
                    cs.fill()
                }
            }
            doc.save(pdfFile)
        }
        val outDir = temporary.newFolder("pdf-pages")
        val rendered = ExternalRewardDownloader.renderPdfPagesToJpeg(pdfFile, "sample_comic", outDir) {}
        assertEquals(2, rendered.size)
        assertTrue(rendered.all { it.isFile && it.length() > 1000 })

        // 6. Pagination URLs with brackets [campaign_id], [cursor] and FANBOX pageUrls must pass validation
        assertTrue(
            DesktopPatreonClient.isAllowedPatreonUrl(
                "https://www.patreon.com/api/posts?filter[campaign_id]=5269893&fields[post]=title,content&page[cursor]=03%3Aabc",
            ),
        )
        assertTrue(
            DesktopFanboxClient.isAllowedApiUrl(
                "https://api.fanbox.cc/post.listCreator?creatorId=kansuke&firstPublishedDatetime=2023-10-05%2011%3A29%3A32&firstId=6000000&sort=newest&limit=10",
            ),
        )
    }
}
