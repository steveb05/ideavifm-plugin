package me.steveb05.ideavifm.render

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.speedSearch.SpeedSearchUtil
import com.intellij.util.IconUtil
import java.awt.Color
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import me.steveb05.ideavifm.search.Declaration
import me.steveb05.ideavifm.search.QueryHighlight
import me.steveb05.ideavifm.tree.NavigatorNodeData
import me.steveb05.ideavifm.vcs.VcsStatusColor

class NavigatorTreeCellRenderer(
    private val project: Project,
    private val highlightProvider: () -> QueryHighlight?,
    private val isMarked: (VirtualFile) -> Boolean = { false },
) : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        val data = ((value as? DefaultMutableTreeNode)?.userObject as? NavigatorNodeData) ?: return
        val file = data.file?.takeIf { it.isValid }
        icon = file?.let { IconUtil.getIcon(it, 0, project) } ?: AllIcons.FileTypes.Any_type
        val statusColor = file?.let {
            if (data.isDirectory) VcsStatusColor.forDirectory(project, it)
            else VcsStatusColor.forFile(project, it)
        }
        val colored = statusColor
            ?.let { SimpleTextAttributes.REGULAR_ATTRIBUTES.derive(-1, it, null, null) }
            ?: SimpleTextAttributes.REGULAR_ATTRIBUTES
        val marked = file != null && isMarked(file)
        val plain =
            if (marked) colored.derive(SimpleTextAttributes.STYLE_BOLD, null, null, null)
            else colored
        if (marked) append("✱ ", plain)
        val highlight = highlightProvider()
        val fragments =
            if (data.isDirectory) highlight?.forDirectory(file, data.name)
            else highlight?.forFile(file, data.name)
        if (fragments != null) {
            SpeedSearchUtil.appendColoredFragments(
                this,
                data.name,
                fragments,
                plain,
                plain.derive(SimpleTextAttributes.STYLE_SEARCH_MATCH, null, null, null),
            )
        } else {
            append(data.name, plain)
        }
        statusColor?.let { restoreForeground(it) }
        appendDeclarations(data.declarations, highlight)
    }

    /**
     * Why a file the query never names is on the list: the classes inside it that the query does name. They trail
     * the file name in gray, after the status color has been put back, so the suffix stays a footnote and
     * does not claim the row's VCS color.
     */
    private fun appendDeclarations(declarations: List<Declaration>, highlight: QueryHighlight?) {
        if (declarations.isEmpty()) return
        val gray = SimpleTextAttributes.GRAYED_ATTRIBUTES
        val lit = gray.derive(SimpleTextAttributes.STYLE_SEARCH_MATCH, null, null, null)
        append("  ", gray)
        for ((index, declaration) in declarations.take(MAX_SHOWN).withIndex()) {
            if (index > 0) append(", ", gray)
            val fragments = highlight?.forDeclaration(declaration.name)
            if (fragments == null) append(declaration.name, gray)
            else SpeedSearchUtil.appendColoredFragments(this, declaration.name, fragments, gray, lit)
        }
        val hidden = declarations.size - MAX_SHOWN
        if (hidden > 0) append(" +$hidden", gray)
    }

    /**
     * A selected row in a pane painted as focused has every fragment rewritten by the platform with the
     * selection foreground, which drops the VCS status color from the one row the user is standing on.
     * Putting the color back afterwards keeps a changed file recognizable under the cursor.
     */
    private fun restoreForeground(color: Color) {
        val fragments = iterator()
        while (fragments.hasNext()) {
            fragments.next()
            fragments.textAttributes = fragments.textAttributes.derive(-1, color, null, null)
        }
    }

    private companion object {
        const val MAX_SHOWN = 3
    }
}
