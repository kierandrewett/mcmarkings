# MCMarkings Companion

A client-side Fabric mod that turns this repository into an in-game browser for
the [ImageFrame](https://modrinth.com/plugin/imageframe) server plugin.

Instead of finding a sign, working out how many item frames it needs, and typing
a `/imageframe create` command with a hand-built GitHub URL, you search the
repository in game, click the image, and the mod issues the command with a
correctly pinned URL and hands you the right number of invisible frames.

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

owo-lib, Rhino, fabric-gui-imgui and the ImGui natives are all bundled inside
the jar. There is nothing else to install.

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
| `repositories` | empty | Folders the mod reads from, all managed from the GUI |
| `commandAlias` | `imageframe` | Command root without the slash; set to `frame` if your server rebinds it |
| `fontSearchPaths` | `~/.local/share/fonts`, `/usr/share/fonts` | Where to look for the Transport typeface |
| `exportPixelsPerFrame` | `256` | Export resolution per map frame |
| `glowingFrames` | `true` | Ask for glowing invisible frames rather than plain |
| `commandsPerSecond` | `2.0` | Command rate limit |

**One key: `M`.** Rebindable in Minecraft's own controls screen. Everything else
is reached from the screens themselves, so there is nothing else to memorise.

On a first run `M` opens a setup screen that walks you through choosing a folder.
There is nothing to edit by hand: repositories are added, switched, renamed and
removed entirely from the GUI, and you can have as many as you like.

Repositories are opened on the first keypress rather than at startup, so a folder
that has been moved can be pointed somewhere new without restarting the game.

## How it works

**Pinned URLs.** ImageFrame fetches images server-side over HTTP, so every image
has to be reachable at a URL. The mod always builds
`raw.githubusercontent.com/<slug>/<commit-sha>/<path>` against a commit, never a
branch. Branch URLs are cached for around five minutes, so a freshly pushed sign
would come back as the previous version or a 404.

**Pull and refresh.** ImageFrame only re-fetches when told to. The Pull button
runs `git pull`, diffs the old and new HEAD, and for every changed PNG that this
client has previously turned into a map, issues an `/imageframe refresh` with a
newly pinned URL. That mapping lives in a small registry file, because the
repository knows nothing about ImageFrame map names and the server knows nothing
about the repository.

**Frame grids.** Maps are 128px squares, so an image's shape decides how many
frames it wants. The recommender scores every grid up to 8x8 by aspect error and
prefers the smallest grid whose distortion is imperceptible, since a wall of
frames is expensive to build. A 1x1 grid gets a plain map item; anything larger
is requested with `combined` so it arrives as one placeable item.

**Generators.** Parameterised signs are JavaScript, run on Rhino, and live in
[`../generators/`](../generators/README.md) rather than inside this mod, so
adding a sign type is a commit to the repository rather than a rebuild. Scripts
get a small drawing API and can composite existing repository PNGs, which is how
a direction sign gets a real roundel on it.

**Git.** Saving a generated sign writes the PNG, commits only that file, and
pushes, so the image has a URL the server can reach. The mod **never reads or
writes git configuration** at any scope. If a commit fails because identity or
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

**Pull** and **Save & publish** genuinely need it, so inside a sandbox the mod
hands those to the host through `flatpak-spawn --host`. That needs one more grant:

```sh
flatpak override --user --talk-name=org.freedesktop.Flatpak org.prismlauncher.PrismLauncher
```

Be clear about what this is. It lets the sandboxed launcher run arbitrary commands
on your host, which is effectively an escape from the sandbox for Prism and every
mod in it. It is the price of publishing from inside a Flatpak. If you would rather
not pay it, leave it ungranted: everything except Pull and publish still works, and
the mod tells you exactly what is missing instead of failing obscurely.

Git is invoked with `-C <repo>` rather than by working directory, because a host
process spawned out of the sandbox does not inherit the sandbox's working
directory.

**Push before you place.** URLs are pinned to the last commit the mod can see on
`origin`, not to your local HEAD, because ImageFrame fetches over HTTP from the
server and a commit sitting unpushed on your machine is a guaranteed 404. If a
sign you just added does not appear, check you have pushed it.

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
  js/                        Rhino generator runtime
  texture/                   runtime texture upload and caching
  gui/                       owo-ui browser, ImGui editors
```

## Things that will catch you out

**Minecraft 26.1 is unobfuscated.** There is no Yarn, no mappings dependency and
no remap step. The Loom plugin must be `net.fabricmc.fabric-loom`; the old
`fabric-loom` id silently means remapping mode, which is wrong here. There is no
`modImplementation` either, because there is nothing to remap.

**`GuiGraphics` no longer exists.** It is `GuiGraphicsExtractor`, and screens
override `extractRenderState` rather than `render`. Essentially every Fabric GUI
tutorial online predates this.

**owo renamed its core API on the 26.1 line** to avoid colliding with Mojang's
`Component`. It is `UIComponents`, `UIContainers`, `UIComponent` and
`OwoUIGraphics` now. The published owo docs still use the old names.

**owo needs the jitpack repository.** Its own setup docs omit this, but it
depends on kdl4j, which is not on the wisp forest maven, and resolution fails
without it.

**Texture components need `blend(true)`.** owo defaults to a no-blend pipeline,
which draws every transparent PNG in this repository on an opaque black square.

**Transport is not in this repository** and cannot be, for licensing reasons. The
mod finds it in your system fonts. Without it, generated lettering falls back to
a generic sans and will not match real signage; the UI says so rather than
pretending.

**Never use `const` inside a loop body in a generator script.** Rhino does not
re-initialise it, so every iteration keeps the first iteration's value and every
line of a sign after the first is silently replaced by line one. Use `let`. This
is documented at length, with the failing and working forms, in
[`../generators/README.md`](../generators/README.md), and guarded by a test.

## Verifying against a real server

The build and unit tests cover everything that can be checked offline. These
need you in game:

1. Open the browser, search for a sign, confirm thumbnails render with
   transparency rather than on black boxes.
2. Create a 1x1 sign; confirm a plain map item arrives.
3. Create a larger one; confirm a single combined item arrives, not loose maps.
4. Get frames and place the image.
5. Change a PNG, push, then Pull in game and confirm the placed map updates.
6. Generate a sign, save and publish, and confirm it appears on the wall.
