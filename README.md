# IdeaVifm

A keyboard driven file navigator for IntelliJ IDEA, inspired by the
[vifm](https://vifm.info/) terminal file manager. It opens a popup over the
editor where you filter the project tree as you type, move between two panes
with vim style keys, and preview files without reaching for the mouse.

Open it with `Ctrl+Alt+E` (remappable under Settings, Keymap, "IdeaVifm").

## What it does

- **Filter as you type.** One query runs over the whole scope: folder names,
  file names, and the classes each file declares. The letters it matches light
  up in both panes.
- **Two panes.** The left pane lists the top level entries of the active scope;
  the right pane shows the selected entry's subtree. Moving in one pane refills
  the other live.
- **Preview.** An optional ranger style pane renders the selected file with the
  editor's own syntax colors.
- **Scopes and zoom.** Cycle Project, Module, Folder and any custom scope with
  `Tab`. Zoom into a folder to make it the root of both panes.
- **VCS aware.** Names carry the IDE's file status colors, and `Alt+C` narrows
  to changed files only.
- **File operations.** New, rename, move, copy, cut, paste and delete delegate
  to the IDE's own actions, so refactorings and safe delete behave as they do
  in the project view.

Icons come from the IDE's icon providers, so icon packs such as Atom Material
apply. Dot files are hidden and single child folder chains are compacted by
default; both toggle off.

## Keys

| Key | Action |
| --- | --- |
| type | Filter the scope. Matched letters light up; the left pane shows per entry match counts and grays out entries with none |
| `Alt+J` / `Alt+K` | Move the left pane selection; the right pane refills |
| `Ctrl+J` / `Ctrl+K` | Move the right pane selection |
| `Down` / `Up` | Move the active pane; in the preview, scroll one line |
| `Ctrl+H` (`Left` when the query is empty) | Collapse or walk to the parent; at a top level row, cross into the left pane; from the preview, return to the tree |
| `Ctrl+L` (`Right` when the query is empty) | Expand the folder; from the left pane, enter the right pane; on a file with the preview open, focus it |
| `Enter` | Open a file, toggle a folder, or enter the right pane from a left pane folder |
| `Ctrl+Enter` | Zoom in: the selected folder becomes the root of both panes |
| `Backspace` (empty query) | Zoom out |
| `Alt+P` | Toggle the preview pane |
| `Ctrl+Period` | Toggle dot files |
| `Alt+C` | Toggle changed files only |
| `Ctrl+F` | Return the caret to the search field |
| `Alt+R` | Collapse the tree back to how it opens |
| `Ctrl+E` / `Ctrl+Y` | Scroll the preview one line |
| `Ctrl+D` / `Ctrl+U` | Scroll the preview half a page |
| `Alt+Insert` | The IDE New menu for the selected folder |
| `Shift+Alt+Insert` | New, inverting the "open what you create" setting |
| `Space` (empty query) or `Alt+M` | Mark or unmark the row for a bulk operation |
| `Shift+F6` / `F6` | Rename / move (IDE refactoring, references updated) |
| `Ctrl+C` / `Ctrl+X` / `Ctrl+V` | Copy, cut, paste files |
| `Delete` (empty query) | Delete (safe delete when the IDE offers it) |
| right click | File menu: New, Rename, Move, Cut, Copy, Paste, Delete |
| `Tab` / `Shift+Tab` | Cycle scope: Project, Module, Folder, custom scopes |
| `Esc` | Close |

Movement keys make their pane the active one, and `Enter`, zoom and the preview
follow whichever pane you last moved. The active pane wears a colored outline.
Neither pane takes keyboard focus, so the caret stays in the search field and
you can keep typing after clicking a row; `Ctrl+F` brings it back if the preview
editor steals it. The left pane loops around its ends.

The popup opens on the file you are editing, walked open down to it. Clearing
the query, switching scope and zooming return to that same view; without the
current file in sight it falls back to wherever the view was left, then to the
selected row.

## Search

A query matches a file name loosely, letter by letter, or a path in word sized
chunks where every jump lands on the start of a word (a folder, a camel hump,
or a piece after `_`, `-` or `.`). So `docbui` finds
`_DocsExtension/build.gradle.kts` without dragging in every `build.gradle.kts`
whose path merely happens to hold a d, an o and a c. Spell a query with a slash
(`api/usrv`) to match the path literally. A file whose own name matches ranks
above one reached only through its folders.

A query also matches the classes, interfaces and objects a file declares, read
from the same index as Go to Class, so `bob` finds `People.kt` when it declares
`Bob`. The matched classes trail the file name in gray (`People.kt  Bob`), and
the preview and `Enter` open on the class rather than the top of the file. A
file reached only through a class ranks below the ones the query names and above
the ones matched through their folders. Single letter queries are left to file
names, since one letter matches a class in almost every file.

Module scope groups a module's Gradle source sets (`main`, `test` and friends)
into one view, so test and resource folders show up alongside `main`. Custom
scopes from Settings, Appearance and Behavior, Scopes appear as extra chips
after Folder.

## Preview

The preview opens the file's own document, so it gets the same colors as the
editor: lexer keywords plus everything the analyzer adds (soft keywords like
`private`, annotations, declarations and references). Files over 100 KB fall
back to a truncated, lexer only preview.

## Marks and file operations

File operations delegate to the IDE, so renaming a class runs the rename
refactoring and moving updates imports. They act on the marked rows, or on the
row under the cursor when nothing is marked, which is how a multi file move
works: mark with `Space`, then `F6`. Marks are bold with a leading `✱`; they
clear when the tree is rebuilt (new query, scope or zoom) or once an operation
finishes. `Ctrl+click` marks with the mouse.

Creating a file follows it into the editor and closes the popup. Turn off "open
what you create", or press `Shift+Alt+Insert` once, and the popup stays up with
the new file selected and the caret back in the search field.

## Settings

Settings, Tools, IdeaVifm:

- Hide dot files (default on).
- Compact single child folder chains (default on).
- Left pane shows the children of top level folders (default on).
- Preview pane (default off).
- Reopen on the last scope and zoom (default off; the scope, zoom and selection
  are remembered per project either way).

The IdeaVifm commands appear with their default shortcuts under Settings,
Keymap, "IdeaVifm"; assignments there win, and removing a shortcut disables it
in the popup. New, rename, move, cut, copy, paste and delete are the IDE's own
actions, so they follow whatever shortcuts you have already set for them.

## Build

- `./gradlew runIde` starts a sandbox IDE with the plugin.
- `./gradlew buildPlugin` writes the installable zip to `build/distributions`
  (Settings, Plugins, Install Plugin from Disk).
- `./gradlew test` runs the test suite.
