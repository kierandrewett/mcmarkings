// Junction direction sign.
//
// Reproduces the green primary-route sign at a junction: white border, destination legend at the
// left with its route number underneath in yellow, a white junction diagram in the middle, and a
// prohibition roundel over an inset white distance panel at the right.
//
// This is laid out as three columns rather than as a true map-type sign where each destination hangs
// off its own arm of the diagram. Columns are deterministic, size() can compute them without a ctx,
// and the result reads correctly; arm-attached legend would need a proper junction geometry model
// and is not worth it for a Minecraft texture.

const lib = require("lib");

const DEFAULT_DESTINATIONS = "Basingstoke|A339\nWootton St Lawrence";

// A real 1024x1024 roundel already in this repo. Verified present at signs/motor_vehicles_prohibited.png.
const DEFAULT_ROUNDEL = "signs/motor_vehicles_prohibited.png";

const DEFAULT_DISTANCE = "500 yards";

const JUNCTION_KINDS = ["crossroads", "t-left", "t-right", "none"];

const resolveJunction = (value) => {
    const key = String(value === null || value === undefined ? "" : value).trim().toLowerCase();
    return JUNCTION_KINDS.indexOf(key) >= 0 ? key : "crossroads";
};

const trimmed = (value) => String(value === null || value === undefined ? "" : value).trim();

// One layout function for size() and render(), the same arrangement as plate.js: size() estimates
// glyph widths, render() measures them.
const layout = (measure, params) => {
    const p = params || {};
    const xHeight = lib.toNumber(p.xHeight, 50);
    const m = lib.metrics(xHeight);
    const scheme = lib.resolveScheme(p.scheme || "primary");
    const size = lib.fontSizeFor(xHeight);
    // One x-height of clear space between columns, the same unit as the margin, so the sign reads as
    // evenly spaced whichever columns happen to be present.
    const gap = m.margin;

    // One block per destination, so a name and its route number stay tight together while separate
    // destinations get pushed apart.
    const source = p.destinations === undefined || p.destinations === null ? DEFAULT_DESTINATIONS : p.destinations;
    const entries = lib.toLines(source);
    const destBlocks = [];
    for (let i = 0; i < entries.length; i += 1) {
        const dest = lib.parseDestination(entries[i]);
        const rows = [];
        if (dest.name.length > 0) {
            rows.push({ text: dest.name, colour: scheme.text });
        }
        if (dest.route.length > 0) {
            rows.push({ text: dest.route, colour: scheme.route });
        }
        if (rows.length === 0) {
            continue;
        }
        destBlocks.push(lib.textBlock(measure, rows, {
            font: "transport-medium",
            size: size,
            align: "left",
            lineGap: m.lineGap,
        }));
    }
    // Twice the within-destination gap, so the eye groups a route number with its own destination.
    const destGap = m.lineGap * 2;
    let destWidth = 0;
    let destHeight = 0;
    for (let i = 0; i < destBlocks.length; i += 1) {
        destWidth = Math.max(destWidth, destBlocks[i].width);
        destHeight += destBlocks[i].height;
        if (i < destBlocks.length - 1) {
            destHeight += destGap;
        }
    }

    const junction = resolveJunction(p.junction);
    // Three x-heights of arm gives the diagram enough room to read as a junction without dominating.
    const junctionWidth = junction === "none" ? 0 : Math.round(xHeight * 3);
    // Two stroke widths. The diagram is a road, so it should read heavier than a letter stroke.
    const junctionStroke = m.border * 2;

    const roundel = trimmed(p.roundel === undefined ? DEFAULT_ROUNDEL : p.roundel);
    // Roundels are drawn noticeably larger than the legend on real signs. imageSize is a ctx call so
    // it is not available here; the box is square and render() letterboxes the real image inside it.
    const roundelSize = roundel.length > 0 ? Math.round(xHeight * 4) : 0;

    // Same rule as the roundel and the destinations: an absent param falls back to the declared
    // default, an explicitly empty one means the operator has turned that part of the sign off.
    const distance = trimmed(p.distancePanel === undefined ? DEFAULT_DISTANCE : p.distancePanel);
    let panel = null;
    if (distance.length > 0) {
        const panelBlock = lib.textBlock(measure, [distance], {
            font: "transport-medium",
            size: size,
            colour: lib.COLOURS.black,
            align: "centre",
            lineGap: 0,
        });
        // Half an x-height of clear space inside the panel, matching the clear space the sign margin
        // leaves inside its own border.
        const pad = Math.round(xHeight * 0.5);
        panel = {
            block: panelBlock,
            pad: pad,
            width: panelBlock.width + pad * 2,
            height: panelBlock.height + pad * 2,
            radius: Math.max(1, Math.round(xHeight * 0.25)),
        };
    }

    const rightWidth = Math.max(roundelSize, panel ? panel.width : 0);
    let rightHeight = roundelSize + (panel ? panel.height : 0);
    if (roundelSize > 0 && panel) {
        rightHeight += m.lineGap;
    }

    let contentWidth = destWidth;
    if (junctionWidth > 0) {
        contentWidth += gap + junctionWidth;
    }
    if (rightWidth > 0) {
        contentWidth += gap + rightWidth;
    }
    // The junction diagram fills whatever height the legend and roundel settle on, but it still needs
    // a floor so a bare "diagram only" sign is not a flat line.
    const junctionFloor = junctionWidth > 0 ? Math.round(xHeight * 2) : 0;
    const contentHeight = Math.max(destHeight, rightHeight, junctionFloor);
    const width = Math.max(contentWidth + m.margin * 2, m.margin * 4);
    const height = Math.max(contentHeight + m.margin * 2, m.margin * 2 + Math.round(xHeight));

    return {
        metrics: m,
        scheme: scheme,
        gap: gap,
        destBlocks: destBlocks,
        destGap: destGap,
        destWidth: destWidth,
        destHeight: destHeight,
        junction: junction,
        junctionWidth: junctionWidth,
        junctionStroke: junctionStroke,
        roundel: roundel,
        roundelSize: roundelSize,
        panel: panel,
        rightWidth: rightWidth,
        rightHeight: rightHeight,
        contentWidth: contentWidth,
        size: { width: Math.round(width), height: Math.round(height) },
    };
};

