# Project Tree Navigator

IntelliJ IDEA plugin: a keyboard driven two pane popup that shows the
project file tree, filtered in place with Search Everywhere style
matching, plus an optional ranger style preview pane. The left pane
lists the top level entries of the active scope, each followed by its
indented children when enabled; the right pane shows the selected
entry's subtree. Icons come from the IDE's icon providers, so icon
packs such as Atom Material apply. Dot files are hidden and single
child folder chains are compacted by default, both togglable.

## Usage

Press `Ctrl+Alt+E` (remappable: Settings, Keymap, "Project Tree Navigator").

| Key | Action |
| --- | --- |
| type | Search the whole scope; the left pane shows per entry match counts, the right pane shows the selected entry's matches |
| `Alt+J` / `Alt+K` | Move the left pane selection; the right pane refills live |
| `Ctrl+J` / `Ctrl+K` | Move the right pane selection |
| `Down` / `Up` | Move the active pane selection; in the preview, scroll one line |
| `Ctrl+H` (`Left` when query empty) | Collapse or walk to the parent; at a top level row, cross into the left pane; from the preview, return to the tree |
| `Ctrl+L` (`Right` when query empty) | Expand the folder; from the left pane, enter the right pane; on a file with the preview shown, focus the preview |
| `Enter` | Open file, toggle folder, or enter the right pane from a left pane folder |
| `Ctrl+Enter` | Zoom: the selected folder becomes the base of both panes |
| `Backspace` (empty query) | Zoom out |
| `Alt+P` | Toggle the preview pane |
| `Ctrl+Period` | Toggle dot file hiding |
| `Alt+C` | Toggle showing only files with VCS changes |
| `Ctrl+E` / `Ctrl+Y` | Scroll the preview one line |
| `Ctrl+D` / `Ctrl+U` | Scroll the preview half a page |
| `Alt+Insert` | Open the IDE New menu for the selected folder (file templates included) |
| `Tab` / `Shift+Tab` | Cycle scope: Project, Module, Folder, custom scopes |
| `Esc` | Close |

Movement keys make their pane the active one; `Enter`, zoom and the
preview follow the pane you last moved. The active pane carries a
focus colored outline. Module scope groups Gradle source set modules
(`main`, `test` and friends) into one view, so test and resource
folders show up. File and folder names carry the IDE's VCS status
colors (modified, added, untracked, ignored), and folders containing
changes are tinted like modified files; `Alt+C` narrows both panes to
changed files only, composing with search, scopes and zoom.

## Settings

Settings, Tools, Project Tree Navigator: hide dot files (default on),
compact folder chains (default on), left pane children of top level
folders with or without loose files (default on), preview pane
(default off). Every popup command above appears with its default
shortcut in Settings, Keymap under "Project Tree Navigator";
assignments there win, and removing a shortcut in the keymap disables
it in the popup.

Custom scopes defined in Settings, Appearance and Behavior, Scopes appear
as extra chips after Folder.

## Build

- `./gradlew runIde` starts a sandbox IDE with the plugin.
- `./gradlew buildPlugin` creates the zip under `build/distributions`,
  installable via Settings, Plugins, Install Plugin from Disk.
- `./gradlew test` runs the test suite.
