package me.steveb05.projecttreenavigator

import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.tree.DefaultMutableTreeNode

class BrowseTreeTest : BasePlatformTestCase() {

    fun testVisibleChildrenSortsDirectoriesFirstThenAlphabetical() {
        myFixture.addFileToProject("root/zed.txt", "")
        myFixture.addFileToProject("root/Alpha.txt", "")
        myFixture.addFileToProject("root/beta/inner.txt", "")
        myFixture.addFileToProject("root/Delta/inner.txt", "")
        val rootDir = myFixture.findFileInTempDir("root")
        val names = BrowseTree.visibleChildren(project, rootDir).map { it.name }
        assertEquals(listOf("beta", "Delta", "Alpha.txt", "zed.txt"), names)
    }

    fun testVisibleChildrenHidesExcludedDirectories() {
        myFixture.addFileToProject("root/keep/a.txt", "")
        myFixture.addFileToProject("root/gone/b.txt", "")
        val rootDir = myFixture.findFileInTempDir("root")
        val excluded = myFixture.findFileInTempDir("root/gone")
        PsiTestUtil.addExcludedRoot(myFixture.module, excluded)
        try {
            val names = BrowseTree.visibleChildren(project, rootDir).map { it.name }
            assertEquals(listOf("keep"), names)
        } finally {
            PsiTestUtil.removeExcludedRoot(myFixture.module, excluded)
        }
    }

    fun testSubtreeModelLoadsChildrenLazily() {
        myFixture.addFileToProject("root/sub/deep.txt", "")
        myFixture.addFileToProject("root/top.txt", "")
        val rootDir = myFixture.findFileInTempDir("root")
        val model = BrowseTree.createSubtreeModel(project, rootDir)
        val hiddenRoot = model.root as DefaultMutableTreeNode
        assertTrue(BrowseTree.isLoaded(hiddenRoot))
        assertEquals(rootDir, (hiddenRoot.userObject as NavigatorNodeData).file)
        val names = hiddenRoot.children().asSequence()
            .filterIsInstance<DefaultMutableTreeNode>()
            .map { (it.userObject as NavigatorNodeData).name }
            .toList()
        assertEquals(listOf("sub", "top.txt"), names)
        val sub = hiddenRoot.firstChild as DefaultMutableTreeNode
        assertFalse(BrowseTree.isLoaded(sub))
        BrowseTree.loadChildren(project, model, sub)
        assertTrue(BrowseTree.isLoaded(sub))
        val deep = sub.firstChild as DefaultMutableTreeNode
        assertEquals("deep.txt", (deep.userObject as NavigatorNodeData).name)
    }

    fun testVisibleChildrenHidesDotEntriesWhenEnabled() {
        myFixture.addFileToProject("root/.idea/misc.xml", "")
        myFixture.addFileToProject("root/.gitignore", "")
        myFixture.addFileToProject("root/keep.txt", "")
        val rootDir = myFixture.findFileInTempDir("root")
        withDotFiles(hidden = true) {
            assertEquals(listOf("keep.txt"), BrowseTree.visibleChildren(project, rootDir).map { it.name })
        }
        withDotFiles(hidden = false) {
            assertEquals(
                listOf(".idea", ".gitignore", "keep.txt"),
                BrowseTree.visibleChildren(project, rootDir).map { it.name },
            )
        }
    }

    fun testHiddenByDotRuleChecksAncestors() {
        myFixture.addFileToProject("root/.idea/misc.xml", "")
        myFixture.addFileToProject("root/src/ok.txt", "")
        val hidden = myFixture.findFileInTempDir("root/.idea/misc.xml")
        val visible = myFixture.findFileInTempDir("root/src/ok.txt")
        withDotFiles(hidden = true) {
            assertTrue(BrowseTree.hiddenByDotRule(project, hidden))
            assertFalse(BrowseTree.hiddenByDotRule(project, visible))
        }
        withDotFiles(hidden = false) {
            assertFalse(BrowseTree.hiddenByDotRule(project, hidden))
        }
    }

