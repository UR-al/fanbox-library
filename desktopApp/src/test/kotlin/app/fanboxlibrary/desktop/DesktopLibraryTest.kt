package app.fanboxlibrary.desktop

import androidx.compose.material.MaterialTheme
import androidx.compose.material.lightColors
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import app.fanboxlibrary.shared.*
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
        } finally { graphics.dispose() }
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
                MaterialTheme(colors = lightColors(primary = violet, secondary = violet, background = paper, onBackground = ink)) {
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
}
