# mcmarkings

Road markings, road signs and safety signs as transparent PNGs, and a Minecraft mod
for putting them on walls.

Two things live here and they are only loosely related. The images are the point;
the mod is one way to use them.

## The images

| Folder | What | Count |
| --- | --- | --- |
| `signs/` | UK road signs | 1113 |
| `iso/` | ISO 7010 safety signs | 310 |
| `ads/` | Advertising boards | 2 |
| root | Road surface markings: give way, stop lines, double yellows, zebra, bus stop, SLOW | 10 |
| root | Shop and car park signage | 8 |
| `generated/` | Images the mod has rendered and committed | varies |

All PNGs, all with transparent backgrounds, all usable on their own. Nothing about
them needs the mod: they are files, and the licences of the originals apply.

The road markings are stretched vertically on purpose. They are looked at from a low
angle in game, so a marking drawn to true proportions reads as squashed on the
ground.

## The mod

[`companion/`](companion/README.md) is a client-side Fabric mod that turns any folder
of images into an in-game browser, layer editor and publisher for the
[ImageFrame](https://modrinth.com/plugin/imageframe) server plugin. You search your
images in game, place one on a wall in a couple of clicks, or compose a new sign from
layers and text and place that.

**It is not about road signs.** This repository happens to hold them, but the mod
knows nothing about it. Point it at photographs, pixel art, maps, logos, or anything
else in PNG form. Its own README covers building, installing and the whole interface.

## The generators

[`generators/`](generators/README.md) holds JavaScript that draws parameterised signs:
give it a few lines of text and it produces a worded plate or a direction sign at
whatever size the layout needs. The mod runs them, and a generator that describes
itself as layers can be opened in the editor and adjusted rather than regenerated.

Scripts live here rather than inside the mod, so adding a new kind of sign is a commit
to this repository rather than a new build of the mod. The contract, the drawing API
and the one Rhino trap that will bite you are all written up there.

## Layout

```
signs/ iso/ ads/     the images
*.png                road markings, and some shop signage
generators/          JavaScript that draws new ones
templates/           saved editor documents, once you make any
generated/           images the mod rendered and committed
companion/           the Fabric mod
```

`templates/` appears the first time something is saved from the editor. A template is
just a saved document, so anything made in the editor can become the starting point
for the next thing.