    fun testSubtreeModelCompactsSingleChildChains() {
        myFixture.addFileToProject("root/a/b/c/deep.txt", "")
        myFixture.addFileToProject("root/top.txt", "")
        val rootDir = myFixture.findFileInTempDir("root")
        withCompact(enabled = true) {
            val hiddenRoot = BrowseTree.createSubtreeModel(project, rootDir).root as DefaultMutableTreeNode
            assertEquals(listOf("a/b/c", "top.txt"), childNames(hiddenRoot))
            val chain = hiddenRoot.firstChild as DefaultMutableTreeNode
            assertEquals(myFixture.findFileInTempDir("root/a/b/c"), (chain.userObject as NavigatorNodeData).file)
        }
        withCompact(enabled = false) {
            val hiddenRoot = BrowseTree.createSubtreeModel(project, rootDir).root as DefaultMutableTreeNode
            assertEquals(listOf("a", "top.txt"), childNames(hiddenRoot))
        }
    }

    fun testCompactChainStopsAtForks() {
        myFixture.addFileToProject("root/a/b/stop.txt", "")
        myFixture.addFileToProject("root/a/b/c/deep.txt", "")
        val a = myFixture.findFileInTempDir("root/a")
        withCompact(enabled = true) {
            val (deepest, name) = BrowseTree.compactChain(project, a)
            assertEquals("a/b", name)
            assertEquals(myFixture.findFileInTempDir("root/a/b"), deepest)
        }
    }

    fun testCompactChainIgnoresHiddenDotSiblings() {
        myFixture.addFileToProject("root/a/.git/config", "")
        myFixture.addFileToProject("root/a/sub/file.txt", "")
        val a = myFixture.findFileInTempDir("root/a")
        withDotFiles(hidden = true) {
            withCompact(enabled = true) {
                val (deepest, name) = BrowseTree.compactChain(project, a)
                assertEquals("a/sub", name)
                assertEquals(myFixture.findFileInTempDir("root/a/sub"), deepest)
            }
        }
    }

    fun testEachBranchOpensUntilItsOwnFilesComeIntoView() {
        myFixture.addFileToProject("root/x/one/inner.txt", "")
        myFixture.addFileToProject("root/y/two.txt", "")
        val rootDir = myFixture.findFileInTempDir("root")
        withCompact(enabled = false) {
            val model = BrowseTree.createSubtreeModel(project, rootDir)
            val targets = BrowseTree.autoExpandTargets(project, model)
            assertEquals(
                "y holding a file must not stop x from opening down to its own",
                listOf("x", "y", "one"),
                targets.map { (it.userObject as NavigatorNodeData).name },
            )
        }
    }

    fun testAFileBesideAFolderDoesNotStopThatFolderFromOpening() {
        myFixture.addFileToProject("root/a/inner.txt", "")
        myFixture.addFileToProject("root/top.txt", "")
        val rootDir = myFixture.findFileInTempDir("root")
        withCompact(enabled = false) {
            val model = BrowseTree.createSubtreeModel(project, rootDir)
            val targets = BrowseTree.autoExpandTargets(project, model)
            assertEquals(listOf("a"), targets.map { (it.userObject as NavigatorNodeData).name })
        }
    }

    fun testAModuleOpensDownToItsSourcesDespiteTheBuildFile() {
        myFixture.addFileToProject("module/build.gradle.kts", "")
        myFixture.addFileToProject("module/src/main/kotlin/Region.kt", "")
        myFixture.addFileToProject("module/src/test/kotlin/RegionSpec.kt", "")
        val moduleDir = myFixture.findFileInTempDir("module")
        withCompact(enabled = true) {
            val model = BrowseTree.createSubtreeModel(project, moduleDir)
            val targets = BrowseTree.autoExpandTargets(project, model).map {
                (it.userObject as NavigatorNodeData).name
            }
            assertEquals(
                "build.gradle.kts sits beside src, and src must still open down to the sources",
                listOf("src", "main/kotlin", "test/kotlin"),
                targets,
            )
        }
    }

    private fun childNames(node: DefaultMutableTreeNode): List<String> =
        node.children().asSequence()
            .filterIsInstance<DefaultMutableTreeNode>()
            .map { (it.userObject as NavigatorNodeData).name }
            .toList()

    private fun withCompact(enabled: Boolean, block: () -> Unit) {
        val settings = NavigatorSettings.getInstance()
        val before = settings.compactFolders
        settings.compactFolders = enabled
        try {
            block()
        } finally {
            settings.compactFolders = before
        }
    }

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
}
