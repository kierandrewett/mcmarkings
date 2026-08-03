# Sign generators

JavaScript that draws UK road signs procedurally, instead of shipping another hand-made PNG. A Java
host loads every `.js` file in this directory, calls `size(params)` to work out how big the canvas
needs to be, then calls `render(ctx, params)` to draw it.

| File | What it is |
| --- | --- |
| `generators/lib.js` | Shared TSRGD colours, metrics, text layout and panel drawing. Not a generator. |
| `generators/plate.js` | Worded rectangular plate, for example the "30 mph speed limit 250 yards ahead" warning. |
| `generators/direction_sign.js` | Junction direction sign with destinations, route numbers, diagram, roundel and distance panel. |
| `generators/roundabout.js` | Map-type roundabout sign, one ring or a dumbbell pair, with an arm per exit. |

---

## The contract

The host provides three globals: `defineGenerator(def)`, `require(name)` and `console`. There is no
`fetch`, no DOM and no Node standard library, so do not reach for them.

```js
defineGenerator({
    id: "plate",                  // required, unique, must match the filename stem
    title: "Worded plate",        // required
    description: "...",           // optional
    params: [                     // { key, label, type, options?, default?, help? }
        { key: "lines", label: "Text lines", type: "lines", default: "", help: "One line per row" },
    ],
    size: (params) => ({ width: 1200, height: 900 }),   // required, computes the output size
    render: (ctx, params) => { /* draw */ },            // required
    document: (params) => ({ /* layers */ }),           // optional, see below
});
```

Param types are `text`, `lines`, `select`, `number`, `boolean`, `colour` and `image`. Values arrive
as strings, except `lines` which arrives as an **array of strings**, `number` as a number and
`boolean` as a boolean.

The `ctx` API is pixels, origin top-left, y down:

```
ctx.width, ctx.height
ctx.fillRect(x, y, w, h, colour)
ctx.roundedRect(x, y, w, h, radius, colour)
ctx.strokeRect(x, y, w, h, thickness, colour)
ctx.strokeRoundedRect(x, y, w, h, radius, thickness, colour)
ctx.line(x1, y1, x2, y2, thickness, colour)
ctx.polygon(points, colour)              // [[x, y], [x, y], ...]
ctx.circle(cx, cy, radius, colour)
ctx.ring(cx, cy, outerRadius, thickness, colour)
ctx.text(str, x, y, opts)                // returns { width, height }
ctx.measureText(str, opts)               // returns { width, height, ascent, descent }
ctx.drawImage(repoPath, x, y, w, h)      // composites an existing repo PNG
ctx.imageSize(repoPath)                  // returns { width, height }
ctx.save() / ctx.restore()
ctx.translate(x, y) / ctx.scale(sx, sy) / ctx.rotate(radians)
ctx.clip(x, y, w, h) / ctx.clearClip()
```

`opts` is `{ font, size, colour, align, baseline, tracking, scaleY }`. `font` is the name of any font
installed on the machine, matched loosely (see below). Colours are `#RGB`, `#RRGGBB`, `#RRGGBBAA` or
`rgba(r, g, b, a)`.

---

## `document(params)`, which makes a generator a starting point

`render` draws pixels. A generator that only draws is a dead end: if the result is nearly right,
the only ways to fix it are editing the script or rebuilding the thing by hand.

A generator that also defines `document(params)` returns the same sign described as **layers**, and
the mod offers to open it in the editor, where it can be nudged, restyled, saved as a template and
placed. It is optional and nothing else changes if you leave it out. Both generators here implement
it, and `lib.js` does most of the work.

It returns a plain object, no drawing calls:

```js
document: (params) => ({
    name: "Plate",                  // what the document is called in the editor
    grid: { columns: 1, rows: 2 },  // frames wide and tall
    pixelsPerFrame: 256,            // render resolution per frame
    background: "#00000000",        // document background, transparent here
    layers: [ /* bottom first, exactly like render order */ ],
})
```

Every layer has `kind`, `name` and `bounds` as `{ x, y, width, height }` in pixels, and may have
`visible`, `locked`, `opacity` and `margins`. Beyond that:

| `kind` | Fields |
| --- | --- |
| `image` | `repoPath`, `fit` (`contain`, `cover`, `stretch`) |
| `text` | `text`, `font`, `size`, `colour`, `horizontalAlign`, `verticalAlign`, `tracking`, `verticalScale`, `lineGap` |
| `shape` | `fill`, `cornerRadius`, `borderColour`, `borderWidth`, `padding` |
| `group` | `children`, `padding` |

`horizontalAlign` is `left`, `centre` or `right`, and `verticalAlign` is `top`, `middle` or
`bottom`. Both are case-insensitive and `center` is accepted for `centre`. Colours are the same
strings `render` takes. `padding` and `margins` are `{ top, right, bottom, left }`.

**Layer order is bottom first**, matching the order you would draw them in, so a panel comes before
the text on top of it.

The two have to describe the same sign, but not at the same size. `render` draws at whatever size
your layout came out as; a document's canvas is always `columns * pixelsPerFrame`, so it is the
smallest frame-aligned canvas that **covers** that layout. `lib.canvasFor(width, height)` picks it
for you and returns the grid, the resolution and the canvas size together.

