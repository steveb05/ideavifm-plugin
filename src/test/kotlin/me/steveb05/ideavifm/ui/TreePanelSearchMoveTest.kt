package me.steveb05.ideavifm.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import me.steveb05.ideavifm.search.RankedFile

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
        panel.expandAll()
        return panel
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
