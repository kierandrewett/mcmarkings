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

    /** Calls whose numeric arguments are sizes on screen. */
    private static final Pattern SIZING = Pattern.compile(
            "ImGui\\.(beginChild|setNextItemWidth|dummy|invisibleButton|inputTextMultiline"
                    + "|setColumnWidth|setNextWindowSize|setCursorPosX|setCursorPosY)\\(([^;]*)\\)");

    /** A literal big enough to be a pixel count rather than a count of something. */
    private static final Pattern PIXELS = Pattern.compile("(?<![\\w.])(\\d+\\.\\d+f|\\d+)(?![\\w.])");

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

    /** Sentinels and counts, which are not measurements. */
    private static final List<String> NOT_A_SIZE = List.of("0", "1", "0.0f", "1.0f", "-1.0f", "2");

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
                    String arguments = call.group(2);
                    if (DERIVED.stream().anyMatch(arguments::contains)) {
                        continue;
                    }
                    Matcher number = PIXELS.matcher(arguments);
                    while (number.find()) {
                        if (NOT_A_SIZE.contains(number.group(1))) {
                            continue;
                        }
                        typed.add("  " + file.getFileName() + ":"
                                + (source.substring(0, call.start()).split("\n", -1).length)
                                + "  ImGui." + call.group(1) + " asks for " + number.group(1));
                    }
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
