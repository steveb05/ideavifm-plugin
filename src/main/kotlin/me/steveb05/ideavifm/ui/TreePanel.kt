package me.steveb05.ideavifm.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ClientProperty
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.render.RenderingUtil
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.JComponent
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel
import me.steveb05.ideavifm.render.NavigatorTreeCellRenderer
import me.steveb05.ideavifm.search.QueryHighlight
import me.steveb05.ideavifm.search.RankedFile
import me.steveb05.ideavifm.settings.NavigatorSettings
import me.steveb05.ideavifm.tree.BrowseTree
import me.steveb05.ideavifm.tree.NavigatorNodeData
import me.steveb05.ideavifm.tree.PrunedMatch
import me.steveb05.ideavifm.tree.PrunedTreeBuilder
import me.steveb05.ideavifm.tree.PrunedTreeNode

object PaneBorders {
    val focus: javax.swing.border.Border
        get() = JBUI.Borders.customLine(
            JBColor.namedColor("Component.focusColor", JBColor(0x87AFDA, 0x466D94)),
            2,
        )
    val normal: javax.swing.border.Border
        get() = JBUI.Borders.customLine(JBColor.border(), 2)
}

class TreePanel(
    private val project: Project,
    highlightProvider: () -> QueryHighlight?,
    private val onActivate: () -> Unit,
    private val onCommit: () -> Unit,
    private val onHover: (NavigatorNodeData) -> Unit = {},
    private val onSelectionChanged: () -> Unit = {},
    private val onContextMenu: (JComponent, Point) -> Unit = { _, _ -> },
) {

    enum class CollapseOutcome { COLLAPSED, MOVED_TO_PARENT, AT_TOP_LEVEL }

    private val tree = Tree()
    private val marked = LinkedHashSet<VirtualFile>()

    /** Set while the pane shows what a query found, where the folder rows are scaffolding rather than results. */
    private var matchesOnly = false

    val component: JComponent = JBScrollPane(tree)

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.toggleClickCount = 0
        tree.isFocusable = false
        tree.cellRenderer = NavigatorTreeCellRenderer(project, highlightProvider) { it in marked }
        tree.addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {
                val node = event.path.lastPathComponent as DefaultMutableTreeNode
                BrowseTree.loadChildren(project, model(), node)
            }

            override fun treeWillCollapse(event: TreeExpansionEvent) = Unit
        })
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeShowContextMenu(e)

            override fun mouseReleased(e: MouseEvent) = maybeShowContextMenu(e)

            override fun mouseClicked(e: MouseEvent) {
                if (e.isPopupTrigger) return
                onActivate()
                if (e.isControlDown) {
                    selectRowAt(e)
                    toggleMark(advance = false)
                    return
                }
                if (e.clickCount == 2) onCommit()
            }
        })
        tree.addTreeSelectionListener { onSelectionChanged() }
        tree.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val path = tree.getPathForLocation(e.x, e.y) ?: return
                val data =
                    ((path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? NavigatorNodeData)
                        ?: return
                if (data.file != null) onHover(data)
            }
        })
    }

    fun setActive(active: Boolean) {
        ClientProperty.put(tree, RenderingUtil.ALWAYS_PAINT_SELECTION_AS_FOCUSED, active)
        component.border = if (active) PaneBorders.focus else PaneBorders.normal
        tree.repaint()
    }

    fun setEmptyText(text: String) {
        tree.emptyText.text = text
    }

    fun toggleMark(advance: Boolean = true) {
        val file = selectedFile() ?: return
        if (!marked.remove(file)) marked.add(file)
        if (advance) move(1)
        tree.repaint()
    }

    fun markedFiles(): List<VirtualFile> = marked.filter { it.isValid }

    fun clearMarks() {
        if (marked.isEmpty()) return
        marked.clear()
        tree.repaint()
    }

    fun showSubtree(base: VirtualFile) {
        marked.clear()
        matchesOnly = false
        tree.model = BrowseTree.createSubtreeModel(project, base)
        if (tree.rowCount > 0) tree.setSelectionRow(0)
    }

    fun showEmpty() {
        marked.clear()
        matchesOnly = false
        tree.model = DefaultTreeModel(DefaultMutableTreeNode(NavigatorNodeData(null, "", true)))
    }

    /**
     * [matchesOnly] says the rows come from a query rather than from browsing, which is what makes the folder
     * rows scaffolding: they are on screen to say where the matches live, not as results of their own.
     */
    fun showPruned(ranked: List<RankedFile>, base: VirtualFile?, matchesOnly: Boolean = false) {
        marked.clear()
        this.matchesOnly = matchesOnly
        val hiddenRoot = DefaultMutableTreeNode(NavigatorNodeData(base, base?.name.orEmpty(), true))
        if (base != null) {
            val prunedMatches = ranked.mapNotNull { m ->
                val relative = VfsUtilCore.getRelativePath(m.file, base) ?: return@mapNotNull null
                if (relative.isEmpty()) return@mapNotNull null
                PrunedMatch(relative.split('/'), m, m.weight)
            }
            val built = PrunedTreeBuilder.build(prunedMatches)
            val display =
                if (NavigatorSettings.getInstance().compactFolders) PrunedTreeBuilder.compact(built)
                else built
            appendPruned(hiddenRoot, display)
        }
        tree.model = DefaultTreeModel(hiddenRoot)
    }

    fun expandToFirstFileLevel() {
        for (node in BrowseTree.autoExpandTargets(project, model())) {
            tree.expandPath(TreePath(node.path))
        }
    }

    fun expandAll() = TreeUtil.expandAll(tree)

    fun expandTopLevel() = TreeUtil.expand(tree, 1)

    /** Collapses the tree and opens it again the way it looks when the popup opens. */
    fun resetToOpenState() {
        TreeUtil.collapseAll(tree, 0)
        expandToFirstFileLevel()
        selectFirstOpenedFolder()
    }

    /**
     * Leaves the cursor where the reset opened to: the first folder whose own files are showing, rather
     * than on a folder such as src that only exists to hold other folders.
     */
    private fun selectFirstOpenedFolder() {
        if (tree.rowCount == 0) return
        val row = (0 until tree.rowCount).firstOrNull { showsItsFiles(it) } ?: 0
        tree.setSelectionRow(row)
        tree.scrollRowToVisible(row)
    }

    private fun showsItsFiles(row: Int): Boolean {
        val path = tree.getPathForRow(row) ?: return false
        if (!tree.isExpanded(path)) return false
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return false
        return node.children().asSequence()
            .filterIsInstance<DefaultMutableTreeNode>()
            .any { nodeData(it)?.isDirectory == false }
    }

    fun move(delta: Int) {
        val rowCount = tree.rowCount
        if (rowCount == 0) return
        val current = tree.selectionRows?.firstOrNull() ?: -1
        val next =
            if (matchesOnly) nextMatchRow(current, delta)
            else (current + delta).coerceIn(0, rowCount - 1)
        if (next < 0) return
        tree.setSelectionRow(next)
        tree.scrollRowToVisible(next)
    }

    /**
     * Where a step lands while a query is showing: on the next match, over the folders that only hold it.
     * A step off either end stays on the outermost match rather than wrapping, the way browsing does.
     */
    private fun nextMatchRow(current: Int, delta: Int): Int {
        val rows = matchRows()
        if (rows.isEmpty()) return -1
        val position = rows.indexOf(current)
        if (position >= 0) return rows[(position + delta).coerceIn(0, rows.lastIndex)]
        if (delta < 0) return rows.lastOrNull { it < current } ?: rows.first()
        return rows.firstOrNull { it > current } ?: rows.last()
    }

    private fun matchRows(): List<Int> = (0 until tree.rowCount).filter { isMatchRow(it) }

    private fun isMatchRow(row: Int): Boolean {
        val node = tree.getPathForRow(row)?.lastPathComponent as? DefaultMutableTreeNode ?: return false
        return nodeData(node)?.isDirectory == false
    }

    fun selectFirstRowIfNone() {
        if (tree.selectionPath != null || tree.rowCount == 0) return
        val row = if (matchesOnly) matchRows().firstOrNull() ?: return else 0
        tree.setSelectionRow(row)
    }

    fun selectBestMatch() {
        val hiddenRoot = model().root as DefaultMutableTreeNode
        var best: DefaultMutableTreeNode? = null
        var bestWeight = Int.MIN_VALUE
        val enumeration = hiddenRoot.depthFirstEnumeration()
        while (enumeration.hasMoreElements()) {
            val node = enumeration.nextElement() as DefaultMutableTreeNode
            val data = nodeData(node) ?: continue
            if (!data.isDirectory && data.weight > bestWeight) {
                best = node
                bestWeight = data.weight
            }
        }
        val target = best ?: return
        selectPath(TreePath(target.path))
    }

    fun selectFile(file: VirtualFile) {
        val hiddenRoot = model().root as DefaultMutableTreeNode
        val enumeration = hiddenRoot.depthFirstEnumeration()
        while (enumeration.hasMoreElements()) {
            val node = enumeration.nextElement() as DefaultMutableTreeNode
            if (nodeData(node)?.file == file) {
                selectPath(TreePath(node.path))
                return
            }
        }
    }

    fun locate(file: VirtualFile, base: VirtualFile) {
        if (file == base || !VfsUtilCore.isAncestor(base, file, false)) return
        val model = model()
        var node = model.root as DefaultMutableTreeNode
        while (nodeData(node)?.file != file) {
            BrowseTree.loadChildren(project, model, node)
            node = node.children().asSequence()
                .filterIsInstance<DefaultMutableTreeNode>()
                .firstOrNull { child ->
                    val childFile = nodeData(child)?.file
                    childFile != null && VfsUtilCore.isAncestor(childFile, file, false)
                } ?: return
        }
        val path = TreePath(node.path)
        path.parentPath?.let { tree.expandPath(it) }
        selectPath(path)
    }

    fun expandSelection() {
        val path = tree.selectionPath ?: return
        val node = path.lastPathComponent as DefaultMutableTreeNode
        if (nodeData(node)?.isDirectory != true) return
        BrowseTree.loadChildren(project, model(), node)
        tree.expandPath(path)
    }

    fun collapseSelection(): CollapseOutcome {
        val path = tree.selectionPath ?: return CollapseOutcome.AT_TOP_LEVEL
        if (tree.isExpanded(path)) {
            tree.collapsePath(path)
            return CollapseOutcome.COLLAPSED
        }
        val parent = path.parentPath
        val parentNode = parent?.lastPathComponent as? DefaultMutableTreeNode
        if (parentNode?.parent == null) return CollapseOutcome.AT_TOP_LEVEL
        selectPath(parent)
        return CollapseOutcome.MOVED_TO_PARENT
    }

    fun selectedData(): NavigatorNodeData? = selectedNode()?.let { nodeData(it) }

    fun selectedFile(): VirtualFile? = selectedData()?.file

    fun selectedDirectory(): VirtualFile? = selectedData()
        ?.takeIf { it.isDirectory }
        ?.file
        ?.takeIf { it.isValid }

    fun isSelectionExpanded(): Boolean =
        tree.selectionPath?.let { tree.isExpanded(it) } == true

    fun collapseSelectionPath() {
        tree.selectionPath?.let { tree.collapsePath(it) }
    }

    fun removeSelectedNode() {
        val node = selectedNode() ?: return
        model().removeNodeFromParent(node)
    }

    private fun appendPruned(parent: DefaultMutableTreeNode, nodes: List<PrunedTreeNode<RankedFile>>) {
        val parentFile = (parent.userObject as NavigatorNodeData).file
        for (n in nodes) {
            val match = n.payload
            val file = match?.file
                ?: n.name.split('/').fold(parentFile) { acc, segment -> acc?.findChild(segment) }
            val child = DefaultMutableTreeNode(
                NavigatorNodeData(file, n.name, match == null, n.weight, match?.declarations.orEmpty()),
            )
            parent.add(child)
            if (match == null) appendPruned(child, n.children)
        }
    }

    private fun maybeShowContextMenu(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        onActivate()
        selectRowAt(e)
        onContextMenu(tree, e.point)
    }

    private fun selectRowAt(e: MouseEvent) {
        val row = tree.getRowForLocation(e.x, e.y)
        if (row >= 0) tree.setSelectionRow(row)
    }

    private fun selectPath(path: TreePath) {
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }

    private fun model(): DefaultTreeModel = tree.model as DefaultTreeModel

    private fun selectedNode(): DefaultMutableTreeNode? =
        tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode

    private fun nodeData(node: DefaultMutableTreeNode): NavigatorNodeData? =
        node.userObject as? NavigatorNodeData
}
