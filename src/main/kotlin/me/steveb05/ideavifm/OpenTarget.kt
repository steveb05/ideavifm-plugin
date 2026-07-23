package me.steveb05.ideavifm

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * Which left pane entry the popup lands on when it opens, or when clearing a search has to restore the way it
 * looks on open. The file being edited wins, so you land where you are working. Failing that it falls
 * back to the entry the view was left on, then to the one that was already selected, then to the top.
 */
object OpenTarget {

    fun choose(
        entries: List<BaseEntry>,
        current: VirtualFile?,
        remembered: VirtualFile?,
        previous: BaseEntry?,
    ): BaseEntry? {
        current?.let { file -> containing(entries, file)?.let { return it } }
        remembered?.let { file -> entries.firstOrNull { it.file == file }?.let { return it } }
        previous?.let { entry -> entries.firstOrNull { it.file == entry.file }?.let { return it } }
        return entries.firstOrNull()
    }

    /** Which file the tree walks open to inside [base]: the first of the candidates that lives under it. */
    fun landing(base: VirtualFile, vararg candidates: VirtualFile?): VirtualFile? =
        candidates.filterNotNull()
            .firstOrNull { it.isValid && VfsUtilCore.isAncestor(base, it, false) }

    private fun containing(entries: List<BaseEntry>, file: VirtualFile): BaseEntry? =
        entries.filter { VfsUtilCore.isAncestor(it.file, file, false) }
            .maxByOrNull { it.file.path.length }
}
