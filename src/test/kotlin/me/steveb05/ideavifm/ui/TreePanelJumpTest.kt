package me.steveb05.ideavifm.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBScrollPane
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import me.steveb05.ideavifm.settings.NavigatorSettings
import me.steveb05.ideavifm.tree.NavigatorNodeData
import me.steveb05.ideavifm.tree.TreeLevel

/** Walking between the top level folders of a root that holds several of them. */
class TreePanelJumpTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("repo/build.gradle.kts", "")
        myFixture.addFileToProject("repo/apps/api/build.gradle.kts", "")
        myFixture.addFileToProject("repo/apps/web/build.gradle.kts", "")
        myFixture.addFileToProject("repo/libs/core/build.gradle.kts", "")
        myFixture.addFileToProject("repo/libs/render/build.gradle.kts", "")
        myFixture.addFileToProject("repo/tools/release.sh", "")
    }

    private fun panel(): TreePanel {
        val panel = TreePanel(project, { null }, onActivate = { }, onCommit = { })
        panel.showSubtree(myFixture.findFileInTempDir("repo"))
        panel.openTo(TreeLevel.NONE)
        return panel
    }

    private fun rows(panel: TreePanel): List<String> {
        val tree = (panel.component as JBScrollPane).viewport.view as JTree
        return (0 until tree.rowCount).map { row ->
            val path = tree.getPathForRow(row)
            val node = path.lastPathComponent as DefaultMutableTreeNode
            val data = node.userObject as NavigatorNodeData
            "  ".repeat(node.level - 1) + data.name + if (tree.isExpanded(path)) "/" else ""
        }
    }

    fun testJumpingWalksTheTopLevelFoldersInOrder() {
        val panel = panel()
        assertEquals("the cursor starts on the first row", "apps", panel.selectedData()?.name)

        assertTrue(panel.jumpToRootFolder(1))
        assertEquals("libs", panel.selectedData()?.name)

        assertTrue(panel.jumpToRootFolder(1))
        assertEquals("tools", panel.selectedData()?.name)

        assertFalse("there is nothing past the last folder", panel.jumpToRootFolder(1))
        assertEquals("tools", panel.selectedData()?.name)

        assertTrue(panel.jumpToRootFolder(-1))
        assertEquals("libs", panel.selectedData()?.name)
    }

    fun testAJumpOpensTheFolderItLandsOnToTheConfiguredLevel() {
        val panel = panel()
        withJump(level = TreeLevel.ONE) {
            panel.jumpToRootFolder(1)
            assertEquals(
                listOf("apps", "libs/", "  core", "  render", "tools", "build.gradle.kts"),
                rows(panel),
            )
        }
    }

    fun testTheFolderLeftBehindCloses() {
        val panel = panel()
        withJump(level = TreeLevel.ONE, closeBehind = true) {
            panel.jumpToRootFolder(1)
            assertTrue("libs opens on the way in", rows(panel).contains("libs/"))

            panel.jumpToRootFolder(-1)
            assertEquals(
                listOf("apps/", "  api", "  web", "libs", "tools", "build.gradle.kts"),
                rows(panel),
            )
        }
    }

    fun testTheFolderLeftBehindStaysOpenWhenAskedTo() {
        val panel = panel()
        withJump(level = TreeLevel.ONE, closeBehind = false) {
            panel.jumpToRootFolder(1)
            panel.jumpToRootFolder(-1)
            assertEquals(
                listOf("apps/", "  api", "  web", "libs/", "  core", "  render", "tools", "build.gradle.kts"),
                rows(panel),
            )
        }
    }

    fun testJumpingCanBeMovementAlone() {
        val panel = panel()
        withJump(level = TreeLevel.NONE) {
            panel.jumpToRootFolder(1)
            assertEquals("libs", panel.selectedData()?.name)
            assertEquals(listOf("apps", "libs", "tools", "build.gradle.kts"), rows(panel))
        }
    }

    fun testWithoutTopLevelFoldersThereIsNowhereToJump() {
        val panel = TreePanel(project, { null }, onActivate = { }, onCommit = { })
        myFixture.addFileToProject("flat/one.txt", "")
        panel.showSubtree(myFixture.findFileInTempDir("flat"))
        assertFalse(panel.jumpToRootFolder(1))
    }

    private fun withJump(level: TreeLevel, closeBehind: Boolean = true, block: () -> Unit) {
        val settings = NavigatorSettings.getInstance()
        val levelBefore = settings.jumpOpenLevel
        val closeBefore = settings.closeFolderOnJump
        settings.jumpOpenLevel = level
        settings.closeFolderOnJump = closeBehind
        try {
            block()
        } finally {
            settings.jumpOpenLevel = levelBefore
            settings.closeFolderOnJump = closeBefore
        }
    }
}
