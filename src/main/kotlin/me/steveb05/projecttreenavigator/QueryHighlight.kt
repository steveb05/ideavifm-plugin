package me.steveb05.projecttreenavigator

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * Which letters of a row the query matched, so both panes can light them up. A query spans folders and file
 * names, so the letters are found on the row's path and then narrowed to the part the row actually shows:
 * with "docbui", _DocsExtension lights up "Doc" and build.gradle.kts lights up "bui". A folder only accounts
 * for the head of the query, so it is matched against the longest head it can carry.
 */
class QueryHighlight(rawQuery: String, private val base: VirtualFile?) {

    private val query = rawQuery.trim().trim('/')
    private val nameMatcher = FileNameSearch.nameMatcher(query)

    fun forFile(file: VirtualFile?, name: String): Iterable<TextRange>? {
        nameMatcher.matchingFragments(name)?.takeIf { it.isNotEmpty() }?.let { return it }
        return shownPart(file, name) { path -> PathChunks.match(query, path) }
    }

    fun forDirectory(file: VirtualFile?, name: String): Iterable<TextRange>? =
        shownPart(file, name) { path -> longestHeadOn(path) }

    /** A declared class is matched by its own name, the way the file names in the same row are. */
    fun forDeclaration(name: String): Iterable<TextRange>? =
        nameMatcher.matchingFragments(name)?.takeIf { it.isNotEmpty() }

    private fun longestHeadOn(path: String): List<TextRange>? {
        val shortest = minOf(MIN_HEAD, query.length)
        for (length in query.length downTo shortest) {
            PathChunks.match(query.take(length), path)?.let { return it }
        }
        return null
    }

    /** The matched letters that fall inside the piece of the path the row displays, in the row's own offsets. */
    private fun shownPart(
        file: VirtualFile?,
        name: String,
        matchOn: (String) -> List<TextRange>?,
    ): List<TextRange>? {
        if (query.contains('/')) return null
        val path = file?.let { pathOf(it) } ?: return null
        if (!path.endsWith(name)) return null
        val offset = path.length - name.length
        val matched = matchOn(path) ?: return null
        return matched
            .filter { it.startOffset >= offset }
            .map { TextRange(it.startOffset - offset, it.endOffset - offset) }
            .ifEmpty { null }
    }

    private fun pathOf(file: VirtualFile): String =
        base?.let { VfsUtilCore.getRelativePath(file, it) } ?: file.name

    private companion object {
        /** A single letter of the query would light up a letter in half the folders on screen. */
        const val MIN_HEAD = 2
    }
}
