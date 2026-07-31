# UK road signs

Every UK traffic sign diagram published on Wikimedia Commons under the `UK traffic sign`
prefix, downloaded on 2026-07-31. Mostly SVG, with a handful of raster files where that is
all Commons has.

The set is broader than just current Great Britain signs. It also covers Wales, Scotland,
Northern Ireland, Gibraltar, Jersey, Guernsey and the Isle of Man, plus withdrawn and
historical designs going back to the 1960s. Region and era are carried in the filename
suffix, so `passing_place.svg` and `passing_place_wales.svg` sit next to each other.

## Naming

Each file is named after what the sign actually means, lowercase with underscores:

```
stop.svg
give_way.svg
no_entry.svg
mini_roundabout.svg
national_speed_limits_apply.svg
```

Names come from the Commons description with the boilerplate stripped off. Suffixes are
added only where two signs would otherwise collide, in this order:

1. region: `_wales`, `_scotland`, `_northern_ireland`, `_gibraltar`, `_jersey`, `_guernsey`, `_isle_of_man`
2. handing: `_left`, `_right`
3. state: `_obsolete`, `_fluorescent`, `_mirrored`, `_unbranded`
4. era: `_1975`, `_1965_1994`
5. variant: `_variant_1`, `_variant_2`
6. TSRGD diagram number, as a last resort: `_611_1`

Around a dozen files have no usable description on Commons and fall back to
`sign_<diagram>.svg`. They are listed as such in the manifest.

## signs.json

The manifest holds the things the filename deliberately leaves out: the TSRGD diagram
number, the full description, the sign class, and where each file came from.

```json
{
  "name": "mini_roundabout",
  "file": "mini_roundabout.svg",
  "diagram": "611.1",
  "class": "regulatory",
  "description": "Mini-roundabout. (Vehicles entering the junction must give way ...)",
  "licence": "OGL v1.0",
  "source_file": "UK traffic sign 611.1.svg",
  "source_url": "https://commons.wikimedia.org/wiki/File:UK_traffic_sign_611.1.svg",
  "download_url": "https://upload.wikimedia.org/...",
  "bytes": 4821
}
```

`class` is derived from the diagram number range, which is how TSRGD groups them:
`warning` (500s), `regulatory` (600s), `level_crossing` (700s), `information` (800s),
`cycle_and_tram` (900s), `directional` (2000s), `traffic_signal` (4000s),
`motorway_signal` (6000s), `temporary_and_works` (7000s).

To find a sign by diagram number rather than by name:

```sh
jq -r '.signs[] | select(.diagram == "611.1") | .file' signs.json
```

## Licensing

UK traffic sign designs are Crown copyright, released under the Open Government Licence.
Most files here are OGL v1.0, with smaller numbers of OGL 2, OGL 3 and public domain.

A handful are contributor-drawn under Creative Commons rather than OGL, and those carry
stricter terms than the rest, CC BY-SA 4.0 in particular. Check before redistributing:

```sh
jq -r '.signs[] | select(.licence | startswith("CC")) | "\(.licence)\t\(.file)"' signs.json
```

Attribution for the OGL files: contains public sector information licensed under the Open
Government Licence v1.0/v2.0/v3.0.
