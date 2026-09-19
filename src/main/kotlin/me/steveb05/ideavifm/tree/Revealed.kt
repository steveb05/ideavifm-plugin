package me.steveb05.ideavifm.tree

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * The dot folders the view has been taken inside. Hiding them is right until you are in one: editing
 * .github/workflows/ci.yml, or zooming into .github, means what is in there is what you came for, so the
 * folder the walk entered stops hiding anything below it while the rest stay out of the way.
 */
@JvmInline
value class Revealed(val roots: Set<VirtualFile>) {

    fun covers(file: VirtualFile): Boolean = roots.any { VfsUtilCore.isAncestor(it, file, false) }

    companion object {
        val NONE = Revealed(emptySet())

        fun of(project: Project, vararg targets: VirtualFile?): Revealed {
            val roots = targets.filterNotNull().mapNotNullTo(HashSet()) { dotRootFor(project, it) }
            return if (roots.isEmpty()) NONE else Revealed(roots)
        }

        /**
         * The outermost dot folder between [target] and the content root that holds it. Revealing the
         * outermost one is what makes the whole way down to the target visible in one step.
         */
        private fun dotRootFor(project: Project, target: VirtualFile): VirtualFile? {
            val index = ProjectFileIndex.getInstance(project)
            var current: VirtualFile? = target
            var outermost: VirtualFile? = null
            var depth = 0
            while (current != null && depth < WALK_CAP) {
                if (index.getContentRootForFile(current) == current) return outermost
                if (current.isDirectory && current.name.startsWith(".")) outermost = current
                current = current.parent
                depth++
            }
            return outermost
        }

        private const val WALK_CAP = 32
    }
}
