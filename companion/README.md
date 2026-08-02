# MCMarkings Companion

A client-side Fabric mod that turns any folder of images into an in-game browser,
layer editor and publisher for the
[ImageFrame](https://modrinth.com/plugin/imageframe) server plugin.

Instead of finding an image, working out how many item frames it needs, and typing
a `/imageframe create` command with a hand-built URL, you search your images in
game, click one, and the mod issues the command with a correctly pinned URL and
hands you the right number of invisible frames.

You can also make the image in the first place: compose one from layers, text and
shapes, or run a script that draws it, then save it into the repository and put it
on a wall without leaving the game.

**It is not about road signs.** This repository happens to hold UK road signs and
its generator scripts draw them, but the mod knows nothing about that. Point it at
photographs, pixel art, maps, logos, or anything else in PNG form. Image metadata,
fonts, the scripts that draw new images and the folder layout are all the
repository's business, not the mod's.

It is **client-side only**. Nothing is installed on the server. Everything it
does goes through commands you are already allowed to run.

---

## Requirements

| Thing | Version |
| --- | --- |
| Minecraft | 26.1.2 |
| Fabric Loader | 0.19.3 or newer |
| Java | 25 (mandatory, not optional) |
| Server-side | ImageFrame, with the standard player command set |

Rhino, fabric-gui-imgui and the ImGui natives are all bundled inside the jar.
There is nothing else to install.

## Build

```sh
cd companion
./gradlew build
```

The jar lands in `build/libs/`. Copy it into your `mods` folder.

Gradle 9.5.1 comes from the wrapper. Do not try to build with the Gradle on your
`PATH` unless it is 9.5+ running on Java 25.

## Configuration

Written to `<minecraft>/config/mcmarkings.json` on first run.

| Key | Default | What it does |
| --- | --- | --- |
| `repositories` | empty | Folders the mod reads from, all managed from the GUI, including the two per-repository URL overrides below |
| `commandAlias` | `imageframe` | Command root without the slash; set to `frame` if your server rebinds it |
| `fontSearchPaths` | per platform | Extra folders to scan for fonts, on top of the ones the system already knows |
| `exportPixelsPerFrame` | `256` | Export resolution per map frame |
| `glowingFrames` | `true` | Ask for glowing invisible frames rather than plain |
| `commandsPerSecond` | `2.0` | Command rate limit |
| `generatedDirectory` | `generated` | Where placed images are written inside the repository before being committed |
| `generatorDirectory` | `generators` | Where the mod looks for generator scripts |
| `ignoredDirectories` | `node_modules`, `build`, `target`, `out`, `dist` | Folder names never walked when scanning, editable in Settings; dot-folders are always skipped |

Everything in that table except the repository list is editable from the
**Settings** tab, which also shows where the file lives. `templates/` is not
configurable: the editor reads and writes it beside the images.

**One key to remember: `M`.** Rebindable in Minecraft's own controls screen.
Everything else has a route through the interface, so nothing below is required.

| Keys | What |
| --- | --- |
| `M` | Open and close the window |
| `Ctrl+P` | Command palette: search everything the window and the visible tab can do |
| `Ctrl+1` .. `Ctrl+6` | Jump to a tab |
| `Ctrl+N` / `Ctrl+O` / `Ctrl+S` | In the editor: new, open, save |
| `Ctrl+Z` / `Ctrl+Shift+Z` | Undo, redo |
| `Ctrl+D`, `Delete`, `Ctrl+G`, `Ctrl+A` | Duplicate, delete, group, select all |
| `Ctrl+C` / `Ctrl+X` / `Ctrl+V` | Copy, cut and paste layers, including between documents |
| `Tab` / `Shift+Tab` | Step the selection through the layer stack |
| `Ctrl+Up` / `Ctrl+Down` | Move the selected layer one place through the stack |
| `Ctrl+Shift+Up` / `Ctrl+Shift+Down` | Bring to front, send to back |
| Arrow keys | Nudge the selection; hold `Shift` for a larger step |

Numbers in the editor's properties can be typed rather than dragged: hold
control and click one. That is ImGui's own behaviour and the only way to enter
an exact value without a steady hand.

Everything except the editor's canvas can also be reached with the keyboard
alone: arrow keys move between controls, Space and Enter activate them, and the
focused control carries a bright ring. Navigation is deliberately off in the
editor, which binds Tab and the arrows itself and can already be driven from
the keyboard.

The palette is the honest answer to "what can this do". It lists everything,
including things that are unavailable right now and why, so a missing entry
always means a wrong search rather than a state you cannot see.

On a first run the window shows a setup panel rather than tabs, and adding a
folder replaces it with the tabs on the next frame. There is nothing to edit by
hand: repositories are added, switched, renamed and removed entirely from the
GUI, and you can have as many as you like.

Repositories are opened on the first keypress rather than at startup, so a folder
that has been moved can be pointed somewhere new without restarting the game.

## The tabs

| Tab | For |
| --- | --- |
| **Browse** | Search the repository's images, see the frame size each one wants, place one, or drop it on the editor's canvas |
| **Editor** | Compose from layers: images, text and shapes, with snapping, alignment, groups, and full styling |
| **Generate** | Run a generator script, fill in its parameters, and either place the result or open it in the editor as layers |
| **Placed** | Everything you have already put on a wall: refresh one after its image changed, reopen it in the editor, ask for the map or its frames again, or delete it |
| **Repositories** | Add, switch, rename, repoint or forget a folder |
| **Settings** | Command name, send rate, export resolution, font folders, and which folder names are never scanned |

Everything lives in one window. No tab replaces the others or hides the tab bar.

## The editor

The mouse does more than it looks like: drag empty canvas to select several
layers, hold control to add one, scroll to zoom, right-drag to pan, double-click
a layer's name to rename it, drag a row to reorder. **Canvas controls** in the
palette lists all of it.

Layers are images, text, shapes, or groups of those. Dragging snaps to the canvas
edges and centre, to the frame cell boundaries, and to other layers, with guides
drawn while you drag; hold `Alt` to suspend it. Alignment and distribution act on
the selection, and a drag is one undo rather than one per frame.

Views worth knowing: **zoom to selection** fills the canvas with whatever is
selected, and **view at map resolution** shows the sign at the 128 pixels per
frame a wall really has, which is the only way to tell before placing it whether
small text survives.

Templates can be deleted from the same list they are opened from, which matters
more than it sounds: without it the list only ever grows and every experiment
stays in the way of the things worth keeping.

**Saving** writes into the repository's `templates/` folder. That format is the
same one the editor reads, so a saved document is a template and a template is a
saved document: there is no second format and anyone who clones the repository
gets them. **Place as a map** renders at full size, commits, pushes and issues the
ImageFrame command, and writes the document alongside the PNG so the sign on the
wall can be reopened and edited later rather than only looked at.

Work in progress is snapshotted to the config directory every few seconds and
offered back if the game does not come back. It is never committed, and an
explicit save clears it, so it only ever describes work that would otherwise be
gone.

## How it works

**Pinned URLs.** ImageFrame fetches images server-side over HTTP, so every image
has to be reachable at a URL. The mod always builds
`raw.githubusercontent.com/<slug>/<commit-sha>/<path>` against a commit, never a
branch. Branch URLs are cached for around five minutes, so a freshly pushed image
would come back as the previous version or a 404.

**Pull and refresh.** ImageFrame only re-fetches when told to. The Pull button
runs `git pull`, diffs the old and new HEAD, and for every changed PNG that this
client has previously turned into a map, issues an `/imageframe refresh` with a
newly pinned URL. That mapping lives in a small registry file, because the
repository knows nothing about ImageFrame map names and the server knows nothing
about the repository.

**Forges other than GitHub.** The URL is worked out from the origin remote, and
GitHub, GitLab and Gitea or Forgejo are recognised. When a remote cannot be read
well enough, the **Repositories** tab has a folded-away *URLs* section per
repository with two overrides: a slug (`owner/repo`) and a raw URL template
taking `{slug}`, `{commit}`, `{path}` and `{host}`. Setting either reopens that
repository so it takes effect at once. Most repositories never need them.

**Frame grids.** Maps are 128px squares, so an image's shape decides how many
frames it wants. That 128 is also the resolution a sign actually has on a wall,
whatever `exportPixelsPerFrame` says: exporting at 256 buys a better downsample,
not a sharper sign. The editor's **View at map resolution** shows it at the
density it will really have, which is the only way to tell before placing it
whether small text survives. The recommender scores every grid up to 8x8 by aspect error and
prefers the smallest grid whose distortion is imperceptible, since a wall of
frames is expensive to build. A 1x1 grid gets a plain map item; anything larger
is requested with `combined` so it arrives as one placeable item.

**Generators.** Parameterised images are JavaScript, run on Rhino, and live in
the repository's own `generators/` folder rather than inside this mod, so adding
a new kind of image is a commit to that repository rather than a rebuild of the
mod. Scripts get a small drawing API, can use any font installed on the machine,
and can composite existing repository PNGs. The scripts in
[`../generators/`](../generators/README.md) draw road signs because that is what
this repository is for; yours would draw whatever you like.

A script that also defines `document(params)` returns a layer tree rather than
only drawing, and the Generate tab will then offer to open its output in the
editor. That is the difference between a generator being a starting point and
being a dead end: without it, a result that is nearly right can only be fixed by
editing the script.

**Git.** Placing an image writes the PNG, commits **only that file**, and then
pushes the branch. The commit is narrow on purpose, because the working tree may
hold edits you are midway through and staging those would be theft. The push is
not narrow and cannot be: `git push` sends the branch, so placing one sign also
sends every other local commit you have. The interface says so wherever it can
be pressed, because it is not something to discover afterwards.

Pushing at all is necessary: the server fetches the image over HTTP, so it has to
exist at a URL before a map can be made from it. The mod **never reads or writes
git configuration** at any scope. If a commit fails because identity or
credentials are missing, it shows you git's own error and stops rather than
quietly patching your setup.

## Running under a Flatpak launcher

Prism Launcher installed as a Flatpak is sandboxed, which costs you three things.
Two are permissions and one is not.

**The repository and your fonts are invisible** until you grant access:

```sh
flatpak override --user --filesystem=/path/to/your/repo org.prismlauncher.PrismLauncher
flatpak override --user --filesystem=~/.local/share/fonts:ro org.prismlauncher.PrismLauncher
```

Undo with `flatpak override --user --reset org.prismlauncher.PrismLauncher`.

**There is no `git` binary in the Flatpak runtime**, and no permission adds one.
Two things follow.

The read path does not use git at all: HEAD, the current branch and the origin URL
are read straight out of `.git` as files, so browsing, pinning URLs, creating maps
and getting frames work in a sandbox with no git anywhere.

**Pull** and placing a map genuinely need it, so inside a sandbox the mod
hands those to the host through `flatpak-spawn --host`. That needs one more grant:

```sh
flatpak override --user --talk-name=org.freedesktop.Flatpak org.prismlauncher.PrismLauncher
```

Be clear about what this is. It lets the sandboxed launcher run arbitrary commands
on your host, which is effectively an escape from the sandbox for Prism and every
mod in it. It is the price of publishing from inside a Flatpak. If you would rather
not pay it, leave it ungranted: everything except Pull and placing still works, and
the mod tells you exactly what is missing instead of failing obscurely.

Git is invoked with `-C <repo>` rather than by working directory, because a host
process spawned out of the sandbox does not inherit the sandbox's working
directory.

**Push before you place.** URLs are pinned to the last commit the mod can see on
`origin`, not to your local HEAD, because ImageFrame fetches over HTTP from the
server and a commit sitting unpushed on your machine is a guaranteed 404. If a
image you just added does not appear, check you have pushed it.

## Layout

```
companion/src/main/java/dev/kierandrewett/mcmarkings/
  McMarkingsCompanion.java   entrypoint, keybind
  CompanionServices.java     composition root
  config/                    JSON settings
  core/                      RepoImage, GridSize, MapEntry and friends
  repo/                      repository scanning, git, raw URL building
  registry/                  which maps exist and what backs them
  imageframe/                command strings and the throttled sender
  render/                    grid recommender, image composition, font lookup
  doc/                       the document model: layers, snapping, history, templates
  command/                   commands, shortcuts and the palette's search
  js/                        Rhino generator runtime
  texture/                   runtime texture upload and caching
  gui/imgui/                 the window, its theme and the publish flow
  gui/imgui/panel/           one file per tab, plus the reusable browser and picker
```

The interface is entirely ImGui. There was a second toolkit until the ports
finished; nothing of it remains.

## Things that will catch you out

**Minecraft 26.1 is unobfuscated.** There is no Yarn, no mappings dependency and
no remap step. The Loom plugin must be `net.fabricmc.fabric-loom`; the old
`fabric-loom` id silently means remapping mode, which is wrong here. There is no
`modImplementation` either, because there is nothing to remap.

**`GuiGraphics` no longer exists.** It is `GuiGraphicsExtractor`, and screens
override `extractRenderState` rather than `render`. Essentially every Fabric GUI
tutorial online predates this.

**The ImGui backend does not implement `RendererHasTextures`.** So the font
scale cannot be changed by scaling the built-in atlas: doing that stretches a
13px bitmap and everything goes blurry. The atlas is rebuilt from a real TTF at
the size the game's GUI scale asks for, between frames.

**Nothing that touches a file may run while drawing.** Listing a folder, reading
an image header, writing the config: all of it happens on a virtual thread and
hops back. This has been got wrong twice here, and both times the symptom was
the whole game freezing rather than anything that looked like a bug in the mod.

**No font is bundled or required.** Generators name whatever font they want and
the mod resolves it against everything installed on the machine, matching on
family, face, PostScript or file name. A font that is not installed does not stop
a preview: the image renders in a substitute and the mod says which name it could
not resolve. The settings screen lists what is available.

**Never use `const` inside a loop body in a generator script.** Rhino does not
re-initialise it, so every iteration keeps the first iteration's value and every
line after the first is silently replaced by line one. Use `let`. This
is documented at length, with the failing and working forms, in
[`../generators/README.md`](../generators/README.md), and guarded by a test.

## Verifying against a real server

The build and unit tests cover everything that can be checked offline. These
need you in game:

1. Open the browser, search for an image, confirm thumbnails render with
   transparency rather than on black boxes.
2. Create a 1x1 image; confirm a plain map item arrives.
3. Create a larger one; confirm a single combined item arrives, not loose maps.
4. Get frames and place the image.
5. Change a PNG, push, then Pull in game and confirm the placed map updates.
6. Change a PNG, push, then use Refresh on that row in **Placed** and confirm the
   same thing happens for one map on its own.
7. Generate an image, place it, and confirm it appears on the wall.
8. Generate one, open it in the editor, move something, save it as a template,
   reopen it and confirm it comes back the same.
9. Press **Create map** twice on the same image. The second should say
   "Refreshing" and update it, not fail: ImageFrame rejects `create` for a name
   it already knows, and every path that builds one of those commands now picks
   refresh when the registry has seen the name.
10. Paste a **Copy command** for something already placed and confirm the same.
11. In **Placed**, use **Get map** after breaking the item, and **Delete** on
    something disposable. Delete cannot be undone from the mod.
12. With unpushed commits on the branch, confirm the publish hint says so, and
    that it stops saying so once you have placed something.
13. Move the config file aside and confirm the first-run panel appears in the
    window rather than as a separate screen.
