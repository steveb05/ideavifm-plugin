package dev.sb.projecttreenavigator

import com.intellij.testFramework.fixtures.BasePlatformTestCase

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
}
