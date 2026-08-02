package dev.kierandrewett.mcmarkings.gui.imgui.panel;

import dev.kierandrewett.mcmarkings.CompanionServices;
import dev.kierandrewett.mcmarkings.McMarkingsCompanion;
import dev.kierandrewett.mcmarkings.core.MapEntry;
import dev.kierandrewett.mcmarkings.gui.imgui.ImGuiScreens;
import dev.kierandrewett.mcmarkings.gui.imgui.Notice;
import dev.kierandrewett.mcmarkings.gui.imgui.Persist;
import dev.kierandrewett.mcmarkings.imageframe.ImageFrameCommands;
import dev.kierandrewett.mcmarkings.registry.MapRegistry;
import dev.kierandrewett.mcmarkings.repo.GitException;
import dev.kierandrewett.mcmarkings.repo.RawUrls;
import imgui.ImGui;
import imgui.type.ImString;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Everything you have already put on a wall.
 *
 * <p>The registry has recorded every map since the beginning and nothing ever showed
 * it back. After an afternoon that is thirty signs you cannot find, and the one
 * thing you want most is the one thing there was no way to do: change a PNG in the
 * repository and have the sign already on the wall pick it up.
 *
 * <p>Refresh is the point of this panel. It works out the current commit, builds a
 * fresh pinned URL for the same path, and tells the plugin to re-read it. Everything
 * else here is in service of finding the right row to press it on.
 */
public final class PlacedPanel implements Panel {

    private static final int QUERY_BUFFER = 128;

    private final CompanionServices services;

    private final ImString query = new ImString("", QUERY_BUFFER);

    private final ImGuiScreens.Status status = new ImGuiScreens.Status();

    /** Which row is confirming a forget, so the confirm is inline rather than modal. */
    private String confirming = "";

    /** True while a refresh is resolving a commit, so the button cannot be spammed. */
    private volatile boolean refreshing;

    private final Persist persist;

    public PlacedPanel(CompanionServices services) {
        this.services = services;
        this.persist = new Persist("the map registry", services.registry::save);
    }

    @Override
    public String title() {
        return "Placed";
    }

    @Override
    public void draw() {
        drawList();

        // Last, and always: a refresh reports through here, and a panel that says
        // nothing after you press a button reads as one that did nothing.
        status.draw();
    }

    private void drawList() {
        List<MapEntry> entries = matching();

        ImGui.setNextItemWidth(ImGui.getFontSize() * 18.0f);
        ImGui.inputTextWithHint("##placed-query", "Search by name or path", query);
        ImGui.sameLine();
        ImGui.textDisabled(entries.size() + " of " + services.registry.all().size());

        ImGui.separator();

        if (services.registry.all().isEmpty()) {
            drawEmptyState();
            return;
        }
        if (entries.isEmpty()) {
            ImGui.textDisabled("Nothing matches that.");
            return;
        }

        // Reserves the status line. A child of zero height takes everything left,
        // which would push the status off the bottom exactly when it has something
        // to say.
        float height = Math.max(ImGui.getFrameHeight(),
                ImGui.getContentRegionAvailY() - ImGui.getFrameHeightWithSpacing());
        ImGuiScreens.child("##placed-list", 0.0f, height, () -> {
            for (MapEntry entry : entries) {
                drawRow(entry);
            }
        });
    }

    private void drawEmptyState() {
        ImGui.textWrapped("Nothing placed yet. Anything you create from the browser, "
                + "a generator or the editor is listed here afterwards.");
        ImGui.spacing();
        ImGui.textDisabled("This is where you come back to when a source image changes "
                + "and the sign on the wall needs to catch up.");
    }

    private void drawRow(MapEntry entry) {
        ImGui.pushID(entry.imageFrameName());

        ImGui.text(entry.imageFrameName());
        ImGui.sameLine();
        ImGui.textDisabled(entry.grid() + ", " + entry.grid().frameCount() + " frames");

        ImGui.textDisabled(ImGuiScreens.truncate(entry.repoPath(), 64)
                + "   " + shortSha(entry.commitSha())
                + repositoryNote(entry));

        drawRowActions(entry);

        ImGui.popID();
        ImGui.separator();
    }

    /**
     * Says which repository a map came from, but only when it could matter.
     *
     * <p>Naming it on every row with one repository set up would be noise. Entries
     * from before the mod knew about more than one are called out, because a refresh
     * on those resolves through the path alone and could reach the wrong PNG.
     */
    private String repositoryNote(MapEntry entry) {
        if (MapRegistry.UNKNOWN_REPOSITORY.equals(entry.repositoryId())) {
            return "   from before repositories were named";
        }
        if (services.workspaces().size() < 2) {
            return "";
        }
        return services.byId(entry.repositoryId())
                .map(workspace -> "   " + workspace.entry().displayName())
                .orElse("   repository no longer set up");
    }

    private void drawRowActions(MapEntry entry) {
        boolean known = services.byId(entry.repositoryId()).isPresent();

        ImGui.beginDisabled(refreshing || !known);
        boolean refresh = ImGui.button("Refresh");
        ImGui.endDisabled();
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(known
                    ? "Re-read the image at its current commit, so the sign on the wall catches up."
                    : "The repository this came from is not set up here, so there is nothing to read.");
        }

