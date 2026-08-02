package dev.kierandrewett.mcmarkings.gui;

import dev.kierandrewett.mcmarkings.CompanionServices;
import dev.kierandrewett.mcmarkings.McMarkingsCompanion;
import dev.kierandrewett.mcmarkings.Workspace;
import dev.kierandrewett.mcmarkings.config.RepositoryEntry;
import dev.kierandrewett.mcmarkings.repo.GitFiles;
import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.component.TextBoxComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.HorizontalAlignment;
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
import java.util.List;

/**
 * Manage every folder the mod knows about.
 *
 * <p>The hard case this screen exists for is a repository whose folder has been
 * moved, renamed or deleted since it was added. That is normal on a machine
 * people actually use, and it must read as a small fixable problem with two
 * obvious buttons, not as a mod that has fallen over. Locating a folder again is
 * treated as the expected repair, not an edge case.
 */
public class RepositoriesScreen extends BaseOwoScreen<FlowLayout> {

    private final CompanionServices services;

    private FlowLayout list;
    private LabelComponent statusLabel;

    /** Holds the nav bar so it can be redrawn when the active repository changes. */
    private FlowLayout navSlot;

    /** At most one row is being renamed or confirming removal at a time. */
    private String renamingId;
    private String removingId;

    public RepositoriesScreen(CompanionServices services) {
        super(Component.literal("MCMarkings repositories"));
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
            // A screen that throws while building leaves the player looking at the
            // world with no way back, so it degrades to something they can read.
            McMarkingsCompanion.LOGGER.error("[mcmarkings] repositories screen failed to build", exception);
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

        navSlot = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        root.child(navSlot);

        root.child(UIComponents.label(Component.literal("Your repositories")
                .withStyle(ChatFormatting.YELLOW)));
        root.child(UIComponents.label(Component.literal(
                "Each one is a folder of images on this computer. Everything you place comes from "
                        + "whichever one is in use.")
                .withStyle(ChatFormatting.GRAY)));

        FlowLayout actions = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        actions.gap(6);
        actions.child(UIComponents.button(Component.literal("Add repository"), pressed -> addRepository()));
        actions.child(UIComponents.button(Component.literal("Close"), pressed -> onClose()));
        root.child(actions);

        statusLabel = UIComponents.label(Component.literal(summaryLine()).withStyle(ChatFormatting.GRAY));
        root.child(statusLabel);

        list = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        list.gap(6);
        root.child(UIContainers.verticalScroll(Sizing.fill(100), Sizing.expand(100), list).scrollStep(28));

        rebuild();
    }

    private String summaryLine() {
        int count = services.workspaces().size();
        if (count == 0) {
            return "Nothing set up yet.";
        }
        return count == 1 ? "1 repository." : count + " repositories.";
    }

    private void rebuild() {
        // The nav bar names the repository in use, so it goes stale the moment a
        // row changes which one that is. Cheaper to redraw it than to reason about
        // which actions could have moved it.
        if (navSlot != null) {
            navSlot.clearChildren();
            navSlot.child(NavBar.build(services, NavBar.Destination.REPOSITORIES));
        }

        if (list == null) {
            return;
        }

        list.clearChildren();

        List<Workspace> workspaces = services.workspaces();
        if (workspaces.isEmpty()) {
            list.child(emptyState());
            return;
        }

        for (Workspace workspace : workspaces) {
            // One unreadable entry, say from a config someone edited by hand, must
            // cost its own row and nothing else. Losing the whole list would take
            // the Remove button with it, which is the way out of that state.
            try {
                list.child(card(workspace));
            } catch (RuntimeException exception) {
                McMarkingsCompanion.LOGGER.error("[mcmarkings] could not draw repository {}",
                        workspace.id(), exception);
                list.child(brokenRow(workspace));
            }
        }
    }

    private FlowLayout brokenRow(Workspace workspace) {
        FlowLayout panel = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        panel.surface(Surface.PANEL_INSET);
        panel.padding(Insets.of(8));
        panel.gap(3);

        panel.child(UIComponents.label(Component.literal("This entry could not be read")
                .withStyle(ChatFormatting.RED)));
        panel.child(UIComponents.label(Component.literal(
                "Its settings do not make sense. Removing it will not touch anything on disk.")
                .withStyle(ChatFormatting.GRAY)));
        panel.child(UIComponents.button(Component.literal("Remove"), pressed -> guarded("Removing", () -> {
            services.removeRepository(workspace.id());
            rebuild();
            status("Removed a repository that could not be read.", ChatFormatting.GREEN);
        })));

        return panel;
    }

