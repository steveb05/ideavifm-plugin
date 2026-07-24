package me.steveb05.ideavifm.ui

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBScrollPane
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import me.steveb05.ideavifm.tree.NavigatorNodeData
import me.steveb05.ideavifm.tree.TreeLevel

class TreePanelExpandTest : BasePlatformTestCase() {

    private fun panel(): TreePanel = TreePanel(project, { null }, onActivate = { }, onCommit = { })

    private fun panelOverModule(): TreePanel {
        val panel = panel()
        myFixture.addFileToProject("module/build.gradle.kts", "")
        myFixture.addFileToProject("module/src/main/kotlin/Region.kt", "")
        myFixture.addFileToProject("module/src/main/kotlin/nested/Deep.kt", "")
        myFixture.addFileToProject("module/src/test/kotlin/RegionSpec.kt", "")
        panel.showSubtree(myFixture.findFileInTempDir("module"))
        panel.openTo(TreeLevel.PACKAGES)
        return panel
    }

    private fun treeOf(panel: TreePanel): JTree =
        (panel.component as JBScrollPane).viewport.view as JTree

    private fun rowsOf(panel: TreePanel): Int = treeOf(panel).rowCount

    private fun namesOf(panel: TreePanel): List<String> {
        val tree = treeOf(panel)
        return (0 until tree.rowCount).map { row ->
            val node = tree.getPathForRow(row).lastPathComponent as DefaultMutableTreeNode
            (node.userObject as NavigatorNodeData).name
        }
    }

    fun testResetLeavesTheTreeTheWayItOpens() {
        val panel = panelOverModule()
        val opened = rowsOf(panel)

        panel.expandAll()
        assertTrue("the fixture must have something deeper to expand", rowsOf(panel) > opened)

        panel.resetToOpenState()
        assertEquals("neither collapsed nor left fully expanded", opened, rowsOf(panel))
    }

    /** What the reset is for: the packages come into view, the files inside them do not. */
    fun testResetShowsThePackagesRatherThanEveryFileInThem() {
        val panel = panel()
        myFixture.addFileToProject("app/src/main/kotlin/me/acme/action/Run.kt", "")
        myFixture.addFileToProject("app/src/main/kotlin/me/acme/action/Stop.kt", "")
        myFixture.addFileToProject("app/src/main/kotlin/me/acme/ui/Panel.kt", "")
        myFixture.addFileToProject("app/build.gradle.kts", "")
        panel.showSubtree(myFixture.findFileInTempDir("app"))
        panel.expandAll()

        panel.resetToOpenState()

        assertEquals(
            listOf("src/main/kotlin/me/acme", "action", "ui", "build.gradle.kts"),
            namesOf(panel),
        )
    }

    /** A plugin's own shape: both source sets open down to the packages, and neither spills what they hold. */
    private fun panelOverSourceRoot(): TreePanel {
        listOf("action", "ui", "vcs").forEach { pkg ->
            myFixture.addFileToProject("src/main/kotlin/me/acme/nav/$pkg/Thing.kt", "")
            myFixture.addFileToProject("src/test/kotlin/me/acme/nav/$pkg/ThingTest.kt", "")
        }
        myFixture.addFileToProject("src/main/resources/META-INF/plugin.xml", "")
        val panel = panel()
        panel.showSubtree(myFixture.findFileInTempDir("src"))
        panel.openTo(TreeLevel.PACKAGES)
        return panel
    }

    fun testBothSourceSetsOpenDownToTheirPackages() {
        assertEquals(
            listOf(
                "main",
                "kotlin/me/acme/nav", "action", "ui", "vcs",
                "resources/META-INF",
                "test/kotlin/me/acme/nav", "action", "ui", "vcs",
            ),
            namesOf(panelOverSourceRoot()),
        )
    }

    /**
     * A row spelling a chain of folders is there to show what the chain leads to, so it opens on files of its
     * own as readily as on packages. What stops the walk is a folder of its own name holding files.
     */
    fun testAFileBesideThePackagesDoesNotCloseTheChainItSitsAtTheEndOf() {
        myFixture.addFileToProject("src/main/kotlin/me/acme/nav/Constants.kt", "")
        assertEquals(
            listOf(
                "main",
                "kotlin/me/acme/nav", "action", "ui", "vcs", "Constants.kt",
                "resources/META-INF",
                "test/kotlin/me/acme/nav", "action", "ui", "vcs",
            ),
            namesOf(panelOverSourceRoot()),
        )
    }

    /** A package with a subpackage among its classes is still a package: opening it would spill the classes. */
    fun testAPackageIsNotOpenedForHavingASubpackage() {
        val entries = "extension/src/main/kotlin/com/acme/superiorskyblock/entries"
        myFixture.addFileToProject("extension/build.gradle.kts", "")
        myFixture.addFileToProject("$entries/action/IslandDisbandActionEntry.kt", "")
        myFixture.addFileToProject("$entries/action/IslandSetBiomeActionEntry.kt", "")
        myFixture.addFileToProject("$entries/action/bank/IslandBankDepositActionEntry.kt", "")
        myFixture.addFileToProject("$entries/event/IslandCreateEventEntry.kt", "")
        myFixture.addFileToProject("$entries/fact/IslandLevelFactEntry.kt", "")
        myFixture.addFileToProject("$entries/group/IslandMemberGroupEntry.kt", "")
        val panel = panel()
        panel.showSubtree(myFixture.findFileInTempDir("extension"))
        panel.openTo(TreeLevel.PACKAGES)

        assertEquals(
            listOf(
                "src/main/kotlin/com/acme/superiorskyblock/entries",
                "action", "event", "fact", "group",
                "build.gradle.kts",
            ),
            namesOf(panel),
        )
    }

