package me.steveb05.projecttreenavigator

import com.intellij.ide.CopyPasteDelegator
import com.intellij.ide.IdeView
import com.intellij.ide.PsiCopyPasteManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import java.awt.Dimension
import java.awt.Point
import javax.swing.Box
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

class NavigatorPopup(private val context: NavigatorContext) {

    private enum class Pane { LEFT, RIGHT, PREVIEW }

    private class ZoomFrame(
        val dir: VirtualFile,
        val leftIndex: Int,
        val rightFile: VirtualFile?,
        val pane: Pane,
    )

    private val project = context.project
    private val searchBase = project.guessProjectDir()
    private val scopes = ScopeResolver.availableScopes(context)
    private var scopeIndex = 0
    private val zoomStack = ArrayDeque<ZoomFrame>()

    private val searchField = SearchTextField(false)
    private val scopeLabel = JBLabel()
    private val footerLabel = JBLabel()
    private val treePanel = TreePanel(
        project,
        { currentHighlight },
        onActivate = { setActivePane(Pane.RIGHT) },
        onCommit = { commitSelection() },
        onHover = { previewHover(it) },
        onSelectionChanged = { refreshPreview() },
        onContextMenu = { component, point -> showContextMenu(component, point) },
    )
    private val rootList = RootListPanel(
        project,
        onUserSelection = { onUserListSelection() },
        onHover = { previewHover(it) },
        onContextMenu = { component, point -> showContextMenu(component, point) },
        highlightProvider = { currentHighlight },
    )
    private val previewPanel = PreviewPanel(project)
    private val panel = BorderLayoutPanel()
    private val copyPaste = object : CopyPasteDelegator(project, panel) {
        override fun getSelectedElements(context: DataContext): Array<PsiElement> = targetElements()
    }

