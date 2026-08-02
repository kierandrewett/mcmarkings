package dev.kierandrewett.mcmarkings.config;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every offered text size lands on a whole pixel.
 *
 * <p>This is the entire reason the control is a set of steps rather than a slider
 * over a range. The bundled face is a pixel font, and a pixel font asked for a
 * fractional height puts its glyphs between pixels and comes out furry. That was a
 * real complaint about this interface before the font was even adjustable, and
 * handing people a free slider would be handing them the same complaint with a
 * control to cause it themselves.
 *
 * <p>The property holds because of an arithmetic accident worth keeping deliberate:
 * the base height is sixteen pixels and the bundled face is asked for five eighths of
 * it, which is exactly ten, so any multiplier in tenths times any whole GUI scale is
 * a whole number. Change either constant and that stops being true silently, at which
 * point nothing looks wrong in the code and the text looks wrong on screen.
 */
class TextScaleTest {

    private static final Path FONTS =
            Path.of("src/main/java/dev/kierandrewett/mcmarkings/gui/imgui/ImGuiFonts.java");

    /** The GUI scales Minecraft actually offers. */
    private static final List<Integer> GUI_SCALES = List.of(1, 2, 3, 4);

    @Test
    @DisplayName("every offered size gives the pixel font a whole pixel height")
    void everyStepLandsWhole() throws IOException {
        double base = constant("BASE_PIXELS");
        double bundled = constant("BUNDLED_SCALE");

        List<String> fractional = new ArrayList<>();
        for (double step : CompanionConfig.TEXT_SCALE_STEPS) {
            for (int guiScale : GUI_SCALES) {
                double pixels = base * guiScale * step * bundled;
                if (Math.abs(pixels - Math.rint(pixels)) > 1e-9) {
                    fractional.add("  " + Math.round(step * 100) + "% at GUI scale " + guiScale
                            + " asks for " + pixels + " pixels");
                }
            }
        }

        assertTrue(fractional.isEmpty(), () -> """
                A text size lands the bundled pixel font between pixels, which is what \
                makes it look furry. The steps are tenths and the height they multiply \
                has to stay a whole number of pixels per unit of GUI scale: it is \
                BASE_PIXELS times BUNDLED_SCALE in ImGuiFonts, which is ten today. \
                Either pick steps that divide whatever it has become, or put it back.
                """ + String.join("\n", fractional));
    }

    /**
     * Every offered size actually changes the text at every scale the game offers.
     *
     * <p>The atlas has a ceiling on it, to stop a pathological GUI scale asking for
     * something enormous. When this setting was added that ceiling was sixty four,
     * which is exactly the base height at GUI scale four, so every size above the
     * default would have clamped to the same number: the slider moves, the setting
     * saves, the atlas rebuilds, and the text comes back the size it already was.
     *
     * <p>The worst kind of broken, because nothing reports it and the person moving
     * the slider concludes the mod ignores them. Two constants in different files have
     * to agree and neither mentions the other, so it is checked here.
     */
    @Test
    @DisplayName("no offered size is silently swallowed by the atlas ceiling")
    void everyStepIsReachable() throws IOException {
        double base = constant("BASE_PIXELS");
        double ceiling = constant("MAX_PIXELS");
        double largestScale = GUI_SCALES.getLast();
        double largestStep = CompanionConfig.TEXT_SCALE_STEPS.getLast();

        double wanted = base * largestScale * largestStep;
        assertTrue(wanted <= ceiling, () ->
                "the largest offered size asks for " + wanted + " pixels at GUI scale " + largestScale
                        + " and MAX_PIXELS in ImGuiFonts stops it at " + ceiling + ", so the sizes "
                        + "above " + Math.round(ceiling / (base * largestScale) * 100) + "% all come "
                        + "out identical there and the setting looks broken. Raise the ceiling or "
                        + "drop the sizes that cannot be reached.");
    }

    /**
     * And that the steps are tenths, which is the other half of the same argument.
     *
     * <p>Kept separate so a failure says which assumption went, since the two are
     * fixed independently and the fix is different for each.
     */
    @Test
    @DisplayName("the offered sizes are tenths, in order, and include unchanged")
    void theStepsAreWellFormed() {
        List<Double> steps = CompanionConfig.TEXT_SCALE_STEPS;

        assertTrue(steps.contains(1.0), "there is no way back to the default size");
        for (int at = 1; at < steps.size(); at++) {
            assertTrue(steps.get(at) > steps.get(at - 1),
                    "the sizes are not in order, so the slider would run backwards at " + at);
        }
        for (double step : steps) {
            assertEquals(0.0, Math.abs(step * 10 - Math.rint(step * 10)), 1e-9,
                    step + " is not a tenth, so the whole-pixel argument does not cover it");
        }
    }

    /** Anything already in a config file, however it got there, resolves to a step. */
    @Test
    @DisplayName("a size from outside the offered set snaps to the nearest one")
    void strayValuesSnap() {
        assertEquals(1.0, CompanionConfig.nearestTextScale(1.0));
        assertEquals(1.2, CompanionConfig.nearestTextScale(1.17));
        assertEquals(0.8, CompanionConfig.nearestTextScale(-4.0), "a negative size would be invisible");
        assertEquals(1.6, CompanionConfig.nearestTextScale(1000.0), "an enormous size would fill the screen");
        assertEquals(0.8, CompanionConfig.nearestTextScale(0.0),
                "zero is nearest the smallest offered size, not the default");
    }

    /**
     * A config written before this setting existed opens at the normal size.
     *
     * <p>Checked rather than reasoned about. I wrote the assertion above believing an
     * absent field arrived as zero, which would have snapped every existing config to
     * the smallest text in the list on first launch. Gson leaves a field it does not
     * find alone, so the initialiser stands, but that is a fact about Gson and worth
     * one line of proof rather than my memory of it.
     */
    @Test
    @DisplayName("a config from before this setting existed opens unchanged")
    void oldConfigsAreUnaffected() {
        CompanionConfig loaded = new com.google.gson.Gson()
                .fromJson("{\"commandAlias\":\"imageframe\"}", CompanionConfig.class);

        assertEquals(1.0, loaded.textScale,
                "an existing config would change size on its own the first time it was opened");
    }

    private static double constant(String name) throws IOException {
        String source = Files.readString(FONTS, StandardCharsets.UTF_8);
        Matcher found = Pattern.compile(name + "\\s*=\\s*([0-9.]+)f").matcher(source);
        assertTrue(found.find(), name + " is no longer written as a literal in ImGuiFonts, "
                + "so this check cannot see what the sizes are being multiplied by");
        return Double.parseDouble(found.group(1));
    }
}
