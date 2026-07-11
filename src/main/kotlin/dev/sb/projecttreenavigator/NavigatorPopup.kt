package dev.sb.projecttreenavigator

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.codeStyle.MinusculeMatcher
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import com.intellij.util.ui.tree.TreeUtil
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.event.DocumentEvent
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
    private val fileNameSearch = FileNameSearch(project)
    private var alarm: Alarm? = null

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
        alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, created)
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = scheduleRefresh()
        })
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
        registerKey("TAB") { cycleScope(1) }
        registerKey("shift TAB") { cycleScope(-1) }
        registerKey("control ENTER", isEnabled = { selectedNode()?.let { nodeData(it)?.isDirectory == true && nodeData(it)?.file != null } == true }) { zoomIn() }
        registerKey("BACK_SPACE", isEnabled = { searchField.text.isEmpty() && zoomStack.isNotEmpty() }) { zoomOut() }
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

    private fun scheduleRefresh() {
        val activeAlarm = alarm ?: return
        activeAlarm.cancelAllRequests()
        activeAlarm.addRequest({ refresh() }, 50)
    }

    private fun cycleScope(delta: Int) {
        scopeIndex = ((scopeIndex + delta) % scopes.size + scopes.size) % scopes.size
        zoomStack.clear()
        refresh()
    }

    private fun zoomIn() {
        val node = selectedNode() ?: return
        val data = nodeData(node) ?: return
        val dir = data.file ?: return
        if (!data.isDirectory || !dir.isValid) return
        zoomStack.addLast(dir)
        refresh()
    }

    private fun zoomOut() {
        if (zoomStack.isEmpty()) return
        zoomStack.removeLast()
        refresh()
    }

    private fun refresh() {
        val query = searchField.text.trim()
        val scope = scopes[scopeIndex]
        val resolved = ScopeResolver.resolve(scope, context)
        updateScopeLabel(resolved)
        if (query.isEmpty()) {
            currentMatcher = null
            if (scope is NavigatorScope.Named) {
                showNamedScopeBrowse(scope, resolved)
            } else {
                generation++
                showBrowseTree(resolved)
            }
            return
        }
        runFilterSearch(query, resolved)
    }

    private fun runFilterSearch(query: String, resolved: ScopeResolver.Resolved) {
        val activePopup = popup ?: return
        val gen = ++generation
        if (DumbService.getInstance(project).isDumb) {
            setFooter("Search available after indexing finishes")
            DumbService.getInstance(project).runWhenSmart {
                if (gen == generation && !activePopup.isDisposed) refresh()
            }
            return
        }
        val searchScope = zoomedSearchScope(resolved)
        ReadAction.nonBlocking<FileNameSearch.Result> {
            fileNameSearch.search(query, searchScope)
        }
            .coalesceBy(this)
            .expireWith(activePopup)
            .finishOnUiThread(ModalityState.stateForComponent(panel)) { result ->
                if (gen != generation) return@finishOnUiThread
                currentMatcher = FileNameSearch.nameMatcher(query)
                val footer =
                    if (result.truncated) "Showing top ${FileNameSearch.DEFAULT_LIMIT} matches, keep typing to narrow"
                    else null
                setPrunedModel(result.files, effectiveRoots(resolved), expandAll = true, footer = footer)
                tree.emptyText.text = "Nothing found"
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun zoomedSearchScope(resolved: ScopeResolver.Resolved): com.intellij.psi.search.GlobalSearchScope {
        val zoomed = zoomStack.lastOrNull() ?: return resolved.searchScope
        return resolved.searchScope.intersectWith(
            com.intellij.psi.search.GlobalSearchScopesCore.directoryScope(project, zoomed, true),
        )
    }

    private fun setPrunedModel(
        ranked: List<FileNameSearch.RankedFile>,
        roots: List<VirtualFile>,
        expandAll: Boolean,
        footer: String?,
    ) {
        val byRoot = LinkedHashMap<VirtualFile, MutableList<FileNameSearch.RankedFile>>()
        for (item in ranked) {
            val root = roots.firstOrNull { VfsUtilCore.isAncestor(it, item.file, false) } ?: continue
            byRoot.getOrPut(root) { mutableListOf() }.add(item)
        }
        val hiddenRoot = DefaultMutableTreeNode(NavigatorNodeData(null, "", true))
        for ((root, matches) in byRoot) {
            val prunedMatches = matches.mapNotNull { m ->
                val relative = VfsUtilCore.getRelativePath(m.file, root) ?: return@mapNotNull null
                if (relative.isEmpty()) return@mapNotNull null
                PrunedMatch(relative.split('/'), m.file, m.weight)
            }
            val rootNode = DefaultMutableTreeNode(NavigatorNodeData(root, root.name, true))
            appendPruned(rootNode, PrunedTreeBuilder.build(prunedMatches))
            hiddenRoot.add(rootNode)
        }
        tree.model = DefaultTreeModel(hiddenRoot)
        if (expandAll) TreeUtil.expandAll(tree)
        selectBestMatch(hiddenRoot)
        setFooter(footer)
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

    private fun selectBestMatch(hiddenRoot: DefaultMutableTreeNode) {
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
        val path = TreePath(target.path)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
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

    private fun showNamedScopeBrowse(named: NavigatorScope.Named, resolved: ScopeResolver.Resolved) {
        val activePopup = popup ?: return
        val gen = ++generation
        ReadAction.nonBlocking<NamedScopeFiles.Result> {
            NamedScopeFiles.collect(project, named.namedScope)
        }
            .coalesceBy(this)
            .expireWith(activePopup)
            .finishOnUiThread(ModalityState.stateForComponent(panel)) { result ->
                if (gen != generation) return@finishOnUiThread
                val zoomed = zoomStack.lastOrNull()
                val files = result.files
                    .filter { zoomed == null || VfsUtilCore.isAncestor(zoomed, it, false) }
                    .map { FileNameSearch.RankedFile(it, 0) }
                val footer =
                    if (result.truncated) "Scope truncated to ${NamedScopeFiles.DEFAULT_LIMIT} files"
                    else null
                setPrunedModel(files, effectiveRoots(resolved), expandAll = false, footer = footer)
                TreeUtil.expand(tree, 1)
                tree.emptyText.text = "No files in scope"
                val current = context.currentFile
                if (current != null) selectFileNode(current)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun selectFileNode(file: VirtualFile) {
        val hiddenRoot = (tree.model as DefaultTreeModel).root as DefaultMutableTreeNode
        val enumeration = hiddenRoot.depthFirstEnumeration()
        while (enumeration.hasMoreElements()) {
            val node = enumeration.nextElement() as DefaultMutableTreeNode
            if (nodeData(node)?.file == file) {
                val path = TreePath(node.path)
                tree.selectionPath = path
                tree.scrollPathToVisible(path)
                return
            }
        }
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
        val hint = if (resolved.fellBack) "  (no current file, showing project)" else ""
        val zoomed = zoomStack.lastOrNull()
        val zoomText = zoomed?.let {
            val base = project.basePath.orEmpty()
            val shown = if (base.isNotEmpty()) it.path.removePrefix(base).trimStart('/') else it.path
            "  zoomed: $shown/"
        }.orEmpty()
        scopeLabel.text = "<html>${chips.joinToString(" ")}$hint$zoomText</html>"
    }

    private fun setFooter(text: String?) {
        footerLabel.text = text.orEmpty()
        footerLabel.isVisible = !text.isNullOrEmpty()
    }
}
