package me.steveb05.projecttreenavigator

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected

class NavigatorConfigurable : BoundConfigurable("Project Tree Navigator") {

    override fun createPanel() = panel {
        val settings = NavigatorSettings.getInstance()
        row {
            checkBox("Hide dot files and folders").bindSelected(settings::hideDotFiles)
        }
        row {
            checkBox("Compact single child folder chains").bindSelected(settings::compactFolders)
        }
        lateinit var children: Cell<JBCheckBox>
        row {
            children = checkBox("Show children of top level folders in the left pane")
                .bindSelected(settings::leftPaneChildren)
        }
        indent {
            row {
                checkBox("Include files among those children")
                    .bindSelected(settings::leftPaneChildFiles)
                    .enabledIf(children.selected)
            }
        }
        row {
            checkBox("Show preview pane").bindSelected(settings::showPreview)
        }
        row {
            checkBox("Open on the last scope and zoom")
                .bindSelected(settings::restoreLastView)
                .comment(
                    "Off: the popup opens in Project scope with no zoom. On: the scope and zoom come back " +
                        "as you left them. Either way it lands on the file you are editing, falling back to " +
                        "what you were last looking at.",
                )
        }
        row {
            comment(
                "Inside the popup: Ctrl+Period toggles dot files, Alt+P toggles the preview, " +
                    "Alt+C shows only files with VCS changes, Alt+R collapses the tree back to how it " +
                    "opens, Space or Alt+M marks rows for a bulk " +
                    "move or delete. New, rename, move, copy, paste and delete run the IDE actions " +
                    "under their own keymap shortcuts and are also on the right click menu. The " +
                    "navigator specific commands are remappable in Settings, Keymap.",
            )
        }
    }
}
