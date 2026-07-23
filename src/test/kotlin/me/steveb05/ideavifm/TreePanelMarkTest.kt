package me.steveb05.ideavifm

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBScrollPane
import javax.swing.JTree

class TreePanelMarkTest : BasePlatformTestCase() {

    private fun panelOverRoot(): TreePanel {
        val panel = TreePanel(project, { null }, onActivate = { }, onCommit = { })
        myFixture.addFileToProject("root/a.txt", "")
        myFixture.addFileToProject("root/b.txt", "")
        myFixture.addFileToProject("root/c.txt", "")
        panel.showSubtree(myFixture.findFileInTempDir("root"))
        return panel
    }

    fun testThePaneNeverStealsFocusFromTheSearchField() {
        val panel = panelOverRoot()
        val tree = (panel.component as JBScrollPane).viewport.view as JTree
        assertFalse("clicking a row must not move the caret out of the search field", tree.isFocusable)
    }

    fun testMarkingAdvancesAndAccumulates() {
        val panel = panelOverRoot()
        assertEquals(emptyList<Any>(), panel.markedFiles())
        panel.toggleMark()
        panel.toggleMark()
        assertEquals(listOf("a.txt", "b.txt"), panel.markedFiles().map { it.name })
        assertEquals("c.txt", panel.selectedFile()?.name)
    }

    fun testMarkingTwiceUnmarks() {
        val panel = panelOverRoot()
        panel.toggleMark(advance = false)
        panel.toggleMark(advance = false)
        assertEquals(emptyList<Any>(), panel.markedFiles())
        assertEquals("a.txt", panel.selectedFile()?.name)
    }

    fun testMarksSurviveNavigationAndClearOnRebuild() {
        val panel = panelOverRoot()
        panel.toggleMark(advance = false)
        panel.move(2)
        assertEquals(listOf("a.txt"), panel.markedFiles().map { it.name })
        panel.showSubtree(myFixture.findFileInTempDir("root"))
        assertEquals(emptyList<Any>(), panel.markedFiles())
    }
}