// Stem plus crossbar. The stem is the road the driver is on and always runs the full content height;
// the crossbar is the side road, on one or both sides depending on the junction.
const drawJunction = (ctx, kind, x, y, w, h, thickness, colour) => {
    const cx = Math.round(x + w / 2);
    const cy = Math.round(y + h / 2);
    ctx.line(cx, y, cx, y + h, thickness, colour);
    if (kind === "crossroads") {
        ctx.line(x, cy, x + w, cy, thickness, colour);
        return;
    }
    if (kind === "t-left") {
        ctx.line(x, cy, cx, cy, thickness, colour);
        return;
    }
    if (kind === "t-right") {
        ctx.line(cx, cy, x + w, cy, thickness, colour);
    }
};

// Letterbox the roundel inside a square box so a non-square image keeps its proportions instead of
// being stretched into an oval.
const drawRoundel = (ctx, path, x, y, box) => {
    let w = box;
    let h = box;
    try {
        const natural = ctx.imageSize(path);
        if (natural && natural.width > 0 && natural.height > 0) {
            const scale = Math.min(box / natural.width, box / natural.height);
            w = Math.max(1, Math.round(natural.width * scale));
            h = Math.max(1, Math.round(natural.height * scale));
        }
    } catch (err) {
        // A roundel that cannot be measured should not take the whole sign down. Fill the box and let
        // drawImage report the real problem.
        console.warn("[direction_sign] could not read image size for " + path + ": " + err);
    }
    ctx.drawImage(path, x + Math.round((box - w) / 2), y + Math.round((box - h) / 2), w, h);
};

