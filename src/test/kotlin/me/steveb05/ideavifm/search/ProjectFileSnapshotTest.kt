package me.steveb05.ideavifm.search

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.search.ProjectScope
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jetbrains.jps.model.java.JpsJavaExtensionService

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

    /** Generated code is registered as content by the build tool, so being under build/ is not what hides it. */
    fun testGeneratedSourcesAreNotSearched() {
        myFixture.addFileToProject("build/generated-sources/Generated.kt", "")
        val generated = myFixture.findFileInTempDir("build/generated-sources")
        val properties = JpsJavaExtensionService.getInstance().createSourceRootProperties("", true)
        PsiTestUtil.addSourceRoot(myFixture.module, generated, JavaSourceRootType.SOURCE, properties)
        try {
            assertFalse(names("generated").contains("Generated.kt"))
        } finally {
            PsiTestUtil.removeSourceRoot(myFixture.module, generated)
        }
    }

    fun testExcludedFoldersAreNotSearched() {
        myFixture.addFileToProject("out/Stale.kt", "")
        val excluded = myFixture.findFileInTempDir("out")
        PsiTestUtil.addExcludedRoot(myFixture.module, excluded)
        try {
            assertFalse(names("stale").contains("Stale.kt"))
        } finally {
            PsiTestUtil.removeExcludedRoot(myFixture.module, excluded)
        }
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
