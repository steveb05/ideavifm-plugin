package me.steveb05.ideavifm.search

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
        myFixture.addFileToProject("extensions/_DocsExtension/build.gradle.kts", "")
        myFixture.addFileToProject("extensions/VaultExtension/build.gradle.kts", "")
        myFixture.addFileToProject("notes/vault.txt", "")
        myFixture.addFileToProject("engine/adapters/loot/config/build.gradle.kts", "")
        myFixture.addFileToProject("engine/content/data/tracker/build.gradle.kts", "")
    }

    private fun names(query: String, limit: Int = FileNameSearch.DEFAULT_LIMIT): List<String> =
        search.search(query, ProjectScope.getContentScope(project), limit).files.map { it.file.name }

    private fun paths(query: String): List<String> =
        search.search(query, ProjectScope.getContentScope(project)).files
            .map { FileNameSearch.searchPath(project, it.file) }

    fun testFuzzyLowercaseMatch() {
        val names = names("usrv")
        assertTrue(names.contains("UserService.kt"))
        assertTrue(names.contains("userService.ts"))
        assertFalse(names.contains("config.xml"))
    }

    fun testAnUppercaseQueryFindsWhatALowercaseOneFinds() {
        assertEquals(names("usrv").toSet(), names("USRV").toSet())
        assertTrue(names("USRV").contains("userService.ts"))
    }

    /** Typing a capital says which of two files spelled the same way is meant, not which of them matches. */
    fun testCaseDecidesTheRankRatherThanTheMatch() {
        assertEquals(listOf("userService.ts", "UserService.kt"), names("user"))
        assertEquals(listOf("UserService.kt", "userService.ts"), names("User"))
    }

    fun testPathQueryMatchesPathSegments() {
        val names = names("api/usrv")
        assertEquals(listOf("userService.ts"), names)
    }

    fun testQuerySpansFolderAndFileName() {
        val paths = paths("docbui")
        assertEquals(listOf("extensions/_DocsExtension/build.gradle.kts"), paths)
    }

    fun testScatteredLettersInADeepFolderChainDoNotMatch() {
        val paths = paths("docbui")
        assertFalse(
            "d, o and c picked out of unrelated folders must not drag in every build.gradle.kts: $paths",
            paths.any { it.startsWith("engine/") },
        )
    }

    fun testFolderNameAloneFindsWhatIsInside() {
        assertTrue(paths("vault").contains("extensions/VaultExtension/build.gradle.kts"))
    }

    fun testFileNameMatchesOutrankFolderOnlyMatches() {
        assertEquals(
            listOf("notes/vault.txt", "extensions/VaultExtension/build.gradle.kts"),
            paths("vault"),
        )
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
