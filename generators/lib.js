// Shared TSRGD helpers for the mcmarkings sign generators.
//
// The one structural constraint that shapes this file: the host calls size(params) without a
// drawing context and render(ctx, params) with one. So layout code has to run twice against two
// different sources of glyph widths. Everything here takes a "measure" function rather than a ctx,
// which lets a generator run the exact same layout in both places and only swap the measurer.

// Colours.
//
// The statutory colours are defined as chromaticity boxes in BS EN 12899-1 rather than as RGB
// triples, so there is no single correct hex value. These are the sRGB reproductions in common use
// for UK signage, cross-checked against the artwork already committed to this repo.
const COLOURS = {
    // Primary route green, the value National Highways uses for its own signage artwork.
    green: "#00703C",
    // Motorway blue. sRGB reproduction of the Pantone 294 family used on motorway signs.
    blue: "#0B3F8F",
    white: "#FFFFFF",
    black: "#000000",
    // Sampled straight out of double_yellow.png in this repo: rgb(236, 211, 17). Reusing the repo's
    // own value matters more than matching any published figure, because generated signs sit next to
    // the hand-made markings and any drift between the two is visible in game.
    yellow: "#ECD311",
    // Traffic sign red, the sRGB equivalent of the Pantone 186 C used on prohibition roundels.
    red: "#C8102E",
};

// Transport's x-height is roughly 0.72 of the em box, so asking the host for an em size of about
// 1.4 x-heights lands on the x-height the caller actually asked for. Everything in the generators is
// specified in x-heights because that is how TSRGD specifies signs, so this is the single place to
// correct if the host's font metrics turn out to differ.
const EM_PER_XHEIGHT = 1.4;

// Background, legend, route number and border colours per sign class.
const SCHEMES = {
    green: { background: COLOURS.green, text: COLOURS.white, route: COLOURS.yellow, border: COLOURS.white },
    blue: { background: COLOURS.blue, text: COLOURS.white, route: COLOURS.white, border: COLOURS.white },
    white: { background: COLOURS.white, text: COLOURS.black, route: COLOURS.black, border: COLOURS.black },
    yellow: { background: COLOURS.yellow, text: COLOURS.black, route: COLOURS.black, border: COLOURS.black },
};

// Two vocabularies reach the same table. Plates are picked by colour because that is how someone
// building a plate thinks about it; direction signs are picked by route class because the colour is
// a consequence of the class, not a choice. Aliasing beats duplicating the table.
const SCHEME_ALIASES = {
    primary: "green",
    "non-primary": "white",
    nonprimary: "white",
    motorway: "blue",
    temporary: "yellow",
    diversion: "yellow",
};

const toNumber = (value, fallback) => {
    const n = typeof value === "number" ? value : parseFloat(value);
    return isFinite(n) && n > 0 ? n : fallback;
};

// Params of type "lines" arrive as an array, but defaults are declared as newline-joined strings and
// a host is free to hand back either. Normalising both here keeps the guard out of every generator.
const toLines = (value) => {
    const raw = Array.isArray(value) ? value : String(value === null || value === undefined ? "" : value).split("\n");
    const out = [];
    for (let i = 0; i < raw.length; i += 1) {
        let line = String(raw[i] === null || raw[i] === undefined ? "" : raw[i]).trim();
        if (line.length > 0) {
            out.push(line);
        }
    }
    return out;
};

const resolveScheme = (name) => {
    const key = String(name === null || name === undefined ? "" : name).trim().toLowerCase();
    const resolved = SCHEME_ALIASES[key] || key;
    if (SCHEMES[resolved]) {
        return SCHEMES[resolved];
    }
    if (key.length > 0) {
        console.warn("[lib] unknown scheme '" + key + "', falling back to primary green");
    }
    return SCHEMES.green;
};

const fontSizeFor = (xHeight) => Math.max(1, Math.round(toNumber(xHeight, 50) * EM_PER_XHEIGHT));