What must hold is that the document canvas covers the drawing without overshooting it by a whole
frame. Too small and the sign is clipped, and nothing in the editor brings back content that was
never on the canvas. Too large and it floats in a field of empty frames. A test in the mod checks
both generators here for exactly that.

Beyond size, the two have to agree on content, or the sign changes depending on which button was
pressed, which is a confusing bug to chase. The generators here avoid it by computing the layout
once and feeding both: `lib.textLayers` turns the same measured rows `render` draws into text
layers, so there is one answer and two ways of expressing it.

`document` runs the script exactly as `render` does, so the same rules apply. In particular the
`const` trap below will bite here too.

---

## The one thing that shapes every generator

`size(params)` runs **without a ctx**, so it cannot call `ctx.measureText`. But the canvas still has
to be sized from the legend rather than hardcoded. Both generators solve this the same way, and you
should copy it:

- Write a single `layout(measure, params)` function that computes the whole sign.
- `size()` calls it with `lib.estimateMeasurer()`, which guesses glyph widths from a per-character
  advance table.
- `render()` calls it with `lib.ctxMeasurer(ctx)`, which asks the host for real metrics.

Same layout code, same ratios, two sources of widths. The estimate is deliberately generous, because
undershooting clips the legend off a canvas that has already been sized whereas overshooting only
wastes background. `lib.warnOverflow` logs it if the two disagree enough to matter, so a clipped sign
shows up in the host log instead of being found weeks later in game.

---

## Adding a generator

Worked example: a square warning plate with one line of legend.

Create `generators/warning_plate.js`. The `id` must match the filename stem.

```js
const lib = require("lib");

const layout = (measure, params) => {
    const p = params || {};
    const xHeight = lib.toNumber(p.xHeight, 50);
    const m = lib.metrics(xHeight);
    const scheme = lib.resolveScheme(p.scheme || "yellow");
    const block = lib.textBlock(measure, lib.toLines(p.lines), {
        font: "transport-heavy",
        size: lib.fontSizeFor(xHeight),
        colour: scheme.text,
        align: "centre",
        lineGap: m.lineGap,
    });
    const side = Math.max(block.width, block.height) + m.margin * 2;
    return { metrics: m, scheme, block, size: { width: Math.round(side), height: Math.round(side) } };
};

defineGenerator({
    id: "warning_plate",
    title: "Square warning plate",
    params: [
        { key: "lines", label: "Text lines", type: "lines", default: "ford", help: "One line per row" },
        { key: "scheme", label: "Colour scheme", type: "select", options: ["yellow", "white"], default: "yellow" },
        { key: "xHeight", label: "x-height (px)", type: "number", default: 50 },
    ],
    size: (params) => layout(lib.estimateMeasurer(), params).size,
    render: (ctx, params) => {
        const l = layout(lib.ctxMeasurer(ctx), params);
        lib.drawPanel(ctx, 0, 0, ctx.width, ctx.height, {
            metrics: l.metrics,
            background: l.scheme.background,
            border: l.scheme.border,
        });
        lib.warnOverflow("warning_plate", l.block.width, ctx.width - l.metrics.margin * 2);
        lib.drawTextBlock(ctx, l.block, l.metrics.margin, Math.round((ctx.height - l.block.height) / 2));
    },
});
```

Then syntax-check it. There is no way to run the host outside Java, so this is the only automatic
check available:

```sh
node --check generators/warning_plate.js
```

---

## What `lib.js` gives you

- `COLOURS` and `SCHEMES`, plus `resolveScheme(name)`. Schemes are keyed both by colour (`green`,
  `blue`, `white`, `yellow`) and by route class (`primary`, `non-primary`, `motorway`), because a
  plate is chosen by colour and a direction sign by class. Each scheme carries `background`, `text`,
  `route` and `border`.
- `metrics(xHeight)` returning `{ border, radius, margin, lineGap }`. Every dimension on a real sign
  derives from the x-height, so nothing else should be a magic number.
- `fontSizeFor(xHeight)` to convert an x-height into the em size the host wants.
- `textBlock(measure, lines, opts)` and `drawTextBlock(ctx, block, x, y)` for stacked legend. Lines
  may be strings or `{ text, colour, font, size }`, which is how a destination and its route number
  end up in one block with two colours.
- `drawPanel(ctx, x, y, w, h, opts)` for a rounded background with an optional inset border.
- `ctxMeasurer(ctx)` and `estimateMeasurer()`, both returning a `measure(text, opts)` that answers
  `{ width, height }`. `textBlock` needs one and which you pass matters: inside `render` you have a
  `ctx`, so use `ctxMeasurer` and get the real font metrics. Inside `document` there is no drawing
  context at all, so `estimateMeasurer` is the only option, and it approximates from character
  widths. Both generators here lay out with the estimate and feed the result to `render` and
  `document` alike, which is what keeps the two agreeing about where the text sits.
