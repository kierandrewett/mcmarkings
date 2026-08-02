package dev.kierandrewett.mcmarkings.gui.imgui.panel;

import dev.kierandrewett.mcmarkings.CompanionServices;
import dev.kierandrewett.mcmarkings.McMarkingsCompanion;
import dev.kierandrewett.mcmarkings.core.MapEntry;
import dev.kierandrewett.mcmarkings.core.RelativeTime;
import dev.kierandrewett.mcmarkings.doc.Document;
import dev.kierandrewett.mcmarkings.doc.DocumentJson;
import dev.kierandrewett.mcmarkings.gui.imgui.ImGuiScreens;
import dev.kierandrewett.mcmarkings.gui.imgui.Notice;
import dev.kierandrewett.mcmarkings.imageframe.ImageFrameCommands;
import dev.kierandrewett.mcmarkings.registry.MapRegistry;
import dev.kierandrewett.mcmarkings.repo.GitException;
import dev.kierandrewett.mcmarkings.repo.RawUrls;
import imgui.ImGui;
import imgui.flag.ImGuiHoveredFlags;
import imgui.type.ImString;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /** Rows drawn at once. Each carries four buttons, so this is not a free number. */
    private static final int MAX_SHOWN = 60;

    private final CompanionServices services;

    /** Brings the editor forward once a sign's document is in it. */
    private final Runnable showEditor;

    private final ImString query = new ImString("", QUERY_BUFFER);

    private final ImGuiScreens.Status status = new ImGuiScreens.Status();

    /** Which row is confirming a forget, so the confirm is inline rather than modal. */
    private String confirming = "";

    /** Which row is confirming a server-side delete, kept apart from the local one. */
    private String confirmingServerDelete = "";

    /** True while a refresh is resolving a commit, so the button cannot be spammed. */
    private volatile boolean refreshing;

    /** What "now" is for this frame, so every row agrees. */
    private long drawnAtMillis;

    public PlacedPanel(CompanionServices services, Runnable showEditor) {
        this.services = services;
        this.showEditor = showEditor;
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
        // Read once per frame rather than once per row. Sixty rows asking the system
        // for the time sixty times a second is work for nothing, and a list where
        // rows disagree about what "now" is would be worse than one that is a
        // fraction of a second stale.
        drawnAtMillis = System.currentTimeMillis();

        List<MapEntry> entries = matching();

        ImGui.setNextItemWidth(ImGuiScreens.fieldWidth(18.0f));
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
            int shown = Math.min(entries.size(), MAX_SHOWN);
            for (MapEntry entry : entries.subList(0, shown)) {
                drawRow(entry);
            }
            if (entries.size() > shown) {
                // The last list in here that did not say this. Every row carries four
                // buttons, so a few hundred maps is a few thousand widgets a frame,
                // and someone with that many is searching rather than scrolling.
                ImGui.textDisabled((entries.size() - shown) + " more; search to narrow it down");
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
                + "   " + RelativeTime.describe(entry.createdAtEpochMillis(), drawnAtMillis)
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

        // AllowWhenDisabled, or the second half of this tooltip can never be seen:
        // the button is only disabled when the repository is unknown, which is
        // precisely the case that message was written for.
        if (ImGuiScreens.explaining()) {
            ImGui.setTooltip(known
                    ? "Re-read the image at its current commit, so the sign on the wall catches up."
                    : "The repository this came from is not set up here, so there is nothing to read.");
        }

        ImGuiScreens.flowTo("Get map");
        boolean getMap = ImGui.button("Get map");
        if (ImGuiScreens.explaining()) {
            ImGui.setTooltip("Hands you the map item again, for when the original was "
                    + "broken or lost. It is the same map, not a copy.");
        }

        ImGuiScreens.flowTo("Get frames");
        boolean frames = ImGui.button("Get frames");
        if (ImGuiScreens.explaining()) {
            ImGui.setTooltip("Another " + entry.grid().frameCount() + " invisible frames, for placing it again.");
        }

        ImGuiScreens.flowTo("Edit");
        boolean edit = ImGui.button("Edit");
        if (ImGuiScreens.explaining()) {
            ImGui.setTooltip("Reopen this sign in the editor, if its document was saved beside it.");
        }

        ImGuiScreens.flowTo("Copy name");
        boolean copy = ImGui.button("Copy name");

        ImGuiScreens.flowTo("Forget...");
        if (confirming.equals(entry.imageFrameName())) {
            // Says what it does not do. "Forget" next to a map on a wall reads like it
            // might take the map down, and it does not: this list is a client-side note.
            Notice.warning("Forget this one?");
            ImGuiScreens.flowTo("Forget");
            if (ImGui.button("Forget")) {
                confirming = "";
                forget(entry);
            }
            ImGuiScreens.flowTo("Keep");
            if (ImGui.button("Keep")) {
                confirming = "";
            }
            ImGui.textDisabled("The map stays on the wall; this only stops tracking it here.");
        } else if (ImGui.button("Forget...")) {
            confirming = entry.imageFrameName();
            confirmingServerDelete = "";
        }

        drawServerDelete(entry);

        // Acted on after the disabled blocks close, so an action that threw cannot
        // leave ImGui's stack unbalanced for the next frame.
        if (refresh) {
            refresh(entry);
        }
        if (getMap) {
            services.commands.send(ImageFrameCommands.get(
                    services.config.commandAlias, entry.imageFrameName(), entry.grid()));
            status.good("Asked for " + entry.imageFrameName() + " again.");
        }
        if (frames) {
            services.commands.send(ImageFrameCommands.giveInvisibleFrames(
                    services.config.commandAlias, services.config.glowingFrames, entry.grid().frameCount()));
            status.good("Requested " + entry.grid().frameCount() + " invisible frames");
        }
        if (edit) {
            edit(entry);
        }
        if (copy) {
            Minecraft.getInstance().keyboardHandler.setClipboard(entry.imageFrameName());
            status.good("Copied " + entry.imageFrameName());
        }
    }

    /**
     * Removing the map from the server, which is the other half of forgetting.
     *
     * <p>Deliberately separate from Forget and worded so the two cannot be confused.
     * One drops a note this mod keeps; the other takes the sign off the wall for
     * everybody. Having only the first meant a map could be created and never
     * removed, so a server accumulated everything anyone had ever tried.
     *
     * <p>The confirm says it cannot be undone from here, because it cannot. The mod
     * can create it again from the same image, but any copy already hanging in a
     * frame goes blank in the meantime.
     */
    private void drawServerDelete(MapEntry entry) {
        if (!confirmingServerDelete.equals(entry.imageFrameName())) {
            ImGuiScreens.flowTo("Delete...");
            if (ImGui.button("Delete...")) {
                confirmingServerDelete = entry.imageFrameName();
                confirming = "";
            }
            if (ImGuiScreens.explaining()) {
                ImGui.setTooltip("Remove the map from the server, not just from this list.");
            }
            return;
        }

        Notice.warning("Delete " + entry.imageFrameName() + " from the server?");
        ImGuiScreens.flowTo("Delete##server");
        if (ImGui.button("Delete##server")) {
            confirmingServerDelete = "";
            services.commands.send(ImageFrameCommands.delete(
                    services.config.commandAlias, entry.imageFrameName()));
            forget(entry);
            status.info("Asked the server to delete " + entry.imageFrameName() + ".");
        }
        ImGuiScreens.flowTo("Keep##server");
        if (ImGui.button("Keep##server")) {
            confirmingServerDelete = "";
        }
        ImGui.textDisabled("Any copy already in a frame goes blank. This cannot be undone from here.");
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
                    services.saveRegistry();

                    status.good("Refreshed " + entry.imageFrameName() + " at " + shortSha(commit));
                });
            } catch (GitException failure) {
                // git's own words. "not a git repository" tells someone what to do;
                // "refresh failed" does not.
                report(entry, failure.describe());
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

    /**
     * Reopens a placed sign in the editor.
     *
     * <p>Only signs placed from the editor have a document beside them: it is written
     * next to the PNG for exactly this. Anything made from a repository image or an
     * older generator has only the picture, so this says so rather than opening a
     * blank canvas, which is the failure this whole path had until recently.
     *
     * <p>Checked when pressed rather than when the row is drawn. Whether a file exists
     * is a question for the filesystem, and asking it once per row per frame is how
     * this mod has frozen the game before.
     */
    private void edit(MapEntry entry) {
        var workspace = services.byId(entry.repositoryId()).orElse(null);
        if (workspace == null) {
            status.bad("The repository " + entry.imageFrameName() + " came from is not set up here.");
            return;
        }

        Path document = workspace.entry().root().resolve(documentPathFor(entry.repoPath()));
        status.info("Opening " + entry.imageFrameName() + "...");

        Thread.ofVirtual().name("mcmarkings-placed-edit").start(() -> {
            try {
                if (!Files.isRegularFile(document)) {
                    Minecraft.getInstance().execute(() -> status.bad(entry.imageFrameName()
                            + " has no saved document, so there is nothing to reopen. "
                            + "Signs placed from the editor do; ones made from an image do not."));
                    return;
                }

                DocumentJson.Result result = DocumentJson.readWithReport(
                        Files.readString(document, StandardCharsets.UTF_8));
                Minecraft.getInstance().execute(() -> {
                    // Pushed, so whatever was on the canvas is one undo away.
                    services.editing.push(result.document(), "Open " + entry.imageFrameName(), null);
                    services.editing.endGesture();
                    services.requestEditorFit();

                    if (result.warnings().isEmpty()) {
                        status.good("Opened " + entry.imageFrameName() + " in the editor.");
                    } else {
                        // A sign published by a newer build can hold a layer kind this
                        // one does not know. Opening it short and saying nothing would
                        // invite a save that makes the loss permanent.
                        status.bad(result.describe(entry.imageFrameName(), 60)
                                + ". Placing it again would make that permanent.");
                    }
                    showEditor.run();
                });
            } catch (IOException | RuntimeException failure) {
                McMarkingsCompanion.LOGGER.error("[mcmarkings] could not open " + document, failure);
                Minecraft.getInstance().execute(() -> status.bad("Could not open "
                        + entry.imageFrameName() + ": " + ImGuiScreens.truncate(
                                String.valueOf(failure.getMessage()), 70)));
            }
        });
    }

    /**
     * The document written beside a published PNG: "signs/a.png" to
     * "signs/a.layout.json".
     *
     * <p>Only the extension of the file name counts. Taking the last dot in the whole
     * path truncates a folder instead when someone has one called "my.signs", and the
     * result is a lookup in a directory that does not exist reported as a sign with
     * no document.
     */
    static String documentPathFor(String repoPath) {
        int slash = repoPath.lastIndexOf('/');
        int dot = repoPath.lastIndexOf('.');
        String stem = dot > slash + 1 ? repoPath.substring(0, dot) : repoPath;
        return stem + ".layout.json";
    }

    private void forget(MapEntry entry) {
        services.registry.remove(entry.imageFrameName());
        services.saveRegistry();
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