defineGenerator({
    id: "direction_sign",
    title: "Junction direction sign",
    description: "Direction sign for a junction, with destinations, route numbers, a junction diagram, "
        + "an optional roundel and an optional distance panel.",
    params: [
        {
            key: "destinations",
            label: "Destinations",
            type: "lines",
            default: DEFAULT_DESTINATIONS,
            help: "One destination per line. Add a route number after a pipe: Basingstoke|A339",
        },
        {
            key: "scheme",
            label: "Route class",
            type: "select",
            options: ["primary", "non-primary", "motorway"],
            default: "primary",
            help: "Primary is green with yellow route numbers, non-primary white with black, motorway blue with white.",
        },
        {
            key: "xHeight",
            label: "x-height (px)",
            type: "number",
            default: 50,
            help: "Drives the legend size and, through it, the border, diagram and roundel proportions.",
        },
        {
            key: "junction",
            label: "Junction diagram",
            type: "select",
            options: JUNCTION_KINDS,
            default: "crossroads",
            help: "Choose none to drop the diagram column entirely.",
        },
        {
            key: "roundel",
            label: "Roundel",
            type: "image",
            default: DEFAULT_ROUNDEL,
            help: "Optional circular sign composited at the right. Leave blank for none.",
        },
        {
            key: "distancePanel",
            label: "Distance panel",
            type: "text",
            default: DEFAULT_DISTANCE,
            help: "Optional white inset panel with black text under the roundel. Leave blank for none.",
        },
    ],

    size: (params) => layout(lib.estimateMeasurer(), params).size,

    render: (ctx, params) => {
        const l = layout(lib.ctxMeasurer(ctx), params);
        const m = l.metrics;
        lib.drawPanel(ctx, 0, 0, ctx.width, ctx.height, {
            metrics: m,
            background: l.scheme.background,
            border: l.scheme.border,
        });

        const top = m.margin;
        const contentHeight = ctx.height - m.margin * 2;
        const contentLeft = m.margin;
        const contentRight = ctx.width - m.margin;
        lib.warnOverflow("direction_sign", l.contentWidth, contentRight - contentLeft);

        // Columns are pinned to the outer edges rather than packed from the left, so any disagreement
        // between the estimated and measured legend widths shows up as a slightly different middle
        // gap instead of pushing the roundel off the sign.
        let y = top + Math.round((contentHeight - l.destHeight) / 2);
        for (let i = 0; i < l.destBlocks.length; i += 1) {
            lib.drawTextBlock(ctx, l.destBlocks[i], contentLeft, y);
            y += l.destBlocks[i].height + l.destGap;
        }
        const destRight = contentLeft + l.destWidth;
        const rightLeft = contentRight - l.rightWidth;

        if (l.junctionWidth > 0) {
            const spanLeft = destRight + l.gap;
            const spanRight = l.rightWidth > 0 ? rightLeft - l.gap : contentRight;
            const slack = Math.max(0, spanRight - spanLeft - l.junctionWidth);
            const jx = Math.round(spanLeft + slack / 2);
            drawJunction(ctx, l.junction, jx, top, l.junctionWidth, contentHeight, l.junctionStroke, lib.COLOURS.white);
        }

        if (l.rightWidth <= 0) {
            return;
        }
        let ry = top + Math.round((contentHeight - l.rightHeight) / 2);
        if (l.roundelSize > 0) {
            drawRoundel(ctx, l.roundel, rightLeft + Math.round((l.rightWidth - l.roundelSize) / 2), ry, l.roundelSize);
            ry += l.roundelSize + m.lineGap;
        }
        if (l.panel) {
            const px = rightLeft + Math.round((l.rightWidth - l.panel.width) / 2);
            lib.drawPanel(ctx, px, ry, l.panel.width, l.panel.height, {
                metrics: m,
                background: lib.COLOURS.white,
                border: null,
                radius: l.panel.radius,
            });
            lib.drawTextBlock(ctx, l.panel.block, px + l.panel.pad, ry + l.panel.pad);
        }
    },
});
