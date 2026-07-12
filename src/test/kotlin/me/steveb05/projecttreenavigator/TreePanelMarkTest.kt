package me.steveb05.projecttreenavigator

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class TreePanelMarkTest : BasePlatformTestCase() {

    private fun panelOverRoot(): TreePanel {
        val panel = TreePanel(project, { null }, onActivate = { }, onCommit = { })
        myFixture.addFileToProject("root/a.txt", "")
        myFixture.addFileToProject("root/b.txt", "")
        myFixture.addFileToProject("root/c.txt", "")
        panel.showSubtree(myFixture.findFileInTempDir("root"))
        return panel
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
