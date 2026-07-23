package me.steveb05.ideavifm.tree

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import me.steveb05.ideavifm.scope.BaseEntry

data class PrunedMatch<T>(val segments: List<String>, val payload: T, val weight: Int)

class PrunedTreeNode<T>(
    val name: String,
    val payload: T?,
    val weight: Int,
    val children: List<PrunedTreeNode<T>>,
)

object PrunedTreeBuilder {

    fun <T> build(matches: List<PrunedMatch<T>>): List<PrunedTreeNode<T>> {
        val root = MutableNode<T>()
        for ((segments, payload, weight) in matches) {
            require(segments.isNotEmpty()) { "match must have at least one segment" }
            var current = root
            for (i in 0 until segments.size - 1) {
                current = current.folders.getOrPut(segments[i]) { MutableNode() }
            }
            current.files.add(PrunedTreeNode(segments.last(), payload, weight, emptyList()))
        }
        return freeze(root)
    }

    fun <T> compact(nodes: List<PrunedTreeNode<T>>): List<PrunedTreeNode<T>> = nodes.map { node ->
        if (node.payload != null) return@map node
        var name = node.name
        var current = node
        while (true) {
            val only = current.children.singleOrNull()?.takeIf { it.payload == null } ?: break
            name = name + "/" + only.name
            current = only
        }
        PrunedTreeNode(name, null, node.weight, compact(current.children))
    }

    private class MutableNode<T> {
        val folders = LinkedHashMap<String, MutableNode<T>>()
        val files = ArrayList<PrunedTreeNode<T>>()
    }

    private fun <T> freeze(node: MutableNode<T>): List<PrunedTreeNode<T>> {
        val folders = node.folders.entries
            .map { (name, child) -> PrunedTreeNode(name, null, 0, freeze(child)) }
            .sortedBy { it.name.lowercase() }
        val files = node.files.sortedBy { it.name.lowercase() }
        return folders + files
    }
}

object SubtreeMatches {

    fun <T> matchesUnder(items: List<T>, entry: BaseEntry, fileOf: (T) -> VirtualFile): List<T> =
        items.filter { VfsUtilCore.isAncestor(entry.file, fileOf(it), false) }

    fun <T> countsFor(
        items: List<T>,
        entries: List<BaseEntry>,
        fileOf: (T) -> VirtualFile,
    ): Map<BaseEntry, Int> = entries.associateWith { matchesUnder(items, it, fileOf).size }
}