        ImGui.sameLine();
        boolean frames = ImGui.button("Get frames");
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Another " + entry.grid().frameCount() + " invisible frames, for placing it again.");
        }

        ImGui.sameLine();
        boolean copy = ImGui.button("Copy name");

        ImGui.sameLine();
        if (confirming.equals(entry.imageFrameName())) {
            // Says what it does not do. "Forget" next to a map on a wall reads like it
            // might take the map down, and it does not: this list is a client-side note.
            Notice.warning("Forget this one?");
            ImGui.sameLine();
            if (ImGui.button("Forget")) {
                confirming = "";
                forget(entry);
            }
            ImGui.sameLine();
            if (ImGui.button("Keep")) {
                confirming = "";
            }
            ImGui.textDisabled("The map stays on the wall; this only stops tracking it here.");
        } else if (ImGui.button("Forget...")) {
            confirming = entry.imageFrameName();
        }

        // Acted on after the disabled blocks close, so an action that threw cannot
        // leave ImGui's stack unbalanced for the next frame.
        if (refresh) {
            refresh(entry);
        }
        if (frames) {
            services.commands.send(ImageFrameCommands.giveInvisibleFrames(
                    services.config.commandAlias, services.config.glowingFrames, entry.grid().frameCount()));
            status.good("Requested " + entry.grid().frameCount() + " invisible frames");
        }
        if (copy) {
            Minecraft.getInstance().keyboardHandler.setClipboard(entry.imageFrameName());
            status.good("Copied " + entry.imageFrameName());
        }
    }

    /**
     * Points an existing map at the current version of its image.
     *
     * <p>Resolving the commit talks to git, so it happens on a worker. The URL is
     * pinned to that commit rather than to a branch, because a branch URL is cached
     * for minutes and a refresh that quietly returns the old image is worse than one
     * that fails.
     */
    private void refresh(MapEntry entry) {
        refreshing = true;
        status.info("Refreshing " + entry.imageFrameName() + "...");

        var workspace = services.byId(entry.repositoryId()).orElse(null);
        if (workspace == null) {
            refreshing = false;
            status.bad("The repository " + entry.imageFrameName() + " came from is not set up here.");
            return;
        }

        Thread.ofVirtual().name("mcmarkings-placed-refresh").start(() -> {
            try {
                RawUrls.Target target = workspace.rawUrls();

                // Deliberately not HEAD. The server fetches the image over HTTP, so a
                // commit that only exists on this machine is a guaranteed 404 and the
                // sign would come back blank rather than updated.
                String commit = workspace.git().pinnableCommit();
                String url = target.pinned(commit, entry.repoPath());

                Minecraft.getInstance().execute(() -> {
                    refreshing = false;
                    services.commands.send(ImageFrameCommands.refresh(
                            services.config.commandAlias, entry.imageFrameName(), url));

                    services.registry.put(new MapEntry(entry.imageFrameName(), entry.repositoryId(),
                            entry.repoPath(), entry.grid(), commit, entry.createdAtEpochMillis()));
                    persist.request();

                    status.good("Refreshed " + entry.imageFrameName() + " at " + shortSha(commit));
                });
            } catch (GitException failure) {
                // git's own words. "not a git repository" tells someone what to do;
                // "refresh failed" does not.
                report(entry, failure.output().isBlank() ? failure.getMessage() : failure.output());
            } catch (RuntimeException failure) {
                McMarkingsCompanion.LOGGER.error("[mcmarkings] refresh failed", failure);
                report(entry, String.valueOf(failure.getMessage()));
            }
        });
    }

    private void report(MapEntry entry, String message) {
        Minecraft.getInstance().execute(() -> {
            refreshing = false;
            status.bad("Could not refresh " + entry.imageFrameName() + ": "
                    + ImGuiScreens.truncate(message, 80));
        });
    }

    private void forget(MapEntry entry) {
        services.registry.remove(entry.imageFrameName());
        persist.request();
        status.info("Stopped tracking " + entry.imageFrameName() + ".");
    }

    /**
     * The list, filtered and newest first.
     *
     * <p>Newest first because the thing most likely to want refreshing is the thing
     * most recently made.
     */
    private List<MapEntry> matching() {
        String text = query.get().trim().toLowerCase(Locale.ROOT);
        List<MapEntry> found = new ArrayList<>();

        for (MapEntry entry : services.registry.all()) {
            if (text.isEmpty()
                    || entry.imageFrameName().toLowerCase(Locale.ROOT).contains(text)
                    || entry.repoPath().toLowerCase(Locale.ROOT).contains(text)) {
                found.add(entry);
            }
        }

        found.sort(Comparator.comparingLong(MapEntry::createdAtEpochMillis).reversed());
        return found;
    }

    private static String shortSha(String sha) {
        return sha == null || sha.length() < 7 ? String.valueOf(sha) : sha.substring(0, 7);
    }
}
