package dev.kierandrewett.mcmarkings.gui;

import dev.kierandrewett.mcmarkings.CompanionServices;
import dev.kierandrewett.mcmarkings.McMarkingsCompanion;
import dev.kierandrewett.mcmarkings.core.GridSize;
import dev.kierandrewett.mcmarkings.core.GridSuggestion;
import dev.kierandrewett.mcmarkings.core.MapEntry;
import dev.kierandrewett.mcmarkings.core.RepoImage;
import dev.kierandrewett.mcmarkings.imageframe.ImageFrameCommands;
import dev.kierandrewett.mcmarkings.render.GridRecommender;
import dev.kierandrewett.mcmarkings.repo.GitException;
import dev.kierandrewett.mcmarkings.repo.RawUrls;
import dev.kierandrewett.mcmarkings.texture.TextureHandle;
import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.component.TextBoxComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.CursorStyle;
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

import java.util.List;

/**
 * The main screen: search the repository, pick an image, and put it on a wall.
 *
 * <p>Results are capped rather than paged. The repository holds well over a
 * thousand images and owo keeps a live component per cell, so showing everything
 * would cost far more than it is worth; searching is the faster path to a
 * specific sign anyway.
 */
public class BrowserScreen extends BaseOwoScreen<FlowLayout> {

    private static final int MAX_RESULTS = 60;
    private static final int COLUMNS = 6;
    private static final int CELL = 64;

    private final CompanionServices services;

    private FlowLayout resultsList;
    private FlowLayout detailPanel;
    private LabelComponent statusLabel;

    private RepoImage selected;
    private GridSize selectedGrid;

    /** Resolved once on open; both are subprocess calls and neither changes mid-session. */
    private String repoSlug;
    private String headSha;

    public BrowserScreen(CompanionServices services) {
        super(Component.literal("MCMarkings"));
        this.services = services;
    }

    @Override
    protected @NotNull OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        root.surface(Surface.VANILLA_TRANSLUCENT);
        root.padding(Insets.of(8));
        root.gap(6);

        root.child(NavBar.build(services, NavBar.Destination.BROWSE));
        root.child(buildHeader());

        statusLabel = UIComponents.label(Component.literal("Loading repository...")
                .withStyle(ChatFormatting.GRAY));
        root.child(statusLabel);

        resultsList = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        resultsList.gap(4);

        detailPanel = UIContainers.verticalFlow(Sizing.fill(34), Sizing.fill(100));
        detailPanel.surface(Surface.DARK_PANEL);
        detailPanel.padding(Insets.of(8));
        detailPanel.gap(4);

