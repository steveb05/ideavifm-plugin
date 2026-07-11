package dev.sb.projecttreenavigator

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

    fun testModelLoadsChildrenLazily() {
        myFixture.addFileToProject("root/sub/deep.txt", "")
        val rootDir = myFixture.findFileInTempDir("root")
        val model = BrowseTree.createModel(project, listOf(rootDir))
        val hiddenRoot = model.root as DefaultMutableTreeNode
        assertEquals(1, hiddenRoot.childCount)

        val rootNode = hiddenRoot.firstChild as DefaultMutableTreeNode
        assertFalse(BrowseTree.isLoaded(rootNode))

        BrowseTree.loadChildren(project, model, rootNode)
        assertTrue(BrowseTree.isLoaded(rootNode))
        val sub = rootNode.firstChild as DefaultMutableTreeNode
        val data = sub.userObject as NavigatorNodeData
        assertEquals("sub", data.name)
        assertTrue(data.isDirectory)
        assertFalse(BrowseTree.isLoaded(sub))
    }
}
