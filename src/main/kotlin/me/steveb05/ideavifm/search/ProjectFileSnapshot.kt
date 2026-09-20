package me.steveb05.ideavifm.search

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.util.concurrency.AppExecutorUtil
import me.steveb05.ideavifm.tree.BrowseTree

/**
 * Every file the navigator draws beside the path a query is matched against, so that a keystroke matches
 * an array already in memory. Reading the file system again for each letter typed is what made a search on a
 * large project take seconds: the walk itself, a relative path built per file and a matcher built per file,
 * all repeated from the first letter on.
 *
 * Creating a file appends to what is held, and deleting one leaves an entry the search skips as invalid. A
 * rename or a move rewrites the paths of a whole subtree, so it marks what is held stale and the next search
 * reads the project again.
 */
@Service(Service.Level.PROJECT)
class ProjectFileSnapshot(private val project: Project) : Disposable {

    class Files(val files: Array<VirtualFile>, val paths: Array<String>) {
        val size: Int get() = files.size
    }

    @Volatile
    private var held: Files? = null

    @Volatile
    private var stale = false

    /** Set while the project is being read: what changes during the walk is not what the walk saw. */
    @Volatile
    private var walking = false

    private val appended = ArrayList<Pair<VirtualFile, String>>()

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) = absorb(events)
            },
        )
        connection.subscribe(
            ModuleRootListener.TOPIC,
            object : ModuleRootListener {
                override fun rootsChanged(event: ModuleRootEvent) {
                    stale = true
                }
            },
        )
    }

    /** Whether a search can run without reading the whole project first. */
    fun isReady(): Boolean = held != null && !stale

    /** What the walk leaves out is a setting, so changing it makes the next search read the project again. */
    fun invalidate() {
        stale = true
    }

    /** Reads the project ahead of the first keystroke, so that typing never waits for the walk. */
    fun prepare() {
        if (isReady()) return
        ReadAction.nonBlocking<Files> { snapshot() }
            .expireWith(this)
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    fun snapshot(): Files {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        val current = held
        if (current == null || stale) return rebuild()
        val extra = drainAppended()
        if (extra.isEmpty()) return current
        val grown = grow(current, extra)
        held = grown
        return grown
    }

    override fun dispose() {
        held = null
    }

    private fun rebuild(): Files {
        val base = project.guessProjectDir()
        val index = ProjectFileIndex.getInstance(project)
        val files = ArrayList<VirtualFile>(INITIAL_CAPACITY)
        val paths = ArrayList<String>(INITIAL_CAPACITY)
        drainAppended()
        stale = false
        walking = true
        val reachable = HashMap<VirtualFile, Boolean>()
        try {
            index.iterateContent(
                { file ->
                    ProgressManager.checkCanceled()
                    if (!file.isDirectory) {
                        files.add(file)
                        paths.add(SearchPath.of(file, base, index))
                    }
                    true
                },
                { file -> isShown(file, reachable) },
            )
        } finally {
            walking = false
        }
        val built = Files(files.toTypedArray(), paths.toTypedArray())
        held = built
        return built
    }

    /**
     * The folders above a file say as much about it as the file does, and every file in a folder shares that
     * answer, so the walk works it out once per folder rather than once per file.
     */
    private fun isShown(file: VirtualFile, reachable: MutableMap<VirtualFile, Boolean>): Boolean {
        if (!BrowseTree.isNavigableItself(project, file)) return false
        val parent = file.parent ?: return true
        return reachable.getOrPut(parent) { BrowseTree.isNavigable(project, parent) }
    }

    private fun grow(current: Files, extra: List<Pair<VirtualFile, String>>): Files {
        val files = ArrayList<VirtualFile>(current.size + extra.size)
        val paths = ArrayList<String>(current.size + extra.size)
        files.addAll(current.files.asList())
        paths.addAll(current.paths.asList())
        for ((file, path) in extra) {
            files.add(file)
            paths.add(path)
        }
        return Files(files.toTypedArray(), paths.toTypedArray())
    }

    private fun absorb(events: List<VFileEvent>) {
        if (held == null) return
        if (walking) {
            stale = true
            return
        }
        for (event in events) {
            when (event) {
                is VFileMoveEvent, is VFilePropertyChangeEvent -> {
                    stale = true
                    return
                }

                is VFileCreateEvent -> append(event.file)
                is VFileCopyEvent -> append(event.findCreatedFile())
                else -> Unit
            }
        }
    }

    private fun append(file: VirtualFile?) {
        if (file == null || file.isDirectory || !file.isValid) return
        if (!ProjectFileIndex.getInstance(project).isInContent(file)) return
        if (!BrowseTree.isNavigable(project, file)) return
        synchronized(appended) {
            if (appended.size >= MAX_APPENDED) {
                appended.clear()
                stale = true
                return
            }
            appended.add(file to SearchPath.of(project, file))
        }
    }

    private fun drainAppended(): List<Pair<VirtualFile, String>> = synchronized(appended) {
        if (appended.isEmpty()) return emptyList()
        val drained = ArrayList(appended)
        appended.clear()
        drained
    }

    companion object {
        private const val INITIAL_CAPACITY = 8192

        /** Past this many creations, reading the project again is cheaper than growing the arrays one by one. */
        private const val MAX_APPENDED = 2000

        fun getInstance(project: Project): ProjectFileSnapshot =
            project.getService(ProjectFileSnapshot::class.java)
    }
}
