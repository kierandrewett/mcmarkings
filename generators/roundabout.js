// Map-type roundabout direction sign: a ring with an arm per exit, each labelled with its
// destination and route number, on the usual coloured plate.
//
// Only the three exits and the approach, all square to the sign. That is not a simplification of
// the drawing, it is a consequence of the editor: a layer cannot be rotated yet, so an angled arm
// could be drawn into the PNG and could not be described as layers, and the two halves of a
// generator have to describe the same sign or it changes depending on which button was pressed.
// A three-exit roundabout approached from the bottom is the common case anyway, and it comes out
// exactly right. Angled arms want layer rotation first.

const lib = require("lib");

const DEFAULT_EXITS = "left|Basingstoke|A339\nahead|Newbury|A34\nright|Winchester|A31";

// Screen coordinates, y down. The approach is the arm you are standing on, drawn without a legend
// because a sign does not tell you where you already are.
const EXITS = {
    left: { dx: -1, dy: 0 },
    ahead: { dx: 0, dy: -1 },
    right: { dx: 1, dy: 0 },
};

const APPROACH = { dx: 0, dy: 1 };

// 5.2.1. Route arm widths in stroke widths, by the status of the route the arm carries.
const ARM_STROKE_WIDTHS = {
    primary: 6,
    motorway: 6,
    "non-primary": 4,
    nonprimary: 4,
    minor: 2.5,
};

const trimmed = (value) => String(value === null || value === undefined ? "" : value).trim();

// "left|Basingstoke;Alton|A339" -> the exit, its legend rows and its route.
//
// A semicolon stacks destinations on one arm, which real signs do constantly, and a pipe separates
// the fields for the same reason lib.parseDestination uses one: place names are full of commas.
const parseExit = (line) => {
    const parts = String(line === null || line === undefined ? "" : line).split("|");

    // An optional ring in front, so one format serves both shapes. A single roundabout has one
    // ring and nobody should have to name it; a pair needs to know which one an arm leaves from,
    // and "a|left|Newbury" says that without a second syntax to learn.
    let fields = parts;
    let ring = "a";
    let head = trimmed(parts[0]).toLowerCase();
    if (head === "a" || head === "b") {
        ring = head;
        fields = parts.slice(1);
    }

    const direction = trimmed(fields[0]).toLowerCase();
    const names = trimmed(fields[1])
        .split(";")
        .map(trimmed)
        .filter((name) => name.length > 0);
    return {
        ring: ring,
        direction: Object.prototype.hasOwnProperty.call(EXITS, direction) ? direction : "",
        names: names,
        route: trimmed(fields.slice(2).join("|")),
    };
};

