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
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.codeStyle.MinusculeMatcher
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.ui.ClientProperty
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.render.RenderingUtil
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

    private enum class Pane { LEFT, RIGHT }

    private class ZoomFrame(
        val dir: VirtualFile,
        val leftIndex: Int,
        val rightFile: VirtualFile?,
        val pane: Pane,
    )

    private val project = context.project
    private val scopes = ScopeResolver.availableScopes(context)
    private var scopeIndex = 0
    private val zoomStack = ArrayDeque<ZoomFrame>()

    private val searchField = SearchTextField(false)
    private val scopeLabel = JBLabel()
    private val footerLabel = JBLabel()
    private val tree = Tree()
    private val rootList = RootListPanel(project) { onUserListSelection() }
    private val panel = BorderLayoutPanel()

    private var popup: JBPopup? = null
    private var generation = 0
    private var activePane = Pane.RIGHT
    private var firstOpen = true
    private var pendingRestore: ZoomFrame? = null
    private var currentMatcher: MinusculeMatcher? = null
    private var filterBuckets: Map<BaseEntry, List<FileNameSearch.RankedFile>>? = null
    private var namedBuckets: Map<BaseEntry, List<FileNameSearch.RankedFile>>? = null
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
        setActivePane(Pane.RIGHT)
        refresh()
        created.showCenteredInCurrentWindow(project)
    }

    private fun buildPanel() {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = NavigatorTreeCellRenderer(project) { currentMatcher }
        tree.addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {
                val node = event.path.lastPathComponent as DefaultMutableTreeNode
                BrowseTree.loadChildren(project, tree.model as DefaultTreeModel, node)
            }

            override fun treeWillCollapse(event: TreeExpansionEvent) = Unit
        })
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                setActivePane(Pane.RIGHT)
                if (e.clickCount == 2) commitSelection()
            }
        })

        footerLabel.isVisible = false
        scopeLabel.border = JBUI.Borders.empty(4, 6, 0, 6)
        footerLabel.border = JBUI.Borders.empty(2, 6, 4, 6)

        val header = Box.createVerticalBox()
        header.add(scopeLabel)
        header.add(searchField)
        val splitter = OnePixelSplitter(false, "dev.sb.projecttreenavigator.Splitter", 0.25f)
        splitter.firstComponent = rootList.component
        splitter.secondComponent = JBScrollPane(tree)
        panel.addToTop(header)
        panel.addToCenter(splitter)
        panel.addToBottom(footerLabel)
        panel.preferredSize = Dimension(JBUI.scale(680), JBUI.scale(440))

        searchField.textEditor.columns = 30
    }

    private fun registerKeys() {
        registerKey("DOWN") { moveSelection(1) }
        registerKey("control J") { moveSelection(1) }
        registerKey("UP") { moveSelection(-1) }
        registerKey("control K") { moveSelection(-1) }
        registerKey("control L") { expandOrEnterRight() }
        registerKey("control H") { collapseOrExitLeft() }
        registerKey("RIGHT", isEnabled = { searchField.text.isEmpty() }) { expandOrEnterRight() }
        registerKey("LEFT", isEnabled = { searchField.text.isEmpty() }) { collapseOrExitLeft() }
        registerKey("ENTER") { commitSelection() }
        registerKey("TAB") { cycleScope(1) }
        registerKey("shift TAB") { cycleScope(-1) }
        registerKey("control ENTER", isEnabled = { activeSelectedDirectory() != null }) { zoomIn() }
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
        pendingRestore = null
        setActivePane(Pane.RIGHT)
        refresh()
    }

    private fun setActivePane(pane: Pane) {
        activePane = pane
        ClientProperty.put(tree, RenderingUtil.ALWAYS_PAINT_SELECTION_AS_FOCUSED, pane == Pane.RIGHT)
        rootList.setActive(pane == Pane.LEFT)
        tree.repaint()
    }

    private fun onUserListSelection() {
        setActivePane(Pane.LEFT)
        rebuildRight()
    }

    private fun zoomIn() {
        val dir = activeSelectedDirectory() ?: return
        zoomStack.addLast(ZoomFrame(dir, rootList.selectedIndex(), rightSelectedFile(), activePane))
        setActivePane(Pane.LEFT)
        refresh()
    }

    private fun zoomOut() {
        val frame = zoomStack.removeLastOrNull() ?: return
        pendingRestore = frame
        refresh()
    }

    private fun refresh() {
        val query = searchField.text.trim()
        val scope = scopes[scopeIndex]
        val resolved = ScopeResolver.resolve(scope, context)
        updateScopeLabel(resolved)
        if (query.isEmpty()) {
            currentMatcher = null
            filterBuckets = null
            if (scope is NavigatorScope.Named) {
                showNamedScopeBrowse(scope, resolved)
            } else {
                generation++
                namedBuckets = null
                showBrowse(resolved)
            }
            return
        }
        namedBuckets = null
        runFilterSearch(query, resolved)
    }

    private fun showBrowse(resolved: ScopeResolver.Resolved) {
        rootList.clearCounts()
        setFooter(null)
        tree.emptyText.text = "No files in scope"
        val entries = effectiveEntries(resolved)
        val previous = rootList.selectedEntry()
        rootList.setEntries(entries)
        val restore = pendingRestore
        pendingRestore = null
        val current = context.currentFile?.takeIf { it.isValid }
        when {
            restore != null -> {
                rootList.selectIndex(restore.leftIndex)
                rebuildRight()
                restoreRightSelection(restore)
                setActivePane(restore.pane)
            }

            firstOpen && current != null -> {
                val containing = rootList.entryContaining(current)
                if (containing == null) rootList.selectIndex(0) else rootList.selectEntry(containing)
                rebuildRight()
                when {
                    containing == null -> Unit
                    containing.file == current -> setActivePane(Pane.LEFT)
                    else -> locateInSubtree(current, containing.file)
                }
            }

            else -> {
                val kept = previous?.let { p -> entries.firstOrNull { it.file == p.file } }
                val containing = current?.let { rootList.entryContaining(it) }
                when {
                    kept != null -> rootList.selectEntry(kept)
                    containing != null -> rootList.selectEntry(containing)
                    else -> rootList.selectIndex(0)
                }
                rebuildRight()
            }
        }
        firstOpen = false
    }

    private fun restoreRightSelection(frame: ZoomFrame) {
        val entry = rootList.selectedEntry() ?: return
        val target = frame.rightFile ?: return
        if (!target.isValid || !VfsUtilCore.isAncestor(entry.file, target, true)) return
        locateInSubtree(target, entry.file)
    }

    private fun rebuildRight() {
        val entry = rootList.selectedEntry()
        val filter = filterBuckets
        val named = namedBuckets
        when {
            filter != null -> {
                setPrunedModel(entry?.let { filter[it] }.orEmpty(), entry?.file)
                TreeUtil.expandAll(tree)
                tree.emptyText.text = "Nothing found"
                selectBestMatch()
            }

            named != null -> {
                setPrunedModel(entry?.let { named[it] }.orEmpty(), entry?.file)
                TreeUtil.expand(tree, 1)
                tree.emptyText.text = "No files in scope"
                context.currentFile?.let { selectFileNode(it) }
            }

            else -> {
                val dir = entry?.file?.takeIf { entry.isDirectory && it.isValid }
                tree.model =
                    if (dir == null) DefaultTreeModel(DefaultMutableTreeNode(NavigatorNodeData(null, "", true)))
                    else BrowseTree.createSubtreeModel(project, dir)
                if (tree.rowCount > 0) tree.setSelectionRow(0)
            }
        }
    }

    private fun runFilterSearch(query: String, resolved: ScopeResolver.Resolved) {
        val activePopup = popup ?: return
        val gen = ++generation
        if (DumbService.getInstance(project).isDumb) {
            rootList.clearCounts()
            setFooter("Search available after indexing finishes")
            DumbService.getInstance(project).runWhenSmart {
                if (gen == generation && !activePopup.isDisposed) refresh()
            }
            return
        }
        val entries = effectiveEntries(resolved)
        val searchScope = zoomedSearchScope(resolved)
        ReadAction.nonBlocking<FileNameSearch.Result> {
            fileNameSearch.search(query, searchScope)
        }
            .coalesceBy(this)
            .expireWith(activePopup)
            .finishOnUiThread(ModalityState.stateForComponent(panel)) { result ->
                if (gen != generation) return@finishOnUiThread
                currentMatcher = FileNameSearch.nameMatcher(query)
                val buckets = EntryGrouping.group(result.files, entries) { it.file }
                filterBuckets = buckets
                rootList.setEntries(entries)
                rootList.setCounts(buckets.mapValues { it.value.size })
                val best = result.files.firstOrNull()
                val bestEntry = best?.let { rootList.entryContaining(it.file) }
                if (bestEntry != null) rootList.selectEntry(bestEntry)
                else if (rootList.selectedEntry() == null) rootList.selectIndex(0)
                rebuildRight()
                setFooter(
                    if (result.truncated) "Showing top ${FileNameSearch.DEFAULT_LIMIT} matches, keep typing to narrow"
                    else null,
                )
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun showNamedScopeBrowse(named: NavigatorScope.Named, resolved: ScopeResolver.Resolved) {
        val activePopup = popup ?: return
        val gen = ++generation
        pendingRestore = null
        ReadAction.nonBlocking<NamedScopeFiles.Result> {
            NamedScopeFiles.collect(project, named.namedScope)
        }
            .coalesceBy(this)
            .expireWith(activePopup)
            .finishOnUiThread(ModalityState.stateForComponent(panel)) { result ->
                if (gen != generation) return@finishOnUiThread
                val zoomed = zoomStack.lastOrNull()?.dir
                val files = result.files
                    .filter { zoomed == null || VfsUtilCore.isAncestor(zoomed, it, false) }
                    .map { FileNameSearch.RankedFile(it, 0) }
                val allEntries = effectiveEntries(resolved)
                val buckets = EntryGrouping.group(files, allEntries) { it.file }
                namedBuckets = buckets
                val entries = allEntries.filter { buckets.getValue(it).isNotEmpty() }
                rootList.clearCounts()
                rootList.setEntries(entries)
                val current = context.currentFile?.takeIf { it.isValid }
                val containing = current?.let { rootList.entryContaining(it) }
                if (containing == null) rootList.selectIndex(0) else rootList.selectEntry(containing)
                rebuildRight()
                setFooter(
                    if (result.truncated) "Scope truncated to ${NamedScopeFiles.DEFAULT_LIMIT} files"
                    else null,
                )
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun zoomedSearchScope(resolved: ScopeResolver.Resolved): GlobalSearchScope {
        val zoomed = zoomStack.lastOrNull()?.dir ?: return resolved.searchScope
        return resolved.searchScope.intersectWith(
            GlobalSearchScopesCore.directoryScope(project, zoomed, true),
        )
    }

    private fun effectiveEntries(resolved: ScopeResolver.Resolved): List<BaseEntry> =
        zoomStack.lastOrNull()?.let { ScopeResolver.entriesForBase(project, it.dir) } ?: resolved.entries

    private fun setPrunedModel(ranked: List<FileNameSearch.RankedFile>, base: VirtualFile?) {
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

    private fun selectBestMatch() {
        val hiddenRoot = (tree.model as DefaultTreeModel).root as DefaultMutableTreeNode
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

    private fun locateInSubtree(file: VirtualFile, base: VirtualFile) {
        val model = tree.model as DefaultTreeModel
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
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }

    private fun moveSelection(delta: Int) {
        if (activePane == Pane.LEFT) {
            rootList.move(delta)
            rebuildRight()
            return
        }
        val rowCount = tree.rowCount
        if (rowCount == 0) return
        val current = tree.selectionRows?.firstOrNull() ?: -1
        val next = (current + delta).coerceIn(0, rowCount - 1)
        tree.setSelectionRow(next)
        tree.scrollRowToVisible(next)
    }

    private fun expandOrEnterRight() {
        if (activePane == Pane.LEFT) {
            enterRightPane()
            return
        }
        val path = tree.selectionPath ?: return
        val node = path.lastPathComponent as DefaultMutableTreeNode
        if (nodeData(node)?.isDirectory != true) return
        BrowseTree.loadChildren(project, tree.model as DefaultTreeModel, node)
        tree.expandPath(path)
    }

    private fun enterRightPane() {
        val entry = rootList.selectedEntry() ?: return
        if (!entry.isDirectory) return
        setActivePane(Pane.RIGHT)
        if (tree.selectionPath == null && tree.rowCount > 0) tree.setSelectionRow(0)
    }

    private fun collapseOrExitLeft() {
        if (activePane == Pane.LEFT) return
        val path = tree.selectionPath
        if (path == null) {
            setActivePane(Pane.LEFT)
            return
        }
        if (tree.isExpanded(path)) {
            tree.collapsePath(path)
            return
        }
        val parent = path.parentPath
        val parentNode = parent?.lastPathComponent as? DefaultMutableTreeNode
        if (parentNode?.parent == null) {
            setActivePane(Pane.LEFT)
            return
        }
        tree.selectionPath = parent
        tree.scrollPathToVisible(parent)
    }

    private fun commitSelection() {
        if (activePane == Pane.LEFT) {
            val entry = rootList.selectedEntry() ?: return
            if (entry.isDirectory) {
                enterRightPane()
                return
            }
            if (!entry.file.isValid) {
                setFooter("File no longer exists")
                refresh()
                return
            }
            popup?.closeOk(null)
            FileEditorManager.getInstance(project).openFile(entry.file, true)
            return
        }
        val path = tree.selectionPath ?: return
        val node = path.lastPathComponent as DefaultMutableTreeNode
        val data = nodeData(node) ?: return
        if (data.isDirectory) {
            if (tree.isExpanded(path)) tree.collapsePath(path) else expandOrEnterRight()
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

    private fun activeSelectedDirectory(): VirtualFile? = when (activePane) {
        Pane.LEFT -> rootList.selectedEntry()
            ?.takeIf { it.isDirectory }
            ?.file
            ?.takeIf { it.isValid }

        Pane.RIGHT -> selectedNode()
            ?.let { nodeData(it) }
            ?.takeIf { it.isDirectory }
            ?.file
            ?.takeIf { it.isValid }
    }

    private fun rightSelectedFile(): VirtualFile? = selectedNode()?.let { nodeData(it) }?.file

    private fun selectedNode(): DefaultMutableTreeNode? =
        tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode

    private fun nodeData(node: DefaultMutableTreeNode): NavigatorNodeData? =
        node.userObject as? NavigatorNodeData

    private fun updateScopeLabel(resolved: ScopeResolver.Resolved) {
        val chips = scopes.mapIndexed { i, s ->
            val label = StringUtil.escapeXmlEntities(s.label)
            if (i == scopeIndex) "<b>[$label]</b>" else "[$label]"
        }
        val hint = if (resolved.fellBack) "  (no current file, showing project)" else ""
        val zoomed = zoomStack.lastOrNull()?.dir
        val zoomText = zoomed?.let {
            val base = project.basePath.orEmpty()
            val shown = if (base.isNotEmpty()) it.path.removePrefix(base).trimStart('/') else it.path
            "  zoomed: ${StringUtil.escapeXmlEntities(shown)}/"
        }.orEmpty()
        scopeLabel.text = "<html>${chips.joinToString(" ")}$hint$zoomText</html>"
    }

    private fun setFooter(text: String?) {
        footerLabel.text = text.orEmpty()
        footerLabel.isVisible = !text.isNullOrEmpty()
    }
}
