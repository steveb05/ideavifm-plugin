package me.steveb05.ideavifm.tree

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import me.steveb05.ideavifm.settings.NavigatorSettings

class RevealedTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(".github/workflows/ci.yml", "")
        myFixture.addFileToProject(".github/.inner/deep.txt", "")
        myFixture.addFileToProject(".cache/blob.bin", "")
        myFixture.addFileToProject("src/Main.kt", "")
    }

    private fun root() = myFixture.findFileInTempDir(".")

    private fun file(path: String) = myFixture.findFileInTempDir(path)

    private fun withDotFiles(hidden: Boolean, block: () -> Unit) {
        val settings = NavigatorSettings.getInstance()
        val before = settings.hideDotFiles
        settings.hideDotFiles = hidden
        try {
            block()
        } finally {
            settings.hideDotFiles = before
        }
    }

    fun testADotFolderStaysHiddenWhileNothingIsInsideIt() {
        withDotFiles(hidden = true) {
            val names = BrowseTree.visibleChildren(project, root()).map { it.name }
            assertFalse(names.contains(".github"))
            assertTrue(names.contains("src"))
        }
    }

    fun testTheFolderHoldingTheCurrentFileIsRevealed() {
        withDotFiles(hidden = true) {
            val revealed = Revealed.of(project, file(".github/workflows/ci.yml"))
            assertTrue(BrowseTree.visibleChildren(project, root(), revealed).map { it.name }.contains(".github"))
            assertFalse(BrowseTree.hiddenByDotRule(project, file(".github/workflows/ci.yml"), revealed))
        }
    }

    /** Coming in for one file means the folder is what you are looking at, dot folders of its own included. */
    fun testEverythingUnderARevealedFolderShows() {
        withDotFiles(hidden = true) {
            val revealed = Revealed.of(project, file(".github/workflows/ci.yml"))
            val inside = BrowseTree.visibleChildren(project, file(".github"), revealed).map { it.name }
            assertEquals(listOf(".inner", "workflows"), inside)
        }
    }

    fun testRevealingOneDotFolderLeavesTheOthersHidden() {
        withDotFiles(hidden = true) {
            val revealed = Revealed.of(project, file(".github/workflows/ci.yml"))
            assertFalse(BrowseTree.visibleChildren(project, root(), revealed).map { it.name }.contains(".cache"))
            assertTrue(BrowseTree.hiddenByDotRule(project, file(".cache/blob.bin"), revealed))
        }
    }

    fun testAFileOutsideAnyDotFolderRevealsNothing() {
        assertEquals(Revealed.NONE, Revealed.of(project, file("src/Main.kt")))
    }
}
