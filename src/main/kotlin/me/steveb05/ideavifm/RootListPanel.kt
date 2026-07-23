package me.steveb05.ideavifm

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ClientProperty
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.render.RenderingUtil
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.DefaultListModel
import javax.swing.DefaultListSelectionModel
import javax.swing.JComponent
import javax.swing.ListSelectionModel

class RootListPanel(
    project: Project,
    private val onUserSelection: () -> Unit,
    private val onHover: (VirtualFile) -> Unit = {},
    private val onContextMenu: (JComponent, Point) -> Unit = { _, _ -> },
    highlightProvider: () -> QueryHighlight? = { null },
) {

    private val listModel = DefaultListModel<BaseEntry>()
    private val list = JBList(listModel)
    private var counts: Map<BaseEntry, Int>? = null
    private var suppressEvents = false

    val component: JComponent = JBScrollPane(list)

    init {
        list.selectionModel = object : DefaultListSelectionModel() {
            override fun setSelectionInterval(index0: Int, index1: Int) {
                if (suppressEvents || isSelectable(index1)) super.setSelectionInterval(index0, index1)
            }
        }
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.isFocusable = false
        list.emptyText.text = "Empty"
        list.cellRenderer =
            NavigatorEntryCellRenderer(project, { entry -> counts?.get(entry) }, highlightProvider)
        list.addListSelectionListener { e ->
            if (!suppressEvents && !e.valueIsAdjusting) onUserSelection()
        }
        list.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val index = list.locationToIndex(e.point)
                if (index >= 0) onHover(listModel.getElementAt(index).file)
            }
        })
        list.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeShowContextMenu(e)

            override fun mouseReleased(e: MouseEvent) = maybeShowContextMenu(e)
        })
    }

    private fun maybeShowContextMenu(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val index = list.locationToIndex(e.point)
        if (index >= 0 && isSelectable(index) && list.getCellBounds(index, index).contains(e.point)) {
            list.selectedIndex = index
        }
        onContextMenu(list, e.point)
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

    /**
     * Movement wraps around the ends of the list, and steps over the entries a search grayed out, which are
     * dead rows. With nothing selectable there is nowhere to go.
     */
    fun move(delta: Int) {
        val size = listModel.size()
        if (size == 0 || (0 until size).none { isSelectable(it) }) return
        val step = if (delta < 0) -1 else 1
        var remaining = if (delta < 0) -delta else delta
        var index = list.selectedIndex.takeIf { it >= 0 } ?: if (step > 0) size - 1 else 0
        while (remaining > 0) {
            index = Math.floorMod(index + step, size)
            if (isSelectable(index)) remaining--
        }
        selectIndex(index)
    }

    /** A search grays out the entries it found nothing in; those rows are dead and cannot take a selection. */
    private fun isSelectable(index: Int): Boolean {
        return !(index < 0 || index >= listModel.size()) && counts?.get(listModel.getElementAt(index)) != 0
    }

    private inline fun suppressed(block: () -> Unit) {
        suppressEvents = true
        try {
            block()
        } finally {
            suppressEvents = false
        }
    }
}
