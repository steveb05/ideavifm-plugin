package me.steveb05.ideavifm

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColorUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import java.io.IOException
import javax.swing.JComponent
import javax.swing.JScrollBar
import javax.swing.JScrollPane
import javax.swing.SwingConstants

class PreviewPanel(private val project: Project) {

    sealed class Content {
        class Source(val document: Document) : Content()
        class Text(val text: String, val truncated: Boolean) : Content()
        class Binary(val name: String, val typeName: String, val length: Long) : Content()
        class Directory(val files: List<VirtualFile>, val capped: Boolean) : Content()
        object Empty : Content()
    }

    private val panel = BorderLayoutPanel()
    private val label = JBLabel("", SwingConstants.CENTER)
    private var editor: EditorEx? = null
    private var scrollable: JScrollPane? = null
    private var popup: JBPopup? = null
    private var alarm: Alarm? = null
    private var generation = 0
    private var target: VirtualFile? = null
    private var targetOffset: Int? = null

    /**
     * Building a viewer costs an editor and a highlighting pass, which is what makes scrolling through files
     * feel heavy. The last few stay alive so walking back over them is free.
     */
    private val recentEditors = object : LinkedHashMap<VirtualFile, EditorEx>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<VirtualFile, EditorEx>): Boolean {
            if (size <= MAX_CACHED_EDITORS) return false
            EditorFactory.getInstance().releaseEditor(eldest.value)
            return true
        }
    }

    val component: JComponent = panel

    init {
        label.border = JBUI.Borders.empty(8)
        panel.border = PaneBorders.normal
    }

    fun attach(popup: JBPopup) {
        this.popup = popup
        alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, popup)
        Disposer.register(popup) { releaseEditors() }
        showLabel("No preview")
    }

    /** [offset] is where the file matched a search, so the preview opens on it rather than at the top. */
    fun setTarget(file: VirtualFile?, offset: Int? = null) {
        if (file == target && offset == targetOffset) return
        target = file
        targetOffset = offset
        val activeAlarm = alarm ?: return
        activeAlarm.cancelAllRequests()
        activeAlarm.addRequest({ load(file, offset) }, LOAD_DELAY_MS)
    }

    fun setActive(active: Boolean) {
        panel.border = if (active) PaneBorders.focus else PaneBorders.normal
        panel.repaint()
    }

    fun scrollLines(delta: Int) {
        val active = editor
        if (active != null) {
            val model = active.scrollingModel
            model.scrollVertically((model.verticalScrollOffset + delta * active.lineHeight).coerceAtLeast(0))
            return
        }
        scrollBar()?.let { it.value += delta * JBUI.scale(18) }
    }

    fun scrollHalfPage(delta: Int) {
        val active = editor
        if (active != null) {
            val model = active.scrollingModel
            val half = (model.visibleArea.height / 2).coerceAtLeast(active.lineHeight)
            model.scrollVertically((model.verticalScrollOffset + delta * half).coerceAtLeast(0))
            return
        }
        scrollBar()?.let { it.value += delta * (it.visibleAmount / 2).coerceAtLeast(JBUI.scale(18)) }
    }

    private fun scrollBar(): JScrollBar? = scrollable?.verticalScrollBar

    private fun load(file: VirtualFile?, offset: Int?) {
        val activePopup = popup ?: return
        val gen = ++generation
        if (file == null || !file.isValid) {
            showLabel("No preview")
            return
        }
        ReadAction.nonBlocking<Content> { computeContent(project, file) }
            .coalesceBy(this)
            .expireWith(activePopup)
            .finishOnUiThread(ModalityState.stateForComponent(panel)) { content ->
                if (gen != generation) return@finishOnUiThread
                apply(content, file)
                offset?.let { reveal(it) }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /**
     * The viewer is mounted but not laid out yet, and a scrolling model with no viewport to measure scrolls
     * nowhere, so the reveal is asked for again once the layout has run.
     */
    private fun reveal(offset: Int) {
        val active = editor ?: return
        if (offset <= 0 || offset >= active.document.textLength) return
        active.caretModel.moveToOffset(offset)
        active.scrollingModel.scrollToCaret(ScrollType.CENTER)
        val gen = generation
        ApplicationManager.getApplication().invokeLater({
            if (gen != generation || editor !== active) return@invokeLater
            active.scrollingModel.scrollToCaret(ScrollType.CENTER)
        }, ModalityState.stateForComponent(panel))
    }

    private fun apply(content: Content, file: VirtualFile) {
        detachEditor()
        panel.removeAll()
        scrollable = null
        when (content) {
            is Content.Source -> installEditor(
                viewerFor(file) { sourceViewer(project, file, content.document) },
                truncated = false,
            )

            is Content.Text -> installEditor(
                viewerFor(file) { textViewer(project, file, content.text) },
                content.truncated,
            )

            is Content.Binary -> showCentered(
                "<html><center>${StringUtil.escapeXmlEntities(content.name)}<br>" +
                    "${StringUtil.escapeXmlEntities(content.typeName)}, " +
                    StringUtil.formatFileSize(content.length) + "</center></html>",
            )

            is Content.Directory -> {
                val names = content.files.joinToString("<br>") { listingRow(it) }
                val suffix = if (content.capped) "<br>..." else ""
                val listing = JBLabel("<html>$names$suffix</html>", SwingConstants.LEFT)
                listing.verticalAlignment = SwingConstants.TOP
                listing.border = JBUI.Borders.empty(8)
                val pane = JBScrollPane(listing)
                scrollable = pane
                panel.addToCenter(pane)
            }

            Content.Empty -> showCentered("No preview")
        }
        panel.revalidate()
        panel.repaint()
    }

    private fun listingRow(child: VirtualFile): String {
        val name = StringUtil.escapeXmlEntities(child.name)
        val color =
            if (child.isDirectory) VcsStatusColor.forDirectory(project, child)
            else VcsStatusColor.forFile(project, child)
        color ?: return name
        return "<span style=\"color:#${ColorUtil.toHex(color)}\">$name</span>"
    }

    private fun viewerFor(file: VirtualFile, create: () -> EditorEx): EditorEx =
        recentEditors.getOrPut(file, create)

    private fun installEditor(created: EditorEx, truncated: Boolean) {
        editor = created
        panel.addToCenter(created.component)
        scrollable = created.scrollPane
        if (!truncated) return
        val note = JBLabel("Preview truncated")
        note.border = JBUI.Borders.empty(2, 8)
        panel.addToBottom(note)
    }

    private fun showLabel(text: String) {
        detachEditor()
        panel.removeAll()
        scrollable = null
        showCentered(text)
        panel.revalidate()
        panel.repaint()
    }

    private fun showCentered(text: String) {
        label.text = text
        panel.addToCenter(label)
    }

    /** The mounted viewer goes back to the cache, which owns it until the popup closes. */
    private fun detachEditor() {
        editor = null
    }

    private fun releaseEditors() {
        val factory = EditorFactory.getInstance()
        recentEditors.values.forEach { factory.releaseEditor(it) }
        recentEditors.clear()
        editor = null
    }

    companion object {
        const val MAX_BYTES = 100 * 1024
        const val MAX_LINES = 300
        const val MAX_DIR_ENTRIES = 100
        const val LOAD_DELAY_MS = 250
        const val MAX_CACHED_EDITORS = 4

        /**
         * Viewer over the file's own document. The document carries a PSI file, which is what lets the
         * code analyzer contribute semantic colors (soft keywords, annotations, declarations) on top of
         * the lexer colors.
         */
        fun sourceViewer(project: Project, file: VirtualFile, document: Document): EditorEx =
            configure(EditorFactory.getInstance().createEditor(document, project, file, true) as EditorEx, project, file)

        /** Viewer over a detached copy of the text, used when the file is too large to preview in full. */
        fun textViewer(project: Project, file: VirtualFile, text: String): EditorEx {
            val factory = EditorFactory.getInstance()
            val document = factory.createDocument(text)
            document.setReadOnly(true)
            return configure(factory.createViewer(document, project) as EditorEx, project, file)
        }

        private fun configure(created: EditorEx, project: Project, file: VirtualFile): EditorEx {
            created.setFile(file)
            created.highlighter =
                EditorHighlighterFactory.getInstance().createEditorHighlighter(project, file)
            created.settings.apply {
                isLineNumbersShown = false
                isFoldingOutlineShown = false
                isLineMarkerAreaShown = false
                isIndentGuidesShown = false
                isCaretRowShown = false
                additionalLinesCount = 0
            }
            return created
        }

        fun computeContent(project: Project, file: VirtualFile): Content {
            if (!file.isValid) return Content.Empty
            if (file.isDirectory) {
                val children = BrowseTree.visibleChildren(project, file)
                return Content.Directory(children.take(MAX_DIR_ENTRIES), children.size > MAX_DIR_ENTRIES)
            }
            if (file.fileType.isBinary) return Content.Binary(file.name, file.fileType.name, file.length)
            if (file.length <= MAX_BYTES) {
                FileDocumentManager.getInstance().getDocument(file)?.let { return Content.Source(it) }
            }
            val bytes = try {
                file.inputStream.use { it.readNBytes(MAX_BYTES + 1) }
            } catch (_: IOException) {
                return Content.Empty
            }
            val byteTruncated = bytes.size > MAX_BYTES
            val raw = String(if (byteTruncated) bytes.copyOf(MAX_BYTES) else bytes, file.charset)
            val normalized = StringUtil.convertLineSeparators(raw)
            val lines = normalized.lines()
            val lineTruncated = lines.size > MAX_LINES
            val text = if (lineTruncated) lines.take(MAX_LINES).joinToString("\n") else normalized
            return Content.Text(text, byteTruncated || lineTruncated)
        }
    }
}