const layout = (measure, params) => {
    const xHeight = lib.toNumber(params.xHeight, 40);
    const m = lib.metrics(xHeight);
    const scheme = lib.resolveScheme(params.scheme);
    const size = lib.fontSizeFor(xHeight);

    // Every dimension below is out of the Traffic Signs Manual chapter 7, in stroke widths, which
    // is the unit the Working Drawings use. One stroke width is a quarter of the x-height: the
    // drawings are made at an x-height of 100mm where 1 sw is 25mm.
    //
    // The numbers here were mine before this and they were not close. The ring was 8.4 sw across
    // its radius against a specified 12, and the arms 5.2 sw long against a minimum of 12, so the
    // diagram read as a small ring with stubs rather than a road layout.
    const sw = xHeight / 4;

    // 5.10.1, and figure 5-13. The roundabout is 12 sw to its outer edge and 7 sw to its inner,
    // which is the 5 sw band the clause fixes "whatever the status of the routes at the junction".
    const ringOuter = Math.round(12 * sw);
    const ringThickness = Math.max(2, Math.round(5 * sw));

    // 5.2.1. The arm carries the status of its route: 6 sw for a primary route or a motorway,
    // 4 sw for a numbered non-primary or another road of traffic importance, 2.5 sw for a minor
    // one. This is the only part of the diagram that changes with the route class, and it is a
    // real distinction on a real sign rather than decoration.
    const armWidth = Math.max(2, Math.round(ARM_STROKE_WIDTHS[params.scheme] === undefined
        ? 6 * sw
        : ARM_STROKE_WIDTHS[params.scheme] * sw));

    // 5.10.1 again: no exit arm shorter than 12 sw, no approach shorter than 8.5.
    const armLength = Math.round(12 * sw);
    const approachLength = Math.round(8.5 * sw);

    // Figure 5-2, the gap from a legend to the arm it belongs to.
    const legendGap = Math.round(2.5 * sw);

    // A pair sits side by side with a link between them, which is the shape of every dumbbell
    // interchange: two roundabouts either side of a dual carriageway, joined over or under it.
    const twin = String(params.roundabouts || "one").toLowerCase() === "two";
    // 5.10.4. The connector between a pair is always 5 sw wide "irrespective of the status of the
    // various routes at the junction", so it is the ring's band rather than an arm.
    const connectorWidth = Math.max(2, Math.round(5 * sw));
    // Measured before anything is placed, because where the two rings go depends on how wide the
    // legends are. Building the block and its position in one pass is what put "Newbury" and
    // "Reading" almost touching over a pair: the separation was a fixed multiple of the x-height
    // and knew nothing about the two names that would end up centred either side of it.
    const wanted = lib.toLines(params.exits);
    const exits = [];
    for (let i = 0; i < wanted.length; i += 1) {
        // let, not const. Rhino keeps the first value of a const declared in a loop body, so every
        // exit after the first would be the first one again.
        let parsed = parseExit(wanted[i]);
        if (parsed.direction === "" || parsed.names.length === 0) {
            continue;
        }

        // The sides facing each other are the link, not exits. Drawing an arm there would put a
        // second bar down the middle of the link and a legend on top of the other roundabout.
        if (twin && ((parsed.ring === "a" && parsed.direction === "right")
                || (parsed.ring === "b" && parsed.direction === "left"))) {
            continue;
        }

        let rows = [];
        for (let n = 0; n < parsed.names.length; n += 1) {
            rows.push({ text: parsed.names[n], colour: scheme.text });
        }
        if (parsed.route !== "") {
            rows.push({ text: parsed.route, colour: scheme.route });
        }

        let vector = EXITS[parsed.direction];
        let align = "centre";
        if (vector.dx > 0) {
            align = "left";
        } else if (vector.dx < 0) {
            align = "right";
        }

        exits.push({
            ring: parsed.ring,
            direction: parsed.direction,
            vector: vector,
            block: lib.textBlock(measure, rows, {
                font: params.font,
                size: size,
                colour: scheme.text,
                lineGap: m.lineGap,
                align: align,
            }),
        });
    }

    // How far apart the rings have to be. Far enough for the link, and far enough that the legends
    // above one do not run into the legends above the other, which is a wider gap whenever the
    // names are long. Measured per side, because a sign can be crowded above and clear below.
    let separation = 0;
    if (twin) {
        // Figure 5-17 dimensions the pair centre to centre with a minimum of 28 sw, which is four
        // more than the two radii and is what keeps the rule in the clause beside it true: "the two
        // roundabout symbols never touch each other".
        separation = Math.round(28 * sw);
        let vertical = ["ahead", "back"];
        for (let v = 0; v < vertical.length; v += 1) {
            let onA = 0;
            let onB = 0;
            for (let i = 0; i < exits.length; i += 1) {
                if (exits[i].direction !== vertical[v]) {
                    continue;
                }
                if (exits[i].ring === "a") {
                    onA = Math.max(onA, exits[i].block.width);
                } else {
                    onB = Math.max(onB, exits[i].block.width);
                }
            }
            if (onA > 0 && onB > 0) {
                separation = Math.max(separation, onA / 2 + onB / 2 + m.margin);
            }
        }
    }
    const ringX = { a: -separation / 2, b: separation / 2 };

    // Placed now that the rings have somewhere to be.
    for (let i = 0; i < exits.length; i += 1) {
        let exit = exits[i];
        let vector = exit.vector;
        let block = exit.block;
        let originX = twin ? ringX[exit.ring] : 0;
        let reach = ringOuter + (exit.direction === "back" ? approachLength : armLength);
        let tipX = originX + vector.dx * reach;
        let tipY = vector.dy * reach;

        exit.originX = originX;
        exit.tipX = tipX;
        exit.tipY = tipY;
        // Sideways arms put the legend alongside and vertically centred on the arm; the ones going
        // up and down put it beyond the tip and centred on the arm.
        if (vector.dx > 0) {
            exit.legendX = tipX + legendGap;
            exit.legendY = tipY - block.height / 2;
        } else if (vector.dx < 0) {
            exit.legendX = tipX - legendGap - block.width;
            exit.legendY = tipY - block.height / 2;
        } else if (vector.dy < 0) {
            exit.legendX = tipX - block.width / 2;
            exit.legendY = tipY - legendGap - block.height;
        } else {
            exit.legendX = tipX - block.width / 2;
            exit.legendY = tipY + legendGap;
        }
    }

    // The bars, as rectangles in the same centred space. They start inside the ring band so the two
    // read as one shape once both are filled, rather than a ring with four bars butted against it.
    const bars = [];
    const inner = ringOuter - ringThickness;
    const addBar = (originX, vector, length) => {
        if (vector.dx !== 0) {
            bars.push({
                x: originX + (vector.dx > 0 ? inner : -(ringOuter + length)),
                y: -armWidth / 2,
                width: ringOuter + length - inner,
                height: armWidth,
            });
            return;
        }
        bars.push({
            x: originX - armWidth / 2,
            y: vector.dy > 0 ? inner : -(ringOuter + length),
            width: armWidth,
            height: ringOuter + length - inner,
        });
    };
    for (let i = 0; i < exits.length; i += 1) {
        addBar(exits[i].originX, exits[i].vector, armLength);
    }

    // The link, drawn between the two ring bands rather than between their centres, so it reads as
    // a road joining them instead of a bar passing behind both.
    if (twin) {
        bars.push({
            x: ringX.a + inner,
            y: -connectorWidth / 2,
            width: (ringX.b - inner) - (ringX.a + inner),
            height: connectorWidth,
        });
    }

    // The approach hangs off whichever ring you arrive at. On a pair that is the one you are
    // standing on, which is the left by default and is what "approach" names.
    const approach = params.approach !== false;
    if (approach) {
        addBar(twin ? ringX.a : 0, APPROACH, approachLength);
    }

    // What the drawing actually occupies, before any margin. The ring is always in it; everything
    // else depends on which exits were asked for, so an empty sign is still a valid one.
    let left = (twin ? ringX.a : 0) - ringOuter;
    let right = (twin ? ringX.b : 0) + ringOuter;
    let top = -ringOuter;
    let bottom = ringOuter;
    const include = (x, y, w, h) => {
        left = Math.min(left, x);
        top = Math.min(top, y);
        right = Math.max(right, x + w);
        bottom = Math.max(bottom, y + h);
    };
    for (let i = 0; i < bars.length; i += 1) {
        include(bars[i].x, bars[i].y, bars[i].width, bars[i].height);
    }
    for (let i = 0; i < exits.length; i += 1) {
        include(exits[i].legendX, exits[i].legendY, exits[i].block.width, exits[i].block.height);
    }

    // The plate is what the drawing needs plus a margin, and the ring sits wherever the arms put
    // it. Centring the ring on the plate instead was the first attempt and it padded the short side
    // to match the long one: a sign whose only tall legend was above the ring came out with an
    // empty third along the bottom, because the space above had been mirrored below it for nothing.
    const width = Math.round(right - left + m.margin * 2);
    const height = Math.round(bottom - top + m.margin * 2);

    return {
        metrics: m,
        scheme: scheme,
        size: { width: width, height: height },
        centreX: Math.round(m.margin - left),
        centreY: Math.round(m.margin - top),
        ringOuter: ringOuter,
        ringThickness: ringThickness,
        ringOffsets: twin ? [ringX.a, ringX.b] : [0],
        bars: bars,
        exits: exits,
    };
};

