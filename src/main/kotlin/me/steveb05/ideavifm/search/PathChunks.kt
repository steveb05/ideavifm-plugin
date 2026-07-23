package me.steveb05.ideavifm.search

import com.intellij.openapi.util.TextRange

/**
 * Matches a query against a path the way a fuzzy finder does, with one restriction that keeps long folder
 * chains from matching everything: the query's letters may jump from one place to another, but every jump
 * has to land on the start of a word (a path segment, a camel hump, or after a separator such as an
 * underscore, hyphen or dot).
 * So "docbui" matches _DocsExtension/build.gradle.kts as "Doc" plus "bui", while the scattered d, o and c of
 * adapters/loot/config never form a chunk and that path does not match at all.
 */
object PathChunks {

    private const val SEPARATORS = "/\\_-. "

    fun matches(query: String, text: String): Boolean = match(query, text) != null

    /** The matched ranges in [text], in order, or null when the query does not match. */
    fun match(query: String, text: String): List<TextRange>? {
        if (query.isEmpty()) return null
        val failed = HashSet<Long>()
        return match(query.lowercase(), text, 0, 0, failed)
    }

    private fun match(query: String, text: String, queryAt: Int, textAt: Int, failed: HashSet<Long>): List<TextRange>? {
        if (queryAt == query.length) return emptyList()
        val key = queryAt.toLong() shl 32 or textAt.toLong()
        if (!failed.add(key)) return null

        for (start in textAt until text.length) {
            if (!isWordStart(text, start)) continue
            if (text[start].lowercaseChar() != query[queryAt]) continue
            var longest = 0
            while (queryAt + longest < query.length &&
                start + longest < text.length &&
                text[start + longest].lowercaseChar() == query[queryAt + longest]
            ) {
                longest++
            }
            for (length in longest downTo 1) {
                val rest = match(query, text, queryAt + length, start + length, failed) ?: continue
                return listOf(TextRange(start, start + length)) + rest
            }
        }
        return null
    }

    private fun isWordStart(text: String, index: Int): Boolean {
        if (index == 0) return true
        val previous = text[index - 1]
        if (previous in SEPARATORS) return true
        val current = text[index]
        return current.isUpperCase() && !previous.isUpperCase() || current.isDigit() && !previous.isDigit()
    }
}