    /**
     * The left pane handing a module over is being inside it, so its own level says what opens and the level
     * the tree was asked for has nothing to say. Both source sets read the same way down to their packages.
     */
    fun testAModuleTheLeftPaneHandsOverOpensDownToItsPackages() {
        myFixture.addFileToProject("build.gradle.kts", "")
        myFixture.addFileToProject("src/main/kotlin/me/acme/nav/Constants.kt", "")
        myFixture.addFileToProject("src/main/kotlin/me/acme/nav/ui/Panel.kt", "")
        myFixture.addFileToProject("src/test/kotlin/me/acme/nav/action/RunTest.kt", "")
        myFixture.addFileToProject("src/test/kotlin/me/acme/nav/ui/PanelTest.kt", "")
        val panel = panel()
        panel.showSubtree(ProjectRootManager.getInstance(project).contentRoots.single())

        panel.openTo(TreeLevel.NONE)

        assertEquals(
            listOf(
                "src",
                "main/kotlin/me/acme/nav", "ui", "Constants.kt",
                "test/kotlin/me/acme/nav", "action", "ui",
                "build.gradle.kts",
            ),
            namesOf(panel),
        )
    }

    /**
     * The chain into the packages is the one that opens on files of its own. A chain running from one package
     * to the next is a package like any other, so its classes stay in it the way a plain package's do.
     */
    fun testAPackageChainDeeperInKeepsItsClassesBack() {
        val entries = "extension/src/main/kotlin/com/acme/entries"
        myFixture.addFileToProject("extension/build.gradle.kts", "")
        myFixture.addFileToProject("$entries/action/Disband.kt", "")
        myFixture.addFileToProject("$entries/data/entities/Island.kt", "")
        myFixture.addFileToProject("$entries/data/entities/member/Member.kt", "")
        val panel = panel()
        panel.showSubtree(myFixture.findFileInTempDir("extension"))

        panel.openTo(TreeLevel.PACKAGES)

        assertEquals(
            listOf("src/main/kotlin/com/acme/entries", "action", "data/entities", "build.gradle.kts"),
            namesOf(panel),
        )
    }

    fun testResetPutsTheCursorOnTheFirstFolderShowingItsFiles() {
        val panel = panel()
        myFixture.addFileToProject("module/alpha/one/A.kt", "")
        myFixture.addFileToProject("module/alpha/two/B.kt", "")
        myFixture.addFileToProject("module/zebra/pack/Thing.kt", "")
        myFixture.addFileToProject("module/zebra/pack/sub/Deep.kt", "")
        panel.showSubtree(myFixture.findFileInTempDir("module"))
        panel.openTo(TreeLevel.PACKAGES)
        panel.expandAll()
        panel.move(3)

        panel.resetToOpenState()

        assertEquals(
            "alpha only holds folders, and the chain to pack has files of its own on show",
            "zebra/pack",
            panel.selectedData()?.name,
        )
    }

    fun testWithoutAFolderToOpenIntoTheCursorSitsOnTheTopRow() {
        val panel = panel()
        myFixture.addFileToProject("flat/one.txt", "")
        myFixture.addFileToProject("flat/two.txt", "")
        panel.showSubtree(myFixture.findFileInTempDir("flat"))
        panel.resetToOpenState()
        assertEquals("one.txt", panel.selectedData()?.name)
    }

    fun testReloadingKeepsTheFoldersThatAreOpenAndTheRowTheCursorIsOn() {
        val panel = panelOverModule()
        val base = myFixture.findFileInTempDir("module")
        val nested = myFixture.findFileInTempDir("module/src/main/kotlin/nested")
        panel.locate(nested, base)
        val opened = namesOf(panel)
        assertTrue("the fixture must have nested opened up", opened.contains("nested"))

        panel.reloadSubtree(base)

        assertEquals("a reload must not close what the user opened", opened, namesOf(panel))
        assertEquals("nested", panel.selectedData()?.name)
    }

    fun testANewFileShowsUpWithoutReopeningTheFoldersTheUserClosed() {
        val panel = panelOverModule()
        val base = myFixture.findFileInTempDir("module")
        val tree = treeOf(panel)
        (tree.rowCount - 1 downTo 0).forEach { tree.collapseRow(it) }
        assertEquals(listOf("src", "build.gradle.kts"), namesOf(panel))

        myFixture.addFileToProject("module/Added.kt", "")
        panel.reloadSubtree(base)

        assertEquals(
            "the new file comes into view and src stays closed",
            listOf("src", "Added.kt", "build.gradle.kts"),
            namesOf(panel),
        )
    }

    fun testAReloadDropsTheRowsOfADeletedFolder() {
        val panel = panelOverModule()
        val base = myFixture.findFileInTempDir("module")
        val doomed = myFixture.findFileInTempDir("module/src")
        assertTrue(namesOf(panel).contains("src"))

        WriteCommandAction.runWriteCommandAction(project) { doomed.delete(this) }
        panel.reloadSubtree(base)

        assertEquals(listOf("build.gradle.kts"), namesOf(panel))
    }

    fun testTheTreeSurvivesTheFolderItIsBrowsingBeingDeleted() {
        val panel = panelOverModule()
        val base = myFixture.findFileInTempDir("module")
        WriteCommandAction.runWriteCommandAction(project) { base.delete(this) }

        panel.reloadSubtree(base)
        panel.openTo(TreeLevel.PACKAGES)
        panel.resetToOpenState()

        assertEquals(0, rowsOf(panel))
    }
}
