package me.steveb05.ideavifm.scope

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import me.steveb05.ideavifm.settings.NavigatorSettings
import me.steveb05.ideavifm.ui.NavigatorContext

class ScopeResolverTest : BasePlatformTestCase() {

    fun testCaptureFindsModuleForProjectFile() {
        val vf = myFixture.addFileToProject("dir/A.kt", "").virtualFile
        val context = NavigatorContext.capture(project, vf)
        assertEquals(vf, context.currentFile)
        assertEquals(myFixture.module, context.module)
    }

    fun testCaptureIgnoresNullFile() {
        val context = NavigatorContext.capture(project, null)
        assertNull(context.currentFile)
        assertNull(context.module)
    }

    fun testProjectScopeEntriesAreBaseChildren() {
        val vf = myFixture.addFileToProject("dir/A.kt", "").virtualFile
        myFixture.addFileToProject("Top.kt", "")
        val context = NavigatorContext.capture(project, vf)
        val resolved = ScopeResolver.resolve(NavigatorScope.Project, context)
        assertFalse(resolved.fellBack)
        val names = resolved.entries.map { it.name }
        assertContainsElements(names, "dir", "Top.kt")
        assertTrue(resolved.searchScope.contains(vf))
    }

    fun testFolderScopeEntriesAreCurrentDirectoryChildren() {
        val inside = myFixture.addFileToProject("dir/A.kt", "").virtualFile
        val outside = myFixture.addFileToProject("other/B.kt", "").virtualFile
        val context = NavigatorContext.capture(project, inside)
        val resolved = ScopeResolver.resolve(NavigatorScope.Folder, context)
        assertFalse(resolved.fellBack)
        assertEquals(listOf("A.kt"), resolved.entries.map { it.name })
        assertTrue(resolved.searchScope.contains(inside))
        assertFalse(resolved.searchScope.contains(outside))
    }

    fun testModuleScopeEntriesComeFromContentRoot() {
        val vf = myFixture.addFileToProject("dir/A.kt", "").virtualFile
        val context = NavigatorContext.capture(project, vf)
        val resolved = ScopeResolver.resolve(NavigatorScope.Module, context)
        assertFalse(resolved.fellBack)
        assertTrue(resolved.entries.isNotEmpty())
        assertTrue(resolved.searchScope.contains(vf))
    }

    fun testModuleAndFolderFallBackToProjectWithoutCurrentFile() {
        val context = NavigatorContext.capture(project, null)
        assertTrue(ScopeResolver.resolve(NavigatorScope.Module, context).fellBack)
        assertTrue(ScopeResolver.resolve(NavigatorScope.Folder, context).fellBack)
    }

    fun testAvailableScopesStartsWithBuiltIns() {
        val context = NavigatorContext.capture(project, null)
        val labels = ScopeResolver.availableScopes(context).map { it.label }
        assertEquals(listOf("Project", "Module", "Folder"), labels.take(3))
    }

    fun testTopLevelRootsDropNestedAndBaseRoots() {
        myFixture.addFileToProject("proj/src/main/A.kt", "")
        myFixture.addFileToProject("proj/src/test/B.kt", "")
        myFixture.addFileToProject("linked/C.kt", "")
        val base = myFixture.findFileInTempDir("proj")
        val main = myFixture.findFileInTempDir("proj/src/main")
        val test = myFixture.findFileInTempDir("proj/src/test")
        val linked = myFixture.findFileInTempDir("linked")
        val result = ScopeResolver.topLevelRoots(listOf(base, main, test, linked), base)
        assertEquals(listOf(linked), result)
    }

    fun testTopLevelRootsWithoutBaseKeepOutermostOnly() {
        myFixture.addFileToProject("a/inner/A.kt", "")
        myFixture.addFileToProject("b/B.kt", "")
        val a = myFixture.findFileInTempDir("a")
        val inner = myFixture.findFileInTempDir("a/inner")
        val b = myFixture.findFileInTempDir("b")
        assertEquals(listOf(a, b), ScopeResolver.topLevelRoots(listOf(a, inner, b), null))
    }

    fun testWithParentHintsMarksDuplicateNamesOnly() {
        myFixture.addFileToProject("x/main/A.kt", "")
        myFixture.addFileToProject("y/main/B.kt", "")
        myFixture.addFileToProject("lib/C.kt", "")
        val entries = listOf(
            BaseEntry(myFixture.findFileInTempDir("x/main"), "main", true),
            BaseEntry(myFixture.findFileInTempDir("y/main"), "main", true),
            BaseEntry(myFixture.findFileInTempDir("lib"), "lib", true),
        )
        val hinted = ScopeResolver.withParentHints(entries)
        assertNotNull(hinted[0].parentHint)
        assertNotNull(hinted[1].parentHint)
        assertNull(hinted[2].parentHint)
    }

