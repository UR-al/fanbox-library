package app.fanboxlibrary.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubUpdaterTest {
    @Test
    fun comparesSemanticAndPreReleaseVersions() {
        assertTrue(GitHubUpdater.compareVersions("1.0.0", "v1.0.1") < 0)
        assertTrue(GitHubUpdater.compareVersions("1.2.0", "1.10.0") < 0)
        assertTrue(GitHubUpdater.compareVersions("1.0.0-preview", "1.0.0") < 0)
        assertEquals(0, GitHubUpdater.compareVersions("v1.0.0", "1.0.0"))
        assertTrue(GitHubUpdater.compareVersions("2.0.0", "1.9.9") > 0)
    }

    @Test
    fun parsesGitHubLatestReleasePayload() {
        val sample = """
            {
              "tag_name": "v1.2.0",
              "name": "FANBOX Library v1.2.0",
              "body": "### 변경 사항\n- 다크 갤러리 UI 개편\n- 1:1 A/B 비교 뷰어 추가",
              "html_url": "https://github.com/UR-al/fanbox-library/releases/tag/v1.2.0",
              "published_at": "2026-11-24T09:00:00Z",
              "assets": [
                {
                  "name": "FanboxLibrary-1.2.0.exe",
                  "browser_download_url": "https://github.com/UR-al/fanbox-library/releases/download/v1.2.0/FanboxLibrary-1.2.0.exe",
                  "size": 52428800
                },
                {
                  "name": "fanbox-library-android-1.2.0.apk",
                  "browser_download_url": "https://github.com/UR-al/fanbox-library/releases/download/v1.2.0/fanbox-library-android-1.2.0.apk",
                  "size": 12582912
                }
              ]
            }
        """.trimIndent()

        val release = GitHubUpdater.parseReleaseJson(sample)
        assertNotNull(release)
        release!!
        assertEquals("v1.2.0", release.tagName)
        assertEquals("1.2.0", release.version)
        assertEquals("FANBOX Library v1.2.0", release.title)
        assertTrue(release.notes.contains("다크 갤러리 UI 개편"))
        assertEquals(2, release.assets.size)
        assertEquals("FanboxLibrary-1.2.0.exe", release.findWindowsInstaller()?.name)
        assertEquals("fanbox-library-android-1.2.0.apk", release.findAndroidApk()?.name)
    }

    @Test
    fun validatesDownloadHosts() {
        assertTrue(GitHubUpdater.isTrustedDownloadUrl("https://github.com/UR-al/fanbox-library/releases/download/v1.0.0/app.exe"))
        assertTrue(GitHubUpdater.isTrustedDownloadUrl("https://objects.githubusercontent.com/github-production-release-asset/foo.exe"))
        assertFalse(GitHubUpdater.isTrustedDownloadUrl("http://github.com/UR-al/fanbox-library/releases/download/v1.0.0/app.exe"))
        assertFalse(GitHubUpdater.isTrustedDownloadUrl("https://evil.example.com/app.exe"))
    }
}
