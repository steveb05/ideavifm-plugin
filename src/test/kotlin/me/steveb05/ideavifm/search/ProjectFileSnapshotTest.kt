package me.steveb05.ideavifm.search

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.search.ProjectScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ProjectFileSnapshotTest : BasePlatformTestCase() {

    private lateinit var search: FileNameSearch

    override fun setUp() {
        super.setUp()
        search = FileNameSearch(project)
        myFixture.addFileToProject("backend/UserService.kt", "")
    }

    private fun names(query: String): List<String> =
        search.search(query, ProjectScope.getContentScope(project)).files.map { it.file.name }

    fun testTheProjectIsReadOnceAndHeldAfterwards() {
        val snapshot = ProjectFileSnapshot.getInstance(project)
        assertFalse(snapshot.isReady())
        names("user")
        assertTrue(snapshot.isReady())
    }

    fun testAFileCreatedAfterTheFirstSearchIsFound() {
        names("user")
        myFixture.addFileToProject("backend/OrderService.kt", "")
        assertTrue(names("order").contains("OrderService.kt"))
    }

    fun testADeletedFileDropsOutOfTheResults() {
        val doomed = myFixture.addFileToProject("backend/Doomed.kt", "").virtualFile
        assertTrue(names("doomed").contains("Doomed.kt"))
        WriteCommandAction.runWriteCommandAction(project) { doomed.delete(this) }
        assertFalse(names("doomed").contains("Doomed.kt"))
    }

    /** A rename rewrites the paths of everything below it, so what is held is read again rather than patched. */
    fun testARenamedFileIsFoundUnderItsNewName() {
        val file = myFixture.addFileToProject("backend/Before.kt", "").virtualFile
        assertTrue(names("before").contains("Before.kt"))
        WriteCommandAction.runWriteCommandAction(project) { file.rename(this, "After.kt") }
        assertFalse(ProjectFileSnapshot.getInstance(project).isReady())
        assertTrue(names("after").contains("After.kt"))
        assertFalse(names("before").contains("Before.kt"))
    }
}
