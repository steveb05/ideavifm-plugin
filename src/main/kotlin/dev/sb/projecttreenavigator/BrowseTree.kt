package dev.sb.projecttreenavigator

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

data class NavigatorNodeData(
    val file: VirtualFile?,
    val name: String,
    val isDirectory: Boolean,
    val weight: Int = 0,
)

object BrowseTree {

    private val PLACEHOLDER = NavigatorNodeData(null, "loading", false)
    private const val DOT_WALK_CAP = 32

    fun createSubtreeModel(project: Project, base: VirtualFile): DefaultTreeModel {
        val hiddenRoot = DefaultMutableTreeNode(NavigatorNodeData(base, base.name, true))
        for (child in visibleChildren(project, base)) {
            if (child.isDirectory) hiddenRoot.add(directoryNode(child))
            else hiddenRoot.add(DefaultMutableTreeNode(NavigatorNodeData(child, child.name, false)))
        }
        return DefaultTreeModel(hiddenRoot)
    }

    fun isLoaded(node: DefaultMutableTreeNode): Boolean =
        node.childCount != 1 ||
            (node.firstChild as DefaultMutableTreeNode).userObject !== PLACEHOLDER

    fun loadChildren(project: Project, model: DefaultTreeModel, node: DefaultMutableTreeNode) {
        if (isLoaded(node)) return
        val dir = (node.userObject as NavigatorNodeData).file ?: return
        node.removeAllChildren()
        for (child in visibleChildren(project, dir)) {
            if (child.isDirectory) node.add(directoryNode(child))
            else node.add(DefaultMutableTreeNode(NavigatorNodeData(child, child.name, false)))
        }
        model.nodeStructureChanged(node)
    }

    fun visibleChildren(project: Project, dir: VirtualFile): List<VirtualFile> =
        ReadAction.compute<List<VirtualFile>, RuntimeException> {
            val index = ProjectFileIndex.getInstance(project)
            val hideDots = NavigatorSettings.getInstance().hideDotFiles
            dir.children
                .filter { it.isValid && !index.isExcluded(it) && !(hideDots && it.name.startsWith(".")) }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        }

    fun hiddenByDotRule(project: Project, file: VirtualFile): Boolean {
        if (!NavigatorSettings.getInstance().hideDotFiles) return false
        val index = ProjectFileIndex.getInstance(project)
        var current: VirtualFile? = file
        var depth = 0
        while (current != null && depth < DOT_WALK_CAP) {
            if (index.getContentRootForFile(current) == current) return false
            if (current.name.startsWith(".")) return true
            current = current.parent
            depth++
        }
        return false
    }

    private fun directoryNode(dir: VirtualFile): DefaultMutableTreeNode {
        val node = DefaultMutableTreeNode(NavigatorNodeData(dir, dir.name, true))
        node.add(DefaultMutableTreeNode(PLACEHOLDER))
        return node
    }
}
