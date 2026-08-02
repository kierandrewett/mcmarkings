package dev.kierandrewett.mcmarkings.gui.imgui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A greyed-out control says why it is greyed out.
 *
 * <p>This started as a sentence in a commit message claiming every disabled control
 * in the mod carried a tooltip explaining itself. It was not true: ten did not, and
 * the pattern ran the wrong way round, with tooltips on the buttons that worked and
 * silence on the ones that did not. "Create map" and "Copy command" both went dead
 * while a git lookup ran, with nothing on screen to say so, which is the worst
 * possible moment for the interface to go quiet.
 *
 * <p>The trap is that {@code ImGui.isItemHovered()} returns false for a disabled
 * item, so the obvious way to write the tooltip produces one that appears only when
 * it is not needed. That is not a visible mistake: the tooltip exists, it reads
 * correctly, and it is simply never on screen in the state it was written for. The
 * check is for the flag rather than for a tooltip, because the tooltip on its own
 * proves nothing.
 *
 * <p>{@code ImGuiScreens.explaining()} is the way to write it now. It carries that
 * flag and answers to keyboard focus as well, so it satisfies this check and is the
 * better of the two.
 *
 * <p>A lint rather than a note, since the note is what drifted.
 */
class DisabledReasonLintTest {

    /** Controls that can be disabled and that someone can point at. */
    private static final Pattern INTERACTIVE =
            Pattern.compile("\\b(button|selectable|checkbox|sliderFloat|dragFloat|combo)\\(");

    private static final Pattern LABEL = Pattern.compile("(?:button|selectable)\\(\"([^\"#]*)");

    /**
     * Controls whose reason is already on screen beside them.
     *
     * <p>A rule that fires on every disabled control teaches people to silence it,
     * so the ones that genuinely explain themselves are named here with the reason
     * rather than being papered over with a tooltip nobody needs. In each of these
     * the thing that disables the control is the thing you are looking at.
     */
    private static final List<String> ALLOWED = List.of(
            // Sits immediately right of the search box it clears, and it is greyed
            // out exactly when that box is empty.
            "Clear",

            // Disabled only at the top of the filesystem, next to a path display
            // showing you are at the top of the filesystem.
            "Up",

            // The grid suggestions. Each is disabled when it is the size already
            // chosen, and the one that is disabled is the one showing as selected.
            "");

    /**
     * How far past {@code endDisabled} to keep looking.
     *
     * <p>The tooltip belongs after the block closes, because the item is submitted
     * by then and the disabled stack is balanced. My first pass at this audit only
     * read inside the block and reported the shared editor helper as an offender
     * when it was correct, which is the false positive this window exists to stop.
     */
    private static final int LINES_AFTER_CLOSE = 8;

    /**
     * Everything a tooltip can belong to.
     *
     * <p>Walking back from a tooltip finds the control it explains, so a control
     * missing from this list is invisible and the search runs past it to whatever
     * came before.
     */
    private static final List<String> CONTROLS = List.of(
            "ImGui.button(", "ImGui.selectable(", "iconButton(", "ImGui.checkbox(",
            "ImGui.dragInt(", "ImGui.dragFloat(", "ImGui.sliderInt(", "ImGui.sliderFloat(",
            "ImGui.inputText(", "ImGui.inputInt(", "ImGui.combo(", "ImGui.colorEdit4(");

