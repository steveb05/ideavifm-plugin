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
}
