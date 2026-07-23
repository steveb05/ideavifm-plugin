package me.steveb05.ideavifm.render

import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.speedSearch.SpeedSearchUtil
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import me.steveb05.ideavifm.scope.BaseEntry
import me.steveb05.ideavifm.search.QueryHighlight
import me.steveb05.ideavifm.vcs.VcsStatusColor
import java.awt.Color
import javax.swing.JList

class NavigatorEntryCellRenderer(
    private val project: Project,
    private val countProvider: (BaseEntry) -> Int? = { null },
    private val highlightProvider: () -> QueryHighlight? = { null },
) : ColoredListCellRenderer<BaseEntry>() {

    private val baseLeftInset = ipad.left

    override fun customizeCellRenderer(
        list: JList<out BaseEntry>,
        value: BaseEntry,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        ipad = JBUI.insets(ipad.top, baseLeftInset + JBUI.scale(16) * value.indent, ipad.bottom, ipad.right)
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
        val highlight = if (count == 0) null else highlightProvider()
        val fragments =
            if (value.isDirectory) highlight?.forDirectory(value.file, value.name)
            else highlight?.forFile(value.file, value.name)
        if (fragments != null) {
            SpeedSearchUtil.appendColoredFragments(
                this,
                value.name,
                fragments,
                nameAttributes,
                nameAttributes.derive(SimpleTextAttributes.STYLE_SEARCH_MATCH, null, null, null),
            )
        } else {
            append(value.name, nameAttributes)
        }
        value.parentHint?.let { append("  $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
        if (count != null && count > 0) append("  $count", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
        if (count != 0 && statusColor != null) restoreForeground(statusColor, value.name.length)
    }

    /**
     * A selected row is repainted by the platform with the selection foreground, which drops the VCS status
     * color from the row the user is standing on. Putting it back keeps a changed entry recognizable there.
     */
    private fun restoreForeground(color: Color, nameLength: Int) {
        val fragments = iterator()
        while (fragments.hasNext()) {
            fragments.next()
            if (fragments.offset >= nameLength) return
            fragments.textAttributes = fragments.textAttributes.derive(-1, color, null, null)
        }
    }
}
