package me.steveb05.ideavifm.search

import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class QueryHighlightTest : BasePlatformTestCase() {

    fun testQuerySplitsBetweenTheFolderAndTheFile() {
        val file = myFixture.addFileToProject("extensions/_DocsExtension/build.gradle.kts", "").virtualFile
        val folder = myFixture.findFileInTempDir("extensions/_DocsExtension")
        val highlight = highlight("docbui")
        assertEquals("Doc", lit(folder.name, highlight.forDirectory(folder, folder.name)))
        assertEquals("bui", lit(file.name, highlight.forFile(file, file.name)))
    }

    fun testPlainNameQueryLightsUpTheMatchedLetters() {
        val file = myFixture.addFileToProject("backend/UserService.kt", "").virtualFile
        assertEquals("Usrv", lit(file.name, highlight("usrv").forFile(file, file.name)))
    }

    fun testFolderQueryLightsUpTheFolder() {
        myFixture.addFileToProject("extensions/VaultExtension/build.gradle.kts", "")
        val folder = myFixture.findFileInTempDir("extensions/VaultExtension")
        assertEquals("Vault", lit(folder.name, highlight("vault").forDirectory(folder, folder.name)))
    }

    fun testRowsTheQueryDidNotMatchStayPlain() {
        val other = myFixture.addFileToProject("engine/region/RegionEngine.kt", "").virtualFile
        val folder = myFixture.findFileInTempDir("engine/region")
        val highlight = highlight("docbui")
        assertNull("a stray letter of the query must not light up an unrelated file", highlight.forFile(other, other.name))
        assertNull(highlight.forDirectory(folder, folder.name))
    }

    private fun highlight(query: String) = QueryHighlight(query, project.guessProjectDir())

    /** The lit up letters, gaps dropped, so an assertion reads as what the eye sees on the row. */
    private fun lit(text: String, ranges: Iterable<TextRange>?): String? =
        ranges?.joinToString("") { text.substring(it.startOffset, it.endOffset) }

}