    /**
     * Having no repositories is where everyone starts, so this reads as an
     * invitation rather than as an error.
     */
    private FlowLayout emptyState() {
        FlowLayout panel = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        panel.surface(Surface.DARK_PANEL);
        panel.padding(Insets.of(14));
        panel.gap(6);
        panel.horizontalAlignment(HorizontalAlignment.CENTER);

        panel.child(UIComponents.label(Component.literal("No repositories yet")
                .withStyle(ChatFormatting.YELLOW)));
        panel.child(UIComponents.label(Component.literal(
                "Point MCMarkings at a folder of PNGs and you can search them in game and put them on a wall.")
                .withStyle(ChatFormatting.GRAY)));
        panel.child(UIComponents.label(Component.literal(
                "A clone of a GitHub repository works best, because the server fetches the images over "
                        + "the internet.")
                .withStyle(ChatFormatting.GRAY)));
        panel.child(UIComponents.button(Component.literal("Add your first repository"),
                pressed -> addRepository()));

        return panel;
    }

    private FlowLayout card(Workspace workspace) {
        RepositoryEntry entry = services.config.byId(workspace.id()).orElseGet(workspace::entry);
        Path root = entry.root();

        // One stat call, not a directory walk. This runs for every row on every
        // rebuild, so it has to stay cheap.
        boolean missing = !Files.isDirectory(root);
        boolean active = workspace.id().equals(services.activeRepositoryId());

        FlowLayout panel = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        panel.surface(Surface.PANEL_INSET);
        panel.padding(Insets.of(8));
        panel.gap(3);

        panel.child(headerRow(workspace, entry, missing, active));
        panel.child(UIComponents.label(Component.literal(root.toString()).withStyle(ChatFormatting.DARK_GRAY)));

        if (missing) {
            panel.child(UIComponents.label(Component.literal(
                    "This folder is not there any more. It may have been moved, renamed or deleted.")
                    .withStyle(ChatFormatting.RED)));
            panel.child(UIComponents.label(Component.literal(
                    "Nothing has been lost. Point it at the folder's new home, or take it off the list.")
                    .withStyle(ChatFormatting.GRAY)));
        } else {
            panel.child(detailRow(workspace, root));
            if (workspace.hasWarning()) {
                panel.child(UIComponents.label(Component.literal(workspace.warning())
                        .withStyle(ChatFormatting.GOLD)));
            }
        }

        panel.child(workspace.id().equals(removingId)
                ? confirmRemovalRow(workspace, entry)
                : buttonRow(workspace, missing, active));

        return panel;
    }

    private FlowLayout headerRow(Workspace workspace, RepositoryEntry entry, boolean missing, boolean active) {
        FlowLayout row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        row.gap(6);
        row.verticalAlignment(VerticalAlignment.CENTER);

        if (workspace.id().equals(renamingId)) {
            TextBoxComponent nameBox = UIComponents.textBox(Sizing.fill(50), entry.displayName());
            row.child(nameBox);
            row.child(UIComponents.button(Component.literal("Save"),
                    pressed -> rename(entry, nameBox.getValue())));
            row.child(UIComponents.button(Component.literal("Cancel"), pressed -> {
                renamingId = null;
                rebuild();
            }));
            return row;
        }

        ChatFormatting colour = missing ? ChatFormatting.RED
                : active ? ChatFormatting.GREEN : ChatFormatting.WHITE;
        row.child(UIComponents.label(Component.literal(NavBar.shorten(entry.displayName(), 40))
                .withStyle(colour)));

        if (active) {
            row.child(UIComponents.label(Component.literal("in use").withStyle(ChatFormatting.AQUA)));
        }
        if (missing) {
            row.child(UIComponents.label(Component.literal("folder missing").withStyle(ChatFormatting.RED)));
        }

        return row;
    }

