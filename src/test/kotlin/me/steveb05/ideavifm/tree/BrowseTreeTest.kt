package me.steveb05.ideavifm.tree

import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.tree.DefaultMutableTreeNode
import me.steveb05.ideavifm.settings.NavigatorSettings

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

    fun testVisibleChildrenHidesDotEntriesWhenEnabled() {
        myFixture.addFileToProject("root/.idea/misc.xml", "")
        myFixture.addFileToProject("root/.gitignore", "")
        myFixture.addFileToProject("root/keep.txt", "")
        val rootDir = myFixture.findFileInTempDir("root")
        withDotFiles(hidden = true) {
            assertEquals(listOf("keep.txt"), BrowseTree.visibleChildren(project, rootDir).map { it.name })
        }
        withDotFiles(hidden = false) {
            assertEquals(
                listOf(".idea", ".gitignore", "keep.txt"),
                BrowseTree.visibleChildren(project, rootDir).map { it.name },
            )
        }
    }

    fun testHiddenByDotRuleChecksAncestors() {
        myFixture.addFileToProject("root/.idea/misc.xml", "")
        myFixture.addFileToProject("root/src/ok.txt", "")
        val hidden = myFixture.findFileInTempDir("root/.idea/misc.xml")
        val visible = myFixture.findFileInTempDir("root/src/ok.txt")
        withDotFiles(hidden = true) {
            assertTrue(BrowseTree.hiddenByDotRule(project, hidden))
            assertFalse(BrowseTree.hiddenByDotRule(project, visible))
        }
        withDotFiles(hidden = false) {
            assertFalse(BrowseTree.hiddenByDotRule(project, hidden))
        }
    }

    fun testSubtreeModelCompactsSingleChildChains() {
        myFixture.addFileToProject("root/a/b/c/deep.txt", "")
        myFixture.addFileToProject("root/top.txt", "")
        val rootDir = myFixture.findFileInTempDir("root")
        withCompact(enabled = true) {
            val hiddenRoot = BrowseTree.createSubtreeModel(project, rootDir).root as DefaultMutableTreeNode
            assertEquals(listOf("a/b/c", "top.txt"), childNames(hiddenRoot))
            val chain = hiddenRoot.firstChild as DefaultMutableTreeNode
            assertEquals(myFixture.findFileInTempDir("root/a/b/c"), (chain.userObject as NavigatorNodeData).file)
        }
        withCompact(enabled = false) {
            val hiddenRoot = BrowseTree.createSubtreeModel(project, rootDir).root as DefaultMutableTreeNode
            assertEquals(listOf("a", "top.txt"), childNames(hiddenRoot))
        }
    }

    fun testCompactChainStopsAtForks() {
        myFixture.addFileToProject("root/a/b/stop.txt", "")
        myFixture.addFileToProject("root/a/b/c/deep.txt", "")
        val a = myFixture.findFileInTempDir("root/a")
        withCompact(enabled = true) {
            val (deepest, name) = BrowseTree.compactChain(project, a)
            assertEquals("a/b", name)
            assertEquals(myFixture.findFileInTempDir("root/a/b"), deepest)
        }
    }

    fun testCompactChainIgnoresHiddenDotSiblings() {
        myFixture.addFileToProject("root/a/.git/config", "")
        myFixture.addFileToProject("root/a/sub/file.txt", "")
        val a = myFixture.findFileInTempDir("root/a")
        withDotFiles(hidden = true) {
            withCompact(enabled = true) {
                val (deepest, name) = BrowseTree.compactChain(project, a)
                assertEquals("a/sub", name)
                assertEquals(myFixture.findFileInTempDir("root/a/sub"), deepest)
            }
        }
    }

    fun testAFolderWithFilesOfItsOwnStaysClosedWhenItHasCompany() {
        myFixture.addFileToProject("root/x/one/inner.txt", "")
        myFixture.addFileToProject("root/x/two/inner.txt", "")
        myFixture.addFileToProject("root/y/two.txt", "")
        val rootDir = myFixture.findFileInTempDir("root")
        withCompact(enabled = false) {
            val model = BrowseTree.createSubtreeModel(project, rootDir)
            val targets = BrowseTree.autoExpandTargets(project, model)
            assertEquals(
                "x holds nothing but folders and opens, while y, one and two, each holding files beside " +
                    "another folder, are left to be opened by hand",
                listOf("x"),
                targets.map { (it.userObject as NavigatorNodeData).name },
            )
        }
    }

    /** Without this a chain of folders that hold no files of their own would open a whole monorepo. */
    fun testAFolderWithFilesOfItsOwnEndsTheWalkThroughIt() {
        myFixture.addFileToProject("apps/web/build.gradle.kts", "")
        myFixture.addFileToProject("apps/web/src/main/kotlin/me/acme/Page.kt", "")
        myFixture.addFileToProject("apps/api/build.gradle.kts", "")
        myFixture.addFileToProject("apps/api/src/main/kotlin/me/acme/Route.kt", "")
        val appsDir = myFixture.findFileInTempDir("apps")
        withCompact(enabled = true) {
            val model = BrowseTree.createSubtreeModel(project, appsDir)
            assertEquals(
                "the build file each module carries is content, so the walk lists the two and stops",
                emptyList<String>(),
                BrowseTree.autoExpandTargets(project, model).map {
                    (it.userObject as NavigatorNodeData).name
                },
            )
        }
    }

    fun testTheOnlyFolderAtItsLevelOpensThoughItHoldsFiles() {
        myFixture.addFileToProject("root/x/one/inner.txt", "")
        myFixture.addFileToProject("root/y/two.txt", "")
        val rootDir = myFixture.findFileInTempDir("root")
        withCompact(enabled = false) {
            withLoneFolder(open = true) {
                val model = BrowseTree.createSubtreeModel(project, rootDir)
                assertEquals(
                    "one is all there is under x, so walking down to it and stopping short helps nobody",
                    listOf("x", "one"),
                    BrowseTree.autoExpandTargets(project, model).map {
                        (it.userObject as NavigatorNodeData).name
                    },
                )
            }
            withLoneFolder(open = false) {
                val model = BrowseTree.createSubtreeModel(project, rootDir)
                assertEquals(
                    listOf("x"),
                    BrowseTree.autoExpandTargets(project, model).map {
                        (it.userObject as NavigatorNodeData).name
                    },
                )
            }
        }
    }

    fun testAFileBesideAFolderDoesNotStopThatFolderFromOpening() {
        myFixture.addFileToProject("root/a/inner.txt", "")
        myFixture.addFileToProject("root/top.txt", "")
        val rootDir = myFixture.findFileInTempDir("root")
        withCompact(enabled = false) {
            val model = BrowseTree.createSubtreeModel(project, rootDir)
            val targets = BrowseTree.autoExpandTargets(project, model)
            assertEquals(listOf("a"), targets.map { (it.userObject as NavigatorNodeData).name })
        }
    }

    fun testAModuleOpensDownToItsPackagesDespiteTheBuildFile() {
        myFixture.addFileToProject("module/build.gradle.kts", "")
        myFixture.addFileToProject("module/src/main/kotlin/me/acme/action/Run.kt", "")
        myFixture.addFileToProject("module/src/main/kotlin/me/acme/ui/Panel.kt", "")
        myFixture.addFileToProject("module/src/main/resources/app.properties", "")
        val moduleDir = myFixture.findFileInTempDir("module")
        withCompact(enabled = true) {
            val model = BrowseTree.createSubtreeModel(project, moduleDir)
            val targets = BrowseTree.autoExpandTargets(project, model).map {
                (it.userObject as NavigatorNodeData).name
            }
            assertEquals(
                "build.gradle.kts sits beside src, and src opens down to the packages under me/acme, " +
                    "leaving what those packages hold closed",
                listOf("src/main", "kotlin/me/acme"),
                targets,
            )
        }
    }

    private fun childNames(node: DefaultMutableTreeNode): List<String> =
        node.children().asSequence()
            .filterIsInstance<DefaultMutableTreeNode>()
            .map { (it.userObject as NavigatorNodeData).name }
            .toList()

    private fun withCompact(enabled: Boolean, block: () -> Unit) {
        val settings = NavigatorSettings.getInstance()
        val before = settings.compactFolders
        settings.compactFolders = enabled
        try {
            block()
        } finally {
            settings.compactFolders = before
        }
    }

    /** A folder that is the only one at its level shares its parent's level: it is one row standing for a chain. */
    fun testALoneFolderDoesNotCountAsALevelOfItsOwn() {
        myFixture.addFileToProject("repo/apps/api/build.gradle.kts", "")
        myFixture.addFileToProject("repo/apps/api/src/main/kotlin/me/acme/action/Run.kt", "")
        myFixture.addFileToProject("repo/apps/api/src/main/kotlin/me/acme/ui/Panel.kt", "")
        myFixture.addFileToProject("repo/apps/web/build.gradle.kts", "")
        val repo = myFixture.findFileInTempDir("repo")
        withCompact(enabled = true) {
            val model = BrowseTree.createSubtreeModel(project, repo)
            assertEquals(
                listOf("apps"),
                BrowseTree.levelTargets(project, model, 1).map { (it.userObject as NavigatorNodeData).name },
            )
            assertEquals(
                "api holds one folder beside its build file, so the chain to me/acme opens with it",
                listOf("apps", "api", "web", "src/main/kotlin/me/acme"),
                BrowseTree.levelTargets(project, model, 2).map { (it.userObject as NavigatorNodeData).name },
            )
        }
    }

    private fun walkOver(base: String, modules: List<String>): List<String> {
        val model = BrowseTree.createSubtreeModel(project, myFixture.findFileInTempDir(base))
        val roots = modules.mapTo(HashSet()) { myFixture.findFileInTempDir(it).path }
        return BrowseTree.autoExpandTargets(project, model, moduleRoots = roots).map {
            (it.userObject as NavigatorNodeData).name
        }
    }

    /** A module is content of its own: the walk lists it and stops, whatever would otherwise open it. */
    fun testTheWalkListsAModuleRatherThanOpeningIt() {
        myFixture.addFileToProject("repo/settings.gradle.kts", "")
        myFixture.addFileToProject("repo/api/build.gradle.kts", "")
        myFixture.addFileToProject("repo/api/src/main/kotlin/me/acme/action/Run.kt", "")
        withCompact(enabled = true) {
            withLoneFolder(open = true) {
                assertEquals(
                    "api is the only folder there, and being a module still holds it closed",
                    emptyList<String>(),
                    walkOver("repo", listOf("repo/api")),
                )
                assertEquals(
                    "without a module to recognise, the only folder there opens as any other would",
                    listOf("api"),
                    walkOver("repo", emptyList()),
                )
            }
        }
    }

    /** A folder holding modules is scaffolding, so its own build file must not end the walk there. */
    fun testAFolderHoldingModulesKeepsTheWalkGoing() {
        myFixture.addFileToProject("repo/tools/release.sh", "")
        myFixture.addFileToProject("repo/stack/docs/guide.md", "")
        myFixture.addFileToProject("repo/stack/apps/build.gradle.kts", "")
        myFixture.addFileToProject("repo/stack/apps/api/build.gradle.kts", "")
        myFixture.addFileToProject("repo/stack/apps/web/build.gradle.kts", "")
        val modules = listOf("repo/stack/apps/api", "repo/stack/apps/web")
        withCompact(enabled = true) {
            assertEquals(
                "without modules to recognise, apps is a folder with files of its own and ends the walk",
                listOf("stack"),
                walkOver("repo", emptyList()),
            )
            assertEquals(
                "knowing api and web are modules makes apps scaffolding, build file or not",
                listOf("stack", "apps"),
                walkOver("repo", modules),
            )
        }
    }

    fun testTheModulesAProjectKnowsAreWhereItsContentRootsAre() {
        assertEquals(
            ProjectRootManager.getInstance(project).contentRoots.map { it.path }.toSet(),
            BrowseTree.moduleRootPaths(project),
        )
    }

    private fun withLoneFolder(open: Boolean, block: () -> Unit) {
        val settings = NavigatorSettings.getInstance()
        val before = settings.openLoneFolder
        settings.openLoneFolder = open
        try {
            block()
        } finally {
            settings.openLoneFolder = before
        }
    }

    private fun withDotFiles(hidden: Boolean, block: () -> Unit) {
        val settings = NavigatorSettings.getInstance()
        val before = settings.hideDotFiles
        settings.hideDotFiles = hidden
        try {
            block()
        } finally {
            settings.hideDotFiles = before
        }
    }
}
