package dev.sb.projecttreenavigator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrunedTreeBuilderTest {

    private fun match(path: String, weight: Int = 0) =
        PrunedMatch(path.split('/'), path, weight)

    @Test
    fun emptyInputGivesEmptyTree() {
        assertTrue(PrunedTreeBuilder.build(emptyList<PrunedMatch<String>>()).isEmpty())
    }

    @Test
    fun singleFileAtRoot() {
        val roots = PrunedTreeBuilder.build(listOf(match("a.txt", 7)))
        assertEquals(1, roots.size)
        assertEquals("a.txt", roots[0].name)
        assertEquals("a.txt", roots[0].payload)
        assertEquals(7, roots[0].weight)
        assertTrue(roots[0].children.isEmpty())
    }

    @Test
    fun nestedFilesShareAncestorFolders() {
        val roots = PrunedTreeBuilder.build(
            listOf(match("src/main/A.kt"), match("src/main/B.kt"), match("src/test/C.kt"))
        )
        assertEquals(1, roots.size)
        val src = roots[0]
        assertEquals("src", src.name)
        assertNull(src.payload)
        assertEquals(listOf("main", "test"), src.children.map { it.name })
        assertEquals(listOf("A.kt", "B.kt"), src.children[0].children.map { it.name })
    }

    @Test
    fun foldersSortBeforeFilesCaseInsensitive() {
        val roots = PrunedTreeBuilder.build(
            listOf(match("Zeta.txt"), match("alpha.txt"), match("beta/x.txt"), match("Apple/y.txt"))
        )
        assertEquals(listOf("Apple", "beta", "alpha.txt", "Zeta.txt"), roots.map { it.name })
    }

    @Test
    fun fileAndFolderWithSameNameStayDistinct() {
        val roots = PrunedTreeBuilder.build(listOf(match("build"), match("build/out.txt")))
        assertEquals(2, roots.size)
        assertNull(roots[0].payload)
        assertEquals("build", roots[0].name)
        assertEquals("build", roots[1].payload)
    }
}
