package dev.sb.projecttreenavigator

import com.intellij.ide.IdeView
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.MinusculeMatcher
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import java.awt.Dimension
import javax.swing.Box
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
    private val scopes = ScopeResolver.availableScopes(context)
    private var scopeIndex = 0
    private val zoomStack = ArrayDeque<ZoomFrame>()

    private val searchField = SearchTextField(false)
    private val scopeLabel = JBLabel()
    private val footerLabel = JBLabel()
    private val treePanel = TreePanel(
        project,
        { currentMatcher },
        onActivate = { setActivePane(Pane.RIGHT) },
        onCommit = { commitSelection() },
        onHover = { previewHover(it) },
        onSelectionChanged = { refreshPreview() },
    )
    private val rootList = RootListPanel(
        project,
        onUserSelection = { onUserListSelection() },
        onHover = { previewHover(it) },
    )
    private val previewPanel = PreviewPanel(project)
    private val panel = BorderLayoutPanel()

    private var popup: JBPopup? = null
    private var generation = 0
    private var activePane = Pane.RIGHT
    private var firstOpen = true
    private var pendingRestore: ZoomFrame? = null
    private var currentMatcher: MinusculeMatcher? = null
    private var autoExpandModule = false
    private var filterMatches: List<FileNameSearch.RankedFile>? = null
    private var namedMatches: List<FileNameSearch.RankedFile>? = null
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
            .setDimensionServiceKey(project, "dev.sb.projecttreenavigator.Popup", true)
            .createPopup()
        popup = created
        previewPanel.attach(created)
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
        footerLabel.isVisible = false
        scopeLabel.border = JBUI.Borders.empty(4, 6, 0, 6)
        footerLabel.border = JBUI.Borders.empty(2, 6, 4, 6)

        val header = Box.createVerticalBox()
        header.add(scopeLabel)
        header.add(searchField)
        val splitter = OnePixelSplitter(false, "dev.sb.projecttreenavigator.Splitter", 0.25f)
        splitter.firstComponent = rootList.component
        val rightSplit = OnePixelSplitter(false, "dev.sb.projecttreenavigator.PreviewSplitter", 0.55f)
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
        commands.bind(NavigatorCommand.NEW_ELEMENT) { showNewElement() }
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
        autoExpandModule = scope == NavigatorScope.Module && !resolved.fellBack && query.isEmpty()
        updateScopeLabel(resolved)
        if (query.isEmpty()) {
            currentMatcher = null
            filterMatches = null
            if (scope is NavigatorScope.Named) {
                showNamedScopeBrowse(scope, resolved)
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
        setFooter(null)
        treePanel.setEmptyText("No files in scope")
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
                    else -> treePanel.locate(current, containing.file)
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
        treePanel.locate(target, entry.file)
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
                    if (autoExpandModule) treePanel.expandToFirstFileLevel()
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
            val raw = fileNameSearch.search(query, searchScope)
            FileNameSearch.Result(raw.files.filter { !BrowseTree.hiddenByDotRule(project, it.file) }, raw.truncated)
        }
            .coalesceBy(this)
            .expireWith(activePopup)
            .finishOnUiThread(ModalityState.stateForComponent(panel)) { result ->
                if (gen != generation) return@finishOnUiThread
                currentMatcher = FileNameSearch.nameMatcher(query)
                filterMatches = result.files
                rootList.setEntries(entries)
                rootList.setCounts(SubtreeMatches.countsFor(result.files, entries) { it.file })
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
            val raw = NamedScopeFiles.collect(project, named.namedScope)
            NamedScopeFiles.Result(raw.files.filter { !BrowseTree.hiddenByDotRule(project, it) }, raw.truncated)
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

    private fun showNewElement() {
        val dir = createTargetDirectory() ?: return
        val psiDir = PsiManager.getInstance(project).findDirectory(dir) ?: return
        val group = ActionManager.getInstance().getAction("NewGroup") as? ActionGroup ?: return
        val dataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(PlatformCoreDataKeys.MODULE, ModuleUtilCore.findModuleForFile(dir, project))
            .add(LangDataKeys.IDE_VIEW, PopupIdeView(psiDir))
            .build()
        JBPopupFactory.getInstance()
            .createActionGroupPopup("New", group, dataContext, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false)
            .showInCenterOf(panel)
    }

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
}
