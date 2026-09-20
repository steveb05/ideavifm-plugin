package me.steveb05.ideavifm.search

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Reads the project into [ProjectFileSnapshot] once the IDE has settled, so that the first query of the day
 * does not pay for the walk. It waits for indexing to finish rather than competing with it, and a popup opened
 * before then asks for the walk itself.
 */
class SnapshotWarmup : ProjectActivity {

    override suspend fun execute(project: Project) {
        DumbService.getInstance(project).runWhenSmart {
            if (!project.isDisposed) ProjectFileSnapshot.getInstance(project).prepare()
        }
    }
}
