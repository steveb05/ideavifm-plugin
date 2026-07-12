package me.steveb05.projecttreenavigator

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBScrollPane
import javax.swing.JList

class RootListPanelTest : BasePlatformTestCase() {

    fun testSelectionHelpersDoNotFireCallback() {
        var fired = 0
        val panel = RootListPanel(project, onUserSelection = { fired++ })
        val ax = myFixture.addFileToProject("a/x.txt", "").virtualFile
        myFixture.addFileToProject("b/y.txt", "")
        val top = myFixture.addFileToProject("top.txt", "").virtualFile
        val entries = listOf(
            BaseEntry(myFixture.findFileInTempDir("a"), "a", true),
            BaseEntry(myFixture.findFileInTempDir("b"), "b", true),
            BaseEntry(top, "top.txt", false),
        )
        panel.setEntries(entries)
        assertEquals(entries, panel.entries())
        panel.selectIndex(1)
        assertEquals(entries[1], panel.selectedEntry())
        panel.move(5)
        assertEquals(2, panel.selectedIndex())
        panel.move(-99)
        assertEquals(0, panel.selectedIndex())
        panel.selectEntry(entries[2])
        assertEquals(2, panel.selectedIndex())
        assertEquals(0, fired)
        assertEquals(entries[0], panel.entryContaining(ax))
        assertEquals(entries[2], panel.entryContaining(top))
        assertNull(panel.entryContaining(myFixture.addFileToProject("c/z.txt", "").virtualFile))
    }

    fun testEntryContainingPrefersDeepestEntry() {
        val file = myFixture.addFileToProject("deep/sub/y.txt", "").virtualFile
        val panel = RootListPanel(project, onUserSelection = { })
        val entries = listOf(
            BaseEntry(myFixture.findFileInTempDir("deep"), "deep", true),
            BaseEntry(myFixture.findFileInTempDir("deep/sub"), "sub", true, indent = 1),
        )
        panel.setEntries(entries)
        assertEquals(entries[1], panel.entryContaining(file))
    }

    fun testMovementStepsOverEntriesWithoutMatches() {
        val panel = RootListPanel(project, onUserSelection = { })
        val entries = threeEntries()
        panel.setEntries(entries)
        panel.setCounts(mapOf(entries[0] to 2, entries[1] to 0, entries[2] to 1))
        panel.selectIndex(0)
        panel.move(1)
        assertEquals("the grayed out entry is skipped", 2, panel.selectedIndex())
        panel.move(-1)
        assertEquals(0, panel.selectedIndex())
    }

    fun testEntryWithoutMatchesCannotBeClicked() {
        var fired = 0
        val panel = RootListPanel(project, onUserSelection = { fired++ })
        val entries = threeEntries()
        panel.setEntries(entries)
        panel.setCounts(mapOf(entries[0] to 2, entries[1] to 0, entries[2] to 1))
        panel.selectIndex(0)
        listInside(panel).selectedIndex = 1
        assertEquals("the selection stays put", 0, panel.selectedIndex())
        assertEquals(0, fired)
        listInside(panel).selectedIndex = 2
        assertEquals(2, panel.selectedIndex())
        assertEquals(1, fired)
    }

    private fun threeEntries(): List<BaseEntry> {
        myFixture.addFileToProject("one/x.txt", "")
        myFixture.addFileToProject("two/y.txt", "")
        myFixture.addFileToProject("three/z.txt", "")
        return listOf(
            BaseEntry(myFixture.findFileInTempDir("one"), "one", true),
            BaseEntry(myFixture.findFileInTempDir("two"), "two", true),
            BaseEntry(myFixture.findFileInTempDir("three"), "three", true),
        )
    }

    private fun listInside(panel: RootListPanel): JList<*> =
        ((panel.component as JBScrollPane).viewport.view as JList<*>)

    fun testSetEntriesKeepsModelWhenUnchanged() {
        val panel = RootListPanel(project, onUserSelection = { })
        myFixture.addFileToProject("a/x.txt", "")
        val entries = listOf(BaseEntry(myFixture.findFileInTempDir("a"), "a", true))
        panel.setEntries(entries)
        panel.selectIndex(0)
        panel.setEntries(entries.toList())
        assertEquals(0, panel.selectedIndex())
    }
}
