package me.steveb05.ideavifm.ui

import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBScrollPane
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import me.steveb05.ideavifm.settings.NavigatorSettings
import me.steveb05.ideavifm.tree.NavigatorNodeData
import me.steveb05.ideavifm.tree.TreeLevel

/** A root holding several modules, which is the shape a level at a time is for. */
class TreePanelLevelTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("repo/build.gradle.kts", "")
        myFixture.addFileToProject("repo/apps/api/build.gradle.kts", "")
        myFixture.addFileToProject("repo/apps/api/src/main/kotlin/me/acme/action/Run.kt", "")
        myFixture.addFileToProject("repo/apps/api/src/main/kotlin/me/acme/ui/Panel.kt", "")
        myFixture.addFileToProject("repo/apps/web/build.gradle.kts", "")
        myFixture.addFileToProject("repo/apps/web/src/main/kotlin/me/acme/page/Home.kt", "")
        myFixture.addFileToProject("repo/libs/core/build.gradle.kts", "")
        myFixture.addFileToProject("repo/libs/core/src/main/kotlin/me/acme/data/Store.kt", "")
        myFixture.addFileToProject("repo/libs/render/build.gradle.kts", "")
        myFixture.addFileToProject("repo/libs/render/src/main/kotlin/me/acme/widget/Button.kt", "")
    }

    private fun panel(): TreePanel {
        val panel = TreePanel(project, { null }, onActivate = { }, onCommit = { })
        panel.showSubtree(myFixture.findFileInTempDir("repo"))
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

    private val bare = listOf("apps", "libs", "build.gradle.kts")

    private val oneLevelIn = listOf(
        "apps/", "  api", "  web",
        "libs/", "  core", "  render",
        "build.gradle.kts",
    )

    private val twoLevelsIn = listOf(
        "apps/",
        "  api/", "    src/main/kotlin/me/acme/", "      action", "      ui", "    build.gradle.kts",
        "  web/", "    src/main/kotlin/me/acme/page/", "      Home.kt", "    build.gradle.kts",
        "libs/",
        "  core/", "    src/main/kotlin/me/acme/data/", "      Store.kt", "    build.gradle.kts",
        "  render/", "    src/main/kotlin/me/acme/widget/", "      Button.kt", "    build.gradle.kts",
        "build.gradle.kts",
    )

    fun testNothingOpensAtTheBareLevel() {
        val panel = panel()
        panel.openTo(TreeLevel.NONE)
        assertEquals(bare, rows(panel))
    }

    fun testOneLevelInOpensTheTopLevelFoldersOnly() {
        val panel = panel()
        panel.openTo(TreeLevel.ONE)
        assertEquals(oneLevelIn, rows(panel))
    }

    /** A module's build file makes it content, so the packages walk stops on the modules themselves. */
    fun testDownToThePackagesStopsAtTheModules() {
        val panel = panel()
        panel.openTo(TreeLevel.PACKAGES)
        assertEquals(
            "every module here holds a build file, which is as far as the walk goes",
            oneLevelIn,
            rows(panel),
        )
    }

    /** A chain of folders is one row, so opening a module carries through to where its packages are. */
    fun testEachPressOpensOneMoreLevel() {
        val panel = panel()
        panel.openTo(TreeLevel.NONE)
        assertEquals(bare, rows(panel))

        panel.expandOneLevel()
        assertEquals(oneLevelIn, rows(panel))

        panel.expandOneLevel()
        assertEquals(twoLevelsIn, rows(panel))
    }

    fun testAPressBackClosesTheLevelJustOpened() {
        val panel = panel()
        panel.openTo(TreeLevel.NONE)
        panel.expandOneLevel()
        panel.expandOneLevel()

        panel.collapseOneLevel()
        assertEquals(oneLevelIn, rows(panel))

        panel.collapseOneLevel()
        assertEquals(bare, rows(panel))
    }

    fun testThereIsNothingLeftToCloseAtTheBareLevel() {
        val panel = panel()
        panel.openTo(TreeLevel.NONE)
        panel.collapseOneLevel()
        assertEquals(bare, rows(panel))
    }

    fun testClosingALevelTakesFoldersOpenedByHandWithIt() {
        val panel = panel()
        panel.openTo(TreeLevel.ONE)
        panel.selectFile(myFixture.findFileInTempDir("repo/apps/api"))
        panel.expandSelection()
        assertTrue("the fixture must have api opened by hand", rows(panel).any { it.trim() == "api/" })

        panel.collapseOneLevel()
        assertEquals(bare, rows(panel))
    }

    fun testEverythingOpensTheWholeTree() {
        val panel = panel()
        panel.openTo(TreeLevel.ALL)
        assertTrue(rows(panel).any { it.trim() == "Run.kt" })
        assertTrue(rows(panel).any { it.trim() == "Button.kt" })
    }

    fun testResetPutsTheTreeBackToTheConfiguredLevel() {
        val panel = panel()
        withLevels(open = TreeLevel.NONE) {
            panel.openTo(TreeLevel.ALL)
            panel.resetToOpenState()
            assertEquals(bare, rows(panel))
        }
    }

    private val insideTheModule = listOf("repo/", "  apps", "  libs", "  build.gradle.kts")

    /** Selecting a module in the left pane makes it the right pane's root, where its own level still rules. */
    fun testAModuleTheLeftPaneHandsOverOpensAsFarAsItSays() {
        val panel = panelOverModule()
        withLevels(open = TreeLevel.PACKAGES, module = TreeLevel.NONE) {
            panel.openTo(TreeLevel.PACKAGES)
            assertEquals(
                "the module says nothing opens, and the level the tree asks for does not overrule it",
                listOf("repo"),
                rows(panel),
            )
        }
    }

    /** The module's own level reads from inside it, so down to the packages carries on past its root row. */
    fun testAModuleAtTheRootOfThePaneWalksInsideItself() {
        val panel = panelOverModule()
        withLevels(open = TreeLevel.NONE, module = TreeLevel.PACKAGES) {
            panel.openTo(TreeLevel.NONE)
            assertEquals(insideTheModule, rows(panel))
        }
    }

    fun testResetOverAModuleGoesBackToTheModuleLevel() {
        val panel = panelOverModule()
        withLevels(open = TreeLevel.PACKAGES, module = TreeLevel.PACKAGES) {
            panel.openTo(TreeLevel.ALL)
            panel.resetToOpenState()
            assertEquals(insideTheModule, rows(panel))
        }
    }

    private fun panelOverModule(): TreePanel {
        val panel = TreePanel(project, { null }, onActivate = { }, onCommit = { })
        panel.showSubtree(moduleRoot())
        return panel
    }

    /** The fixture's content root is what the project knows as a module, which is what puts one at the root. */
    private fun moduleRoot(): VirtualFile {
        val root = ProjectRootManager.getInstance(project).contentRoots.single()
        assertEquals("the content root must be the folder holding repo", root, myFixture.findFileInTempDir("repo").parent)
        return root
    }

    private fun withLevels(open: TreeLevel, module: TreeLevel = TreeLevel.ONE, block: () -> Unit) {
        val settings = NavigatorSettings.getInstance()
        val openBefore = settings.treeOpenLevel
        val moduleBefore = settings.moduleOpenLevel
        settings.treeOpenLevel = open
        settings.moduleOpenLevel = module
        try {
            block()
        } finally {
            settings.treeOpenLevel = openBefore
            settings.moduleOpenLevel = moduleBefore
        }
    }
}
