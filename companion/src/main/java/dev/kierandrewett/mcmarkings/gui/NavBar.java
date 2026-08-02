package dev.kierandrewett.mcmarkings.gui;

import dev.kierandrewett.mcmarkings.CompanionServices;
import dev.kierandrewett.mcmarkings.McMarkingsCompanion;
import dev.kierandrewett.mcmarkings.Workspace;
import dev.kierandrewett.mcmarkings.config.RepositoryEntry;
import dev.kierandrewett.mcmarkings.gui.imgui.BuilderScreen;
import dev.kierandrewett.mcmarkings.gui.imgui.GeneratorScreen;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import io.wispforest.owo.ui.core.VerticalAlignment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The row of destinations every screen puts at the top, plus the repository
 * switcher.
 *
 * <p>There is one key for the whole mod, so the screens themselves have to be the
 * map. Repeating a header per screen would let them drift apart, which is how you
 * end up with a screen nobody can get out of.
 *
 * <p>Nothing here throws. A nav bar that failed to build would take the screen
 * that embeds it down with it, and losing navigation is worse than losing a
 * button.
 */
public final class NavBar {

    /** Where the bar can take you. Order is the order they appear. */
    public enum Destination {

        BROWSE("Browse", "Search the images in the active repository"),
        GENERATE("Generate", "Build a sign from a generator script"),
        BUILD("Build", "Lay several images out on one canvas"),
        REPOSITORIES("Repositories", "Add, switch, rename or repair repositories"),
        SETTINGS("Settings", "Command alias, export size, fonts and folders");

        private final String label;
        private final String help;

        Destination(String label, String help) {
            this.label = label;
            this.help = help;
        }

        public String label() {
            return label;
        }
    }

    private NavBar() {
    }

    /**
     * The bar, ready to be added as the first child of a screen's root.
     *
     * <p>Two rows: the destinations, and a repository chooser that stays collapsed
     * until it is asked for. The chooser is inline rather than an overlay because
     * an overlay that outlives its screen is a much worse failure than a list that
     * pushes the page down a little.
     */
    public static FlowLayout build(CompanionServices services, Destination current) {
        FlowLayout bar = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        bar.gap(4);

        try {
            FlowLayout chooser = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
            chooser.gap(2);

            FlowLayout row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
            row.gap(4);
            row.verticalAlignment(VerticalAlignment.CENTER);

            for (Destination destination : Destination.values()) {
                row.child(tab(services, destination, current));
            }
            row.child(switcher(services, current, chooser));

            bar.child(row);
            bar.child(chooser);
        } catch (RuntimeException exception) {
            McMarkingsCompanion.LOGGER.error("[mcmarkings] could not build the navigation bar", exception);
            bar.child(UIComponents.label(Component.literal("Navigation unavailable, press escape to close")
                    .withStyle(ChatFormatting.RED)));
        }

        return bar;
    }

    /**
     * Opens a destination, sending anyone with nothing set up to the first-run
     * screen instead of to an editor with no repository behind it.
     */
    public static void open(CompanionServices services, Destination destination) {
        Minecraft client = Minecraft.getInstance();
        try {
            switch (destination) {
                case REPOSITORIES -> client.setScreen(new RepositoriesScreen(services));
                case SETTINGS -> client.setScreen(new SettingsScreen(services));
                case BROWSE -> client.setScreen(needsSetup(services)
                        ? new WelcomeScreen(services)
                        : new BrowserScreen(services));
                case GENERATE -> client.setScreen(needsSetup(services)
                        ? new WelcomeScreen(services)
                        : new GeneratorScreen(services));
                case BUILD -> client.setScreen(needsSetup(services)
                        ? new WelcomeScreen(services)
                        : new BuilderScreen(services));
            }
        } catch (RuntimeException exception) {
            McMarkingsCompanion.LOGGER.error("[mcmarkings] could not open {}", destination, exception);
        }
    }

    private static boolean needsSetup(CompanionServices services) {
        return services == null || !services.hasRepositories();
    }

    private static ButtonComponent tab(CompanionServices services, Destination destination, Destination current) {
        boolean here = destination == current;

        ButtonComponent button = UIComponents.button(
                Component.literal(destination.label)
                        .withStyle(here ? ChatFormatting.YELLOW : ChatFormatting.WHITE),
                pressed -> open(services, destination));

        // The current destination stays visible but unclickable, so the highlight
        // reads as "you are here" rather than as a button that does nothing.
        button.active(!here);
        button.tooltip(Component.literal(here ? "You are here" : destination.help));
        return button;
    }

