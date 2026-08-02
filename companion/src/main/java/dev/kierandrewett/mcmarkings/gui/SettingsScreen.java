package dev.kierandrewett.mcmarkings.gui;

import dev.kierandrewett.mcmarkings.CompanionServices;
import dev.kierandrewett.mcmarkings.McMarkingsCompanion;
import dev.kierandrewett.mcmarkings.config.CompanionConfig;
import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.component.CheckboxComponent;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.component.TextBoxComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import io.wispforest.owo.ui.core.VerticalAlignment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Everything in the config file except the repository list, editable in game.
 *
 * <p>The config is JSON on disk because that is a good storage format, not
 * because anyone should have to open it. Each field saves as you type, and a
 * value that would break the mod is refused here with a reason rather than
 * written out and discovered later as a sign that will not place.
 */
public class SettingsScreen extends BaseOwoScreen<FlowLayout> {

    /** Above this, one map frame costs more memory than it is worth. */
    private static final int MAX_PIXELS_PER_FRAME = 4096;

    /** Servers drop chat well before this, so anything faster is wishful. */
    private static final double MAX_COMMANDS_PER_SECOND = 20.0;

    private final CompanionServices services;

    private LabelComponent statusLabel;
    private FlowLayout fontPathList;

    public SettingsScreen(CompanionServices services) {
        super(Component.literal("MCMarkings settings"));
        this.services = services;
    }