    @Test
    @DisplayName("every disabled control says why, in a tooltip that shows while disabled")
    void disabledControlsExplainThemselves() throws IOException {
        List<String> offences = new ArrayList<>();
        Path root = Path.of("src/main/java/dev/kierandrewett/mcmarkings/gui");

        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                offences.addAll(scan(file));
            }
        }

        assertTrue(offences.isEmpty(), () -> """
                A control is greyed out with no way to find out why. Guard its \
                tooltip with ImGuiScreens.explaining(), since plain isItemHovered is \
                false while an item is disabled and the tooltip would never appear. \
                If the reason is already visible beside the control, add its label to \
                ALLOWED with the reason.
                """ + String.join("\n", offences));
    }

    private static List<String> scan(Path file) throws IOException {
        List<String> found = new ArrayList<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

        Deque<Integer> open = new ArrayDeque<>();
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).contains("beginDisabled(")) {
                open.push(index);
            }
            if (!lines.get(index).contains("endDisabled(") || open.isEmpty()) {
                continue;
            }

            int start = open.pop();
            int end = Math.min(lines.size(), index + 1 + LINES_AFTER_CLOSE);
            String region = String.join("\n", lines.subList(start, end));

            // Either the flag directly, or the helper that carries it. The helper also
            // answers to keyboard focus, which the flag alone does not, so it is the
            // better of the two and this check should not push anyone back to the flag.
            boolean explains = region.contains("AllowWhenDisabled")
                    || region.contains("ImGuiScreens.explaining()");
            if (!INTERACTIVE.matcher(region).find() || explains) {
                continue;
            }

            Matcher label = LABEL.matcher(region);
            String name = label.find() ? label.group(1) : "";
            if (ALLOWED.contains(name)) {
                continue;
            }
            found.add("  " + file.getFileName() + ":" + (start + 1)
                    + "  " + (name.isEmpty() ? "an unlabelled control" : "\"" + name + "\""));
        }
        return found;
    }

    /**
     * A control that can speak while disabled says why, not only what.
     *
     * <p>The rule above checks a disabled control is able to explain itself. This
     * checks it has something to explain: making every tooltip answer while disabled
     * meant several of them describing an action the button was not going to take,
     * which is a confident answer to the wrong question.
     *
     * <p>Only for tooltips inside a disabled block whose condition is a variable
     * rather than a constant. A control disabled by something fixed is disabled
     * always, and there is nothing to say about a state it never leaves.
     */
    @Test
    @DisplayName("a tooltip on a control that can be disabled accounts for being disabled")
    void disabledTooltipsSayWhy() throws IOException {
        List<String> silent = new ArrayList<>();
        Path root = Path.of("src/main/java/dev/kierandrewett/mcmarkings/gui");

        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

                // The depth at every line, so a tooltip can be asked about the control
                // it actually belongs to. Looking back a fixed number of lines finds
                // the previous control's block after it has closed, which reported
                // "Add to editor" as needing a disabled reason when it is never
                // disabled.
                int[] depthAt = new int[lines.size()];
                int depth = 0;
                for (int line = 0; line < lines.size(); line++) {
                    if (lines.get(line).contains("beginDisabled(")) {
                        depth++;
                    }
                    depthAt[line] = depth;
                    if (lines.get(line).contains("endDisabled(")) {
                        depth = Math.max(0, depth - 1);
                    }
                }

                for (int at = 0; at < lines.size(); at++) {
                    if (!lines.get(at).contains("ImGuiScreens.explaining()")) {
                        continue;
                    }
                    // The control a tooltip explains is the one submitted before it.
                    // Every control, not only the ones that can be pressed. Knowing
                    // three of them meant a tooltip on a drag field was attributed to
                    // whichever button happened to sit above it, and if that button was
                    // inside a disabled block the tooltip was reported for not
                    // explaining a state it has nothing to do with. Two correct
                    // tooltips were named that way.
                    int control = at;
                    while (control > 0 && CONTROLS.stream().noneMatch(lines.get(control)::contains)) {
                        control--;
                    }
                    if (control == 0 || depthAt[control] == 0) {
                        continue;
                    }
                    String tooltip = String.join(" ", lines.subList(at, Math.min(lines.size(), at + 8)));
                    // A branch, a helper that carries one, or a named reason.
                    boolean accounts = tooltip.contains("?") || tooltip.contains("Reason(")
                            || tooltip.contains(".note()");
                    if (!accounts) {
                        silent.add("  " + file.getFileName() + ":" + (at + 1));
                    }
                }
            }
        }

        assertTrue(silent.isEmpty(), () -> """
                A tooltip can be read while its control is disabled and says the same \
                thing either way, so it describes an action the button will not take. \
                Branch on the condition that disables it, or fold the reason into a \
                helper the way the publish buttons do.
                """ + String.join("\n", silent));
    }
}
