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
        icon = data.file?.takeIf { it.isValid }?.let { IconUtil.getIcon(it, 0, project) }
            ?: AllIcons.FileTypes.Any_type
        val matcher = if (data.isDirectory) null else matcherProvider()
        val fragments = matcher?.matchingFragments(data.name)
        if (fragments != null) {
            SpeedSearchUtil.appendColoredFragments(
                this,
                data.name,
                fragments,
                SimpleTextAttributes.REGULAR_ATTRIBUTES,
                SimpleTextAttributes.REGULAR_ATTRIBUTES.derive(
                    SimpleTextAttributes.STYLE_SEARCH_MATCH, null, null, null,
                ),
            )
        } else {
            append(data.name)
        }
    }
}
