package me.steveb05.projecttreenavigator

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
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
        class Text(val text: String, val truncated: Boolean) : Content()
        class Binary(val name: String, val typeName: String, val length: Long) : Content()
        class Directory(val names: List<String>, val capped: Boolean) : Content()
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

    val component: JComponent = panel

    init {
        label.border = JBUI.Borders.empty(8)
        panel.border = PaneBorders.normal
    }

    fun attach(popup: JBPopup) {
        this.popup = popup
        alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, popup)
        Disposer.register(popup) { releaseEditor() }
        showLabel("No preview")
    }

    fun setTarget(file: VirtualFile?) {
        if (file == target) return
        target = file
        val activeAlarm = alarm ?: return
        activeAlarm.cancelAllRequests()
        activeAlarm.addRequest({ load(file) }, 150)
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
        scrollBar()?.let { it.value = it.value + delta * JBUI.scale(18) }
    }

    fun scrollHalfPage(delta: Int) {
        val active = editor
        if (active != null) {
            val model = active.scrollingModel
            val half = (model.visibleArea.height / 2).coerceAtLeast(active.lineHeight)
            model.scrollVertically((model.verticalScrollOffset + delta * half).coerceAtLeast(0))
            return
        }
        scrollBar()?.let { it.value = it.value + delta * (it.visibleAmount / 2).coerceAtLeast(JBUI.scale(18)) }
    }

    private fun scrollBar(): JScrollBar? = scrollable?.verticalScrollBar

    private fun load(file: VirtualFile?) {
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
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun apply(content: Content, file: VirtualFile) {
        releaseEditor()
        panel.removeAll()
        scrollable = null
        when (content) {
            is Content.Text -> {
                val factory = EditorFactory.getInstance()
                val document = factory.createDocument(content.text)
                document.setReadOnly(true)
                val created = factory.createViewer(document, project) as EditorEx
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
                editor = created
                panel.addToCenter(created.component)
                scrollable = created.scrollPane
                if (content.truncated) {
                    val note = JBLabel("Preview truncated")
                    note.border = JBUI.Borders.empty(2, 8)
                    panel.addToBottom(note)
                }
            }

            is Content.Binary -> showCentered(
                "<html><center>${StringUtil.escapeXmlEntities(content.name)}<br>" +
                    "${StringUtil.escapeXmlEntities(content.typeName)}, " +
                    StringUtil.formatFileSize(content.length) + "</center></html>",
            )

            is Content.Directory -> {
                val names = content.names.joinToString("<br>") { StringUtil.escapeXmlEntities(it) }
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

    private fun showLabel(text: String) {
        releaseEditor()
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

    private fun releaseEditor() {
        editor?.let { EditorFactory.getInstance().releaseEditor(it) }
        editor = null
    }

    companion object {
        const val MAX_BYTES = 100 * 1024
        const val MAX_LINES = 300
        const val MAX_DIR_ENTRIES = 100

        fun computeContent(project: Project, file: VirtualFile): Content {
            if (!file.isValid) return Content.Empty
            if (file.isDirectory) {
                val names = BrowseTree.visibleChildren(project, file).map { it.name }
                return Content.Directory(names.take(MAX_DIR_ENTRIES), names.size > MAX_DIR_ENTRIES)
            }
            if (file.fileType.isBinary) return Content.Binary(file.name, file.fileType.name, file.length)
            val bytes = try {
                file.inputStream.use { it.readNBytes(MAX_BYTES + 1) }
            } catch (e: IOException) {
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