- `canvasFor(width, height)` returning `{ grid, pixelsPerFrame, width, height }`: the smallest
  frame-aligned canvas covering a natural layout size, scored by wasted area rather than by shape.
  This is what a `document` should use for its `grid` and `pixelsPerFrame`.
- `textLayers(block, x, y, width)` turning a laid-out `textBlock` into text layers, so a generator
  that already measured its legend for `render` can describe the same rows to `document` without
  measuring twice.
- `toLines`, `toNumber`, `parseDestination`, `warnOverflow`.

### Destination syntax

`direction_sign.js` reads one destination per line, with an optional route number after a pipe:

```
Basingstoke|A339
Wootton St Lawrence
```

A pipe rather than a comma or a bracket, because destinations legitimately contain both and nobody
should have to escape `Newcastle upon Tyne (A1)`. Any further pipes fold back into the route number
rather than being silently dropped.

---

## Assumptions resolved against the contract

Worth knowing before you change anything, because the contract does not settle these:

- `require("lib")` is called with the filename stem, not a path or an extension.
- `ctx.text` size is treated as an em size, not an x-height. `lib.EM_PER_XHEIGHT` is the single place
  to correct if the host's font metrics turn out to differ.
- `strokeRoundedRect` may stroke its path centred or inward. The border is inset by one stroke width
  and `margin` allows two before the legend starts, so the layout is correct either way.
- Row heights come from the em box rather than from measured glyphs, so a line with no descender does
  not sit on a shorter row than the line above it.

---

## Fonts are yours to choose

`font` in the text options is any font installed on the machine. Names match loosely, so the
family, the face name, the PostScript name or the file name all work, and case and punctuation
are ignored:

```js
ctx.text("30 mph", x, y, { font: "Transport Heavy", size: 80 });
ctx.text("Ausfahrt", x, y, { font: "DIN 1451 Mittelschrift", size: 80 });
```

The mod has no opinion about which font a sign wants and treats none of them specially. The
generators here use Transport because these are UK road signs; a repository of German signs would
name a different one, and nothing in the mod needs changing for that. If a font is not installed
the sign still renders in a substitute and the mod says which name it could not resolve, so a
preview is never blocked by a missing typeface.

The settings screen lists what is available on the machine.

## Never use `const` inside a loop body

This is the one host quirk you have to know, and it is not your fault when it bites.

Rhino does not re-initialise a `const` declared inside a loop body. Every iteration keeps the value
from the first one:

```js
// WRONG. line is "30 mph" on every pass.
for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i];
    ctx.text(line, x, y, opts);
}

// RIGHT.
for (let i = 0; i < lines.length; i += 1) {
    let line = lines[i];
    ctx.text(line, x, y, opts);
}
```

`let` is fine. `var` is fine. The loop variable itself can be `let`. It is only a `const` *declared
in the body* that sticks.

This cost real time to find because it fails so quietly. A generator written that way still produces
a correctly sized, non-blank, entirely plausible sign, with every line after the first silently
replaced by line one. `node --check` passes, because the JavaScript is valid; the bug is in the host.
It reproduces on Rhino 1.7.15 through 1.9.1, in both interpreted and compiled mode.

`RealGeneratorsIntegrationTest` guards against it by rendering a sign whose lines differ and one
whose lines are identical, and failing if they come out the same. If you add a generator that stacks
repeated elements, add a case there too.

## A layer cannot be rotated, and that decides some layouts

`roundabout.js` offers left, ahead and right, square to the sign, and no angled arms. That is not
about the drawing: `render` could put an arm at any angle with two lines of trigonometry. It is
about `document`. There is no rotation on a layer, so an angled arm can be drawn into the PNG and
cannot be described as layers, and a generator whose two halves describe different signs is the one
failure this arrangement exists to prevent.

The choice, when the two cannot agree, is to narrow what the generator offers rather than to let
them drift. A three-exit roundabout approached from the bottom is the common case and comes out
exactly right; angled arms wait for layer rotation.

---

## Anti-patterns

- **Do not hardcode the canvas size.** `size()` must fall out of the legend and the metrics. A
  generator that returns fixed numbers breaks the moment someone changes the text.
- **Do not measure text in `size()`.** There is no ctx there. Use the two-measurer pattern above.
- **Do not treat a `lines` param as a string.** It arrives as an array. `lib.toLines` accepts both
  and trims blanks, so use it rather than calling `.split("\n")` on an array.
- **Do not invent `ctx` methods.** The list above is the whole API. There is no `beginPath`, no
  `fillText`, no gradient.
- **Do not put raw pixel numbers in a generator.** Derive from `metrics(xHeight)`. If you genuinely
  need a new ratio, add it to `lib.js` with a comment explaining the reasoning, so the next sign gets
  it too.
- **Do not let an optional part change the rest of the layout by accident.** No roundel and no
  distance panel must still produce a correct sign with `size()` shrinking to match. Test that case.
- **Do not throw on a missing image.** `ctx.imageSize` can fail. Warn and carry on, as
  `direction_sign.js` does, rather than taking the whole sign down.
- **Do not duplicate layout between `size()` and `render()`.** They will drift, and the drift shows
  up as clipped legend.
