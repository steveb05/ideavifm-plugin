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
| type | Fuzzy search the whole scope: one query spans folders and file names (`docbui` finds `_DocsExtension/build.gradle.kts`) and the matched letters are lit up in both panes; the left pane shows per entry match counts and grays out the entries with none, the right pane shows the selected entry's matches |
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
| `Ctrl+F` | Put the caret back in the search field |
| `Alt+R` | Collapse the tree back to how it opens, open down to the first folders holding files |
| `Ctrl+E` / `Ctrl+Y` | Scroll the preview one line |
| `Ctrl+D` / `Ctrl+U` | Scroll the preview half a page |
| `Alt+Insert` | The IDE New menu for the selected folder (file templates included) |
| `Space` (empty query) or `Alt+M` | Mark or unmark the row; marked rows are bold and survive navigation |
| `Shift+F6` / `F6` | Rename / move the target (IDE refactoring, references are updated) |
| `Ctrl+C` / `Ctrl+X` / `Ctrl+V` | Copy, cut, paste files (empty query; paste falls back to text when the clipboard holds no files) |
| `Delete` (empty query) | Delete the target (safe delete when the IDE offers it) |
| right click | File menu: New, Rename, Move, Cut, Copy, Paste, Delete |
| `Tab` / `Shift+Tab` | Cycle scope: Project, Module, Folder, custom scopes |
| `Esc` | Close |

Movement keys make their pane the active one; `Enter`, zoom and the
preview follow the pane you last moved. The active pane carries a
focus colored outline. Neither pane takes keyboard focus, so the caret
stays in the search field and you can keep typing after clicking a row;
`Ctrl+F` brings it back if the preview editor took it. Movement in the
left pane loops around the ends of the list. While a search is running,
left pane entries with no matches are grayed out and behave as dead
rows: movement steps over them and they cannot be selected or clicked.
Clearing the query, switching scope and zooming all leave the same view
the popup opens with: the tree walked open down to the file you are
editing. Without that file in view it falls back to the entry the view
was left on, then to the one that was already selected. Toggles keep the
tree open where it was.

A query matches a file name loosely, letter by letter, or a path in
word sized chunks: the letters may jump between folders and the file
name, but each jump has to land on the start of a word (a folder, a
camel hump, a part after `_`, `-` or `.`). That is what keeps the
scattered d, o and c of `adapters/loot/config` from dragging in every
`build.gradle.kts`. Spell a query with a slash (`api/usrv`) to match
the path literally. Files whose own name matches rank above files
matched through their folders. Module scope groups Gradle source set modules
(`main`, `test` and friends) into one view, so test and resource
folders show up. File and folder names carry the IDE's VCS status
colors (modified, added, untracked, ignored) in both panes and in the
folder listing the preview shows. A folder that contains changes is
tinted like a modified file however deep they sit, so `src` and the
module root light up as well. `Alt+C` narrows both panes to changed
files only, composing with search, scopes and zoom.

The preview opens the file's own document, so it gets the same colors
as the editor: the lexer keywords plus everything the code analyzer
contributes (soft keywords such as `private`, annotations, declarations
and references). Files over 100 KB fall back to a truncated, lexer only
preview.

File operations delegate to the IDE, so renaming a class file runs the
rename refactoring and moving updates imports. They act on the marked
rows if there are any, otherwise on the row under the cursor, which is
how a multi file move works: mark with `Space`, then `F6`. Marks are
bold with a leading `✱` and the footer counts them; they clear when the
tree is rebuilt (new query, scope, zoom) or once an operation finishes.
`Ctrl+click` marks with the mouse.

## Settings

Settings, Tools, Project Tree Navigator: hide dot files (default on),
compact folder chains (default on), left pane children of top level
folders with or without loose files (default on), preview pane
(default off), and whether the popup opens on the last scope and zoom
(default off, so it opens in Project scope with no zoom; the scope, the
zoom and the selection are remembered per project either way). The navigator specific commands appear with their
default shortcuts in Settings, Keymap under "Project Tree Navigator";
assignments there win, and removing a shortcut in the keymap disables
it in the popup. New, rename, move, cut, copy, paste and delete are the
IDE actions themselves, so they follow the shortcuts you already have
for them and remapping them in the keymap changes them in the popup
too.

Custom scopes defined in Settings, Appearance and Behavior, Scopes appear
as extra chips after Folder.

## Build

- `./gradlew runIde` starts a sandbox IDE with the plugin.
- `./gradlew buildPlugin` creates the zip under `build/distributions`,
  installable via Settings, Plugins, Install Plugin from Disk.
- `./gradlew test` runs the test suite.
