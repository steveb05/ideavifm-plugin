package me.steveb05.ideavifm.preview

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PreviewHighlightTest : BasePlatformTestCase() {

    fun testXmlPreviewIsHighlighted() {
        myFixture.addFileToProject("root/sample.xml", "<root attr=\"v\"><child/></root>")
        val result = lexPreview(myFixture.findFileInTempDir("root/sample.xml"))
        assertTrue("expected multiple token types, got ${result.tokenTypes}", result.tokenTypes.size > 1)
        assertTrue("expected colored attributes for xml", result.colored)
    }

    fun testKotlinPreviewIsHighlighted() {
        myFixture.addFileToProject(
            "root/sample.kt",
            "fun main() {\n    val greeting = \"hi\"\n    println(greeting)\n}\n",
        )
        val result = lexPreview(myFixture.findFileInTempDir("root/sample.kt"))
        assertTrue("expected multiple token types, got ${result.tokenTypes}", result.tokenTypes.size > 1)
        assertTrue("expected colored attributes for kotlin", result.colored)
    }

    fun testPreviewEditorCarriesTheFileSoTheAnalyzerCanRun() {
        myFixture.addFileToProject("root/annotated.kt", "@Deprecated(\"x\")\nprivate val answer = 42\n")
        val file = myFixture.findFileInTempDir("root/annotated.kt")
        val content = PreviewPanel.computeContent(project, file) as PreviewPanel.Content.Source
        val viewer = viewerFor(content, file)
        try {
            assertEquals(file, viewer.virtualFile)
            assertTrue(viewer.isViewer)
            assertTrue("the shared document must not be marked read only", content.document.isWritable)
            assertNotNull(PsiDocumentManager.getInstance(project).getPsiFile(content.document))
        } finally {
            EditorFactory.getInstance().releaseEditor(viewer)
        }
    }

    private class LexResult(val tokenTypes: Set<IElementType>, val colored: Boolean)

    private fun viewerFor(content: PreviewPanel.Content.Source, file: VirtualFile): EditorEx =
        PreviewPanel.sourceViewer(project, file, content.document)

    private fun lexPreview(file: VirtualFile): LexResult {
        val content = PreviewPanel.computeContent(project, file) as PreviewPanel.Content.Source
        val viewer = viewerFor(content, file)
        try {
            val types = mutableSetOf<IElementType>()
            var colored = false
            val iterator = viewer.highlighter.createIterator(0)
            while (!iterator.atEnd()) {
                types.add(iterator.tokenType)
                val attributes = iterator.textAttributes
                if (attributes != null && (attributes.foregroundColor != null || attributes.fontType != 0)) {
                    colored = true
                }
                iterator.advance()
            }
            return LexResult(types, colored)
        } finally {
            EditorFactory.getInstance().releaseEditor(viewer)
        }
    }
}
