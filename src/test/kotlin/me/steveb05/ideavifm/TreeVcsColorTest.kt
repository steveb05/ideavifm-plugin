package me.steveb05.ideavifm

import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.FileStatusManager
import com.intellij.openapi.vcs.impl.FileStatusProvider
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.ClientProperty
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.components.JBList
import com.intellij.ui.render.RenderingUtil
import com.intellij.ui.treeStructure.Tree
import java.awt.Color
import javax.swing.UIManager
import javax.swing.tree.DefaultMutableTreeNode

class TreeVcsColorTest : BasePlatformTestCase() {

    fun testChangedFileRowIsPaintedWithItsStatusColor() {
        val file = myFixture.addFileToProject("root/changed.kt", "val a = 1\n").virtualFile
        markModified(file)
        assertEquals(FileStatus.MODIFIED.color, rowColor(file, selected = false))
    }

    fun testUnchangedFileRowKeepsTheDefaultColor() {
        val file = myFixture.addFileToProject("root/clean.kt", "val b = 2\n").virtualFile
        assertNull(rowColor(file, selected = false))
    }

    fun testChangedFileKeepsItsColorOnTheSelectedRow() {
        val file = myFixture.addFileToProject("root/open.kt", "val c = 3\n").virtualFile
        markModified(file)
        val forced = UIManager.getBoolean(FORCE_SELECTION_FOREGROUND)
        UIManager.put(FORCE_SELECTION_FOREGROUND, true)
        try {
            assertEquals(
                "the row the navigator lands on is the file being edited, its color must survive selection",
                FileStatus.MODIFIED.color,
                rowColor(file, selected = true),
            )
        } finally {
            UIManager.put(FORCE_SELECTION_FOREGROUND, forced)
        }
    }

    fun testChangedFileKeepsItsColorOnTheSelectedLeftPaneRow() {
        val file = myFixture.addFileToProject("root/entry.kt", "val d = 4\n").virtualFile
        markModified(file)
        val list = JBList(listOf(BaseEntry(file, file.name, false)))
        val renderer = NavigatorEntryCellRenderer(project)
        renderer.getListCellRendererComponent(list, BaseEntry(file, file.name, false), 0, true, true)
        assertEquals(
            "the left pane lands on the file being edited, its color must survive selection",
            FileStatus.MODIFIED.color,
            firstColor(renderer),
        )
    }

    private fun markModified(target: VirtualFile) {
        val provider = FileStatusProvider { file -> if (file == target) FileStatus.MODIFIED else null }
        FileStatusProvider.EP_NAME.getPoint(project).registerExtension(provider, testRootDisposable)
        FileStatusManager.getInstance(project).fileStatusesChanged()
    }

    private fun rowColor(file: VirtualFile, selected: Boolean): Color? {
        val tree = object : Tree() {
            override fun hasFocus(): Boolean = true
        }
        ClientProperty.put(tree, RenderingUtil.ALWAYS_PAINT_SELECTION_AS_FOCUSED, true)
        val node = DefaultMutableTreeNode(NavigatorNodeData(file, file.name, false))
        val renderer = NavigatorTreeCellRenderer(project, { null })
        renderer.getTreeCellRendererComponent(tree, node, selected, false, true, 0, selected)
        return firstColor(renderer)
    }

    private fun firstColor(renderer: SimpleColoredComponent): Color? {
        val fragments = renderer.iterator()
        while (fragments.hasNext()) {
            fragments.next()
            fragments.textAttributes.fgColor?.let { return it }
        }
        return null
    }

    private companion object {
        const val FORCE_SELECTION_FOREGROUND = "Tree.forceFocusedSelectionForeground"
    }
}
