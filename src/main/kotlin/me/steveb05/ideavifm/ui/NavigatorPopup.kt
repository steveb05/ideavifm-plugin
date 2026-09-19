package me.steveb05.ideavifm.ui

import com.intellij.ide.CopyPasteDelegator
import com.intellij.ide.IdeView
import com.intellij.ide.PsiCopyPasteManager
import com.intellij.ide.util.DeleteHandler
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.IdeFocusManager
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
import me.steveb05.ideavifm.action.NavigatorCommand
import me.steveb05.ideavifm.action.NavigatorCommands
import me.steveb05.ideavifm.file.NavigatorFileActions
import me.steveb05.ideavifm.file.OpenTarget
import me.steveb05.ideavifm.preview.PreviewPanel
import me.steveb05.ideavifm.scope.BaseEntry
import me.steveb05.ideavifm.scope.NavigatorScope
import me.steveb05.ideavifm.scope.ScopeResolver
import me.steveb05.ideavifm.search.*
import me.steveb05.ideavifm.settings.NavigatorSettings
import me.steveb05.ideavifm.settings.NavigatorViewState
import me.steveb05.ideavifm.tree.BrowseTree
import me.steveb05.ideavifm.tree.NamedScopeFiles
import me.steveb05.ideavifm.tree.NavigatorNodeData
import me.steveb05.ideavifm.tree.Revealed
import me.steveb05.ideavifm.tree.SubtreeMatches
import java.awt.Dimension
import java.awt.Point
import javax.swing.Box
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

class NavigatorPopup(private val context: NavigatorContext) {

    private enum class Pane { LEFT, RIGHT, PREVIEW }

    /** Who asked for a refresh. What changed on disk must not reshape a tree the user is reading. */
    private enum class Refresh { USER, FILE_SYSTEM }

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

    /** Delete is disabled unless the context carries a provider; this is the one the project view uses. */
    private val deleteProvider = DeleteHandler.DefaultDeleteProvider()

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
    private var openCreated = true
    private var filterMatches: List<RankedFile>? = null
    private var namedMatches: List<RankedFile>? = null

    /** Whether the running search has already picked the entry to look in, and which one it picked. */
    private var searchLanded = false
    private var landedOn: VirtualFile? = null
    private var changedOnly = false

    /** Cycled inside the popup and reset from the settings the next time it opens, the way [changedOnly] is. */
    private var declarationDepth = NavigatorSettings.getInstance().declarationDepth
    private var footerNote: String? = null
    private val fileNameSearch = FileNameSearch(project)
    private val declarationSearch = DeclarationSearch(project)
    private var alarm: Alarm? = null

    /** Its own alarm: the search one is cancelled wholesale whenever the query changes. */
    private var focusAlarm: Alarm? = null

    private var pendingRefresh: Refresh? = null

    /** The browsing view a query replaced: clearing the query puts it back rather than opening afresh. */
    private var browseEntry: VirtualFile? = null
    private var browseExpansion: Set<VirtualFile> = emptySet()
    private var browseSelection: VirtualFile? = null
    private var restoringBrowse = false

    @Volatile
    private var watchedRoots: List<String> = emptyList()

    @Volatile
    private var revealedRoots: List<String> = emptyList()

    /** The dot folders this view was taken inside, read again whenever the zoom or the current file moves. */
    private var revealed = Revealed.NONE

