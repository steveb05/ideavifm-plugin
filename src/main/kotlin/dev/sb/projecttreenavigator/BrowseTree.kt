package dev.sb.projecttreenavigator

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

    fun createModel(project: Project, roots: List<VirtualFile>): DefaultTreeModel {
        val hiddenRoot = DefaultMutableTreeNode(NavigatorNodeData(null, "", true))
        for (root in roots) hiddenRoot.add(directoryNode(root))
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

    fun visibleChildren(project: Project, dir: VirtualFile): List<VirtualFile> {
        val index = ProjectFileIndex.getInstance(project)
        return dir.children
            .filter { it.isValid && !index.isExcluded(it) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    private fun directoryNode(dir: VirtualFile): DefaultMutableTreeNode {
        val node = DefaultMutableTreeNode(NavigatorNodeData(dir, dir.name, true))
        node.add(DefaultMutableTreeNode(PLACEHOLDER))
        return node
    }
}
