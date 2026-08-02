package dev.kierandrewett.mcmarkings.gui.imgui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Toolbar icons stay inside their square, and stay distinct from each other.
 *
 * <p>Both of these were caught by rendering the set at the sizes it is used at and
 * looking, not by reasoning. Place was the four frames with one filled, which is the
 * same picture as Frames at thirteen pixels; redrawn as an arrow into a bar it became
 * Save, which is its neighbour. It is a sideways arrow into a wall now.
 *
 * <p>The distinctness check here is a coarse version of that: two icons with the same
 * geometry cannot be told apart, and the eye is needed for the rest. It catches the
 * copy-and-paste case, which is the one that would slip in later.
 */
class IconTest {

    private static Map<String, Icon> all() {
        Map<String, Icon> icons = new HashMap<>();
        for (Field field : Icon.class.getFields()) {
            if (field.getType() == Icon.class) {
                try {
                    icons.put(field.getName(), (Icon) field.get(null));
                } catch (IllegalAccessException unreachable) {
                    throw new AssertionError(unreachable);
                }
            }
        }
        return icons;
    }

    @Test
    @DisplayName("every icon is drawn inside the square it is given")
    void iconsStayInTheirBox() {
        List<String> escaping = new ArrayList<>();

        all().forEach((name, icon) -> {
            for (float[] stroke : icon.strokes()) {
                for (float value : stroke) {
                    if (value < 0.0f || value > 1.0f) {
                        escaping.add(name + " has a stroke at " + value);
                    }
                }
            }
            for (float[] box : icon.boxes()) {
                if (box[0] < 0.0f || box[1] < 0.0f
                        || box[0] + box[2] > 1.0f || box[1] + box[3] > 1.0f) {
                    escaping.add(name + " has a box running to "
                            + (box[0] + box[2]) + "," + (box[1] + box[3]));
                }
            }
        });

        assertTrue(escaping.isEmpty(), () -> "an icon draws outside its button: " + escaping);
    }

    @Test
    @DisplayName("no two icons are the same picture")
    void iconsAreDistinct() {
        Map<String, String> byShape = new HashMap<>();
        List<String> clashes = new ArrayList<>();

        all().forEach((name, icon) -> {
            StringBuilder shape = new StringBuilder();
            icon.strokes().forEach(stroke -> shape.append(java.util.Arrays.toString(stroke)));
            shape.append('|');
            icon.boxes().forEach(box -> shape.append(java.util.Arrays.toString(box)));

            String existing = byShape.putIfAbsent(shape.toString(), name);
            if (existing != null) {
                clashes.add(name + " and " + existing);
            }
        });

        assertTrue(clashes.isEmpty(), () -> "two icons draw the same thing: " + clashes);
    }

    /**
     * An icon of nothing is a blank button, which reads as broken rather than as an
     * icon that has not been drawn yet.
     */
    @Test
    @DisplayName("no icon is empty")
    void everyIconDrawsSomething() {
        all().forEach((name, icon) -> assertTrue(
                !icon.strokes().isEmpty() || !icon.boxes().isEmpty(), name + " draws nothing"));
    }

    /**
     * Destructive confirmations are words, not pictures.
     *
     * <p>The routine actions on a placed map are icons now, because every map carries
     * that row and a dozen signs was a dozen rows of seven words. The confirmations
     * are deliberately left alone: an icon is a thing you interpret, and the moment to
     * be certain rather than quick is the one where something goes.
     *
     * <p>Checked in the source, since it is a decision about that panel rather than a
     * property of the icons.
     */
    @Test
    @DisplayName("the confirm buttons on a placed map are still words")
    void confirmationsAreNotIcons() throws java.io.IOException {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/dev/kierandrewett/mcmarkings/gui/imgui/panel/PlacedPanel.java"));

        for (String confirm : List.of("\"Forget\"", "\"Keep\"", "\"Delete##server\"", "\"Keep##server\"")) {
            assertTrue(source.contains("ImGui.button(" + confirm),
                    "the confirmation " + confirm + " is no longer a plain labelled button");
        }
    }

    /**
     * Every command the toolbar claims to offer has an icon to offer it with.
     *
     * <p>Distribute was registered when the editor was built and lived only in the
     * command palette, which for a tool you reach for while arranging things means
     * it may as well not exist. Checking the toolbar against the icons is the cheap
     * half of noticing that.
     */
    @Test
    @DisplayName("the alignment set on the toolbar is complete")
    void theAlignmentSetIsComplete() throws java.io.IOException {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/dev/kierandrewett/mcmarkings/gui/imgui/panel/EditorPanel.java"));

        for (String command : List.of("editor.align.left", "editor.align.centre", "editor.align.right",
                "editor.align.top", "editor.align.middle", "editor.align.bottom",
                "editor.distribute.horizontal", "editor.distribute.vertical")) {
            assertTrue(source.contains("new ToolbarItem(\"" + toolbarLabel(command)),
                    command + " is registered but not on the toolbar, so it is palette-only");
        }
    }

    private static String toolbarLabel(String command) {
        return switch (command) {
            case "editor.align.left" -> "Align left";
            case "editor.align.centre" -> "Align centre";
            case "editor.align.right" -> "Align right";
            case "editor.align.top" -> "Align top";
            case "editor.align.middle" -> "Align middle";
            case "editor.align.bottom" -> "Align bottom";
            case "editor.distribute.horizontal" -> "Distribute horizontally";
            default -> "Distribute vertically";
        };
    }
}
