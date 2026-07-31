# ISO 7010 safety signs

The ISO 7010 symbol set from Wikimedia Commons, downloaded on 2026-07-31. 310 signs, all
transparent RGBA PNGs.

These are safety signs, not road signs, so they live here rather than in `signs/`. Keeping
them apart avoids collisions: ISO has its own `no_pedestrians` and `no_entry` that mean
different things from the TSRGD ones.

## Categories

ISO 7010 codes carry the category in the first letter:

| Prefix | Category | Count | Shape and colour |
|---|---|---|---|
| `W` | warning | 85 | yellow triangle |
| `P` | prohibition | 81 | red circle with diagonal bar |
| `E` | safe condition | 73 | green square |
| `M` | mandatory | 52 | solid blue circle |
| `F` | fire safety | 19 | red square |

## Naming

Named after what the sign means, lowercase with underscores:

```
no_access_for_unauthorized_persons.png    P080
smoking_prohibited.png                    P002
wear_ear_protection.png                   M003
biohazard.png                             W009
sharks.png                                W054
first_aid.png                             E003
fire_extinguisher.png                     F001
```

Names come from the Commons description with the category wrapper and the standard's
explanatory clause stripped, so "Warning; Jellyfish; To warn of jellyfish in the water"
becomes `jellyfish.png`. Where the description is boilerplate, the meaning is taken from
the Commons filename instead.

The ISO code is appended only where two signs would otherwise collide. That happens four
times, two genuine pairs that share a meaning: `emergency_exit_e001` / `emergency_exit_e002`
(running man facing left and right), and `emergency_exit_for_people_unable_to_walk_or_with_walking_e026`
/ `..._e030`.

Four signs (E018, E019, F007, M048) are documented only in German on Commons. Their names
come from a translation of that text, with the German original kept in the manifest.

## iso.json

The manifest carries the ISO code, category, full description, pixel size, licence and the
Commons source URL.

To find a sign by ISO code rather than by name:

```sh
jq -r '.signs[] | select(.code == "P080") | .file' iso.json
```

To list a whole category:

```sh
jq -r '.signs[] | select(.category == "prohibition") | "\(.code)\t\(.name)"' iso.json
```

## Transparency

60 signs have no transparent pixels. That is not a defect: they are the green safe
condition and red fire safety signs, which are solid squares by design, so the artwork
fills the canvas. Every prohibition circle, mandatory circle and warning triangle has
transparency around the shape.

```sh
jq -r '.signs[] | select(.has_transparency == false) | .file' iso.json
```

## Licensing

CC0 (168) and public domain (137), plus five under CC BY-SA which carry stricter terms.
The symbols are defined by ISO 7010, a paid standard; these are contributor-drawn
reproductions on Commons rather than copies of the ISO artwork. Check before
redistributing:

```sh
jq -r '.signs[] | select(.licence | startswith("CC BY")) | "\(.licence)\t\(.file)"' iso.json
```

## Not included

The Commons prefix holds 450 files. The 140 left out are language-specific exit sign
templates (DE, EN, FR variants), Red Crescent alternates to the Red Cross first aid signs,
draft redraws marked `new`, map-icon adaptations, and one blank template. They are variants
and localisations rather than part of the standard set.
