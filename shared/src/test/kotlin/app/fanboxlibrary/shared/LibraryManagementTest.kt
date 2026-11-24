package app.fanboxlibrary.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryManagementTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun mergesSeriesPostsAndDeletesIndividualAssets() {
        val store = LibraryStore(temp.newFolder("lib"))
        store.importManaged(
            PostDraft("Ototo", "시리즈 1화", 2025),
            listOf(ImportSource("01.png") { "img-1".byteInputStream() }),
        )
        store.importManaged(
            PostDraft("Ototo", "시리즈 2화", 2025),
            listOf(ImportSource("02.png") { "img-2".byteInputStream() }),
        )
        val initial = store.posts()
        assertEquals(2, initial.size)

        val target = initial.first { it.title == "시리즈 1화" }
        val other = initial.first { it.title == "시리즈 2화" }
        val report = store.mergePosts(target.id, setOf(other.id), newTitle = "시리즈 합본")
        assertEquals(1, report.added)

        val mergedList = store.posts()
        assertEquals(1, mergedList.size)
        val merged = mergedList.single()
        assertEquals("시리즈 합본", merged.title)
        assertEquals(2, merged.assets.size)

        val toDelete = merged.assets.first { it.name == "02.png" }
        val deletedCount = store.deleteAssets(merged.id, setOf(toDelete.id))
        assertEquals(1, deletedCount)

        val afterDelete = store.posts().single()
        assertEquals(1, afterDelete.assets.size)
        assertEquals("01.png", afterDelete.assets.single().name)
    }

    @Test
    fun acceptsPatreonPostUrls() {
        val store = LibraryStore(temp.newFolder("lib-patreon"))
        val url = "https://www.patreon.com/posts/summer-set-123456789"
        assertTrue(FanboxLinks.isPatreonUrl(url))
        assertEquals("123456789", FanboxLinks.patreonPostId(url))
        assertFalse(FanboxLinks.isOfficialUrl(url))

        store.importManaged(
            PostDraft("CreatorP", "Summer Set", 2026, url),
            listOf(ImportSource("a.png") { "abc".byteInputStream() }),
        )
        val post = store.posts().single()
        assertEquals("patreon:123456789", post.id)
        assertEquals(url, post.sourceUrl)
    }
}