    /**
     * Image count is already in memory from the scan at open, so it is free. The
     * remote comes off disk, so it lands late rather than holding up the row.
     */
    private FlowLayout detailRow(Workspace workspace, Path root) {
        FlowLayout row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        row.gap(8);

        int images = imageCount(workspace);
        row.child(UIComponents.label(Component.literal(images == 1 ? "1 image" : images + " images")
                .withStyle(images == 0 ? ChatFormatting.GOLD : ChatFormatting.GRAY)));

        LabelComponent remote = UIComponents.label(Component.literal("checking remote...")
                .withStyle(ChatFormatting.DARK_GRAY));
        row.child(remote);
        resolveRemote(root, remote);

        return row;
    }

    private static int imageCount(Workspace workspace) {
        try {
            return workspace.repo().images().size();
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    /**
     * Reads .git/config rather than shelling out to git, which keeps a row cheap,
     * but it is still disk I/O on a folder that might be a dead network mount.
     */
    private void resolveRemote(Path root, LabelComponent target) {
        Thread.ofVirtual().start(() -> {
            String slug;
            try {
                slug = GitFiles.at(root).flatMap(GitFiles::remoteSlug).orElse("");
            } catch (RuntimeException exception) {
                McMarkingsCompanion.LOGGER.debug("[mcmarkings] could not read the remote of {}", root, exception);
                slug = "";
            }

            String resolved = slug;
            Minecraft.getInstance().execute(() -> target.text(resolved.isBlank()
                    ? Component.literal("no GitHub remote").withStyle(ChatFormatting.GOLD)
                    : Component.literal(resolved).withStyle(ChatFormatting.GRAY)));
        });
    }

    private FlowLayout buttonRow(Workspace workspace, boolean missing, boolean active) {
        FlowLayout row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        row.gap(4);

        if (missing) {
            // A missing folder gets exactly the two things that help: point it
            // somewhere real, or stop tracking it. Everything else would fail.
            row.child(UIComponents.button(Component.literal("Locate"), pressed -> locate(workspace)));
            row.child(UIComponents.button(Component.literal("Remove"), pressed -> askToRemove(workspace)));
            return row;
        }

        row.child(useButton(workspace, active));
        row.child(UIComponents.button(Component.literal("Rename"), pressed -> {
            renamingId = workspace.id();
            removingId = null;
            rebuild();
        }));
        row.child(UIComponents.button(Component.literal("Refresh"), pressed -> refresh(workspace)));

        if (workspace.hasWarning()) {
            row.child(UIComponents.button(Component.literal("Locate"), pressed -> locate(workspace)));
        }

        row.child(UIComponents.button(Component.literal("Remove"), pressed -> askToRemove(workspace)));
        return row;
    }

    private ButtonComponent useButton(Workspace workspace, boolean active) {
        ButtonComponent button = UIComponents.button(Component.literal(active ? "In use" : "Use"), pressed -> {
            services.setActive(workspace.id());
            status("Now using " + NavBar.displayName(services, workspace), ChatFormatting.GREEN);
            rebuild();
        });
        button.active(!active);
        return button;
    }

    /**
     * Removing only edits configuration, but it is still the one action here that
     * throws work away, so it asks first and says what it will not touch.
     */
    private FlowLayout confirmRemovalRow(Workspace workspace, RepositoryEntry entry) {
        FlowLayout row = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        row.gap(3);

        row.child(UIComponents.label(Component.literal(
                "Take " + NavBar.shorten(entry.displayName(), 30) + " off the list?")
                .withStyle(ChatFormatting.YELLOW)));
        row.child(UIComponents.label(Component.literal(
                "The folder and its images stay exactly where they are on disk.")
                .withStyle(ChatFormatting.GRAY)));

        FlowLayout buttons = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        buttons.gap(4);
        buttons.child(UIComponents.button(Component.literal("Yes, remove").withStyle(ChatFormatting.RED),
                pressed -> remove(workspace, entry)));
        buttons.child(UIComponents.button(Component.literal("Keep it"), pressed -> {
            removingId = null;
            rebuild();
        }));
        row.child(buttons);

        return row;
    }

    private void askToRemove(Workspace workspace) {
        removingId = workspace.id();
        renamingId = null;
        rebuild();
    }

    private void remove(Workspace workspace, RepositoryEntry entry) {
        guarded("Removing " + entry.displayName(), () -> {
            services.removeRepository(workspace.id());
            removingId = null;
            renamingId = null;
            rebuild();
            status(entry.displayName() + " removed from the list.", ChatFormatting.GREEN);
        });
    }

    private void rename(RepositoryEntry entry, String newName) {
        guarded("Renaming", () -> {
            String trimmed = newName == null ? "" : newName.trim();
            if (trimmed.isEmpty()) {
                status("A repository needs a name.", ChatFormatting.RED);
                return;
            }

            services.config.replaceRepository(entry.withName(trimmed));
            services.config.save();
            renamingId = null;
            rebuild();
            status("Renamed to " + trimmed, ChatFormatting.GREEN);
        });
    }

    /**
     * Re-scanning walks the whole folder and reads the header of every PNG, which
     * is far too slow for the render thread. The map entry is replaced under a key
     * that is already there, and the screen is only touched back on the client
     * thread once the scan has finished.
     */
    private void refresh(Workspace workspace) {
        String id = workspace.id();
        status("Refreshing " + NavBar.displayName(services, workspace) + "...", ChatFormatting.GRAY);

        Thread.ofVirtual().start(() -> {
            Workspace reloaded;
            try {
                reloaded = services.reload(id);
            } catch (RuntimeException exception) {
                McMarkingsCompanion.LOGGER.error("[mcmarkings] could not refresh {}", id, exception);
                Minecraft.getInstance().execute(() ->
                        status("Could not refresh: " + exception.getMessage(), ChatFormatting.RED));
                return;
            }

            Workspace result = reloaded;
            Minecraft.getInstance().execute(() -> {
                if (result == null) {
                    status("That repository is no longer configured.", ChatFormatting.RED);
                    rebuild();
                    return;
                }
                rebuild();
                status(result.hasWarning()
                                ? "Refreshed, with something to look at: " + result.warning()
                                : "Refreshed, " + imageCount(result) + " images",
                        result.hasWarning() ? ChatFormatting.GOLD : ChatFormatting.GREEN);
            });
        });
    }

    private void addRepository() {
        openPicker(startingPoint(), directory -> guarded("Adding a repository", () -> {
            Workspace added = services.addRepository(directory);
            status(added.hasWarning()
                            ? "Added, with something to look at: " + added.warning()
                            : "Added " + NavBar.displayName(services, added),
                    added.hasWarning() ? ChatFormatting.GOLD : ChatFormatting.GREEN);
        }));
    }

    /**
     * Re-points a repository at a folder that has moved.
     *
     * <p>The id is derived from the path, so a moved repository is genuinely a
     * different entry rather than the same one edited. It is rebuilt in place: the
     * old entry goes, the new folder is added, and the name and in-use flag are
     * carried across so it still feels like the same repository afterwards.
     */
    private void locate(Workspace workspace) {
        RepositoryEntry old = services.config.byId(workspace.id()).orElseGet(workspace::entry);
        boolean wasActive = workspace.id().equals(services.activeRepositoryId());

        openPicker(startingPoint(), directory -> guarded("Locating " + old.displayName(), () -> {
            services.removeRepository(old.id());

            Workspace relocated = services.addRepository(directory);
            services.config.byId(relocated.id())
                    .ifPresent(entry -> services.config.replaceRepository(entry.withName(old.name())));
            services.config.save();

            if (wasActive) {
                services.setActive(relocated.id());
            }

            status(relocated.hasWarning()
                            ? "Re-pointed, with something to look at: " + relocated.warning()
                            : "Found it. " + old.displayName() + " now points at " + directory,
                    relocated.hasWarning() ? ChatFormatting.GOLD : ChatFormatting.GREEN);
        }));
    }

    /**
     * The picker returns to this screen once the callback has run, so the callback
     * only changes state and lets the rebuilt screen show the result.
     */
    private void openPicker(Path startAt, java.util.function.Consumer<Path> onChosen) {
        removingId = null;
        renamingId = null;
        Minecraft.getInstance().setScreen(new DirectoryPickerScreen(this, startAt, onChosen));
    }

    /**
     * Starts next door to a repository that already works, since a second one is
     * usually a sibling of the first. Falls back to the home directory, and never
     * assumes anything about how this machine is laid out.
     */
    private Path startingPoint() {
        Path home = Path.of(System.getProperty("user.home", "."));

        return services.active()
                .map(workspace -> workspace.entry().root())
                .filter(Files::isDirectory)
                .map(Path::getParent)
                .filter(Files::isDirectory)
                .orElse(home);
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
