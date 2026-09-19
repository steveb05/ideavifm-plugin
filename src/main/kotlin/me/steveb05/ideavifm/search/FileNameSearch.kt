package me.steveb05.ideavifm.search

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
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
     *
     * The paths come from [ProjectFileSnapshot] rather than from the file system, and the matchers are built
     * once for the query rather than once per file: both are what a search over a large project was spending
     * its seconds on. [onPartial] is handed what has ranked so far every so often, on this thread, so a long
     * search fills the panes as it runs.
     */
    fun search(
        rawQuery: String,
        scope: GlobalSearchScope,
        limit: Int = DEFAULT_LIMIT,
        onPartial: (SearchResult) -> Unit = {},
    ): SearchResult {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        val query = rawQuery.trim().trim('/')
        if (query.isEmpty()) return SearchResult(emptyList(), false)

        val letters = query.lowercase()
        val pathMatcher = if (query.contains('/')) loosePathMatcher(query) else null
        val nameMatcher = if (pathMatcher == null) nameMatcher(query) else null
        val snapshot = ProjectFileSnapshot.getInstance(project).snapshot()
        val ranked = ArrayList<RankedFile>()
        var reportAt = System.nanoTime() + PARTIAL_INTERVAL_NANOS

        for (i in 0 until snapshot.size) {
            if ((i and CHECK_EVERY) == 0) {
                ProgressManager.checkCanceled()
                if (ranked.isNotEmpty() && System.nanoTime() >= reportAt) {
                    reportAt = System.nanoTime() + PARTIAL_INTERVAL_NANOS
                    onPartial(rank(ranked, limit))
                }
            }
            val path = snapshot.paths[i]
            if (!couldMatch(letters, path)) continue
            val file = snapshot.files[i]
            if (!file.isValid) continue
            val weight = weigh(query, file, path, nameMatcher, pathMatcher) ?: continue
            if (!scope.contains(file)) continue
            ranked.add(RankedFile(file, weight))
        }
        return rank(ranked, limit)
    }

    /**
     * A query the user spelled with slashes is taken literally: it has to match the path. A plain query
     * matches either the file name, loosely letter by letter, or the path in word sized chunks.
     */
    private fun weigh(
        query: String,
        file: VirtualFile,
        path: String,
        nameMatcher: MinusculeMatcher?,
        pathMatcher: MinusculeMatcher?,
    ): Int? {
        if (pathMatcher != null) {
            return if (pathMatcher.matches(path)) pathMatcher.matchingDegree(path) else null
        }
        requireNotNull(nameMatcher) { "a query without a slash is matched by name" }
        if (nameMatcher.matches(file.name)) return NAME_MATCH_BONUS + nameMatcher.matchingDegree(file.name)
        val chunks = PathChunks.match(query, path) ?: return null
        return -chunks.size * CHUNK_PENALTY - path.length
    }

    private fun rank(ranked: List<RankedFile>, limit: Int): SearchResult {
        val sorted = ranked.sortedByDescending { it.weight }
        if (sorted.size > limit) return SearchResult(sorted.subList(0, limit).toList(), true)
        return SearchResult(sorted, false)
    }

    companion object {
        const val DEFAULT_LIMIT = 1000
        private const val NAME_MATCH_BONUS = 100_000
        private const val CHUNK_PENALTY = 1000
        private const val CHECK_EVERY = 0x3FF
        private const val PARTIAL_INTERVAL_NANOS = 150_000_000L

        fun nameMatcher(query: String): MinusculeMatcher =
            NameUtil.buildMatcher(looseLetters(query.trim().trim('/').substringAfterLast('/'))).build()

        fun searchPath(project: Project, file: VirtualFile): String = SearchPath.of(project, file)

        /**
         * Whether the query's letters appear along the path at all, in order. Every way a query can match
         * needs that much, and most files fail it, so it stands in front of the matchers and the chunk walk
         * and keeps them off all but a handful of the project's files.
         */
        private fun couldMatch(letters: String, path: String): Boolean {
            var at = 0
            for (c in path) {
                if (c.lowercaseChar() != letters[at]) continue
                at++
                if (at == letters.length) return true
            }
            return false
        }

        private fun loosePathMatcher(query: String): MinusculeMatcher =
            NameUtil.buildMatcher(looseLetters(query)).withSeparators("/").build()

        private fun looseLetters(query: String): String =
            query.toCharArray().joinToString(separator = "*", prefix = "*", postfix = "*")
    }
}