// Standard sign anatomy, all derived from the x-height because that is the only dimension TSRGD
// actually fixes. The ratios below are the Working Drawings relationships rounded to something a
// generator can use, not quoted clause values.
const metrics = (xHeight) => {
    const x = toNumber(xHeight, 50);
    // Transport's stroke width is about a quarter of its x-height, and a sign border is drawn to read
    // about as heavy as a letter stroke. One stroke width is therefore the unit everything else is
    // built from, which is also how the Working Drawings lay signs out.
    const border = Math.max(1, Math.round(x * 0.25));
    return {
        border,
        // Three stroke widths. Generous enough to read as a proper rounded sign corner without
        // turning small plates into pills.
        radius: Math.max(border, Math.round(x * 0.75)),
        // Sign edge to legend: one stroke width of background outside the border, the border itself,
        // then half an x-height of clear space inside it. That adds up to exactly one x-height, which
        // is convenient and also close to what real plates measure. The floor of three border widths
        // keeps the border from touching the legend at very small x-heights where rounding bites.
        margin: Math.max(border * 3, Math.round(x)),
        // Clear space between stacked lines of legend on the same panel.
        lineGap: Math.max(1, Math.round(x * 0.5)),
    };
};

// Rough Transport advance widths as a fraction of the em size. size() has no ctx to measure against
// so it has to estimate, and the estimate is deliberately a little generous: too wide costs a few
// pixels of background, too narrow clips the legend off the edge of the PNG.
const NARROW_GLYPHS = "ijlt.,;:'!|()[]{}/\\ ";
const WIDE_GLYPHS = "mwMW@%";
const ADVANCE_NARROW = 0.34;
const ADVANCE_WIDE = 0.92;
const ADVANCE_UPPER = 0.66;
const ADVANCE_LOWER = 0.58;
// Deliberately more headroom than the per-glyph figures need. The estimate only has to be an upper
// bound; when it undershoots, the legend runs off a canvas that has already been sized and there is
// nothing render() can do about it, whereas overshooting costs a few pixels of background.
const WIDTH_HEADROOM = 1.08;

const estimateTextWidth = (text, opts) => {
    const str = String(text === null || text === undefined ? "" : text);
    const size = toNumber(opts && opts.size, 20);
    const tracking = opts && typeof opts.tracking === "number" && isFinite(opts.tracking) ? opts.tracking : 0;
    let ems = 0;
    for (let i = 0; i < str.length; i += 1) {
        let ch = str.charAt(i);
        if (NARROW_GLYPHS.indexOf(ch) >= 0) {
            ems += ADVANCE_NARROW;
        } else if (WIDE_GLYPHS.indexOf(ch) >= 0) {
            ems += ADVANCE_WIDE;
        } else if ((ch >= "A" && ch <= "Z") || (ch >= "0" && ch <= "9")) {
            ems += ADVANCE_UPPER;
        } else {
            ems += ADVANCE_LOWER;
        }
    }
    const tracked = str.length > 1 ? tracking * (str.length - 1) : 0;
    return Math.ceil(ems * size * WIDTH_HEADROOM + tracked);
};

// Row height comes from the em box rather than from the measured glyphs, in both measurers, so that
// a line with no descender does not sit on a shorter row than the line above it and throw the
// spacing of a stacked legend out.
const estimateTextHeight = (opts) => {
    const size = toNumber(opts && opts.size, 20);
    const scaleY = toNumber(opts && opts.scaleY, 1);
    return Math.ceil(size * scaleY);
};

const estimateMeasurer = () => (text, opts) => ({
    width: estimateTextWidth(text, opts),
    height: estimateTextHeight(opts),
});

const ctxMeasurer = (ctx) => (text, opts) => {
    const measured = ctx.measureText(text, opts);
    const usable = measured && typeof measured.width === "number" && isFinite(measured.width);
    return {
        width: usable ? Math.ceil(measured.width) : estimateTextWidth(text, opts),
        height: estimateTextHeight(opts),
    };
};

