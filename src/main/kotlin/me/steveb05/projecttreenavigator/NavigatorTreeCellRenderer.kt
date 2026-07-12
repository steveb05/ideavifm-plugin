package me.steveb05.projecttreenavigator

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.psi.codeStyle.MinusculeMatcher
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.speedSearch.SpeedSearchUtil
import com.intellij.util.IconUtil
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

class NavigatorTreeCellRenderer(
    private val project: Project,
    private val matcherProvider: () -> MinusculeMatcher?,
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
        val plain = statusColor
            ?.let { SimpleTextAttributes.REGULAR_ATTRIBUTES.derive(-1, it, null, null) }
            ?: SimpleTextAttributes.REGULAR_ATTRIBUTES
        val matcher = if (data.isDirectory) null else matcherProvider()
        val fragments = matcher?.matchingFragments(data.name)
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
    }
}