    @Override
    protected @NotNull OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        try {
            layout(root);
        } catch (RuntimeException exception) {
            // Losing the settings screen must not strand the player in a screen
            // with no way out, so it degrades to something readable.
            McMarkingsCompanion.LOGGER.error("[mcmarkings] settings screen failed to build", exception);
            root.clearChildren();
            root.child(UIComponents.label(Component.literal(
                    "Something went wrong drawing this screen. Press escape to close.")
                    .withStyle(ChatFormatting.RED)));
        }
    }

    private void layout(FlowLayout root) {
        root.surface(Surface.VANILLA_TRANSLUCENT);
        root.padding(Insets.of(8));
        root.gap(6);

        root.child(NavBar.build(services, NavBar.Destination.SETTINGS));

        root.child(UIComponents.label(Component.literal("Settings").withStyle(ChatFormatting.YELLOW)));

        FlowLayout actions = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        actions.gap(6);
        actions.child(UIComponents.button(Component.literal("Reload now"), pressed -> reloadEverything()));
        actions.child(UIComponents.button(Component.literal("Close"), pressed -> onClose()));
        root.child(actions);

        statusLabel = UIComponents.label(Component.literal("Changes save as you type.")
                .withStyle(ChatFormatting.GRAY));
        root.child(statusLabel);

        FlowLayout page = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        page.gap(8);
        page.child(commandsSection());
        page.child(generatingSection());
        page.child(fontsSection());

        root.child(UIContainers.verticalScroll(Sizing.fill(100), Sizing.expand(100), page).scrollStep(28));
    }

    private FlowLayout commandsSection() {
        CompanionConfig config = services.config;
        FlowLayout section = section("Commands", "How the mod talks to the server.");

        section.child(textField("Command alias",
                "The ImageFrame command without the slash. Some servers rebind it.",
                config.commandAlias,
                value -> {
                    String trimmed = value.trim();
                    if (trimmed.isEmpty()) {
                        return "The command alias cannot be empty.";
                    }
                    if (trimmed.startsWith("/")) {
                        return "Leave the slash off, just the command name.";
                    }
                    if (trimmed.contains(" ")) {
                        return "A command name cannot contain spaces.";
                    }
                    config.commandAlias = trimmed;
                    return null;
                }));

        section.child(textField("Commands per second",
                "Kept low so the server does not drop them as chat spam.",
                String.valueOf(config.commandsPerSecond),
                value -> {
                    Double parsed = parseDouble(value);
                    if (parsed == null || parsed <= 0.0) {
                        return "Give a number greater than zero.";
                    }
                    if (parsed > MAX_COMMANDS_PER_SECOND) {
                        return "Anything above " + MAX_COMMANDS_PER_SECOND + " a second will just be dropped.";
                    }
                    config.commandsPerSecond = parsed;
                    return null;
                }));

        section.child(checkboxField("Ask for glowing item frames",
                "Glowing frames hide the map border, which suits signs.",
                config.glowingFrames,
                checked -> {
                    config.glowingFrames = checked;
                    return null;
                }));

        section.child(note("The command rate applies the next time the mod reloads."));
        return section;
    }

    private FlowLayout generatingSection() {
        CompanionConfig config = services.config;
        FlowLayout section = section("Generating and building",
                "Where generated signs are written, and how detailed they are.");

        section.child(textField("Export pixels per frame",
                "Vanilla maps are 128. ImageFrame renders more detail, so 256 is a good default.",
                String.valueOf(config.exportPixelsPerFrame),
                value -> {
                    Integer parsed = parseInteger(value);
                    if (parsed == null || parsed <= 0) {
                        return "Give a whole number greater than zero.";
                    }
                    if (parsed > MAX_PIXELS_PER_FRAME) {
                        return "Keep it at or below " + MAX_PIXELS_PER_FRAME + ", or a large sign will not fit "
                                + "in memory.";
                    }
                    config.exportPixelsPerFrame = parsed;
                    return null;
                }));

        section.child(textField("Generated images folder",
                "Inside each repository. Generated signs are written here.",
                config.generatedDirectory,
                value -> {
                    String problem = relativeFolderProblem(value);
                    if (problem != null) {
                        return problem;
                    }
                    config.generatedDirectory = value.trim();
                    return null;
                }));

        section.child(textField("Generator scripts folder",
                "Inside each repository. The .js files that draw signs are read from here.",
                config.generatorDirectory,
                value -> {
                    String problem = relativeFolderProblem(value);
                    if (problem != null) {
                        return problem;
                    }
                    config.generatorDirectory = value.trim();
                    return null;
                }));

        section.child(note("Scripts are re-read when a repository is refreshed or the mod reloads."));
        return section;
    }

    private FlowLayout fontsSection() {
        FlowLayout section = section("Fonts",
                "Generated signs are lettered in Transport, which is not shipped with the mod.");

        boolean hasTransport = hasTransport();
        section.child(UIComponents.label(Component.literal(hasTransport
                        ? "Transport was found. Generated signs will be lettered properly."
                        : "Transport was not found. Signs still generate, but in a substitute typeface "
                                + "that will not look right.")
                .withStyle(hasTransport ? ChatFormatting.GREEN : ChatFormatting.GOLD)));

        for (String warning : fontWarnings()) {
            section.child(UIComponents.label(Component.literal(warning).withStyle(ChatFormatting.GOLD)));
        }

        section.child(UIComponents.label(Component.literal("Folders searched for fonts")
                .withStyle(ChatFormatting.WHITE)));

        fontPathList = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        fontPathList.gap(2);
        section.child(fontPathList);
        rebuildFontPaths();

        FlowLayout add = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        add.gap(4);
        add.verticalAlignment(VerticalAlignment.CENTER);

        TextBoxComponent box = UIComponents.textBox(Sizing.fill(60));
        add.child(box);
        add.child(UIComponents.button(Component.literal("Add"), pressed -> addFontPath(box.getValue())));
        add.child(UIComponents.button(Component.literal("Choose folder"), pressed -> pickFontPath()));
        section.child(add);

        section.child(note("Font changes apply the next time the mod reloads."));
        return section;
    }

    private void rebuildFontPaths() {
        if (fontPathList == null) {
            return;
        }

        fontPathList.clearChildren();

        List<String> paths = services.config.fontSearchPaths;
        if (paths == null || paths.isEmpty()) {
            fontPathList.child(UIComponents.label(Component.literal(
                    "No folders are being searched, so Transport cannot be found.")
                    .withStyle(ChatFormatting.GOLD)));
            return;
        }

        for (String path : List.copyOf(paths)) {
            fontPathList.child(fontPathRow(path));
        }
    }

    private FlowLayout fontPathRow(String path) {
        boolean present = isExistingDirectory(path);

        FlowLayout row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        row.gap(6);
        row.verticalAlignment(VerticalAlignment.CENTER);
        row.child(UIComponents.label(Component.literal(path)
                .withStyle(present ? ChatFormatting.GRAY : ChatFormatting.DARK_GRAY)));

        if (!present) {
            row.child(UIComponents.label(Component.literal("not on this computer")
                    .withStyle(ChatFormatting.GOLD)));
        }

        row.child(UIComponents.button(Component.literal("Remove"), pressed -> removeFontPath(path)));
        return row;
    }

    private void addFontPath(String value) {
        guarded("Adding a font folder", () -> {
            String trimmed = value == null ? "" : value.trim();
            if (trimmed.isEmpty()) {
                status("Type a folder, or use Choose folder.", ChatFormatting.RED);
                return;
            }

            List<String> paths = fontPaths();
            if (paths.contains(trimmed)) {
                status("That folder is already being searched.", ChatFormatting.GOLD);
                return;
            }

            paths.add(trimmed);
            services.config.save();
            rebuildFontPaths();
            status(isExistingDirectory(trimmed)
                            ? "Added " + trimmed
                            : "Added " + trimmed + ", though nothing is there at the moment.",
                    isExistingDirectory(trimmed) ? ChatFormatting.GREEN : ChatFormatting.GOLD);
        });
    }

    private void removeFontPath(String path) {
        guarded("Removing a font folder", () -> {
            fontPaths().remove(path);
            services.config.save();
            rebuildFontPaths();
            status("Removed " + path, ChatFormatting.GREEN);
        });
    }

    /**
     * The picker returns to this screen once the callback has run, so this only
     * changes state and lets the rebuilt screen show it.
     */
    private void pickFontPath() {
        Path start = Path.of(System.getProperty("user.home", "."));
        Minecraft.getInstance().setScreen(new DirectoryPickerScreen(this, start,
                directory -> addFontPath(directory.toString())));
    }

    /** Never null, even if a hand-edited config left the list out entirely. */
    private List<String> fontPaths() {
        if (services.config.fontSearchPaths == null) {
            services.config.fontSearchPaths = new ArrayList<>();
        }
        return services.config.fontSearchPaths;
    }

    private List<String> fontWarnings() {
        try {
            return services.fonts.warnings();
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private boolean hasTransport() {
        try {
            return services.fonts.hasTransport();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean isExistingDirectory(String path) {
        try {
            return Files.isDirectory(Path.of(path));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * Throws away the loaded services and builds them again, which is the only way
     * fonts and generator folders take effect without restarting the game.
     *
     * <p>Deliberately on the client thread. It re-scans every repository, so it
     * does pause for a moment, but it must not run while another screen is reading
     * the services it is replacing.
     */
    private void reloadEverything() {
        guarded("Reloading", () -> {
            status("Reloading, this can pause for a moment...", ChatFormatting.GRAY);
            services.config.save();

            // The textures belong to the services being thrown away, and nothing
            // else will ever free them once the reference is gone.
            services.thumbnails.evictAll();
            McMarkingsCompanion.reset();

            CompanionServices reloaded = McMarkingsCompanion.services();
            Minecraft.getInstance().setScreen(new SettingsScreen(reloaded));
        });
    }

    private FlowLayout section(String title, String help) {
        FlowLayout panel = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        panel.surface(Surface.PANEL_INSET);
        panel.padding(Insets.of(8));
        panel.gap(4);
        panel.child(UIComponents.label(Component.literal(title).withStyle(ChatFormatting.YELLOW)));
        panel.child(UIComponents.label(Component.literal(help).withStyle(ChatFormatting.GRAY)));
        return panel;
    }

    private LabelComponent note(String text) {
        return UIComponents.label(Component.literal(text).withStyle(ChatFormatting.DARK_GRAY));
    }

    /**
     * One labelled text field that validates as you type.
     *
     * <p>{@code apply} returns null when it accepted the value, or the reason it
     * did not. Nothing is written to disk unless it accepted, so a half-typed
     * number never reaches the config file.
     */
    private FlowLayout textField(String title, String help, String value, Function<String, String> apply) {
        FlowLayout group = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        group.gap(2);
        group.child(UIComponents.label(Component.literal(title).withStyle(ChatFormatting.WHITE)));
        group.child(UIComponents.label(Component.literal(help).withStyle(ChatFormatting.DARK_GRAY)));

        TextBoxComponent box = UIComponents.textBox(Sizing.fill(60), value == null ? "" : value);
        group.child(box);

        // Always present, so the row does not jump about as errors come and go.
        LabelComponent problemLabel = UIComponents.label(Component.literal(""));
        group.child(problemLabel);

        box.onChanged().subscribe(text -> {
            String problem = validate(title, apply, text);
            problemLabel.text(problem == null
                    ? Component.literal("")
                    : Component.literal(problem).withStyle(ChatFormatting.RED));
            if (problem == null) {
                services.config.save();
                status(title + " saved.", ChatFormatting.GREEN);
            }
        });

        return group;
    }

    private FlowLayout checkboxField(String title, String help, boolean value, Function<Boolean, String> apply) {
        FlowLayout group = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        group.gap(2);

        CheckboxComponent checkbox = UIComponents.checkbox(Component.literal(title)).checked(value);
        // Subscribed after the initial value, so opening the screen does not look
        // like an edit and does not rewrite the config file.
        checkbox.onChanged(checked -> {
            String problem = validate(title, apply, checked);
            if (problem != null) {
                status(problem, ChatFormatting.RED);
                return;
            }
            services.config.save();
            status(title + " saved.", ChatFormatting.GREEN);
        });

        group.child(checkbox);
        group.child(UIComponents.label(Component.literal(help).withStyle(ChatFormatting.DARK_GRAY)));
        return group;
    }

    /** A validator that throws is a bug, not a reason to lose the screen. */
    private <T> String validate(String title, Function<T, String> apply, T value) {
        try {
            return apply.apply(value);
        } catch (RuntimeException exception) {
            McMarkingsCompanion.LOGGER.error("[mcmarkings] validating {} failed", title, exception);
            return "Could not use that value.";
        }
    }

    private static String relativeFolderProblem(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            return "Give a folder name, or generated files would land in the repository root.";
        }
        if (trimmed.startsWith("/") || trimmed.startsWith("\\") || trimmed.contains(":")) {
            return "This is a folder inside each repository, so keep it relative.";
        }
        if (trimmed.contains("..")) {
            return "Keep it inside the repository, with no .. in the path.";
        }
        return null;
    }

    private static Integer parseInteger(String value) {
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Double parseDouble(String value) {
        try {
            return Double.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private void guarded(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            McMarkingsCompanion.LOGGER.error("[mcmarkings] {} failed", what, exception);
            status(what + " failed: " + exception.getMessage(), ChatFormatting.RED);
        }
    }

    private void status(String message, ChatFormatting colour) {
        if (statusLabel != null) {
            statusLabel.text(Component.literal(message).withStyle(colour));
        }
    }
}