    /**
     * Shows which repository the screens are acting on, and opens the chooser.
     *
     * <p>Deliberately the widest thing in the bar. The single most confusing state
     * in a multi-repository mod is doing the right thing to the wrong folder.
     */
    private static ButtonComponent switcher(CompanionServices services, Destination current, FlowLayout chooser) {
        String name = activeName(services);
        boolean broken = services != null && services.active().map(NavBar::isBroken).orElse(false);

        ButtonComponent button = UIComponents.button(
                Component.literal(name.isEmpty() ? "No repository yet" : "Repository: " + shorten(name, 24))
                        .withStyle(brokenOrNormal(name, broken)),
                pressed -> toggleChooser(services, current, chooser));
        button.tooltip(Component.literal(name.isEmpty()
                ? "Nothing is set up yet. Click to get started."
                : "Everything acts on this repository. Click to switch."));
        return button;
    }

    private static ChatFormatting brokenOrNormal(String name, boolean broken) {
        if (name.isEmpty()) {
            return ChatFormatting.GOLD;
        }
        return broken ? ChatFormatting.RED : ChatFormatting.AQUA;
    }

    private static void toggleChooser(CompanionServices services, Destination current, FlowLayout chooser) {
        try {
            if (!chooser.children().isEmpty()) {
                chooser.clearChildren();
                return;
            }

            if (needsSetup(services)) {
                Minecraft.getInstance().setScreen(new WelcomeScreen(services));
                return;
            }

            FlowLayout panel = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
            panel.surface(Surface.PANEL_INSET);
            panel.padding(Insets.of(6));
            panel.gap(2);
            panel.child(UIComponents.label(Component.literal("Act on which repository?")
                    .withStyle(ChatFormatting.YELLOW)));

            String activeId = services.activeRepositoryId();
            for (Workspace workspace : services.workspaces()) {
                panel.child(chooserRow(services, current, workspace, workspace.id().equals(activeId)));
            }

            panel.child(UIComponents.button(Component.literal("Manage repositories"),
                    pressed -> open(services, Destination.REPOSITORIES)));

            chooser.child(panel);
        } catch (RuntimeException exception) {
            McMarkingsCompanion.LOGGER.error("[mcmarkings] repository chooser failed", exception);
        }
    }

    /**
     * Switching re-opens the current destination rather than patching the screen in
     * place. Every screen reads the active repository while it builds, so a fresh
     * build is the only way to be sure nothing stale is left on it.
     */
    private static ButtonComponent chooserRow(CompanionServices services, Destination current,
            Workspace workspace, boolean active) {
        boolean broken = isBroken(workspace);
        String label = (active ? "> " : "   ") + shorten(displayName(services, workspace), 34)
                + (broken ? "  (needs attention)" : "");

        ButtonComponent button = UIComponents.button(
                Component.literal(label).withStyle(rowColour(active, broken)),
                pressed -> {
                    services.setActive(workspace.id());
                    open(services, current);
                });
        button.active(!active);
        button.tooltip(Component.literal(workspace.entry().root().toString()));
        return button;
    }

    private static ChatFormatting rowColour(boolean active, boolean broken) {
        if (broken) {
            return ChatFormatting.RED;
        }
        return active ? ChatFormatting.GREEN : ChatFormatting.WHITE;
    }

    /** Cheap enough to ask per frame build: one stat call, no directory walk. */
    static boolean isBroken(Workspace workspace) {
        if (workspace == null) {
            return false;
        }
        Path root = workspace.entry().root();
        return !Files.isDirectory(root) || workspace.hasWarning();
    }

    /** The configured name wins, since a rename does not rebuild the workspace. */
    static String displayName(CompanionServices services, Workspace workspace) {
        if (workspace == null) {
            return "";
        }
        if (services == null) {
            return workspace.entry().displayName();
        }
        return services.config.byId(workspace.id())
                .map(RepositoryEntry::displayName)
                .orElseGet(() -> workspace.entry().displayName());
    }

    private static String activeName(CompanionServices services) {
        if (needsSetup(services)) {
            return "";
        }
        return services.active().map(workspace -> displayName(services, workspace)).orElse("");
    }

    static String shorten(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, Math.max(1, limit - 3)) + "...";
    }
}
