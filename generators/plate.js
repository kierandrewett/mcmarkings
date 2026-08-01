// Worded rectangular plate.
//
// Reproduces the blue "30 mph / speed limit / 250 yards / ahead" advance warning plate: rounded
// rectangle, white inset border, left-aligned legend. The other schemes cover the same plate in
// green, white and yellow, where white and yellow flip the legend to black.

const lib = require("lib");

const DEFAULT_LINES = "30 mph\nspeed limit\n250 yards\nahead";

// One layout function serves both size() and render(). size() has no ctx so it passes the estimating
// measurer; render() passes the host's real one. Same code, same ratios, so the canvas and the
// drawing agree unless the estimate is off, which warnOverflow reports.
const layout = (measure, params) => {
    const p = params || {};
    const xHeight = lib.toNumber(p.xHeight, 60);
    const m = lib.metrics(xHeight);
    const scheme = lib.resolveScheme(p.scheme || "blue");
    const lines = lib.toLines(p.lines === undefined || p.lines === null ? DEFAULT_LINES : p.lines);
    const block = lib.textBlock(measure, lines, {
        font: "transport-medium",
        size: lib.fontSizeFor(xHeight),
        colour: scheme.text,
        align: p.align === "centre" ? "centre" : "left",
        lineGap: m.lineGap,
    });
    // Floors keep an empty or single-word plate looking like a plate rather than a sliver.
    const width = Math.max(block.width + m.margin * 2, m.margin * 4);
    const height = Math.max(block.height + m.margin * 2, m.margin * 2 + Math.round(xHeight));
    return {
        metrics: m,
        scheme: scheme,
        block: block,
        size: { width: Math.round(width), height: Math.round(height) },
    };
};

defineGenerator({
    id: "plate",
    title: "Worded plate",
    description: "Rectangular worded plate with a rounded inset border, such as an advance speed limit warning.",
    params: [
        {
            key: "lines",
            label: "Text lines",
            type: "lines",
            default: DEFAULT_LINES,
            help: "One line per row. The plate is sized to fit them.",
        },
        {
            key: "scheme",
            label: "Colour scheme",
            type: "select",
            options: ["blue", "green", "white", "yellow"],
            default: "blue",
            help: "Blue and green carry white legend; white and yellow carry black legend.",
        },
        {
            key: "xHeight",
            label: "x-height (px)",
            type: "number",
            default: 60,
            help: "Drives the legend size and, through it, the border, corner radius and margins.",
        },
        {
            key: "align",
            label: "Alignment",
            type: "select",
            options: ["left", "centre"],
            default: "left",
            help: "Real advance warning plates are left-aligned; centre suits short single-phrase plates.",
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
        const available = ctx.width - m.margin * 2;
        lib.warnOverflow("plate", l.block.width, available);
        // Left-aligned legend starts at the margin. A centred block is centred against the real canvas
        // rather than against its own width, so a small estimate error shows as even padding either
        // side instead of an off-centre plate.
        const x = l.block.align === "left" ? m.margin : m.margin + Math.round((available - l.block.width) / 2);
        const y = Math.round((ctx.height - l.block.height) / 2);
        lib.drawTextBlock(ctx, l.block, x, y);
    },
});
