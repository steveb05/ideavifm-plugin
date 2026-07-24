package me.steveb05.ideavifm.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
import com.intellij.ui.dsl.builder.toNullableProperty
import me.steveb05.ideavifm.search.DeclarationDepth
import me.steveb05.ideavifm.tree.TreeLevel

class NavigatorConfigurable : BoundConfigurable("IdeaVifm") {

    private fun levelRenderer(): SimpleListCellRenderer<TreeLevel?> =
        SimpleListCellRenderer.create("") { it?.label.orEmpty() }

    override fun createPanel() = panel {
        val settings = NavigatorSettings.getInstance()
        row {
            checkBox("Hide dot files and folders").bindSelected(settings::hideDotFiles)
        }
        row {
            checkBox("Compact single child folder chains").bindSelected(settings::compactFolders)
        }
        row("How far the tree opens:") {
            comboBox(TreeLevel.entries, levelRenderer())
                .bindItem(settings::treeOpenLevel.toNullableProperty())
                .comment(
                    "What the tree shows when the popup opens, and what Alt+R puts it back to. A root " +
                        "holding many modules reads better at nothing, with Alt+L opening a level at a time.",
                )
        }
        row("Inside a module, open:") {
            comboBox(TreeLevel.entries, levelRenderer())
                .bindItem(settings::moduleOpenLevel.toNullableProperty())
                .comment(
                    "The tree lists a module and stops there, so a root full of them reads as a list. How " +
                        "far one opens is read from inside it, which is where selecting it in the left pane " +
                        "or zooming into it puts you. The IDE says what a module is, taking the build " +
                        "tool's word for where a project sits.",
                )
        }
        row("Jumping to a folder opens:") {
            comboBox(TreeLevel.entries, levelRenderer())
                .bindItem(settings::jumpOpenLevel.toNullableProperty())
        }
        row {
            checkBox("Jumping to a folder closes the one you left")
                .bindSelected(settings::closeFolderOnJump)
                .comment(
                    "Ctrl+Shift+J and Ctrl+Shift+K walk between the top level folders, opening the one " +
                        "they land on. On: only one of them stays open at a time.",
                )
        }
        row {
            checkBox("Open a folder holding nothing but files when it is the only one there")
                .bindSelected(settings::openLoneFolder)
                .comment(
                    "The tree opens down to the folder the packages sit in, files at that level included, " +
                        "and leaves what each package holds closed. This is about the packages themselves: " +
                        "on, one holding nothing but files opens when no other folder sits beside it.",
                )
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
        lateinit var preview: Cell<JBCheckBox>
        row {
            preview = checkBox("Show preview pane").bindSelected(settings::showPreview)
        }
        indent {
            row {
                checkBox("Preview what the mouse points at")
                    .bindSelected(settings::previewOnHover)
                    .enabledIf(preview.selected)
                    .comment(
                        "Off: only the selected row is previewed, so a mouse crossing a pane on its way " +
                            "somewhere else leaves what you are reading alone.",
                    )
            }
        }
        buttonsGroup("What a query matches inside a file, beside its name and path:") {
            DeclarationDepth.entries.forEach { depth ->
                row { radioButton(depth.label, depth) }
            }
        }.bind(settings::declarationDepth)
        row {
            comment(
                "Top level declarations cover Kotlin extension functions and top level functions. All " +
                    "symbols adds every member of every class, which is thorough and noisy: a short query " +
                    "matches a great many of them. Alt+S cycles this inside the popup without changing " +
                    "the setting.",
            )
        }
        row {
            checkBox("Open the file you create")
                .bindSelected(settings::openCreatedFile)
                .comment(
                    "On: creating a file follows it into the editor and closes the popup. Off: the popup " +
                        "stays up with the new file selected. Shift+Alt+Insert creates the other way round.",
                )
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
                    "opens, Alt+L and Alt+H open and close a level at a time, Space or Alt+M marks rows for a bulk " +
                    "move or delete. New, rename, move, copy, paste and delete run the IDE actions " +
                    "under their own keymap shortcuts and are also on the right click menu. The " +
                    "navigator specific commands are remappable in Settings, Keymap.",
            )
        }
    }
}
