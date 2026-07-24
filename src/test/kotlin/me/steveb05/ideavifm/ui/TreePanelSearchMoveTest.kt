package me.steveb05.ideavifm.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBScrollPane
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import me.steveb05.ideavifm.search.RankedFile
import me.steveb05.ideavifm.settings.NavigatorSettings
import me.steveb05.ideavifm.tree.NavigatorNodeData
import me.steveb05.ideavifm.tree.TreeLevel

/**
 * A search fills the pane with the files it found under the folders that hold them. Those folders are there
 * to say where a match lives, so stepping through the pane visits the matches and passes over them.
 */
class TreePanelSearchMoveTest : BasePlatformTestCase() {

    private fun panelOverMatches(matchesOnly: Boolean): TreePanel {
        val panel = TreePanel(project, { null }, onActivate = { }, onCommit = { })
        val region = myFixture.addFileToProject("root/one/Region.kt", "").virtualFile
        val regional = myFixture.addFileToProject("root/two/deep/Regional.kt", "").virtualFile
        myFixture.addFileToProject("root/two/deep/Unrelated.kt", "")
        panel.showPruned(
            listOf(RankedFile(region, 2), RankedFile(regional, 1)),
            myFixture.findFileInTempDir("root"),
            matchesOnly,
        )
        return panel
    }

    /**
     * The levels shape a tree that is being browsed. What a query found is shown wherever it sits, so naming a
     * module or a folder still opens it whatever the opening level is set to.
     */
    fun testAQuerysMatchesOpenWhateverTheLevelSettingSays() {
        val settings = NavigatorSettings.getInstance()
        val before = settings.treeOpenLevel
        settings.treeOpenLevel = TreeLevel.NONE
        try {
            val panel = panelOverMatches(matchesOnly = true)
            assertEquals(listOf("one", "Region.kt", "two/deep", "Regional.kt"), rows(panel))
            panel.resetToOpenState()
            assertEquals("a reset leaves them open too", listOf("one", "Region.kt", "two/deep", "Regional.kt"), rows(panel))
        } finally {
            settings.treeOpenLevel = before
        }
    }

    private fun rows(panel: TreePanel): List<String> {
        val tree = (panel.component as JBScrollPane).viewport.view as JTree
        return (0 until tree.rowCount).map { row ->
            val node = tree.getPathForRow(row).lastPathComponent as DefaultMutableTreeNode
            (node.userObject as NavigatorNodeData).name
        }
    }

    fun testTheFoldersHoldingTheMatchesAreSteppedOver() {
        val panel = panelOverMatches(matchesOnly = true)
        panel.move(1)
        assertEquals("the first step lands on a match, not on the folder above it", "Region.kt", name(panel))
        panel.move(1)
        assertEquals("two/deep only holds the match, so the step goes through it", "Regional.kt", name(panel))
        panel.move(-1)
        assertEquals("Region.kt", name(panel))
    }

    fun testAStepPastTheEndsStaysOnTheOutermostMatch() {
        val panel = panelOverMatches(matchesOnly = true)
        panel.move(1)
        panel.move(5)
        assertEquals("Regional.kt", name(panel))
        panel.move(-5)
        assertEquals("Region.kt", name(panel))
    }

    fun testBrowsingAPrunedTreeStillStopsOnFolders() {
        val panel = panelOverMatches(matchesOnly = false)
        panel.move(1)
        assertEquals(
            "the changed files view is browsed rather than searched, and its folders are rows to stop on",
            "one",
            name(panel),
        )
    }

    fun testAPaneWithoutMatchesHasNowhereToStep() {
        val panel = TreePanel(project, { null }, onActivate = { }, onCommit = { })
        myFixture.addFileToProject("empty/x.txt", "")
        panel.showPruned(emptyList(), myFixture.findFileInTempDir("empty"), matchesOnly = true)
        panel.move(1)
        assertNull(panel.selectedData())
    }

    private fun name(panel: TreePanel): String? = panel.selectedData()?.name
}
