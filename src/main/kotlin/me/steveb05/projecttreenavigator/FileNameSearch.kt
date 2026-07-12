package me.steveb05.projecttreenavigator

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ContentIterator
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileFilter
import com.intellij.psi.codeStyle.MinusculeMatcher
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.psi.search.GlobalSearchScope

class FileNameSearch(private val project: Project) {

    data class RankedFile(val file: VirtualFile, val weight: Int)
    data class Result(val files: List<RankedFile>, val truncated: Boolean)

    /**
     * Matches the query against the path of every file in scope, so a single query can span folders and the
     * file name: "docbui" finds _DocsExtension/build.gradle.kts. Files whose own name matches outrank files
     * that only matched through their folders.
     */
    fun search(rawQuery: String, scope: GlobalSearchScope, limit: Int = DEFAULT_LIMIT): Result {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        val query = rawQuery.trim().trim('/')
        if (query.isEmpty()) return Result(emptyList(), false)

        val pathMatcher = pathMatcher(query)
        val nameMatcher = nameMatcher(query)
        val base = project.guessProjectDir()
        val ranked = ArrayList<RankedFile>()

        ProjectFileIndex.getInstance(project).iterateContent(
            ContentIterator { file ->
                ProgressManager.checkCanceled()
                if (file.isDirectory) return@ContentIterator true
                val path = searchPath(file, base)
                if (pathMatcher.matches(path)) {
                    val bonus = if (nameMatcher.matches(file.name)) NAME_MATCH_BONUS else 0
                    ranked.add(RankedFile(file, pathMatcher.matchingDegree(path) + bonus))
                }
                true
            },
            VirtualFileFilter { it.isDirectory || scope.contains(it) },
        )

        ranked.sortByDescending { it.weight }
        if (ranked.size > limit) return Result(ranked.subList(0, limit).toList(), true)
        return Result(ranked, false)
    }

    companion object {
        const val DEFAULT_LIMIT = 1000
        private const val NAME_MATCH_BONUS = 100_000

        fun nameMatcher(query: String): MinusculeMatcher =
            NameUtil.buildMatcher(fuzzyPattern(query.trim().trim('/').substringAfterLast('/'))).build()

        fun searchPath(file: VirtualFile, base: VirtualFile?): String =
            base?.let { VfsUtilCore.getRelativePath(file, it) } ?: file.path

        private fun pathMatcher(query: String): MinusculeMatcher =
            NameUtil.buildMatcher(fuzzyPattern(query)).withSeparators("/").build()

        private fun fuzzyPattern(query: String): String =
            query.toCharArray().joinToString(separator = "*", prefix = "*", postfix = "*")
    }
}