// Lay out a stack of lines and report where each one goes plus the size of the whole block.
//
// lines entries are either a string or { text, colour, font, size, tracking, scaleY }, so a caller
// can mix legend and route-number colours inside one block without laying out twice.
// Returns { width, height, align, rows: [{ text, opts, x, y, width, height }] } with x and y
// relative to the top-left of the block.
const textBlock = (measure, lines, opts) => {
    const base = opts || {};
    const align = base.align === "centre" || base.align === "right" ? base.align : "left";
    const lineGap = typeof base.lineGap === "number" && isFinite(base.lineGap) ? base.lineGap : 0;
    const list = Array.isArray(lines) ? lines : [];
    const rows = [];
    let width = 0;
    let y = 0;
    for (let i = 0; i < list.length; i += 1) {
        let raw = list[i];
        let spec = raw !== null && typeof raw === "object" ? raw : { text: raw };
        let textOpts = {
            font: spec.font || base.font || "transport-medium",
            size: toNumber(spec.size, toNumber(base.size, 20)),
            colour: spec.colour || base.colour || COLOURS.white,
            tracking: toNumber(spec.tracking, toNumber(base.tracking, 0)),
            scaleY: toNumber(spec.scaleY, toNumber(base.scaleY, 1)),
            // Rows are positioned explicitly, so the host is always asked to draw from the top-left.
            align: "left",
            baseline: "top",
        };
        let text = String(spec.text === null || spec.text === undefined ? "" : spec.text);
        let m = measure(text, textOpts);
        rows.push({ text: text, opts: textOpts, x: 0, y: y, width: m.width, height: m.height });
        width = Math.max(width, m.width);
        y += m.height;
        if (i < list.length - 1) {
            y += lineGap;
        }
    }
    for (let i = 0; i < rows.length; i += 1) {
        if (align === "centre") {
            rows[i].x = Math.round((width - rows[i].width) / 2);
        } else if (align === "right") {
            rows[i].x = width - rows[i].width;
        }
    }
    return { width: width, height: y, align: align, rows: rows };
};

const drawTextBlock = (ctx, block, x, y) => {
    for (let i = 0; i < block.rows.length; i += 1) {
        let row = block.rows[i];
        ctx.text(row.text, x + row.x, y + row.y, row.opts);
    }
};

// Standard rounded panel: background fill plus an inset border.
//
// opts is { metrics, background, border, radius }. A falsy border draws the fill only, which is what
// an inset white distance panel wants.
const drawPanel = (ctx, x, y, w, h, opts) => {
    const cfg = opts || {};
    const m = cfg.metrics || metrics(50);
    const radius = typeof cfg.radius === "number" && isFinite(cfg.radius) ? cfg.radius : m.radius;
    ctx.roundedRect(x, y, w, h, radius, cfg.background || COLOURS.green);
    if (!cfg.border) {
        return;
    }
    // The border sits one stroke width in from the sign edge. Whether the host strokes that path
    // centred or inward, the result stays inside the margin, which is why margin allows two stroke
    // widths before the legend starts.
    const inset = m.border;
    if (w - inset * 2 <= 0 || h - inset * 2 <= 0) {
        return;
    }
    ctx.strokeRoundedRect(
        x + inset,
        y + inset,
        w - inset * 2,
        h - inset * 2,
        Math.max(0, radius - inset),
        m.border,
        cfg.border,
    );
};

// "Basingstoke|A339" -> { name: "Basingstoke", route: "A339" }.
//
// A pipe is the separator because destinations legitimately contain commas, brackets and full stops
// and nobody should have to escape "Newcastle upon Tyne (A1)". Extra pipes fold back into the route
// field rather than being silently dropped.
const parseDestination = (line) => {
    const parts = String(line === null || line === undefined ? "" : line).split("|");
    return {
        name: parts[0].trim(),
        route: parts.length > 1 ? parts.slice(1).join("|").trim() : "",
    };
};

// size() estimates and render() measures, so the two can disagree. When they disagree badly enough
// to push legend past the margin, say so: a silently clipped sign is much harder to spot later than
// a warning in the host log.
const warnOverflow = (tag, needed, available) => {
    if (needed > available) {
        console.warn(
            "[" + tag + "] legend measures " + Math.round(needed - available) + "px wider than the sign allows; "
                + "shorten the text or lower the x-height",
        );
    }
};

