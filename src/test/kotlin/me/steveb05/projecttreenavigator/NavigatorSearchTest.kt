package me.steveb05.projecttreenavigator

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class NavigatorSearchTest : BasePlatformTestCase() {

    private lateinit var named: VirtualFile
    private lateinit var declaring: VirtualFile
    private lateinit var foldered: VirtualFile

    override fun setUp() {
        super.setUp()
        named = myFixture.addFileToProject("Bob.kt", "").virtualFile
        declaring = myFixture.addFileToProject("model/People.kt", "").virtualFile
        foldered = myFixture.addFileToProject("bob/config/build.gradle.kts", "").virtualFile
    }

    private fun bob(offset: Int = 10, weight: Int = 500) = Declaration("Bob", offset, weight)

    fun testAFileReachedThroughAClassOutranksOneMatchedThroughItsFolders() {
        val merged = NavigatorSearch.merge(
            SearchResult(listOf(RankedFile(foldered, -900)), false),
            mapOf(declaring to listOf(bob())),
        )
        assertEquals(listOf(declaring, foldered), merged.files.map { it.file })
    }

    fun testAFileTheQueryNamesStillComesFirst() {
        val merged = NavigatorSearch.merge(
            SearchResult(listOf(RankedFile(named, 100_500)), false),
            mapOf(declaring to listOf(bob())),
        )
        assertEquals(listOf(named, declaring), merged.files.map { it.file })
    }

    fun testANamedFileCarriesTheClassesItAlsoDeclares() {
        val merged = NavigatorSearch.merge(
            SearchResult(listOf(RankedFile(named, 100_500)), false),
            mapOf(named to listOf(bob())),
        )
        val only = merged.files.single()
        assertEquals("the row has to be able to say the class is in there too", listOf("Bob"), only.declarations.map { it.name })
        assertEquals("its rank is the one its own name earned", 100_500, only.weight)
    }

    fun testTheBestClassInAFileSetsItsRank() {
        val merged = NavigatorSearch.merge(
            SearchResult(emptyList(), false),
            mapOf(
                declaring to listOf(bob(weight = 100)),
                foldered to listOf(bob(weight = 900)),
            ),
        )
        assertEquals(listOf(foldered, declaring), merged.files.map { it.file })
    }

    fun testWithoutClassesTheNamedResultIsHandedBackUntouched() {
        val named = SearchResult(listOf(RankedFile(foldered, -900)), true)
        assertSame(named, NavigatorSearch.merge(named, emptyMap()))
    }

    fun testTheLimitTruncates() {
        val merged = NavigatorSearch.merge(
            SearchResult(listOf(RankedFile(named, 100_500)), false),
            mapOf(declaring to listOf(bob())),
            limit = 1,
        )
        assertEquals(listOf(named), merged.files.map { it.file })
        assertTrue(merged.truncated)
    }
}
