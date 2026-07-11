package dev.sb.projecttreenavigator

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

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
        for (match in matches) {
            require(match.segments.isNotEmpty()) { "match must have at least one segment" }
            var current = root
            for (i in 0 until match.segments.size - 1) {
                current = current.folders.getOrPut(match.segments[i]) { MutableNode() }
            }
            current.files.add(PrunedTreeNode(match.segments.last(), match.payload, match.weight, emptyList()))
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
            .map { (name, child) -> PrunedTreeNode<T>(name, null, 0, freeze(child)) }
            .sortedBy { it.name.lowercase() }
        val files = node.files.sortedBy { it.name.lowercase() }
        return folders + files
    }
}

object EntryGrouping {

    fun <T> group(
        items: List<T>,
        entries: List<BaseEntry>,
        fileOf: (T) -> VirtualFile,
    ): LinkedHashMap<BaseEntry, List<T>> {
        val buckets = LinkedHashMap<BaseEntry, MutableList<T>>()
        for (entry in entries) buckets[entry] = mutableListOf()
        for (item in items) {
            val file = fileOf(item)
            val entry = entries.firstOrNull { VfsUtilCore.isAncestor(it.file, file, false) } ?: continue
            buckets.getValue(entry).add(item)
        }
        @Suppress("UNCHECKED_CAST")
        return buckets as LinkedHashMap<BaseEntry, List<T>>
    }
}
