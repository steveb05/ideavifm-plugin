package me.steveb05.projecttreenavigator

import com.intellij.openapi.application.WriteAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PreviewContentTest : BasePlatformTestCase() {

    fun testTextContent() {
        myFixture.addFileToProject("root/hello.txt", "line one\nline two")
        val file = myFixture.findFileInTempDir("root/hello.txt")
        val content = PreviewPanel.computeContent(project, file) as PreviewPanel.Content.Text
        assertEquals("line one\nline two", content.text)
        assertFalse(content.truncated)
    }

    fun testTextTruncatedByLines() {
        val body = (1..400).joinToString("\n") { "line $it" }
        myFixture.addFileToProject("root/long.txt", body)
        val file = myFixture.findFileInTempDir("root/long.txt")
        val content = PreviewPanel.computeContent(project, file) as PreviewPanel.Content.Text
        assertTrue(content.truncated)
        assertEquals(PreviewPanel.MAX_LINES, content.text.lines().size)
    }

    fun testDirectoryListingRespectsDotHiding() {
        myFixture.addFileToProject("root/dir/.hidden/x.txt", "")
        myFixture.addFileToProject("root/dir/visible.txt", "")
        val dir = myFixture.findFileInTempDir("root/dir")
        val settings = NavigatorSettings.getInstance()
        val before = settings.hideDotFiles
        settings.hideDotFiles = true
        try {
            val content = PreviewPanel.computeContent(project, dir) as PreviewPanel.Content.Directory
            assertEquals(listOf("visible.txt"), content.names)
            assertFalse(content.capped)
        } finally {
            settings.hideDotFiles = before
        }
    }

    fun testBinaryContent() {
        val file = myFixture.addFileToProject("root/img.png", "").virtualFile
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        WriteAction.runAndWait<Exception> { file.setBinaryContent(signature) }
        assertTrue(PreviewPanel.computeContent(project, file) is PreviewPanel.Content.Binary)
    }
}
