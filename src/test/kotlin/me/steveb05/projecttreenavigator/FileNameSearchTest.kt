package me.steveb05.projecttreenavigator

import com.intellij.psi.search.ProjectScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FileNameSearchTest : BasePlatformTestCase() {

    private lateinit var search: FileNameSearch

    override fun setUp() {
        super.setUp()
        search = FileNameSearch(project)
        myFixture.addFileToProject("backend/services/UserService.kt", "")
        myFixture.addFileToProject("frontend/api/userService.ts", "")
        myFixture.addFileToProject("config.xml", "")
    }

    private fun names(query: String, limit: Int = FileNameSearch.DEFAULT_LIMIT): List<String> =
        search.search(query, ProjectScope.getContentScope(project), limit).files.map { it.file.name }

    fun testFuzzyLowercaseMatch() {
        val names = names("usrv")
        assertTrue(names.contains("UserService.kt"))
        assertTrue(names.contains("userService.ts"))
        assertFalse(names.contains("config.xml"))
    }

    fun testCamelHumpMatch() {
        val names = names("USK")
        assertTrue(names.contains("UserService.kt"))
        assertFalse(names.contains("userService.ts"))
    }

    fun testPathQueryMatchesPathSegments() {
        val names = names("api/usrv")
        assertEquals(listOf("userService.ts"), names)
    }

    fun testEmptyAndSlashOnlyQueriesReturnNothing() {
        assertTrue(names("").isEmpty())
        assertTrue(names("  ").isEmpty())
        assertTrue(names("/").isEmpty())
    }

    fun testLimitTruncates() {
        val result = search.search("userservice", ProjectScope.getContentScope(project), 1)
        assertEquals(1, result.files.size)
        assertTrue(result.truncated)
    }

    fun testResultsAreRankedByWeightDescending() {
        val result = search.search("userservice", ProjectScope.getContentScope(project))
        val weights = result.files.map { it.weight }
        assertEquals(weights.sortedDescending(), weights)
    }
}