        FlowLayout body = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.expand(100));
        body.gap(8);
        body.child(UIContainers.verticalScroll(Sizing.fill(64), Sizing.fill(100), resultsList)
                .scrollStep(CELL));
        body.child(detailPanel);
        root.child(body);

        resolveRepoIdentity();
        showResults("");
        showDetail(null);
    }

    private FlowLayout buildHeader() {
        TextBoxComponent search = UIComponents.textBox(Sizing.fill(38));
        search.onChanged().subscribe(this::showResults);

        FlowLayout header = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        header.gap(6);
        header.verticalAlignment(VerticalAlignment.CENTER);
        header.child(UIComponents.label(Component.literal("Search").withStyle(ChatFormatting.GRAY)));
        header.child(search);
        header.child(UIComponents.button(Component.literal("Pull"), button -> pullAndRefresh()));
        header.child(UIComponents.button(Component.literal("Close"), button -> onClose()));
        return header;
    }

    /**
     * Both values feed the commit-pinned raw URL. Doing this off-thread keeps a
     * slow or broken git clone from freezing the screen on open.
     */
    private void resolveRepoIdentity() {
        Thread.ofVirtual().start(() -> {
            try {
                // A per-repository override wins; otherwise ask the clone for its origin.
            String override = services.current().entry().slugOverride();
            String slug = override == null || override.isBlank()
                    ? services.git().remoteSlug()
                    : override;
                // The last commit known to be on the remote, not HEAD: the server
                // fetches these URLs over HTTP, so an unpushed commit is a 404.
                String head = services.git().pinnableCommit();
                Minecraft.getInstance().execute(() -> {
                    repoSlug = slug;
                    headSha = head;
                    status(services.repo().images().size() + " images, at " + shortSha(head), ChatFormatting.GRAY);
                });
            } catch (GitException exception) {
                Minecraft.getInstance().execute(() ->
                        status("git: " + exception.output(), ChatFormatting.RED));
            }
        });
    }

    private void showResults(String query) {
        List<RepoImage> matches = services.repo().search(query, MAX_RESULTS);

        resultsList.clearChildren();

        FlowLayout row = null;
        for (int index = 0; index < matches.size(); index++) {
            if (index % COLUMNS == 0) {
                row = UIContainers.horizontalFlow(Sizing.content(), Sizing.content());
                row.gap(4);
                resultsList.child(row);
            }
            row.child(buildCell(matches.get(index)));
        }

        if (matches.isEmpty()) {
            resultsList.child(UIComponents.label(Component.literal("No matches")
                    .withStyle(ChatFormatting.DARK_GRAY)));
        }
    }

    /**
     * A cell starts as an empty box and swaps in its texture when the thumbnail
     * arrives. Mutating through queue() rather than directly keeps the change off
     * the layout pass, which is not safe to mutate during.
     */
    private FlowLayout buildCell(RepoImage image) {
        FlowLayout cell = UIContainers.verticalFlow(Sizing.fixed(CELL), Sizing.fixed(CELL));
        cell.surface(Surface.PANEL_INSET);
        cell.horizontalAlignment(HorizontalAlignment.CENTER);
        cell.verticalAlignment(VerticalAlignment.CENTER);
        cell.cursorStyle(CursorStyle.HAND);
        cell.tooltip(Component.literal(image.displayName()));

        cell.mouseDown().subscribe((event, doubled) -> {
            select(image);
            return true;
        });

        services.thumbnails.request(image).thenAccept(handle ->
                Minecraft.getInstance().execute(() -> cell.queue(() -> {
                    cell.clearChildren();
                    cell.child(textureFor(handle));
                })));

        return cell;
    }

    private io.wispforest.owo.ui.core.UIComponent textureFor(TextureHandle handle) {
        int width = handle.width();
        int height = handle.height();

        // Fit inside the cell without distorting; these images are every shape
        // from square roundels to very wide direction signs.
        double scale = Math.min((CELL - 6) / (double) width, (CELL - 6) / (double) height);
        int drawWidth = Math.max(1, (int) Math.round(width * scale));
        int drawHeight = Math.max(1, (int) Math.round(height * scale));

        return UIComponents.texture(handle.identifier(), 0, 0, width, height, width, height)
                // Without this owo picks a no-blend pipeline and the transparent
                // PNGs in this repository render on an opaque black square.
                .blend(true)
                .sizing(Sizing.fixed(drawWidth), Sizing.fixed(drawHeight));
    }

    private void select(RepoImage image) {
        selected = image;
        selectedGrid = GridRecommender.best(image.width(), image.height());
        showDetail(image);
    }

    private void showDetail(RepoImage image) {
        detailPanel.clearChildren();

        if (image == null) {
            detailPanel.child(UIComponents.label(Component.literal("Select an image")
                    .withStyle(ChatFormatting.DARK_GRAY)));
            return;
        }

        detailPanel.child(UIComponents.label(Component.literal(truncate(image.displayName(), 90))));
        detailPanel.child(UIComponents.label(Component.literal(image.path())
                .withStyle(ChatFormatting.DARK_GRAY)));
        detailPanel.child(UIComponents.label(Component.literal(image.width() + " x " + image.height() + " px")
                .withStyle(ChatFormatting.GRAY)));

        if (image.reference() != null) {
            detailPanel.child(UIComponents.label(Component.literal(image.reference())
                    .withStyle(ChatFormatting.GRAY)));
        }

        detailPanel.child(UIComponents.label(Component.literal("Frame size")
                .withStyle(ChatFormatting.YELLOW)));

        for (GridSuggestion suggestion : GridRecommender.top(image.width(), image.height(), 3)) {
            detailPanel.child(buildGridOption(suggestion));
        }

        detailPanel.child(UIComponents.button(Component.literal("Create map"), button -> create()));
        detailPanel.child(UIComponents.button(Component.literal("Get frames"), button -> giveFrames()));
        detailPanel.child(UIComponents.button(Component.literal("Copy command"), button -> copyCommand()));
    }

    private io.wispforest.owo.ui.core.UIComponent buildGridOption(GridSuggestion suggestion) {
        String label = suggestion.grid() + "  " + suggestion.grid().frameCount() + " frames"
                + (suggestion.isComfortable() ? "" : "  " + suggestion.distortionPercent() + "% stretch");

        return UIComponents.button(Component.literal(label), button -> {
            selectedGrid = suggestion.grid();
            status("Frame size " + selectedGrid, ChatFormatting.GRAY);
        });
    }

    private void create() {
        if (!ready()) {
            return;
        }

        String name = ImageFrameCommands.sanitiseName(selected.name());
        String url = RawUrls.pinned(repoSlug, headSha, selected.path());

        services.commands.send(ImageFrameCommands.create(
                services.config.commandAlias, name, url, selectedGrid));

        services.registry.put(new MapEntry(name, services.activeRepositoryId(), selected.path(), selectedGrid, headSha,
                System.currentTimeMillis()));
        saveRegistry();

        status("Creating " + name + " at " + selectedGrid, ChatFormatting.GREEN);
    }

    private void giveFrames() {
        if (selectedGrid == null) {
            status("Select an image first", ChatFormatting.RED);
            return;
        }
        services.commands.send(ImageFrameCommands.giveInvisibleFrames(
                services.config.commandAlias, services.config.glowingFrames, selectedGrid.frameCount()));
        status("Requested " + selectedGrid.frameCount() + " invisible frames", ChatFormatting.GREEN);
    }

    private void copyCommand() {
        if (!ready()) {
            return;
        }
        String command = "/" + ImageFrameCommands.create(services.config.commandAlias,
                ImageFrameCommands.sanitiseName(selected.name()),
                RawUrls.pinned(repoSlug, headSha, selected.path()),
                selectedGrid);
        Minecraft.getInstance().keyboardHandler.setClipboard(command);
        status("Copied to clipboard", ChatFormatting.GREEN);
    }

    /**
     * Pull, then re-issue a refresh for every map whose backing PNG moved. Without
     * this the server keeps serving the old image, because ImageFrame only fetches
     * when told to.
     */
    private void pullAndRefresh() {
        status("Pulling...", ChatFormatting.GRAY);

        Thread.ofVirtual().start(() -> {
            try {
                var result = services.git().pull();
                services.repo().rescan();

                List<String> commands = result.changedPaths().stream()
                        .flatMap(path -> services.registry.byRepoPath(path).stream())
                        .map(entry -> ImageFrameCommands.refresh(services.config.commandAlias,
                                entry.imageFrameName(),
                                RawUrls.pinned(repoSlug, result.newHead(), entry.repoPath())))
                        .toList();

                Minecraft.getInstance().execute(() -> {
                    headSha = result.newHead();
                    services.commands.sendAll(commands);
                    status(result.changed()
                                    ? result.changedPaths().size() + " images changed, refreshing "
                                            + commands.size() + " maps"
                                    : "Already up to date",
                            ChatFormatting.GREEN);
                    showResults("");
                });
            } catch (GitException exception) {
                Minecraft.getInstance().execute(() ->
                        status("git: " + exception.output(), ChatFormatting.RED));
            } catch (Exception exception) {
                McMarkingsCompanion.LOGGER.error("[mcmarkings] pull failed", exception);
                Minecraft.getInstance().execute(() ->
                        status("Pull failed: " + exception.getMessage(), ChatFormatting.RED));
            }
        });
    }

    private boolean ready() {
        if (selected == null || selectedGrid == null) {
            status("Select an image first", ChatFormatting.RED);
            return false;
        }
        if (repoSlug == null || headSha == null) {
            status("Repository identity not resolved yet", ChatFormatting.RED);
            return false;
        }
        return true;
    }

    private void saveRegistry() {
        try {
            services.registry.save();
        } catch (Exception exception) {
            McMarkingsCompanion.LOGGER.error("[mcmarkings] could not save registry", exception);
        }
    }

    private void status(String message, ChatFormatting colour) {
        if (statusLabel != null) {
            statusLabel.text(Component.literal(message).withStyle(colour));
        }
    }

    private static String shortSha(String sha) {
        return sha.length() > 7 ? sha.substring(0, 7) : sha;
    }

    private static String truncate(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "...";
    }
}