defineGenerator({
    id: "roundabout",
    title: "Roundabout direction sign",
    description: "Map-type roundabout sign: a ring with an arm for each exit, labelled with "
        + "destinations and route numbers. Exits are left, ahead and right, square to the sign.",

    params: [
        {
            key: "exits",
            label: "Exits",
            type: "lines",
            default: DEFAULT_EXITS,
            help: "One exit per line: left|Basingstoke|A339. Directions are left, ahead and right. "
                + "Stack two destinations on one arm with a semicolon: left|Basingstoke;Alton|A339. "
                + "With two roundabouts, name one first: b|right|Winchester|A31",
        },
        {
            key: "scheme",
            label: "Route class",
            type: "select",
            options: ["primary", "non-primary", "motorway"],
            default: "primary",
            help: "Primary is green with yellow route numbers, non-primary white with black, "
                + "motorway blue with white.",
        },
        {
            key: "xHeight",
            label: "x-height (px)",
            type: "number",
            default: 40,
            help: "Drives the legend size and, through it, the ring, the arms and the border.",
        },
        {
            key: "roundabouts",
            label: "Roundabouts",
            type: "select",
            options: ["one", "two"],
            default: "one",
            help: "Two draws a dumbbell: a pair side by side with a link between them, which is "
                + "what a dual carriageway junction looks like. Put a| or b| in front of an exit "
                + "to say which roundabout it leaves from.",
        },
        {
            key: "approach",
            label: "Show the approach",
            type: "boolean",
            default: true,
            help: "The stub at the bottom for the road you are on. It carries no legend.",
        },
    ],

    size: (params) => layout(lib.estimateMeasurer(), params).size,

    document: (params) => {
        const l = layout(lib.estimateMeasurer(), params);
        const m = l.metrics;
        const canvas = lib.canvasFor(l.size.width, l.size.height);
        const layers = [];

        layers.push({
            kind: "shape",
            name: "Sign",
            bounds: { x: 0, y: 0, width: canvas.width, height: canvas.height },
            fill: l.scheme.background,
            cornerRadius: m.radius,
            borderColour: l.scheme.border,
            borderWidth: m.border,
        });

        // A square with a corner radius of half its side is a circle, and with a transparent fill
        // and a thick border it is a ring. That is the only way to say "ring" in a document: the
        // layer kinds are rectangle, text, image and group, and this is a rectangle being honest
        // about it rather than a new kind nobody else needs.
        const diameter = l.ringOuter * 2;
        for (let i = 0; i < l.ringOffsets.length; i += 1) {
            layers.push({
                kind: "shape",
                name: l.ringOffsets.length > 1 ? "Roundabout " + (i + 1) : "Roundabout",
                bounds: {
                    x: Math.round(l.centreX + l.ringOffsets[i] - l.ringOuter),
                    y: l.centreY - l.ringOuter,
                    width: diameter,
                    height: diameter,
                },
                fill: "#00000000",
                cornerRadius: Math.round(l.ringOuter),
                borderColour: l.scheme.border,
                borderWidth: l.ringThickness,
            });
        }

        for (let i = 0; i < l.bars.length; i += 1) {
            let bar = l.bars[i];
            layers.push({
                kind: "shape",
                name: "Arm " + (i + 1),
                bounds: {
                    x: Math.round(l.centreX + bar.x),
                    y: Math.round(l.centreY + bar.y),
                    width: Math.round(bar.width),
                    height: Math.round(bar.height),
                },
                fill: l.scheme.border,
                cornerRadius: 0,
                borderColour: "#00000000",
                borderWidth: 0,
            });
        }

        for (let i = 0; i < l.exits.length; i += 1) {
            let exit = l.exits[i];
            let rows = lib.textLayers(
                exit.block,
                l.centreX + exit.legendX,
                l.centreY + exit.legendY,
                exit.block.width,
            );
            for (let r = 0; r < rows.length; r += 1) {
                layers.push(rows[r]);
            }
        }

        return {
            name: "Roundabout sign",
            grid: canvas.grid,
            pixelsPerFrame: canvas.pixelsPerFrame,
            background: "#00000000",
            layers: layers,
        };
    },

    render: (ctx, params) => {
        const l = layout(lib.ctxMeasurer(ctx), params);
        const m = l.metrics;
        lib.warnOverflow("roundabout", l.size.width, ctx.width);

        lib.drawPanel(ctx, 0, 0, ctx.width, ctx.height, {
            metrics: m,
            background: l.scheme.background,
            border: l.scheme.border,
        });

        // The bars first, then the ring over them. Both are the same colour, so the join disappears
        // and what is left is a ring with arms rather than four rectangles touching a circle.
        for (let i = 0; i < l.bars.length; i += 1) {
            let bar = l.bars[i];
            ctx.fillRect(
                l.centreX + bar.x,
                l.centreY + bar.y,
                bar.width,
                bar.height,
                l.scheme.border,
            );
        }
        for (let i = 0; i < l.ringOffsets.length; i += 1) {
            ctx.ring(l.centreX + l.ringOffsets[i], l.centreY, l.ringOuter, l.ringThickness,
                l.scheme.border);
        }

        for (let i = 0; i < l.exits.length; i += 1) {
            let exit = l.exits[i];
            lib.drawTextBlock(ctx, exit.block, l.centreX + exit.legendX, l.centreY + exit.legendY);
        }
    },
});
