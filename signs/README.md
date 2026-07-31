# UK road signs

Every UK traffic sign published on Wikimedia Commons under the `UK traffic sign` prefix,
downloaded on 2026-07-31. 1111 signs, all transparent RGBA PNGs.

The set is broader than just current Great Britain signs. It also covers Wales, Scotland,
Northern Ireland, Gibraltar, Jersey, Guernsey and the Isle of Man, plus withdrawn and
historical designs going back to the 1960s. Region and era are carried in the filename
suffix, so `passing_place.png` and `passing_place_wales.png` sit next to each other.

## Naming

Each file is named after what the sign actually means, lowercase with underscores:

```
stop.png
give_way.png
no_entry.png
mini_roundabout.png
national_speed_limits_apply.png
```

Names come from the Commons description with the boilerplate stripped off. Suffixes are
added only where two signs would otherwise collide, in this order:

1. region: `_wales`, `_scotland`, `_northern_ireland`, `_gibraltar`, `_jersey`, `_guernsey`, `_isle_of_man`
2. handing: `_left`, `_right`
3. state: `_obsolete`, `_fluorescent`, `_mirrored`, `_unbranded`
4. era: `_1975`, `_1965_1994`
5. variant: `_variant_1`, `_variant_2`
6. TSRGD diagram number, as a last resort: `_611_1`

Around a dozen signs have no usable description on Commons and fall back to
`sign_<diagram>.png`. They are listed as such in the manifest.

## Images

Rendered from the Commons SVG with `rsvg-convert`, fitted inside a 1024x1024 box so the
longest side is 1024 and the aspect ratio is preserved. Everything is RGBA.

Two caveats on transparency:

- 25 signs have no transparent pixels at all. Most are rectangular plates or motorway
  matrix signals where the artwork fills the whole canvas, so there is nothing to cut out.
- 22 of those came from photographs or screenshots rather than vector art, because that is
  all Commons has for them. They are smaller than 1024 on the longest side, since
  upscaling a photo would not add detail.

Find them with:

```sh
jq -r '.signs[] | select(.has_transparency == false) | .file' signs.json
```

## signs.json

The manifest holds the things the filename deliberately leaves out: the TSRGD diagram
number, the full description, the sign class, pixel size, and where each file came from.

```json
{
  "name": "mini_roundabout",
  "file": "mini_roundabout.png",
  "diagram": "611.1",
  "class": "regulatory",
  "description": "Mini-roundabout. (Vehicles entering the junction must give way ...)",
  "width": 1024,
  "height": 1024,
  "has_transparency": true,
  "licence": "OGL v1.0",
  "source_file": "UK traffic sign 611.1.svg",
  "source_url": "https://commons.wikimedia.org/wiki/File:UK_traffic_sign_611.1.svg",
  "bytes": 48213
}
```

`class` is derived from the diagram number range, which is how TSRGD groups them:
`warning` (500s, 220 signs), `regulatory` (600s, 192), `information` (800s, 178),
`directional` (2000s, 126), `temporary_and_works` (7000s, 73), `level_crossing` (700s, 66),
`cycle_and_tram` (900s, 41), `motorway_signal` (6000s, 13), `traffic_signal` (4000s, 12).
The remaining 190 have no diagram number in that scheme and land in `other`.

To find a sign by diagram number rather than by name:

```sh
jq -r '.signs[] | select(.diagram == "611.1") | .file' signs.json
```

## Licensing

UK traffic sign designs are Crown copyright, released under the Open Government Licence.
893 files here are OGL (v1.0, 2 or 3) and 197 are public domain.

21 are contributor-drawn under Creative Commons rather than OGL, and those carry stricter
terms than the rest, CC BY-SA 4.0 in particular. Check before redistributing:

```sh
jq -r '.signs[] | select(.licence | startswith("CC")) | "\(.licence)\t\(.file)"' signs.json
```

Attribution for the OGL files: contains public sector information licensed under the Open
Government Licence v1.0/v2.0/v3.0.