    fun testAssembleProjectEntriesFlattensWhenNoOutsideRoots() {
        myFixture.addFileToProject("base/a/x.txt", "")
        val base = myFixture.findFileInTempDir("base")
        val children = ScopeResolver.entriesForBase(project, base)
        val entries = ScopeResolver.assembleProjectEntries(BaseEntry(base, base.name, true), children, emptyList())
        assertEquals(children, entries)
    }

    fun testAssembleProjectEntriesShowsBaseBesideOutsideRootsSorted() {
        myFixture.addFileToProject("extension/sub/x.txt", "")
        myFixture.addFileToProject("engine/main/y.txt", "")
        val extension = myFixture.findFileInTempDir("extension")
        val engine = myFixture.findFileInTempDir("engine")
        val entries = ScopeResolver.assembleProjectEntries(
            BaseEntry(extension, extension.name, true),
            ScopeResolver.entriesForBase(project, extension),
            listOf(BaseEntry(engine, engine.name, true)),
        )
        assertEquals(listOf("engine", "extension"), entries.map { it.name })
    }

    fun testAssembleProjectEntriesWithNullBaseSortsOutsideRoots() {
        myFixture.addFileToProject("zeta/x.txt", "")
        myFixture.addFileToProject("alpha/y.txt", "")
        val zeta = myFixture.findFileInTempDir("zeta")
        val alpha = myFixture.findFileInTempDir("alpha")
        val entries = ScopeResolver.assembleProjectEntries(
            null,
            emptyList(),
            listOf(BaseEntry(zeta, zeta.name, true), BaseEntry(alpha, alpha.name, true)),
        )
        assertEquals(listOf("alpha", "zeta"), entries.map { it.name })
    }

    fun testEntriesForBaseCompactChains() {
        myFixture.addFileToProject("cbase/a/b/x.txt", "")
        myFixture.addFileToProject("cbase/top.txt", "")
        val base = myFixture.findFileInTempDir("cbase")
        withCompact {
            val entries = ScopeResolver.entriesForBase(project, base)
            assertEquals(listOf("a/b", "top.txt"), entries.map { it.name })
            assertEquals(myFixture.findFileInTempDir("cbase/a/b"), entries.first().file)
        }
    }

    fun testWithChildEntriesInsertsIndentedChildren() {
        myFixture.addFileToProject("wce/engine/core/x.txt", "")
        myFixture.addFileToProject("wce/engine/modules/y.txt", "")
        myFixture.addFileToProject("wce/engine/readme.md", "")
        val engine = myFixture.findFileInTempDir("wce/engine")
        withLeftChildren(children = true, files = true) {
            val entries = ScopeResolver.withChildEntries(project, listOf(BaseEntry(engine, "engine", true)))
            assertEquals(listOf("engine", "core", "modules", "readme.md"), entries.map { it.name })
            assertEquals(listOf(0, 1, 1, 1), entries.map { it.indent })
        }
    }

    fun testWithChildEntriesSkipsFilesWhenDisabled() {
        myFixture.addFileToProject("wcf/engine/core/x.txt", "")
        myFixture.addFileToProject("wcf/engine/readme.md", "")
        val engine = myFixture.findFileInTempDir("wcf/engine")
        withLeftChildren(children = true, files = false) {
            val entries = ScopeResolver.withChildEntries(project, listOf(BaseEntry(engine, "engine", true)))
            assertEquals(listOf("engine", "core"), entries.map { it.name })
        }
    }

    fun testWithChildEntriesDisabledKeepsEntries() {
        myFixture.addFileToProject("wcd/engine/core/x.txt", "")
        val engine = myFixture.findFileInTempDir("wcd/engine")
        withLeftChildren(children = false, files = true) {
            val original = listOf(BaseEntry(engine, "engine", true))
            assertEquals(original, ScopeResolver.withChildEntries(project, original))
        }
    }

    fun testModuleFamilyNamesGroupsSourceSetSiblings() {
        val all = listOf("extension", "extension.main", "extension.test", "engine", "other.main")
        assertEquals(
            listOf("extension", "extension.main", "extension.test"),
            ScopeResolver.moduleFamilyNames(all, "extension.main"),
        )
    }

    fun testModuleFamilyNamesPlainNameIsItself() {
        assertEquals(listOf("engine"), ScopeResolver.moduleFamilyNames(listOf("engine", "extension"), "engine"))
    }

    private fun withLeftChildren(children: Boolean, files: Boolean, block: () -> Unit) {
        val settings = NavigatorSettings.getInstance()
        val beforeChildren = settings.leftPaneChildren
        val beforeFiles = settings.leftPaneChildFiles
        settings.leftPaneChildren = children
        settings.leftPaneChildFiles = files
        try {
            block()
        } finally {
            settings.leftPaneChildren = beforeChildren
            settings.leftPaneChildFiles = beforeFiles
        }
    }

    private fun withCompact(block: () -> Unit) {
        val settings = NavigatorSettings.getInstance()
        val before = settings.compactFolders
        settings.compactFolders = true
        try {
            block()
        } finally {
            settings.compactFolders = before
        }
    }
}
