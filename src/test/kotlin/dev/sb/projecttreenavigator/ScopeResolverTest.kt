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

    fun testProjectScopeContainsAllProjectFiles() {
        val vf = myFixture.addFileToProject("dir/A.kt", "").virtualFile
        val context = NavigatorContext.capture(project, vf)
        val resolved = ScopeResolver.resolve(NavigatorScope.Project, context)
        assertFalse(resolved.fellBack)
        assertTrue(resolved.roots.isNotEmpty())
        assertTrue(resolved.searchScope.contains(vf))
    }

    fun testFolderScopeIsLimitedToCurrentDirectory() {
        val inside = myFixture.addFileToProject("dir/A.kt", "").virtualFile
        val outside = myFixture.addFileToProject("other/B.kt", "").virtualFile
        val context = NavigatorContext.capture(project, inside)
        val resolved = ScopeResolver.resolve(NavigatorScope.Folder, context)
        assertFalse(resolved.fellBack)
        assertEquals(listOf(inside.parent), resolved.roots)
        assertTrue(resolved.searchScope.contains(inside))
        assertFalse(resolved.searchScope.contains(outside))
    }

    fun testModuleScopeResolvesToModuleContentRoots() {
        val vf = myFixture.addFileToProject("dir/A.kt", "").virtualFile
        val context = NavigatorContext.capture(project, vf)
        val resolved = ScopeResolver.resolve(NavigatorScope.Module, context)
        assertFalse(resolved.fellBack)
        assertTrue(resolved.roots.isNotEmpty())
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
}
