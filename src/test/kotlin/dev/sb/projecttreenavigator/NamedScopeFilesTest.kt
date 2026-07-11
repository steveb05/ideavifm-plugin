package dev.sb.projecttreenavigator

import com.intellij.psi.search.scope.packageSet.FilePatternPackageSet
import com.intellij.psi.search.scope.packageSet.NamedScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.EmptyIcon
import java.util.function.Supplier

class NamedScopeFilesTest : BasePlatformTestCase() {

    private fun extScope(): NamedScope = NamedScope(
        "Ext",
        Supplier { "Ext" },
        EmptyIcon.ICON_0,
        FilePatternPackageSet(null, "ext//*"),
    )

    fun testCollectReturnsOnlyFilesInScope() {
        myFixture.addFileToProject("ext/one/A.kt", "")
        myFixture.addFileToProject("ext/B.kt", "")
        myFixture.addFileToProject("other/C.kt", "")
        val result = NamedScopeFiles.collect(project, extScope())
        val names = result.files.map { it.name }.sorted()
        assertEquals(listOf("A.kt", "B.kt"), names)
        assertFalse(result.truncated)
    }

    fun testCollectHonorsLimit() {
        myFixture.addFileToProject("ext/A.kt", "")
        myFixture.addFileToProject("ext/B.kt", "")
        val result = NamedScopeFiles.collect(project, extScope(), 1)
        assertEquals(1, result.files.size)
        assertTrue(result.truncated)
    }

    fun testScopeWithoutPackageSetGivesEmptyResult() {
        myFixture.addFileToProject("ext/A.kt", "")
        val empty = NamedScope("Empty", Supplier { "Empty" }, EmptyIcon.ICON_0, null)
        val result = NamedScopeFiles.collect(project, empty)
        assertTrue(result.files.isEmpty())
    }
}
