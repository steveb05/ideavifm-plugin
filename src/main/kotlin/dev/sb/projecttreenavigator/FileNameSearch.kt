package dev.sb.projecttreenavigator

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.codeStyle.MinusculeMatcher
import com.intellij.psi.codeStyle.NameUtil

class FileNameSearch(private val project: Project) {

    data class RankedFile(val file: VirtualFile, val weight: Int)
    data class Result(val files: List<RankedFile>, val truncated: Boolean)

    fun search(rawQuery: String, scope: GlobalSearchScope, limit: Int = DEFAULT_LIMIT): Result {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        val query = rawQuery.trim().trimEnd('/')
        val nameQuery = query.substringAfterLast('/')
        if (nameQuery.isEmpty()) return Result(emptyList(), false)

        val nameMatcher = nameMatcher(nameQuery)
        val pathMatcher = if (query.contains('/')) pathMatcher(query) else null

        val matchingNames = ArrayList<String>()
        FilenameIndex.processAllFileNames({ name ->
            ProgressManager.checkCanceled()
            if (nameMatcher.matches(name)) matchingNames.add(name)
            true
        }, scope, null)

        val ranked = ArrayList<RankedFile>()
        for (name in matchingNames) {
            ProgressManager.checkCanceled()
            for (file in FilenameIndex.getVirtualFilesByName(name, scope)) {
                if (file.isDirectory) continue
                if (pathMatcher != null) {
                    if (!pathMatcher.matches(file.path)) continue
                    ranked.add(RankedFile(file, pathMatcher.matchingDegree(file.path)))
                } else {
                    ranked.add(RankedFile(file, nameMatcher.matchingDegree(name)))
                }
            }
        }
        ranked.sortByDescending { it.weight }
        if (ranked.size > limit) return Result(ranked.subList(0, limit).toList(), true)
        return Result(ranked, false)
    }

    companion object {
        const val DEFAULT_LIMIT = 1000

        fun nameMatcher(query: String): MinusculeMatcher =
            NameUtil.buildMatcher(fuzzyPattern(query.substringAfterLast('/'))).build()

        private fun pathMatcher(query: String): MinusculeMatcher =
            NameUtil.buildMatcher(fuzzyPattern(query)).withSeparators("/").build()

        private fun fuzzyPattern(query: String): String =
            query.toCharArray().joinToString(separator = "*", prefix = "*", postfix = "*")
    }
}