// Chooses a frame grid and canvas for a natural pixel size.
//
// A document is expressed as item frames rather than raw pixels, because that is
// what the person placing it has to build. The grid is picked to sit close to the
// natural shape, then the layout is done into the resulting canvas rather than
// being computed at the natural size and scaled, which would distort the legend.
const canvasFor = (naturalWidth, naturalHeight) => {
    const width = Math.max(1, Math.round(naturalWidth));
    const height = Math.max(1, Math.round(naturalHeight));

    // Chooses the grid that wastes the least canvas while still covering the
    // layout. Scoring by shape alone picked a square for a sign twice as wide as
    // it is tall, which left the legend crammed into one half and everything else
    // pushed against the edge. Wasted area is the thing actually being minimised,
    // and it falls out of the frame resolution rather than being guessed at.
    let best = null;
    for (let columns = 1; columns <= 8; columns += 1) {
        for (let rows = 1; rows <= 8; rows += 1) {
            let perFrame = Math.max(128, Math.ceil(Math.max(width / columns, height / rows)));
            if (perFrame > 1024) {
                continue;
            }

            let canvasWidth = columns * perFrame;
            let canvasHeight = rows * perFrame;
            // A canvas smaller than the measured layout would clip it, which is the
            // one outcome worse than a little wasted background.
            if (canvasWidth < width || canvasHeight < height) {
                continue;
            }

            // Frames are placed by hand, so a marginally tighter fit is not worth
            // extra ones; the penalty is what breaks near-ties towards fewer.
            let score = canvasWidth * canvasHeight * (1 + columns * rows * 0.02);
            if (best === null || score < best.score) {
                best = {
                    score: score,
                    grid: { columns: columns, rows: rows },
                    pixelsPerFrame: perFrame,
                    width: canvasWidth,
                    height: canvasHeight,
                };
            }
        }
    }

    if (best === null) {
        // Larger than eight frames of 1024 in some direction. Nothing here fits, so
        // take the biggest canvas available and let the layout be clipped rather
        // than returning something unusable.
        return { grid: { columns: 8, rows: 8 }, pixelsPerFrame: 1024, width: 8192, height: 8192 };
    }
    return best;
};


// Turns a laid-out text block into one text layer per line.
//
// One layer per line rather than one for the whole block, so a single line can be
// recoloured, moved or retyped in the editor without touching its neighbours.
const textLayers = (block, originX, originY, width) => {
    const layers = [];
    for (let i = 0; i < block.rows.length; i += 1) {
        let row = block.rows[i];
        layers.push({
            kind: "text",
            name: row.text || "Line " + (i + 1),
            bounds: {
                x: Math.round(originX),
                y: Math.round(originY + row.y),
                width: Math.round(width),
                height: Math.round(row.height),
            },
            text: row.text,
            font: row.opts.font,
            size: Math.round(row.opts.size),
            colour: row.opts.colour,
            horizontalAlign: block.align === "centre" ? "centre" : "left",
            verticalAlign: "top",
            tracking: row.opts.tracking || 0,
            verticalScale: row.opts.scaleY || 1,
        });
    }
    return layers;
};

module.exports = {
    COLOURS: COLOURS,
    SCHEMES: SCHEMES,
    EM_PER_XHEIGHT: EM_PER_XHEIGHT,
    toNumber: toNumber,
    toLines: toLines,
    resolveScheme: resolveScheme,
    fontSizeFor: fontSizeFor,
    metrics: metrics,
    estimateTextWidth: estimateTextWidth,
    estimateMeasurer: estimateMeasurer,
    ctxMeasurer: ctxMeasurer,
    textBlock: textBlock,
    drawTextBlock: drawTextBlock,
    drawPanel: drawPanel,
    parseDestination: parseDestination,
    warnOverflow: warnOverflow,
    canvasFor: canvasFor,
    textLayers: textLayers,
};
