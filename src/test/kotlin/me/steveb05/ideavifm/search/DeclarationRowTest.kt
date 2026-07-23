package me.steveb05.ideavifm.search

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import javax.swing.tree.DefaultMutableTreeNode
import me.steveb05.ideavifm.render.NavigatorTreeCellRenderer
import me.steveb05.ideavifm.tree.NavigatorNodeData

/**
 * A file the query never names has to say why it is on the list, so the classes it matched trail the file
 * name, grayed, with the matched letters lit the way they are everywhere else.
 */
class DeclarationRowTest : BasePlatformTestCase() {

    private lateinit var file: VirtualFile

    override fun setUp() {
        super.setUp()
        file = myFixture.addFileToProject("model/People.kt", "class Bob\n").virtualFile
    }

    private fun render(declarations: List<Declaration>, query: String?): NavigatorTreeCellRenderer {
        val highlight = query?.let { QueryHighlight(it, file.parent.parent) }
        val renderer = NavigatorTreeCellRenderer(project, { highlight })
        val node = DefaultMutableTreeNode(NavigatorNodeData(file, file.name, false, 0, declarations))
        renderer.getTreeCellRendererComponent(Tree(), node, false, false, true, 0, false)
        return renderer
    }

    private fun text(renderer: NavigatorTreeCellRenderer): String =
        renderer.getCharSequence(false).toString()

    private fun litFragments(renderer: NavigatorTreeCellRenderer): List<String> {
        val lit = ArrayList<String>()
        val fragments = renderer.iterator()
        while (fragments.hasNext()) {
            val fragment = fragments.next()
            val style = fragments.textAttributes.style
            if (style and SimpleTextAttributes.STYLE_SEARCH_MATCH != 0) lit.add(fragment)
        }
        return lit
    }

    fun testTheMatchedClassesTrailTheFileName() {
        val rendered = text(render(listOf(Declaration("Bob", 6, 500), Declaration("Bobby", 20, 400)), "bob"))
        assertEquals("People.kt  Bob, Bobby", rendered)
    }

    fun testTheMatchedLettersOfTheClassAreLit() {
        assertEquals(listOf("Bob"), litFragments(render(listOf(Declaration("Bob", 6, 500)), "bob")))
    }

    fun testTheSuffixIsGrayed() {
        val renderer = render(listOf(Declaration("Bob", 6, 500)), null)
        val fragments = renderer.iterator()
        fragments.next()
        fragments.next()
        assertEquals(
            "the suffix is a footnote, not part of the name",
            SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor,
            fragments.textAttributes.fgColor,
        )
    }

    fun testALongListIsCountedRatherThanSpelledOut() {
        val declarations = listOf("Bob", "Bobby", "Bobcat", "Bobsleigh", "Bobbin")
            .mapIndexed { index, name -> Declaration(name, index, 500 - index) }
        assertEquals("People.kt  Bob, Bobby, Bobcat +2", text(render(declarations, "bob")))
    }

    fun testAFileWithoutMatchedClassesRendersAsBefore() {
        assertEquals("People.kt", text(render(emptyList(), "peop")))
    }
}
