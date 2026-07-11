package dev.sb.projecttreenavigator

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.codeStyle.MinusculeMatcher
import com.intellij.ui.ClientProperty
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.render.RenderingUtil
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

class TreePanel(
    private val project: Project,
    matcherProvider: () -> MinusculeMatcher?,
    private val onActivate: () -> Unit,
    private val onCommit: () -> Unit,
) {

    enum class CollapseOutcome { COLLAPSED, MOVED_TO_PARENT, AT_TOP_LEVEL }

    private val tree = Tree()

    val component: JComponent = JBScrollPane(tree)

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = NavigatorTreeCellRenderer(project, matcherProvider)
        tree.addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {
                val node = event.path.lastPathComponent as DefaultMutableTreeNode
                BrowseTree.loadChildren(project, model(), node)
            }

            override fun treeWillCollapse(event: TreeExpansionEvent) = Unit
        })
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                onActivate()
                if (e.clickCount == 2) onCommit()
            }
        })
    }

    fun setActive(active: Boolean) {
        ClientProperty.put(tree, RenderingUtil.ALWAYS_PAINT_SELECTION_AS_FOCUSED, active)
        tree.repaint()
    }

    fun setEmptyText(text: String) {
        tree.emptyText.text = text
    }

    fun showSubtree(base: VirtualFile) {
        tree.model = BrowseTree.createSubtreeModel(project, base)
        if (tree.rowCount > 0) tree.setSelectionRow(0)
    }

    fun showEmpty() {
        tree.model = DefaultTreeModel(DefaultMutableTreeNode(NavigatorNodeData(null, "", true)))
    }

    fun showPruned(ranked: List<FileNameSearch.RankedFile>, base: VirtualFile?) {
        val hiddenRoot = DefaultMutableTreeNode(NavigatorNodeData(base, base?.name.orEmpty(), true))
        if (base != null) {
            val prunedMatches = ranked.mapNotNull { m ->
                val relative = VfsUtilCore.getRelativePath(m.file, base) ?: return@mapNotNull null
                if (relative.isEmpty()) return@mapNotNull null
                PrunedMatch(relative.split('/'), m.file, m.weight)
            }
            appendPruned(hiddenRoot, PrunedTreeBuilder.build(prunedMatches))
        }
        tree.model = DefaultTreeModel(hiddenRoot)
    }

    fun expandAll() = TreeUtil.expandAll(tree)

    fun expandTopLevel() = TreeUtil.expand(tree, 1)

    fun move(delta: Int) {
        val rowCount = tree.rowCount
        if (rowCount == 0) return
        val current = tree.selectionRows?.firstOrNull() ?: -1
        val next = (current + delta).coerceIn(0, rowCount - 1)
        tree.setSelectionRow(next)
        tree.scrollRowToVisible(next)
    }

    fun selectFirstRowIfNone() {
        if (tree.selectionPath == null && tree.rowCount > 0) tree.setSelectionRow(0)
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
        val model = model()
        var node = model.root as DefaultMutableTreeNode
        val relative = VfsUtilCore.getRelativePath(file, base) ?: return
        if (relative.isEmpty()) return
        for (segment in relative.split('/')) {
            BrowseTree.loadChildren(project, model, node)
            node = node.children().asSequence()
                .filterIsInstance<DefaultMutableTreeNode>()
                .firstOrNull { nodeData(it)?.name == segment } ?: return
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

    private fun appendPruned(parent: DefaultMutableTreeNode, nodes: List<PrunedTreeNode<VirtualFile>>) {
        val parentFile = (parent.userObject as NavigatorNodeData).file
        for (n in nodes) {
            val file = n.payload ?: parentFile?.findChild(n.name)
            val child = DefaultMutableTreeNode(
                NavigatorNodeData(file, n.name, n.payload == null, n.weight),
            )
            parent.add(child)
            if (n.payload == null) appendPruned(child, n.children)
        }
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
