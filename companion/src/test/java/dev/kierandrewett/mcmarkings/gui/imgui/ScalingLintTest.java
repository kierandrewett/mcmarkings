package dev.kierandrewett.mcmarkings.gui.imgui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nothing in the layout is measured in pixels somebody typed.
 *
 * <p>The interface is sized from the text: {@code unit()} is a line height and
 * {@code fieldWidth} is a count of font sizes, so a panel laid out that way follows
 * the GUI scale and now the text size setting as well. A number typed straight into a
 * width or a height opts out of all of that, and it does so quietly, because it looks
 * correct at whatever size it was written against.
 *
 * <p>Written after finding one. A generator's multiline parameter box asked for a
 * hundred and ten pixels, which is a comfortable five lines at the size I happened to
 * be running. At GUI scale four with the largest text the line height is sixty five
 * pixels, so the same box was about to show one and a half lines of a field whose
 * entire purpose is holding several. Nothing about the code looked wrong.
 *
 * <p>Adding a text size setting is what made that reachable, and it is the reason
 * this exists rather than staying a thing I checked once by hand.
 */
class ScalingLintTest {

    /**
     * Calls whose numeric arguments are sizes on screen.
     *
     * <p>The mod's own {@code child} helper is in here for a reason. The first version
     * of this rule only knew about ImGui's own functions and passed clean, while four
     * typed pixel sizes sat in plain sight: a panel body floored at sixty four pixels
     * in two places, and the generator's two panes fixed at two hundred and twenty and
     * three hundred and sixty. All four reached the screen through {@code child},
     * which the rule could not see, so it reported nothing and I believed it.
     */
    private static final Pattern SIZING = Pattern.compile(
            "(?:ImGui|ImGuiScreens)\\.(beginChild|child|textChild|setNextItemWidth|dummy"
                    + "|invisibleButton|inputTextMultiline|setColumnWidth|setNextWindowSize"
                    + "|setCursorPosX|setCursorPosY)\\(([^;]*)\\)");

    /**
     * A size worked out into a local before it is passed anywhere.
     *
     * <p>The other half of the same blind spot. A pixel count assigned to a variable
     * and used a line later is the same pixel count, and it is the more common shape
     * of the two because a size that needs a floor or a clamp needs somewhere to live.
     */
    private static final Pattern SIZE_LOCAL = Pattern.compile(
            "float\\s+\\w*(?:[Hh]eight|HEIGHT|[Ww]idth|WIDTH)\\s*=\\s*([^;]+);");

    /**
     * A literal, and whether it follows a multiplication.
     *
     * <p>The distinction is the whole difficulty. {@code unit() * 8.0f} is eight
     * lines, which is exactly right and scales on its own; {@code Math.max(64.0f, …)}
     * is sixty four pixels, which does not. Both are a float beside a size, and what
     * separates them is the operator in front.
     */
    private static final Pattern PIXELS = Pattern.compile("(\\*\\s*)?(?<![\\w.])(\\d+\\.?\\d*)f?(?![\\w.])");

    /**
     * Below this a literal is a visibility floor rather than a layout size.
     *
     * <p>A gap held at two pixels so it does not vanish, a handle kept at three so it
     * can still be grabbed. Those are about the smallest thing a person can see or
     * hit, which is a real screen-pixel question and does not scale with text.
     */
    private static final double SMALLEST_LAYOUT_SIZE = 8.0;

    /**
     * Ways of asking that follow the text.
     *
     * <p>The viewport ones are here because a fraction of the window is proportional
     * too. The picker modal asks for seven tenths of the screen and that is right at
     * any text size, which my first pass at this reported as an offence.
     */
    private static final List<String> DERIVED = List.of(
            "unit(", "fieldWidth", "getFontSize", "getTextLineHeight", "withinWindow",
            "iconButtonWidth", "getContentRegionAvail", "getStyle", "getWorkSize", "getSize");

    /**
     * Offences in one expression.
     *
     * <p>A number is only an offence when it stands as a size in its own right. One
     * that multiplies something is a count of that thing and scales with it, which is
     * how the whole interface is supposed to be written.
     */
    private static List<String> offences(Path file, String source, int at, String what, String expression) {
        List<String> found = new ArrayList<>();
        Matcher number = PIXELS.matcher(withoutCountsHandedToHelpers(expression));
        while (number.find()) {
            if (number.group(1) != null || Double.parseDouble(number.group(2)) < SMALLEST_LAYOUT_SIZE) {
                continue;
            }
            found.add("  " + file.getFileName() + ":" + (source.substring(0, at).split("\n", -1).length)
                    + "  " + what + number.group(2));
        }
        return found;
    }

    /**
     * Empties out the arguments of the helpers that already work in counts.
     *
     * <p>{@code fieldWidth(24.0f)} is twenty four characters, not twenty four pixels,
     * and it scales perfectly. Without this the rule reported five of those as
     * offences, which is the shape of false positive that gets a check deleted rather
     * than fixed: everything it named was correct and the person reading it learns to
     * stop reading it.
     *
     * <p>Innermost first, repeatedly, so a helper called inside another is emptied
     * before the one holding it.
     */
    private static String withoutCountsHandedToHelpers(String expression) {
        String text = expression;
        for (String helper : DERIVED) {
            String name = helper.endsWith("(") ? helper.substring(0, helper.length() - 1) : helper;
            Pattern call = Pattern.compile(Pattern.quote(name) + "\\([^()]*\\)");
            for (int pass = 0; pass < 4; pass++) {
                String emptied = call.matcher(text).replaceAll(name + "()");
                if (emptied.equals(text)) {
                    break;
                }
                text = emptied;
            }
        }
        return text;
    }

    @Test
    @DisplayName("no width or height is a pixel count somebody typed")
    void layoutFollowsTheTextSize() throws IOException {
        List<String> typed = new ArrayList<>();
        Path root = Path.of("src/main/java/dev/kierandrewett/mcmarkings/gui");

        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher call = SIZING.matcher(source);
                while (call.find()) {
                    typed.addAll(offences(file, source, call.start(),
                            call.group(1) + " asks for ", call.group(2)));
                }

                Matcher local = SIZE_LOCAL.matcher(source);
                while (local.find()) {
                    typed.addAll(offences(file, source, local.start(),
                            "a size local is worked out from ", local.group(1)));
                }
            }
        }

        assertTrue(typed.isEmpty(), () -> """
                A size on screen is a number rather than a multiple of the text. It \
                will look right at the size it was written against and wrong at every \
                other one, and there are now three things that move it: the game's GUI \
                scale, the text size setting, and the window. Ask in line heights with \
                unit() or ImGui.getTextLineHeight(), in font sizes with fieldWidth, or \
                in a fraction of the viewport.
                """ + String.join("\n", typed));
    }

    /**
     * And that it is still looking at something.
     *
     * <p>A rename of any of those calls would leave this passing over nothing, which
     * is the failure mode of every check that works by matching source.
     */
    @Test
    @DisplayName("the rule is still finding the layout calls")
    void theRuleStillMatches() throws IOException {
        int found = 0;
        Path root = Path.of("src/main/java/dev/kierandrewett/mcmarkings/gui");

        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher call = SIZING.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (call.find()) {
                    found++;
                }
            }
        }

        assertTrue(found >= 20,
                "only matched " + found + " sizing calls, so this has stopped reading the "
                        + "layout and a typed pixel size would go straight past it");
    }
}
