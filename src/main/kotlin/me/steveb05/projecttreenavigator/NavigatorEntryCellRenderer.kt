package me.steveb05.projecttreenavigator

import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Insets
import javax.swing.JList

class NavigatorEntryCellRenderer(
    private val project: Project,
    private val countProvider: (BaseEntry) -> Int? = { null },
) : ColoredListCellRenderer<BaseEntry>() {

    private val baseLeftInset = ipad.left

    override fun customizeCellRenderer(
        list: JList<out BaseEntry>,
        value: BaseEntry,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        ipad = Insets(ipad.top, baseLeftInset + JBUI.scale(16) * value.indent, ipad.bottom, ipad.right)
        icon = IconUtil.getIcon(value.file, 0, project)
        val count = countProvider(value)
        val statusColor = value.file.takeIf { it.isValid }?.let {
            if (value.isDirectory) VcsStatusColor.forDirectory(project, it)
            else VcsStatusColor.forFile(project, it)
        }
        val nameAttributes = when {
            count == 0 -> SimpleTextAttributes.GRAYED_ATTRIBUTES
            statusColor != null -> SimpleTextAttributes.REGULAR_ATTRIBUTES.derive(-1, statusColor, null, null)
            else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
        }
        append(value.name, nameAttributes)
        value.parentHint?.let { append("  $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
        if (count != null && count > 0) append("  $count", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
        if (count != 0 && statusColor != null) restoreForeground(statusColor)
    }

    /**
     * A selected row is repainted by the platform with the selection foreground, which drops the VCS status
     * color from the row the user is standing on. Putting it back keeps a changed entry recognizable there.
     */
    private fun restoreForeground(color: Color) {
        val fragments = iterator()
        if (!fragments.hasNext()) return
        fragments.next()
        fragments.textAttributes = fragments.textAttributes.derive(-1, color, null, null)
    }
}