    private var popup: JBPopup? = null
    private var generation = 0
    private var activePane = Pane.RIGHT
    private var firstOpen = true
    private var pendingRestore: ZoomFrame? = null
    private var currentHighlight: QueryHighlight? = null
    private var autoExpand = false
    private var restoredEntry: VirtualFile? = null
    private var restoredFile: VirtualFile? = null
    private var searchWasActive = false
    private var reopen = false
    private var filterMatches: List<FileNameSearch.RankedFile>? = null
    private var namedMatches: List<FileNameSearch.RankedFile>? = null
    private var changedOnly = false
    private var footerNote: String? = null
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
            .setCancelOnWindowDeactivation(false)
            .setDimensionServiceKey(project, "me.steveb05.projecttreenavigator.Popup", true)
            .createPopup()
        popup = created
        previewPanel.attach(created)
        alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, created)
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = scheduleRefresh()
        })
        registerKeys()
        Disposer.register(created) { saveView() }
        setActivePane(Pane.RIGHT)
        if (NavigatorSettings.getInstance().restoreLastView) restoreView()
        refresh()
        created.showCenteredInCurrentWindow(project)
    }

    private fun restoreView() {
        val saved = NavigatorViewState.getInstance(project)
        scopeIndex = scopes.indexOfFirst { it.label == saved.scope() }.takeIf { it >= 0 } ?: scopeIndex
        saved.zoom().forEach { zoomStack.addLast(ZoomFrame(it, 0, null, Pane.RIGHT)) }
        restoredEntry = saved.entry()
        restoredFile = saved.file()
    }

    private fun saveView() {
        NavigatorViewState.getInstance(project).save(
            scope = scopes[scopeIndex].label,
            zoom = zoomStack.map { it.dir }.filter { it.isValid },
            entry = rootList.selectedEntry()?.file,
            file = treePanel.selectedFile(),
        )
    }

    private fun buildPanel() {
        footerLabel.isVisible = false
        scopeLabel.border = JBUI.Borders.empty(4, 6, 0, 6)
        footerLabel.border = JBUI.Borders.empty(2, 6, 4, 6)

        val header = Box.createVerticalBox()
        header.add(scopeLabel)
        header.add(searchField)
        val splitter = OnePixelSplitter(false, "me.steveb05.projecttreenavigator.Splitter", 0.25f)
        splitter.firstComponent = rootList.component
        val rightSplit = OnePixelSplitter(false, "me.steveb05.projecttreenavigator.PreviewSplitter", 0.55f)
        rightSplit.firstComponent = treePanel.component
        rightSplit.secondComponent = previewPanel.component
        splitter.secondComponent = rightSplit
        applyPreviewVisibility()
        panel.addToTop(header)
        panel.addToCenter(splitter)
        panel.addToBottom(footerLabel)
        val width = if (NavigatorSettings.getInstance().showPreview) 980 else 680
        panel.preferredSize = Dimension(JBUI.scale(width), JBUI.scale(440))

        searchField.textEditor.columns = 30
    }

    private fun registerKeys() {
        val activePopup = popup ?: return
        val commands = NavigatorCommands(panel, activePopup)
        commands.bind(NavigatorCommand.LEFT_DOWN) { moveLeft(1) }
        commands.bind(NavigatorCommand.LEFT_UP) { moveLeft(-1) }
        commands.bind(NavigatorCommand.RIGHT_DOWN) { moveRight(1) }
        commands.bind(NavigatorCommand.RIGHT_UP) { moveRight(-1) }
        commands.bindFixed("DOWN") { moveActive(1) }
        commands.bindFixed("UP") { moveActive(-1) }
        commands.bind(NavigatorCommand.PANE_RIGHT) { expandOrEnterRight() }
        commands.bind(NavigatorCommand.PANE_LEFT) { collapseOrExitLeft() }
        commands.bindFixed("RIGHT", isEnabled = { searchField.text.isEmpty() }) { expandOrEnterRight() }
        commands.bindFixed("LEFT", isEnabled = { searchField.text.isEmpty() }) { collapseOrExitLeft() }
        commands.bindFixed("ENTER") { commitSelection() }
        commands.bindFixed("TAB") { cycleScope(1) }
        commands.bindFixed("shift TAB") { cycleScope(-1) }
        commands.bind(NavigatorCommand.ZOOM_IN, isEnabled = { activeSelectedDirectory() != null }) { zoomIn() }
        commands.bind(
            NavigatorCommand.ZOOM_OUT,
            isEnabled = { searchField.text.isEmpty() && zoomStack.isNotEmpty() },
        ) { zoomOut() }
        commands.bind(NavigatorCommand.TOGGLE_PREVIEW) { togglePreview() }
        commands.bind(NavigatorCommand.TOGGLE_DOT_FILES) { toggleDotFiles() }
        commands.bind(NavigatorCommand.TOGGLE_CHANGED) { toggleChangedOnly() }
        commands.bind(NavigatorCommand.NEW_ELEMENT) { showNewElement() }
        commands.bind(NavigatorCommand.FOCUS_SEARCH) { focusSearch() }
        commands.bind(NavigatorCommand.TOGGLE_MARK, isEnabled = { activePane == Pane.RIGHT }) {
            toggleMark()
        }
        commands.bindFixed(
            "SPACE",
            isEnabled = { searchField.text.isEmpty() && activePane == Pane.RIGHT },
        ) { toggleMark() }
        commands.bind(
            NavigatorCommand.RENAME,
            isEnabled = { targetFiles().size == 1 },
        ) { runFileAction(NavigatorFileActions.RENAME) }
        commands.bind(
            NavigatorCommand.MOVE,
            isEnabled = { targetFiles().isNotEmpty() },
        ) { runFileAction(NavigatorFileActions.MOVE) }
        commands.bind(
            NavigatorCommand.DELETE,
            isEnabled = { searchField.text.isEmpty() && targetFiles().isNotEmpty() },
        ) { runFileAction(NavigatorFileActions.DELETE) }
        commands.bind(
            NavigatorCommand.COPY,
            isEnabled = { searchField.text.isEmpty() && targetFiles().isNotEmpty() },
        ) { runFileAction(NavigatorFileActions.COPY) }
        commands.bind(
            NavigatorCommand.CUT,
            isEnabled = { searchField.text.isEmpty() && targetFiles().isNotEmpty() },
        ) { runFileAction(NavigatorFileActions.CUT) }
        commands.bind(
            NavigatorCommand.PASTE,
            isEnabled = { searchField.text.isEmpty() && clipboardHasFiles() && createTargetDirectory() != null },
        ) { runFileAction(NavigatorFileActions.PASTE) }
        commands.bind(NavigatorCommand.PREVIEW_LINE_DOWN, isEnabled = { previewVisible() }) { previewPanel.scrollLines(1) }
        commands.bind(NavigatorCommand.PREVIEW_LINE_UP, isEnabled = { previewVisible() }) { previewPanel.scrollLines(-1) }
        commands.bind(NavigatorCommand.PREVIEW_HALF_DOWN, isEnabled = { previewVisible() }) { previewPanel.scrollHalfPage(1) }
        commands.bind(NavigatorCommand.PREVIEW_HALF_UP, isEnabled = { previewVisible() }) { previewPanel.scrollHalfPage(-1) }
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
        reopen = true
        setActivePane(Pane.RIGHT)
        refresh()
    }

    private fun setActivePane(pane: Pane) {
        activePane = pane
        treePanel.setActive(pane == Pane.RIGHT)
        rootList.setActive(pane == Pane.LEFT)
        previewPanel.setActive(pane == Pane.PREVIEW)
        refreshPreview()
    }

    private fun onUserListSelection() {
        setActivePane(Pane.LEFT)
        rebuildRight()
    }

    private fun applyPreviewVisibility() {
        previewPanel.component.isVisible = NavigatorSettings.getInstance().showPreview
        panel.revalidate()
        panel.repaint()
    }

    private fun refreshPreview() {
        if (!NavigatorSettings.getInstance().showPreview) return
        val file = when (activePane) {
            Pane.LEFT -> rootList.selectedEntry()?.file
            Pane.RIGHT -> treePanel.selectedFile()
            Pane.PREVIEW -> treePanel.selectedFile()
        }
        previewPanel.setTarget(file)
    }

    private fun previewHover(file: VirtualFile) {
        if (!NavigatorSettings.getInstance().showPreview) return
        previewPanel.setTarget(file)
    }

    private fun togglePreview() {
        val settings = NavigatorSettings.getInstance()
        settings.showPreview = !settings.showPreview
        if (!settings.showPreview && activePane == Pane.PREVIEW) setActivePane(Pane.RIGHT)
        applyPreviewVisibility()
        refreshPreview()
    }

    private fun previewVisible(): Boolean = NavigatorSettings.getInstance().showPreview

    private fun zoomIn() {
        val dir = activeSelectedDirectory() ?: return
        zoomStack.addLast(ZoomFrame(dir, rootList.selectedIndex(), treePanel.selectedFile(), activePane))
        reopen = true
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
        autoExpand = query.isEmpty()
        if (query.isEmpty() && searchWasActive) reopen = true
        searchWasActive = query.isNotEmpty()
        updateScopeLabel(resolved)
        if (query.isEmpty()) {
            currentHighlight = null
            filterMatches = null
            if (scope is NavigatorScope.Named) {
                showNamedScopeBrowse(scope, resolved)
            } else if (changedOnly) {
                showChangedBrowse(resolved)
            } else {
                generation++
                namedMatches = null
                showBrowse(resolved)
            }
            return
        }
        namedMatches = null
        runFilterSearch(query, resolved)
    }

    private fun showBrowse(resolved: ScopeResolver.Resolved) {
        rootList.clearCounts()
        updateFooter(null)
        treePanel.setEmptyText("No files in scope")
        val entries = effectiveEntries(resolved)
        val previous = rootList.selectedEntry()
        val previousFile = treePanel.selectedFile()
        rootList.setEntries(entries)
        val restore = pendingRestore
        pendingRestore = null
        val current = context.currentFile?.takeIf { it.isValid }
        val opening = firstOpen || reopen
        reopen = false
        when {
            restore != null -> {
                rootList.selectIndex(restore.leftIndex)
                rebuildRight()
                walkOpenTo(restore.rightFile, current)
                setActivePane(restore.pane)
            }

            opening -> positionOnOpen(entries, current, previous, previousFile)

            else -> {
                val kept = previous?.let { p -> entries.firstOrNull { it.file == p.file } }
                val containing = current?.let { rootList.entryContaining(it) }
                when {
                    kept != null -> rootList.selectEntry(kept)
                    containing != null -> rootList.selectEntry(containing)
                    else -> rootList.selectIndex(0)
                }
                rebuildRight()
                walkOpenTo(previousFile, current)
            }
        }
        firstOpen = false
    }

    /**
     * Opening the popup, switching scope, zooming and clearing a search all have to leave the same view: the
     * tree walked open down to the file being edited. Without that file in view it falls back to the view
     * that was remembered, then to whatever was already selected.
     */
    private fun positionOnOpen(
        entries: List<BaseEntry>,
        current: VirtualFile?,
        previous: BaseEntry?,
        previousFile: VirtualFile?,
    ) {
        val remembered = restoredEntry
        val rememberedFile = restoredFile
        restoredEntry = null
        restoredFile = null

        val target = OpenTarget.choose(entries, current, remembered, previous)
        if (target == null) {
            rootList.selectIndex(0)
            rebuildRight()
            return
        }
        rootList.selectEntry(target)
        rebuildRight()
        walkOpenTo(current, rememberedFile, previousFile)
    }

    /** Walks the tree open to the first of [candidates] that lives under the selected entry. */
    private fun walkOpenTo(vararg candidates: VirtualFile?) {
        val base = rootList.selectedEntry()?.file ?: return
        val land = OpenTarget.landing(base, *candidates) ?: return
        if (land == base) setActivePane(Pane.LEFT) else treePanel.locate(land, base)
    }

    private fun rebuildRight() {
        val entry = rootList.selectedEntry()
        val filter = filterMatches
        val named = namedMatches
        when {
            filter != null -> {
                treePanel.showPruned(bucketFor(filter, entry), entry?.file)
                treePanel.expandAll()
                treePanel.setEmptyText("Nothing found")
                treePanel.selectBestMatch()
            }

            named != null -> {
                treePanel.showPruned(bucketFor(named, entry), entry?.file)
                treePanel.expandTopLevel()
                treePanel.setEmptyText("No files in scope")
                context.currentFile?.let { treePanel.selectFile(it) }
            }

            else -> {
                val dir = entry?.file?.takeIf { entry.isDirectory && it.isValid }
                if (dir == null) {
                    treePanel.showEmpty()
                } else {
                    treePanel.showSubtree(dir)
                    if (autoExpand) treePanel.expandToFirstFileLevel()
                }
            }
        }
        refreshPreview()
    }

    private fun bucketFor(
        matches: List<FileNameSearch.RankedFile>,
        entry: BaseEntry?,
    ): List<FileNameSearch.RankedFile> =
        entry?.let { SubtreeMatches.matchesUnder(matches, it) { m -> m.file } }.orEmpty()

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
            val changed = if (changedOnly) changedFileSet() else null
            val raw = fileNameSearch.search(query, searchScope)
            FileNameSearch.Result(
                raw.files.filter {
                    !BrowseTree.hiddenByDotRule(project, it.file) && (changed == null || it.file in changed)
                },
                raw.truncated,
            )
        }
            .coalesceBy(this)
            .expireWith(activePopup)
            .finishOnUiThread(ModalityState.stateForComponent(panel)) { result ->
                if (gen != generation) return@finishOnUiThread
                currentHighlight = QueryHighlight(query, searchBase)
                filterMatches = result.files
                rootList.setEntries(entries)
                rootList.setCounts(SubtreeMatches.countsFor(result.files, entries) { it.file })
                val best = result.files.firstOrNull()
                val bestEntry = best?.let { rootList.entryContaining(it.file) }
                if (bestEntry != null) rootList.selectEntry(bestEntry)
                else if (rootList.selectedEntry() == null) rootList.selectIndex(0)
                rebuildRight()
                updateFooter(
                    if (result.truncated) "Showing top ${FileNameSearch.DEFAULT_LIMIT} matches, keep typing to narrow"
                    else null,
                )
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun showChangedBrowse(resolved: ScopeResolver.Resolved) {
        val activePopup = popup ?: return
        val gen = ++generation
        pendingRestore = null
        namedMatches = null
        val entries = effectiveEntries(resolved)
        val searchScope = zoomedSearchScope(resolved)
        ReadAction.nonBlocking<List<FileNameSearch.RankedFile>> {
            changedFileSet()
                .filter { searchScope.contains(it) && !BrowseTree.hiddenByDotRule(project, it) }
                .sortedBy { it.path }
                .map { FileNameSearch.RankedFile(it, 0) }
        }
            .coalesceBy(this)
            .expireWith(activePopup)
            .finishOnUiThread(ModalityState.stateForComponent(panel)) { files ->
                if (gen != generation) return@finishOnUiThread
                filterMatches = files
                rootList.setEntries(entries)
                rootList.setCounts(SubtreeMatches.countsFor(files, entries) { it.file })
                val current = context.currentFile?.takeIf { it.isValid }
                val containing = current?.let { rootList.entryContaining(it) }
                if (containing != null) rootList.selectEntry(containing)
                else if (rootList.selectedEntry() == null) rootList.selectIndex(0)
                rebuildRight()
                updateFooter(null)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun changedFileSet(): Set<VirtualFile> {
        val manager = ChangeListManager.getInstance(project)
        val tracked = manager.allChanges.mapNotNull { it.virtualFile }
        val unversioned = manager.unversionedFilesPaths.mapNotNull { it.virtualFile }
        return (tracked + unversioned).filterTo(LinkedHashSet()) { it.isValid }
    }

    private fun showNamedScopeBrowse(named: NavigatorScope.Named, resolved: ScopeResolver.Resolved) {
        val activePopup = popup ?: return
        val gen = ++generation
        pendingRestore = null
        ReadAction.nonBlocking<NamedScopeFiles.Result> {
            val changed = if (changedOnly) changedFileSet() else null
            val raw = NamedScopeFiles.collect(project, named.namedScope)
            NamedScopeFiles.Result(
                raw.files.filter { !BrowseTree.hiddenByDotRule(project, it) && (changed == null || it in changed) },
                raw.truncated,
            )
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
                namedMatches = files
                val entries = allEntries.filter { SubtreeMatches.matchesUnder(files, it) { m -> m.file }.isNotEmpty() }
                rootList.clearCounts()
                rootList.setEntries(entries)
                val current = context.currentFile?.takeIf { it.isValid }
                val containing = current?.let { rootList.entryContaining(it) }
                if (containing == null) rootList.selectIndex(0) else rootList.selectEntry(containing)
                rebuildRight()
                updateFooter(
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

    private fun moveLeft(delta: Int) {
        setActivePane(Pane.LEFT)
        rootList.move(delta)
        rebuildRight()
    }

    private fun moveRight(delta: Int) {
        setActivePane(Pane.RIGHT)
        treePanel.move(delta)
    }

    private fun moveActive(delta: Int) {
        when (activePane) {
            Pane.LEFT -> {
                rootList.move(delta)
                rebuildRight()
            }

            Pane.RIGHT -> treePanel.move(delta)

            Pane.PREVIEW -> previewPanel.scrollLines(delta)
        }
    }

    private fun toggleDotFiles() {
        val settings = NavigatorSettings.getInstance()
        settings.hideDotFiles = !settings.hideDotFiles
        refresh()
    }

    private fun toggleChangedOnly() {
        changedOnly = !changedOnly
        refresh()
    }

    private fun showNewElement() {
        val dataContext = fileActionContext() ?: return
        NavigatorFileActions.perform(NavigatorFileActions.NEW_ELEMENT, dataContext) { }
    }

    private fun showContextMenu(component: JComponent, point: Point) {
        val dataContext = fileActionContext() ?: return
        JBPopupFactory.getInstance()
            .createActionGroupPopup(
                null,
                NavigatorFileActions.contextGroup(),
                dataContext,
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                true,
            )
            .show(RelativePoint(component, point))
    }

    /** The panes never take focus, but the preview editor can, and then typing would go into it. */
    private fun focusSearch() {
        val editor = searchField.textEditor
        editor.requestFocusInWindow()
        editor.caretPosition = editor.document.length
    }

    private fun toggleMark() {
        treePanel.toggleMark()
        updateFooter()
    }

    private fun runFileAction(actionId: String) {
        val dataContext = fileActionContext() ?: return
        NavigatorFileActions.perform(actionId, dataContext) { afterFileAction() }
    }

    private fun afterFileAction() {
        val activePopup = popup ?: return
        ApplicationManager.getApplication().invokeLater(
            {
                if (activePopup.isDisposed) return@invokeLater
                treePanel.clearMarks()
                refresh()
            },
            ModalityState.stateForComponent(panel),
        )
    }

    private fun targetFiles(): List<VirtualFile> {
        val selected = when (activePane) {
            Pane.LEFT -> listOfNotNull(rootList.selectedEntry()?.file)
            else -> treePanel.markedFiles().ifEmpty { listOfNotNull(treePanel.selectedFile()) }
        }
        return selected.filter { it.isValid }
    }

    private fun targetElements(): Array<PsiElement> =
        ReadAction.compute<Array<PsiElement>, RuntimeException> {
            val manager = PsiManager.getInstance(project)
            val elements: List<PsiElement> = targetFiles().mapNotNull {
                if (it.isDirectory) manager.findDirectory(it) else manager.findFile(it)
            }
            elements.toTypedArray()
        }

    private fun fileActionContext(): DataContext? =
        ReadAction.compute<DataContext?, RuntimeException> { buildFileActionContext() }

    private fun buildFileActionContext(): DataContext? {
        val dir = createTargetDirectory() ?: return null
        val psiDir = PsiManager.getInstance(project).findDirectory(dir) ?: return null
        val files = targetFiles()
        val elements = targetElements()
        val builder = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(PlatformCoreDataKeys.CONTEXT_COMPONENT, panel)
            .add(PlatformCoreDataKeys.MODULE, ModuleUtilCore.findModuleForFile(dir, project))
            .add(LangDataKeys.IDE_VIEW, PopupIdeView(psiDir))
            .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, files.toTypedArray())
            .add(PlatformCoreDataKeys.PSI_ELEMENT_ARRAY, elements)
            .add(PlatformDataKeys.COPY_PROVIDER, copyPaste.copyProvider)
            .add(PlatformDataKeys.CUT_PROVIDER, copyPaste.cutProvider)
            .add(PlatformDataKeys.PASTE_PROVIDER, copyPaste.pasteProvider)
        files.firstOrNull()?.let { builder.add(CommonDataKeys.VIRTUAL_FILE, it) }
        elements.firstOrNull()?.let { builder.add(CommonDataKeys.PSI_ELEMENT, it) }
        return builder.build()
    }

    private fun clipboardHasFiles(): Boolean =
        PsiCopyPasteManager.getInstance().getElements(BooleanArray(1)) != null

    private fun createTargetDirectory(): VirtualFile? {
        val selected =
            if (activePane == Pane.LEFT) rootList.selectedEntry()?.file
            else (treePanel.selectedFile() ?: rootList.selectedEntry()?.file)
        val valid = selected?.takeIf { it.isValid } ?: return null
        return if (valid.isDirectory) valid else valid.parent
    }

    private fun selectCreated(file: VirtualFile) {
        if (searchField.text.isNotEmpty()) searchField.text = ""
        refresh()
        val entry = rootList.entryContaining(file) ?: return
        rootList.selectEntry(entry)
        rebuildRight()
        if (entry.file == file) {
            setActivePane(Pane.LEFT)
        } else {
            setActivePane(Pane.RIGHT)
            treePanel.locate(file, entry.file)
        }
        refreshPreview()
    }

    private inner class PopupIdeView(private val dir: PsiDirectory) : IdeView {

        override fun getDirectories(): Array<PsiDirectory> = arrayOf(dir)

        override fun getOrChooseDirectory(): PsiDirectory? = dir

        override fun selectElement(element: PsiElement) {
            val file = when (element) {
                is PsiFile -> element.virtualFile
                is PsiDirectory -> element.virtualFile
                else -> element.containingFile?.virtualFile
            } ?: return
            selectCreated(file)
        }
    }

    private fun expandOrEnterRight() {
        when (activePane) {
            Pane.LEFT -> enterRightPane()
            Pane.PREVIEW -> Unit
            Pane.RIGHT -> {
                val data = treePanel.selectedData()
                if (data != null && !data.isDirectory && previewVisible()) setActivePane(Pane.PREVIEW)
                else treePanel.expandSelection()
            }
        }
    }

    private fun enterRightPane() {
        val entry = rootList.selectedEntry() ?: return
        if (!entry.isDirectory) return
        setActivePane(Pane.RIGHT)
        treePanel.selectFirstRowIfNone()
    }

    private fun collapseOrExitLeft() {
        when (activePane) {
            Pane.LEFT -> Unit
            Pane.PREVIEW -> setActivePane(Pane.RIGHT)
            Pane.RIGHT -> {
                if (treePanel.collapseSelection() == TreePanel.CollapseOutcome.AT_TOP_LEVEL) {
                    setActivePane(Pane.LEFT)
                }
            }
        }
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
        val data = treePanel.selectedData() ?: return
        if (data.isDirectory) {
            if (treePanel.isSelectionExpanded()) treePanel.collapseSelectionPath() else expandOrEnterRight()
            return
        }
        val file = data.file ?: return
        if (!file.isValid) {
            setFooter("File no longer exists")
            treePanel.removeSelectedNode()
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

        Pane.RIGHT -> treePanel.selectedDirectory()

        Pane.PREVIEW -> treePanel.selectedDirectory()
    }

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

    private fun updateFooter(note: String? = footerNote) {
        footerNote = note
        setFooter(footerNotes(note))
    }

    private fun footerNotes(extra: String?): String? {
        val markCount = treePanel.markedFiles().size
        val notes = listOfNotNull(
            if (markCount > 0) "$markCount marked" else null,
            if (changedOnly) "Changed files only" else null,
            extra,
        )
        return notes.joinToString("; ").ifEmpty { null }
    }
}