    fun show() {
        buildPanel()
        val created = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, searchField.textEditor)
            .setTitle("IdeaVifm")
            .setRequestFocus(true)
            .setFocusable(true)
            .setResizable(true)
            .setMovable(true)
            .setCancelOnClickOutside(true)
            // The New and Rename dialogs deactivate this window, and the popup is there again once they close.
            .setCancelOnWindowDeactivation(false)
            .setDimensionServiceKey(project, "me.steveb05.ideavifm.Popup", true)
            .createPopup()
        popup = created
        previewPanel.attach(created)
        alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, created)
        focusAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, created)
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = scheduleRefresh()
        })
        registerKeys()
        watchFileSystem(created)
        closeWhenIdeLosesFocus(created)
        Disposer.register(created) { saveView() }
        setActivePane(Pane.RIGHT)
        ProjectFileSnapshot.getInstance(project).prepare()
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
        val splitter = OnePixelSplitter(false, "me.steveb05.ideavifm.Splitter", 0.25f)
        splitter.firstComponent = rootList.component
        val rightSplit = OnePixelSplitter(false, "me.steveb05.ideavifm.PreviewSplitter", 0.55f)
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
        commands.bind(NavigatorCommand.CYCLE_DECLARATIONS) { cycleDeclarationDepth() }
        commands.bind(NavigatorCommand.NEW_ELEMENT) { showNewElement(inverted = false) }
        commands.bind(NavigatorCommand.NEW_ELEMENT_INVERTED) { showNewElement(inverted = true) }
        commands.bind(NavigatorCommand.FOCUS_SEARCH) { focusSearch() }
        commands.bind(NavigatorCommand.RESET_TREE, isEnabled = { searchField.text.isEmpty() }) {
            treePanel.resetToOpenState()
        }
        commands.bind(NavigatorCommand.EXPAND_LEVEL, isEnabled = { browsing() }) {
            setActivePane(Pane.RIGHT)
            treePanel.expandOneLevel()
        }
        commands.bind(NavigatorCommand.COLLAPSE_LEVEL, isEnabled = { browsing() }) {
            setActivePane(Pane.RIGHT)
            treePanel.collapseOneLevel()
        }
        commands.bind(NavigatorCommand.NEXT_ROOT_FOLDER, isEnabled = { browsing() }) { jumpRootFolder(1) }
        commands.bind(NavigatorCommand.PREVIOUS_ROOT_FOLDER, isEnabled = { browsing() }) { jumpRootFolder(-1) }
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

    /**
     * Both panes read the file system once and then sit still, so whatever changes it while they are up has
     * to bring them back in step: a delete run from the context menu, a refactoring that only finishes once
     * the action that started it has returned, or the IDE moving files around on its own.
     *
     * Only what the panes are showing counts. A build writing its output, git rewriting its index and the IDE
     * saving its own settings all land here too, and rebuilding the tree for those is what made folders open
     * and close while nobody was touching the keyboard.
     */
    private fun watchFileSystem(parent: JBPopup) {
        project.messageBus.connect(parent).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { showsWhatChanged(it) }) scheduleFileSystemRefresh()
                }
            },
        )
    }

    /** Whether a change is one the panes would draw differently: inside them, and not somewhere they hide. */
    private fun showsWhatChanged(event: VFileEvent): Boolean {
        if (event is VFileContentChangeEvent) return false
        val path = event.path
        val root = watchedRoots.firstOrNull { path.startsWith(it) } ?: return false
        val inside = "/" + path.substring(root.length)
        val hidesDots = NavigatorSettings.getInstance().hideDotFiles &&
            revealedRoots.none { path.startsWith(it) }
        if (hidesDots && inside.contains("/.")) return false
        val file = event.file ?: return true
        return !file.isValid || !ProjectFileIndex.getInstance(project).isExcluded(file)
    }

    /** The folders the panes are drawing, read by the file system listener off the thread it arrives on. */
    private fun rememberWatchedRoots() {
        val roots = rootList.entries().map { "${it.file.path}/" } +
            listOfNotNull(zoomStack.lastOrNull()?.dir?.path?.let { "$it/" })
        watchedRoots = roots.ifEmpty { listOfNotNull(project.basePath?.let { "$it/" }) }
        revealedRoots = revealed.roots.map { "${it.path}/" }
    }

    /**
     * Switching workspace or window leaves the popup floating over whatever the user moved to, since the
     * platform never cancels a popup over a window it cannot see the clicks of. Leaving the IDE is reported
     * only once the focus lands outside it, so the dialogs opened from here still hold the popup open.
     */
    private fun closeWhenIdeLosesFocus(parent: JBPopup) = runWhenIdeLosesFocus(parent) { parent.cancel() }

    private fun scheduleRefresh() = schedule(Refresh.USER, QUERY_DELAY_MS)

    private fun scheduleFileSystemRefresh() = schedule(Refresh.FILE_SYSTEM, FILE_SYSTEM_DELAY_MS)

    /**
     * A file system change can land while the popup is on its way out, and its alarm is gone by then. A burst
     * of them must not cancel a keystroke either, so a refresh the user asked for holds the slot.
     */
    private fun schedule(reason: Refresh, delay: Int) {
        val activePopup = popup ?: return
        val activeAlarm = alarm ?: return
        if (activePopup.isDisposed || project.isDisposed) return
        if (reason == Refresh.FILE_SYSTEM && pendingRefresh == Refresh.USER) return
        pendingRefresh = reason
        activeAlarm.cancelAllRequests()
        activeAlarm.addRequest(
            {
                pendingRefresh = null
                refresh(reason)
            },
            delay,
        )
    }

    private fun cycleScope(delta: Int) {
        scopeIndex = ((scopeIndex + delta) % scopes.size + scopes.size) % scopes.size
        zoomStack.clear()
        pendingRestore = null
        forgetBrowseView()
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
        if (activePane == Pane.LEFT) {
            previewPanel.setTarget(rootList.selectedEntry()?.file)
            return
        }
        val data = treePanel.selectedData()
        previewPanel.setTarget(data?.file, declarationOffset(data))
    }

    private fun previewHover(file: VirtualFile) {
        if (!hoverPreviews()) return
        previewPanel.setTarget(file)
    }

    private fun previewHover(data: NavigatorNodeData) {
        if (!hoverPreviews()) return
        previewPanel.setTarget(data.file, declarationOffset(data))
    }

    /**
     * The mouse crossing a pane on its way somewhere else would otherwise replace what is being read, so the
     * preview follows the selection alone until the setting says the pointer may drive it too.
     */
    private fun hoverPreviews(): Boolean =
        NavigatorSettings.getInstance().let { it.showPreview && it.previewOnHover }

    /** Where a file the query reached through something it declares should open: on that declaration. */
    private fun declarationOffset(data: NavigatorNodeData?): Int? =
        data?.takeUnless { it.isDirectory }?.declarations?.firstOrNull()?.offset

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
        forgetBrowseView()
        reopen = true
        setActivePane(Pane.LEFT)
        refresh()
    }

    private fun zoomOut() {
        val frame = zoomStack.removeLastOrNull() ?: return
        forgetBrowseView()
        pendingRestore = frame
        refresh()
    }

    private fun refresh(reason: Refresh = Refresh.USER) {
        dropDeletedZooms()
        val query = searchField.text.trim()
        val scope = scopes[scopeIndex]
        revealed = Revealed.of(project, zoomStack.lastOrNull()?.dir, context.currentFile)
        treePanel.setRevealed(revealed)
        val resolved = ScopeResolver.resolve(scope, context, revealed)
        autoExpand = query.isEmpty() && reason == Refresh.USER
        if (query.isNotEmpty() && !searchWasActive) rememberBrowseView()
        val cleared = query.isEmpty() && searchWasActive
        if (cleared) reopen = true
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
                restoringBrowse = cleared && browseEntry != null
                showBrowse(resolved, reason)
            }
            return
        }
        namedMatches = null
        runFilterSearch(query, resolved)
    }

    /** A zoom into a folder that has since been deleted pops back out rather than showing what is gone. */
    private fun dropDeletedZooms() {
        while (zoomStack.isNotEmpty() && !zoomStack.last().dir.isValid) {
            zoomStack.removeLast()
            reopen = true
        }
    }

    private fun showBrowse(resolved: ScopeResolver.Resolved, reason: Refresh = Refresh.USER) {
        rootList.clearCounts()
        updateFooter(null)
        treePanel.setEmptyText("No files in scope")
        val entries = effectiveEntries(resolved)
        val previous = rootList.selectedEntry()
        val previousFile = treePanel.selectedFile()
        rootList.setEntries(entries)
        val restore = pendingRestore
        val current = context.currentFile?.takeIf { it.isValid }
        when {
            restore != null -> {
                pendingRestore = null
                reopen = false
                rootList.selectIndex(restore.leftIndex)
                rebuildRight()
                walkOpenTo(restore.rightFile, current)
                setActivePane(restore.pane)
            }

            reason == Refresh.FILE_SYSTEM -> keepInPlace(entries, previous, previousFile)

            restoringBrowse -> {
                restoringBrowse = false
                reopen = false
                restoreBrowseView(entries, current, previous, previousFile)
            }

            firstOpen || reopen -> {
                reopen = false
                positionOnOpen(entries, current, previous, previousFile)
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
                walkOpenTo(previousFile, current)
            }
        }
        if (reason == Refresh.USER) firstOpen = false
    }

    /** The view a query is about to replace, held so that clearing the query gives it back. */
    private fun rememberBrowseView() {
        if (filterMatches != null || namedMatches != null) return
        browseEntry = rootList.selectedEntry()?.file
        browseExpansion = treePanel.expandedFiles()
        browseSelection = treePanel.selectedFile()
    }

    private fun forgetBrowseView() {
        browseEntry = null
        browseExpansion = emptySet()
        browseSelection = null
        restoringBrowse = false
    }

    /**
     * Clearing a query puts back the folders that were open when it was typed. Opening the tree afresh would
     * throw away a view the user had shaped, which typing a few letters should not cost.
     */
    private fun restoreBrowseView(
        entries: List<BaseEntry>,
        current: VirtualFile?,
        previous: BaseEntry?,
        previousFile: VirtualFile?,
    ) {
        val saved = browseEntry
        val entry = saved?.let { file -> entries.firstOrNull { it.file == file } }
        if (entry == null) {
            positionOnOpen(entries, current, previous, previousFile)
            return
        }
        autoExpand = false
        rootList.selectEntry(entry)
        rebuildRight()
        treePanel.expandFiles(browseExpansion)
        browseSelection?.takeIf { it.isValid }?.let { treePanel.selectFile(it) }
    }

    /**
     * What a change on disk leaves behind: the same entry, the same row and the same folders open. Walking the
     * tree back open to the file being edited is what an opening does, and a build writing a file is not one.
     */
    private fun keepInPlace(entries: List<BaseEntry>, previous: BaseEntry?, previousFile: VirtualFile?) {
        val kept = previous?.let { p -> entries.firstOrNull { it.file == p.file } }
        if (kept != null) rootList.selectEntry(kept) else rootList.selectIndex(0)
        rebuildRight()
        previousFile?.takeIf { it.isValid }?.let { treePanel.selectFile(it) }
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
                val keep = if (searchLanded) treePanel.selectedFile() else null
                treePanel.showPruned(bucketFor(filter, entry), entry?.file, matchesOnly = searchActive())
                treePanel.setEmptyText("Nothing found")
                if (keep == null || !treePanel.selectFile(keep)) treePanel.selectBestMatch()
            }

            named != null -> {
                treePanel.showPruned(bucketFor(named, entry), entry?.file, openFully = false)
                treePanel.setEmptyText("No files in scope")
                context.currentFile?.let { treePanel.selectFile(it) }
            }

            else -> {
                val dir = entry?.file?.takeIf { entry.isDirectory && it.isValid }
                when {
                    dir == null -> treePanel.showEmpty()
                    treePanel.isBrowsing(dir) -> treePanel.reloadSubtree(dir)
                    else -> {
                        treePanel.showSubtree(dir)
                        if (autoExpand) treePanel.openTo(NavigatorSettings.getInstance().treeOpenLevel)
                    }
                }
            }
        }
        rememberWatchedRoots()
        refreshPreview()
    }

    /** A query is running; the changed files view and the named scopes fill the panes with an empty one. */
    private fun searchActive(): Boolean = searchField.text.trim().isNotEmpty()

    /** The level and jump keys shape a tree that is being browsed, not the rows a query or a scope filled. */
    private fun browsing(): Boolean = searchField.text.isEmpty() && filterMatches == null && namedMatches == null

    private fun jumpRootFolder(delta: Int) {
        setActivePane(Pane.RIGHT)
        treePanel.jumpToRootFolder(delta)
    }

    private fun bucketFor(
        matches: List<RankedFile>,
        entry: BaseEntry?,
    ): List<RankedFile> =
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
        searchLanded = false
        landedOn = null
        ReadAction.nonBlocking<SearchResult> {
            val shown = shownFilter()
            val named = fileNameSearch.search(query, searchScope) { partial ->
                publishLater(gen, query, entries, keepShown(partial, shown), running = true)
            }
            keepShown(named, shown)
        }
            .coalesceBy(this, NAME_PASS)
            .expireWith(activePopup)
            .finishOnUiThread(ModalityState.stateForComponent(panel)) { named ->
                if (gen != generation) return@finishOnUiThread
                publish(query, entries, named, running = true)
                runDeclarationSearch(query, gen, entries, searchScope, named, activePopup)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /**
     * What a file declares is read from the index and resolved to PSI, which costs more than matching names
     * does. It runs once the names are on screen and merges into them, so typing never waits on it.
     */
    private fun runDeclarationSearch(
        query: String,
        gen: Int,
        entries: List<BaseEntry>,
        searchScope: GlobalSearchScope,
        named: SearchResult,
        activePopup: JBPopup,
    ) {
        val depth = declarationDepth
        ReadAction.nonBlocking<SearchResult> {
            val shown = shownFilter()
            NavigatorSearch.merge(named, declarationSearch.search(query, searchScope, depth).filterKeys(shown))
        }
            .coalesceBy(this, DECLARATION_PASS)
            .expireWith(activePopup)
            .finishOnUiThread(ModalityState.stateForComponent(panel)) { merged ->
                if (gen != generation) return@finishOnUiThread
                if (merged.files == named.files) updateFooter(searchNote(merged, running = false))
                else publish(query, entries, merged, running = false)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /** The rows a search may show: the dot rule and the changed files toggle both cut into what it found. */
    private fun shownFilter(): (VirtualFile) -> Boolean {
        val changed = if (changedOnly) changedFileSet() else null
        val shownRoots = revealed
        return { file ->
            !BrowseTree.hiddenByDotRule(project, file, shownRoots) && (changed == null || file in changed)
        }
    }

    private fun keepShown(result: SearchResult, shown: (VirtualFile) -> Boolean): SearchResult =
        SearchResult(result.files.filter { shown(it.file) }, result.truncated)

    private fun publishLater(
        gen: Int,
        query: String,
        entries: List<BaseEntry>,
        result: SearchResult,
        running: Boolean,
    ) {
        ApplicationManager.getApplication().invokeLater(
            {
                if (gen == generation && popup?.isDisposed == false) publish(query, entries, result, running)
            },
            ModalityState.stateForComponent(panel),
        )
    }

    private fun publish(query: String, entries: List<BaseEntry>, result: SearchResult, running: Boolean) {
        currentHighlight = QueryHighlight(query, searchBase)
        filterMatches = result.files
        val counts = SubtreeMatches.countsFor(result.files, entries) { it.file }
        rootList.setEntries(entries)
        rootList.setCounts(counts)
        land(entries, counts, result)
        rebuildRight()
        updateFooter(searchNote(result, running))
    }

    /**
     * A search picks the entry to look in once, on the first rows it has. What comes in afterwards, the
     * declarations among it, must not pull the left pane out from under a selection the user has moved.
     */
    private fun land(entries: List<BaseEntry>, counts: Map<BaseEntry, Int>, result: SearchResult) {
        if (searchLanded && rootList.selectedEntry()?.file != landedOn) return
        val target = OpenTarget.searchLanding(
            entries,
            counts,
            rootList.selectedEntry(),
            result.files.firstOrNull()?.file,
        ) ?: return
        rootList.selectEntry(target)
        searchLanded = true
        landedOn = target.file
    }

    private fun searchNote(result: SearchResult, running: Boolean): String? = when {
        running -> "${result.files.size} matches, searching"
        result.truncated -> "Showing top ${FileNameSearch.DEFAULT_LIMIT} matches, keep typing to narrow"
        else -> null
    }

    private fun showChangedBrowse(resolved: ScopeResolver.Resolved) {
        val activePopup = popup ?: return
        val gen = ++generation
        pendingRestore = null
        namedMatches = null
        val entries = effectiveEntries(resolved)
        val searchScope = zoomedSearchScope(resolved)
        ReadAction.nonBlocking<List<RankedFile>> {
            val shownRoots = revealed
            changedFileSet()
                .filter { searchScope.contains(it) && !BrowseTree.hiddenByDotRule(project, it, shownRoots) }
                .sortedBy { it.path }
                .map { RankedFile(it, 0) }
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
            val shownRoots = revealed
            val raw = NamedScopeFiles.collect(project, named.namedScope)
            NamedScopeFiles.Result(
                raw.files.filter {
                    !BrowseTree.hiddenByDotRule(project, it, shownRoots) && (changed == null || it in changed)
                },
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
                    .map { RankedFile(it, 0) }
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
        zoomStack.lastOrNull()?.let { ScopeResolver.entriesForBase(project, it.dir, revealed) }
            ?: resolved.entries

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

    private fun cycleDeclarationDepth() {
        declarationDepth = declarationDepth.next()
        updateFooter()
        refresh()
    }

    private fun showNewElement(inverted: Boolean) {
        val dataContext = fileActionContext() ?: return
        openCreated = NavigatorSettings.getInstance().openCreatedFile != inverted
        NavigatorFileActions.perform(NavigatorFileActions.NEW_ELEMENT, dataContext) { }
    }

    private fun showContextMenu(component: JComponent, point: Point) {
        val dataContext = fileActionContext() ?: return
        openCreated = NavigatorSettings.getInstance().openCreatedFile
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

    /**
     * The panes never take focus, but the preview editor can, and so can the IDE window once a dialog closes
     * over us. Going through the focus manager rather than the window means the popup can take the focus back
     * even when its window is no longer the active one.
     */
    private fun focusSearch() {
        val editor = searchField.textEditor
        IdeFocusManager.getInstance(project).requestFocus(editor, true)
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
            .add(PlatformDataKeys.DELETE_ELEMENT_PROVIDER, deleteProvider)
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

    /**
     * Opening what was just created is the IdeView's job, not the New action's: the project view opens it
     * from its own selectElement. Ours has to say what happens, so either follow the new file into the editor
     * and get out of the way, or keep the navigator up with the file selected and the caret in the search field.
     */
    private fun selectCreated(file: VirtualFile) {
        val activePopup = popup ?: return
        if (openCreated && !file.isDirectory) {
            if (!activePopup.isDisposed) activePopup.closeOk(null)
            FileEditorManager.getInstance(project).openFile(file, true)
            return
        }

        // Leaving the IDE closes the popup, and the New dialog it opened outlives it to create the file.
        if (activePopup.isDisposed) return
        if (!file.isDirectory) closeEditorFor(file)
        if (searchField.text.isNotEmpty()) searchField.text = ""
        refresh()
        val entry = rootList.entryContaining(file)
        if (entry != null) {
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
        returnFocusToSearch()
    }

    /**
     * Some templates do open the file on their way out. The file was created a moment ago, so a tab for it
     * can only be that one, and it is the one to close when the file was not meant to open.
     */
    private fun closeEditorFor(file: VirtualFile) {
        val editors = FileEditorManager.getInstance(project)
        if (editors.isFileOpen(file)) editors.closeFile(file)
    }

    /**
     * As the New dialog unwinds, the IDE restores the focus to the window underneath, and that restore can
     * land after the request below. So the focus is asked for once the dialog is done, and asked for again a
     * moment later if the IDE took it back in the meantime.
     */
    private fun returnFocusToSearch() {
        val activePopup = popup ?: return
        ApplicationManager.getApplication().invokeLater(
            { if (!activePopup.isDisposed) focusSearch() },
            ModalityState.nonModal(),
        )
        focusAlarm?.addRequest(
            {
                if (activePopup.isDisposed || searchField.textEditor.hasFocus()) return@addRequest
                focusSearch()
            },
            FOCUS_RETRY_MS,
        )
    }

    private inner class PopupIdeView(private val dir: PsiDirectory) : IdeView {

        override fun getDirectories(): Array<PsiDirectory> = arrayOf(dir)

        override fun getOrChooseDirectory(): PsiDirectory = dir

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
        val offset = declarationOffset(data)
        if (offset == null) {
            FileEditorManager.getInstance(project).openFile(file, true)
            return
        }
        OpenFileDescriptor(project, file, offset).navigate(true)
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
            declarationDepth.takeIf { it != NavigatorSettings.getInstance().declarationDepth }?.label,
            extra,
        )
        return notes.joinToString("; ").ifEmpty { null }
    }

    private companion object {
        const val FOCUS_RETRY_MS = 150
        const val QUERY_DELAY_MS = 50
        const val FILE_SYSTEM_DELAY_MS = 300

        /** The two passes of a search run one after the other, so each coalesces against itself alone. */
        const val NAME_PASS = "names"
        const val DECLARATION_PASS = "declarations"
    }
}
