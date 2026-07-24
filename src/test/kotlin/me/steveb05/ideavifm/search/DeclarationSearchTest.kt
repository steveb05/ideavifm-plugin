package me.steveb05.ideavifm.search

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.psi.search.ProjectScope
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The point of the whole feature: a class the user can name lives in a file whose name they cannot. These run
 * against the real Kotlin contributor, the same one Go to Class uses, rather than a stand in for it.
 */
class DeclarationSearchTest : BasePlatformTestCase() {

    private lateinit var search: DeclarationSearch
    private lateinit var people: VirtualFile
    private lateinit var strings: VirtualFile

    override fun setUp() {
        super.setUp()
        search = DeclarationSearch(project)
        strings = myFixture.addFileToProject(
            "model/Strings.kt",
            """
            package model

            fun String.bobcase(): String = uppercase()

            class Holder {
                fun bobmember(): Int = 1
            }
            """.trimIndent(),
        ).virtualFile
        people = myFixture.addFileToProject(
            "model/People.kt",
            """
            package model

            class Bob
            class Bobby
            class Alice
            interface Jim
            """.trimIndent(),
        ).virtualFile
        myFixture.addFileToProject("model/Robot.kt", "package model\n\nclass BobRunner\n")
        myFixture.addFileToProject("notes/bob.txt", "not a class\n")
    }

    private fun declaring(
        query: String,
        scope: com.intellij.psi.search.GlobalSearchScope = contentScope(),
        depth: DeclarationDepth = DeclarationDepth.TOP_LEVEL,
    ) = ReadAction.compute<Map<VirtualFile, List<Declaration>>, RuntimeException> {
        search.search(query, scope, depth)
    }

    private fun contentScope() = ProjectScope.getContentScope(project)

    /** Indexing can start after the popup has checked for it and before this search runs on its own thread. */
    fun testIndexingLeavesNothingToReadRatherThanThrowing() {
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertTrue(declaring("bob").isEmpty())
        }
        assertEquals(listOf("Bob", "Bobby"), declaring("bob")[people]?.map { it.name })
    }

    fun testAClassNameReachesTheFileThatDeclaresIt() {
        assertEquals(listOf("Bob", "Bobby"), declaring("bob")[people]?.map { it.name })
    }

    fun testTheBestMatchingClassComesFirst() {
        val names = declaring("bobby")[people]?.map { it.name }
        assertEquals("the class the query spells out is the one to jump to", "Bobby", names?.first())
    }

    fun testTheOffsetPointsAtTheDeclaration() {
        val offset = declaring("bobby")[people]?.first()?.offset
        assertNotNull("without an offset there is nothing to jump to", offset)
        assertTrue(
            "the offset must land on the declaration, not at the top of the file",
            String(people.contentsToByteArray()).substring(offset!!).startsWith("Bobby"),
        )
    }

    fun testFuzzyMatchingWorksTheWayItDoesForFileNames() {
        assertEquals(listOf("BobRunner"), declaring("brun").values.flatten().map { it.name })
    }

    fun testAFileWhoseNameMatchesButDeclaresNothingIsNotReached() {
        val files = declaring("bob").keys.map { it.name }
        assertFalse("bob.txt declares no class, its name is the file search's business: $files", "bob.txt" in files)
    }

    fun testASingleLetterIsLeftAlone() {
        assertTrue(
            "one letter matches a class in most files, which would bury the files named after the query",
            declaring("b").isEmpty(),
        )
    }

    fun testScopeIsRespected() {
        val notes = people.parent.parent.findChild("notes")!!
        val declaring = declaring("bob", GlobalSearchScopesCore.directoryScope(project, notes, true))
        assertTrue("a class outside the searched folder must not show up: $declaring", declaring.isEmpty())
    }

    fun testAnExtensionFunctionReachesTheFileThatDeclaresIt() {
        assertEquals(
            "an extension function is written at the top level of a file, not inside a class",
            listOf("bobcase"),
            declaring("bobcase")[strings]?.map { it.name },
        )
    }

    fun testTheOffsetOfAnExtensionFunctionLandsOnIt() {
        val offset = declaring("bobcase")[strings]?.first()?.offset
        assertNotNull(offset)
        assertTrue(
            "the offset must land on the function, not at the top of the file",
            String(strings.contentsToByteArray()).substring(offset!!).startsWith("bobcase"),
        )
    }

    fun testClassesOnlyLeavesFunctionsAlone() {
        assertTrue(
            "the narrowest setting is what Go to Class offers, and a function is not a class",
            declaring("bobcase", depth = DeclarationDepth.CLASSES).isEmpty(),
        )
    }

    fun testTopLevelLeavesWhatAClassHoldsAlone() {
        assertTrue(
            "a member is reachable through the class that holds it, which is the row worth showing",
            declaring("bobmember", depth = DeclarationDepth.TOP_LEVEL).isEmpty(),
        )
    }

    fun testAllSymbolsReachesWhatAClassHolds() {
        assertEquals(
            listOf("bobmember"),
            declaring("bobmember", depth = DeclarationDepth.SYMBOLS)[strings]?.map { it.name },
        )
    }

    fun testAClassIsStillFoundAtEveryDepth() {
        for (depth in DeclarationDepth.entries) {
            assertTrue(
                "widening what a query matches must not lose the classes: $depth",
                declaring("bobby", depth = depth)[people]?.map { it.name }.orEmpty().contains("Bobby"),
            )
        }
    }

    fun testAPathQueryStaysAPathQuery() {
        assertTrue(
            "a query spelled with a slash asks for a path, and classes have none",
            declaring("model/bob").isEmpty(),
        )
    }
}
