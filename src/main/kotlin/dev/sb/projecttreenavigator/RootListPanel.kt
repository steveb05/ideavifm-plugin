package dev.sb.projecttreenavigator

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ClientProperty
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.render.RenderingUtil
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import java.awt.Insets
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListSelectionModel

class RootListPanel(
    private val project: Project,
    private val onUserSelection: () -> Unit,
    private val onHover: (VirtualFile) -> Unit = {},
) {

    private val listModel = DefaultListModel<BaseEntry>()
    private val list = JBList(listModel)
    private var counts: Map<BaseEntry, Int>? = null
    private var suppressEvents = false

    val component: JComponent = JBScrollPane(list)

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.emptyText.text = "Empty"
        list.cellRenderer = object : ColoredListCellRenderer<BaseEntry>() {
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
                val count = counts?.get(value)
                val nameAttributes =
                    if (count == 0) SimpleTextAttributes.GRAYED_ATTRIBUTES
                    else SimpleTextAttributes.REGULAR_ATTRIBUTES
                append(value.name, nameAttributes)
                value.parentHint?.let { append("  $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
                if (count != null && count > 0) {
                    append("  $count", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
                }
            }
        }
        list.addListSelectionListener { e ->
            if (!suppressEvents && !e.valueIsAdjusting) onUserSelection()
        }
        list.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val index = list.locationToIndex(e.point)
                if (index >= 0) onHover(listModel.getElementAt(index).file)
            }
        })
    }

    fun setActive(active: Boolean) {
        ClientProperty.put(list, RenderingUtil.ALWAYS_PAINT_SELECTION_AS_FOCUSED, active)
        component.border = if (active) PaneBorders.focus else PaneBorders.normal
        list.repaint()
    }

    fun entries(): List<BaseEntry> = (0 until listModel.size()).map { listModel.getElementAt(it) }

    fun setEntries(newEntries: List<BaseEntry>) {
        if (newEntries == entries()) return
        suppressed {
            listModel.clear()
            newEntries.forEach { listModel.addElement(it) }
        }
    }

    fun setCounts(newCounts: Map<BaseEntry, Int>) {
        counts = newCounts
        list.repaint()
    }

    fun clearCounts() {
        counts = null
        list.repaint()
    }

    fun selectedEntry(): BaseEntry? = list.selectedValue

    fun selectedIndex(): Int = list.selectedIndex

    fun selectIndex(index: Int) {
        if (listModel.isEmpty) return
        val coerced = index.coerceIn(0, listModel.size() - 1)
        suppressed {
            list.selectedIndex = coerced
            list.ensureIndexIsVisible(coerced)
        }
    }

    fun selectEntry(entry: BaseEntry) {
        val index = entries().indexOfFirst { it.file == entry.file }
        if (index >= 0) selectIndex(index)
    }

    fun entryContaining(file: VirtualFile): BaseEntry? =
        entries().filter { VfsUtilCore.isAncestor(it.file, file, false) }
            .maxByOrNull { it.file.path.length }

    fun move(delta: Int) = selectIndex(list.selectedIndex + delta)

    private inline fun suppressed(block: () -> Unit) {
        suppressEvents = true
        try {
            block()
        } finally {
            suppressEvents = false
        }
    }
}
