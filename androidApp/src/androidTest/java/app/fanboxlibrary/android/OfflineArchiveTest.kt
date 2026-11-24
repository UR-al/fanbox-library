package app.fanboxlibrary.android

import android.app.Activity
import android.app.Instrumentation
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.filters.SdkSuppress
import app.fanboxlibrary.shared.LibraryStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.hamcrest.Matchers.allOf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class OfflineArchiveTest {
    @get:Rule val ui = createEmptyComposeRule()

    @Test fun browseDispatchesOfficialHttpsPageToBrowser() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            Intents.init()
            try {
                val official = allOf(hasAction(Intent.ACTION_VIEW), hasData(Uri.parse("https://www.fanbox.cc/")))
                Intents.intending(official).respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
                ui.onNodeWithText("내 보관함").performClick()
                ui.onNodeWithText("FANBOX 열기").performClick()
                Intents.intended(official)
            } finally {
                Intents.release()
            }
        }
    }

    @Test fun sharedPostImportsReadsAndReopensWithoutDuplicateAssets() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = LibraryStore(File(context.filesDir, "library"))
        val token = System.currentTimeMillis().toString()
        val url = "https://library-test.fanbox.cc/posts/$token"
        val title = "기기 검증 $token"
        val png = ByteArrayOutputStream().use { output ->
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.rgb(66, 95, 82))
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
        val zip = ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use {
                it.putNextEntry(ZipEntry("fixture.txt"))
                it.write("offline-test".toByteArray())
            }
            bytes.toByteArray()
        }
        val imageName = "$token-page.png"
        val zipName = "$token-project.zip"
        val uris = arrayListOf<Uri>()
        try {
            val resolver = context.contentResolver
            uris += createDownload(resolver, imageName, "image/png", png)
            uris += createDownload(resolver, zipName, "application/zip", zip)
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND_MULTIPLE
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_TEXT, url)
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            repeat(2) {
                ActivityScenario.launch<MainActivity>(intent).use {
                    ui.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("파일 고르기"))
                    waitFor("2개 가져오기")
                    ui.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("작품 제목"))
                    ui.onNodeWithText("작품 제목").performTextReplacement(title)
                    ui.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("2개 가져오기"))
                    ui.onNodeWithText("2개 가져오기").performClick()
                    ui.waitUntil(15000) { store.posts().any { p -> p.title == title } }
                    waitFor("작품 보관함")
                    ui.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(title))
                    ui.onNodeWithText(title).performClick()
                    ui.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(imageName))
                    waitFor(imageName)
                    ui.onNodeWithText(imageName).performClick()
                    ui.onNodeWithText("1 / 1").assertIsDisplayed()
                    val post = store.posts().single { p -> p.sourceUrl == url }
                    assertEquals(2, post.assets.size)
                    val attachment = post.assets.single { a -> a.name == zipName }
                    val readUri = FileProvider.getUriForFile(context, "${context.packageName}.files",
                        File(attachment.path), attachment.name)
                    assertArrayEquals(zip, resolver.openInputStream(readUri)!!.use { stream -> stream.readBytes() })
                    assertArrayEquals(png, File(post.assets.single { a -> a.isImage }.path).readBytes())
                }
            }
        } finally {
            uris.forEach { context.contentResolver.delete(it, null, null) }
        }
    }

    private fun waitFor(label: String) {
        ui.waitUntil(15000) { ui.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun createDownload(resolver: ContentResolver, name: String, mime: String, bytes: ByteArray): Uri {
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }))
        try {
            requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            return uri
        } catch (failure: Exception) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }
}
