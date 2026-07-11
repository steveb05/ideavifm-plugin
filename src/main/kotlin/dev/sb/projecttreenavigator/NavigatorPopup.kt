package dev.sb.projecttreenavigator

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.codeStyle.MinusculeMatcher
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

class NavigatorPopup(private val context: NavigatorContext) {

    private val project = context.project
    private val scopes = ScopeResolver.availableScopes(context)
    private var scopeIndex = 0
    private val zoomStack = ArrayDeque<VirtualFile>()

    private val searchField = SearchTextField(false)
    private val scopeLabel = JBLabel()
    private val footerLabel = JBLabel()
    private val tree = Tree()
    private val panel = BorderLayoutPanel()

    private var popup: JBPopup? = null
    private var generation = 0
    private var currentMatcher: MinusculeMatcher? = null

    fun show() {
        buildPanel()
        val created = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, searchField.textEditor)
            .setTitle("Project Tree Navigator")
            .setRequestFocus(true)
            .setFocusable(true)
            .setResizable(true)
            .setMovable(true)
            .setCancelOnClickOutside(true)
            .setDimensionServiceKey(project, "dev.sb.projecttreenavigator.Popup", true)
            .createPopup()
        popup = created
        registerKeys()
        refresh()
        created.showCenteredInCurrentWindow(project)
    }

    private fun buildPanel() {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = NavigatorTreeCellRenderer { currentMatcher }
        tree.addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {
                val node = event.path.lastPathComponent as DefaultMutableTreeNode
                BrowseTree.loadChildren(project, tree.model as DefaultTreeModel, node)
            }

            override fun treeWillCollapse(event: TreeExpansionEvent) = Unit
        })
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) commitSelection()
            }
        })

        footerLabel.isVisible = false
        scopeLabel.border = JBUI.Borders.empty(4, 6, 0, 6)
        footerLabel.border = JBUI.Borders.empty(2, 6, 4, 6)

        val header = Box.createVerticalBox()
        header.add(scopeLabel)
        header.add(searchField)
        panel.addToTop(header)
        panel.addToCenter(JBScrollPane(tree))
        panel.addToBottom(footerLabel)
        panel.preferredSize = Dimension(JBUI.scale(520), JBUI.scale(440))

        searchField.textEditor.columns = 30
    }

    private fun registerKeys() {
        registerKey("DOWN") { moveSelection(1) }
        registerKey("control J") { moveSelection(1) }
        registerKey("UP") { moveSelection(-1) }
        registerKey("control K") { moveSelection(-1) }
        registerKey("control L") { expandSelection() }
        registerKey("control H") { collapseSelection() }
        registerKey("RIGHT", isEnabled = { searchField.text.isEmpty() }) { expandSelection() }
        registerKey("LEFT", isEnabled = { searchField.text.isEmpty() }) { collapseSelection() }
        registerKey("ENTER") { commitSelection() }
    }

    private fun registerKey(
        shortcut: String,
        isEnabled: () -> Boolean = { true },
        perform: () -> Unit,
    ) {
        val activePopup = popup ?: return
        val action = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) = perform()
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = isEnabled()
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }
        action.registerCustomShortcutSet(CustomShortcutSet.fromString(shortcut), panel, activePopup)
    }

    private fun refresh() {
        val resolved = ScopeResolver.resolve(scopes[scopeIndex], context)
        updateScopeLabel(resolved)
        currentMatcher = null
        showBrowseTree(resolved)
    }

    private fun showBrowseTree(resolved: ScopeResolver.Resolved) {
        val roots = effectiveRoots(resolved)
        tree.model = BrowseTree.createModel(project, roots)
        tree.emptyText.text = "No files in scope"
        setFooter(null)
        val current = context.currentFile
        if (current != null && current.isValid) locateFile(current, roots)
        if (tree.selectionPath == null && tree.rowCount > 0) tree.setSelectionRow(0)
    }

    private fun locateFile(file: VirtualFile, roots: List<VirtualFile>) {
        val root = roots.firstOrNull { VfsUtilCore.isAncestor(it, file, false) } ?: return
        val model = tree.model as DefaultTreeModel
        val hiddenRoot = model.root as DefaultMutableTreeNode
        var node = hiddenRoot.children().asSequence()
            .filterIsInstance<DefaultMutableTreeNode>()
            .firstOrNull { nodeData(it)?.file == root } ?: return
        val relative = VfsUtilCore.getRelativePath(file, root) ?: return
        if (relative.isNotEmpty()) {
            for (segment in relative.split('/')) {
                BrowseTree.loadChildren(project, model, node)
                node = node.children().asSequence()
                    .filterIsInstance<DefaultMutableTreeNode>()
                    .firstOrNull { nodeData(it)?.name == segment } ?: return
            }
        }
        val path = TreePath(node.path)
        path.parentPath?.let { tree.expandPath(it) }
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }

    private fun moveSelection(delta: Int) {
        val rowCount = tree.rowCount
        if (rowCount == 0) return
        val current = tree.selectionRows?.firstOrNull() ?: -1
        val next = (current + delta).coerceIn(0, rowCount - 1)
        tree.setSelectionRow(next)
        tree.scrollRowToVisible(next)
    }

    private fun expandSelection() {
        val path = tree.selectionPath ?: return
        val node = path.lastPathComponent as DefaultMutableTreeNode
        if (nodeData(node)?.isDirectory != true) return
        BrowseTree.loadChildren(project, tree.model as DefaultTreeModel, node)
        tree.expandPath(path)
    }

    private fun collapseSelection() {
        val path = tree.selectionPath ?: return
        if (tree.isExpanded(path)) {
            tree.collapsePath(path)
            return
        }
        val parent = path.parentPath ?: return
        val parentNode = parent.lastPathComponent as DefaultMutableTreeNode
        if (parentNode.parent == null) return
        tree.selectionPath = parent
        tree.scrollPathToVisible(parent)
    }

    private fun commitSelection() {
        val path = tree.selectionPath ?: return
        val node = path.lastPathComponent as DefaultMutableTreeNode
        val data = nodeData(node) ?: return
        if (data.isDirectory) {
            if (tree.isExpanded(path)) tree.collapsePath(path) else expandSelection()
            return
        }
        val file = data.file ?: return
        if (!file.isValid) {
            setFooter("File no longer exists")
            (tree.model as DefaultTreeModel).removeNodeFromParent(node)
            return
        }
        popup?.closeOk(null)
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    private fun selectedNode(): DefaultMutableTreeNode? =
        tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode

    private fun nodeData(node: DefaultMutableTreeNode): NavigatorNodeData? =
        node.userObject as? NavigatorNodeData

    private fun effectiveRoots(resolved: ScopeResolver.Resolved): List<VirtualFile> =
        zoomStack.lastOrNull()?.let { listOf(it) } ?: resolved.roots

    private fun updateScopeLabel(resolved: ScopeResolver.Resolved) {
        val chips = scopes.mapIndexed { i, s ->
            if (i == scopeIndex) "<b>[${s.label}]</b>" else "[${s.label}]"
        }
        scopeLabel.text = "<html>${chips.joinToString(" ")}</html>"
    }

    private fun setFooter(text: String?) {
        footerLabel.text = text.orEmpty()
        footerLabel.isVisible = !text.isNullOrEmpty()
    }
}
