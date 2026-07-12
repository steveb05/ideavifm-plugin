package me.steveb05.projecttreenavigator

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBScrollPane
import javax.swing.JTree

class TreePanelExpandTest : BasePlatformTestCase() {

    private fun panelOverModule(): TreePanel {
        val panel = TreePanel(project, { null }, onActivate = { }, onCommit = { })
        myFixture.addFileToProject("module/build.gradle.kts", "")
        myFixture.addFileToProject("module/src/main/kotlin/Region.kt", "")
        myFixture.addFileToProject("module/src/main/kotlin/nested/Deep.kt", "")
        myFixture.addFileToProject("module/src/test/kotlin/RegionSpec.kt", "")
        panel.showSubtree(myFixture.findFileInTempDir("module"))
        panel.expandToFirstFileLevel()
        return panel
    }

    private fun rowsOf(panel: TreePanel): Int =
        ((panel.component as JBScrollPane).viewport.view as JTree).rowCount

    fun testResetLeavesTheTreeTheWayItOpens() {
        val panel = panelOverModule()
        val opened = rowsOf(panel)

        panel.expandAll()
        assertTrue("the fixture must have something deeper to expand", rowsOf(panel) > opened)

        panel.resetToOpenState()
        assertEquals("neither collapsed nor left fully expanded", opened, rowsOf(panel))
    }

    fun testResetPutsTheCursorOnTheFolderItOpenedInto() {
        val panel = panelOverModule()
        panel.expandAll()
        panel.move(5)
        panel.resetToOpenState()
        assertEquals(
            "src only holds folders, the cursor belongs on the first folder showing its own files",
            "main/kotlin",
            panel.selectedData()?.name,
        )
    }

    fun testWithoutAFolderToOpenIntoTheCursorSitsOnTheTopRow() {
        val panel = TreePanel(project, { null }, onActivate = { }, onCommit = { })
        myFixture.addFileToProject("flat/one.txt", "")
        myFixture.addFileToProject("flat/two.txt", "")
        panel.showSubtree(myFixture.findFileInTempDir("flat"))
        panel.resetToOpenState()
        assertEquals("one.txt", panel.selectedData()?.name)
    }
}
