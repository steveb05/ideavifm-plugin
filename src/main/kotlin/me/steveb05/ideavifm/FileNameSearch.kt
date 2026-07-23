package me.steveb05.ideavifm

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ContentIterator
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.codeStyle.MinusculeMatcher
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.psi.search.GlobalSearchScope

class FileNameSearch(private val project: Project) {

    /**
     * A file matches when its name matches the query loosely, letter by letter, or when its path does, which
     * lets one query span folders and file name ("docbui" finds _DocsExtension/build.gradle.kts). Path
     * matching keeps the query's letters together in each segment, otherwise the scattered letters of a long
     * folder chain would match nearly anything. Name matches outrank folder ones.
     */
    fun search(rawQuery: String, scope: GlobalSearchScope, limit: Int = DEFAULT_LIMIT): SearchResult {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        val query = rawQuery.trim().trim('/')
        if (query.isEmpty()) return SearchResult(emptyList(), false)

        val index = ProjectFileIndex.getInstance(project)
        val base = project.guessProjectDir()
        val ranked = ArrayList<RankedFile>()

        index.iterateContent(
            ContentIterator { file ->
                ProgressManager.checkCanceled()
                if (file.isDirectory) return@ContentIterator true
                weigh(query, file, searchPath(file, base, index))?.let { ranked.add(RankedFile(file, it)) }
                true
            },
        ) { it.isDirectory || scope.contains(it) }

        ranked.sortByDescending { it.weight }
        if (ranked.size > limit) return SearchResult(ranked.subList(0, limit).toList(), true)
        return SearchResult(ranked, false)
    }

    /**
     * A query the user spelled with slashes is taken literally: it has to match the path. A plain query
     * matches either the file name, loosely letter by letter, or the path in word sized chunks.
     */
    private fun weigh(query: String, file: VirtualFile, path: String): Int? {
        if (query.contains('/')) {
            val matcher = loosePathMatcher(query)
            return if (matcher.matches(path)) matcher.matchingDegree(path) else null
        }
        val matcher = nameMatcher(query)
        if (matcher.matches(file.name)) return NAME_MATCH_BONUS + matcher.matchingDegree(file.name)
        val chunks = PathChunks.match(query, path) ?: return null
        return -chunks.size * CHUNK_PENALTY - path.length
    }

    companion object {
        const val DEFAULT_LIMIT = 1000
        private const val NAME_MATCH_BONUS = 100_000
        private const val CHUNK_PENALTY = 1000

        fun nameMatcher(query: String): MinusculeMatcher =
            NameUtil.buildMatcher(looseLetters(query.trim().trim('/').substringAfterLast('/'))).build()

        /** The path a query is matched against: relative to the project, or to the content root that holds it. */
        fun searchPath(project: Project, file: VirtualFile): String =
            searchPath(file, project.guessProjectDir(), ProjectFileIndex.getInstance(project))

        private fun searchPath(file: VirtualFile, base: VirtualFile?, index: ProjectFileIndex): String {
            base?.let { VfsUtilCore.getRelativePath(file, it) }?.let { return it }
            val root = index.getContentRootForFile(file) ?: return file.name
            val relative = VfsUtilCore.getRelativePath(file, root) ?: return file.name
            return "${root.name}/$relative"
        }

        private fun loosePathMatcher(query: String): MinusculeMatcher =
            NameUtil.buildMatcher(looseLetters(query)).withSeparators("/").build()

        private fun looseLetters(query: String): String =
            query.toCharArray().joinToString(separator = "*", prefix = "*", postfix = "*")
    }
}
