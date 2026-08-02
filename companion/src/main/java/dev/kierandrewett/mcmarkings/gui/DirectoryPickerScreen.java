package dev.kierandrewett.mcmarkings.gui;

import dev.kierandrewett.mcmarkings.McMarkingsCompanion;
import dev.kierandrewett.mcmarkings.RepositoryCheck;
import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.component.TextBoxComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.CursorStyle;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import io.wispforest.owo.ui.core.VerticalAlignment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Browse the filesystem and choose a folder.
 *
 * <p>Written rather than taken from a library because neither Minecraft nor the
 * bundled ImGui binding ships a directory chooser, and asking someone to type an
 * absolute path into a text box is not a setup experience.
 *
 * <p>Starts at the user's home directory. Nothing here assumes any particular
 * layout on disk.
 */
public class DirectoryPickerScreen extends BaseOwoScreen<FlowLayout> {

    private static final int MAX_LISTED = 300;

    private final Screen parent;
    private final Consumer<Path> onChosen;

    private Path current;

    private FlowLayout listing;
    private LabelComponent pathLabel;
    private FlowLayout verdictPanel;
    private TextBoxComponent pathBox;

    public DirectoryPickerScreen(Screen parent, Path startAt, Consumer<Path> onChosen) {
        super(Component.literal("Choose a folder"));
        this.parent = parent;
        this.onChosen = onChosen;
        this.current = firstUsable(startAt);
    }

    /** Falls back through sensible options rather than showing an empty screen. */
    private static Path firstUsable(Path preferred) {
        if (preferred != null && Files.isDirectory(preferred)) {
            return preferred.toAbsolutePath().normalize();
        }
        Path home = Path.of(System.getProperty("user.home", "."));
        if (Files.isDirectory(home)) {
            return home.toAbsolutePath().normalize();
        }
        return Path.of("").toAbsolutePath();
    }

    @Override
    protected @NotNull OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        root.surface(Surface.VANILLA_TRANSLUCENT);
        root.padding(Insets.of(10));
        root.gap(6);

        root.child(UIComponents.label(Component.literal("Choose a repository folder")
                .withStyle(ChatFormatting.WHITE)));
        root.child(UIComponents.label(Component.literal(
                "Pick the folder your sign images live in. It is normally a clone of a GitHub repository.")
                .withStyle(ChatFormatting.GRAY)));

        pathLabel = UIComponents.label(Component.literal(current.toString()).withStyle(ChatFormatting.YELLOW));
        root.child(pathLabel);

        root.child(buildTypeInRow());

        listing = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        listing.gap(1);
        root.child(UIContainers.verticalScroll(Sizing.fill(100), Sizing.fill(46), listing).scrollStep(24));

        verdictPanel = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        verdictPanel.surface(Surface.PANEL_INSET);
        verdictPanel.padding(Insets.of(6));
        verdictPanel.gap(2);
        root.child(verdictPanel);

        FlowLayout actions = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        actions.gap(6);
        actions.child(UIComponents.button(Component.literal("Use this folder"), button -> choose()));
        actions.child(UIComponents.button(Component.literal("Cancel"), button -> onClose()));
        root.child(actions);

        refresh();
    }

    private FlowLayout buildTypeInRow() {
        pathBox = UIComponents.textBox(Sizing.fill(76), current.toString());
        FlowLayout row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        row.gap(4);
        row.verticalAlignment(VerticalAlignment.CENTER);
        row.child(pathBox);
        row.child(UIComponents.button(Component.literal("Go"), button -> navigateTo(Path.of(pathBox.getValue()))));
        return row;
    }

    private void navigateTo(Path target) {
        if (target == null) {
            return;
        }
        Path normalised = target.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalised)) {
            showVerdict(RepositoryCheck.inspect(normalised));
            return;
        }
        current = normalised;
        refresh();
    }

    private void refresh() {
        pathLabel.text(Component.literal(current.toString()).withStyle(ChatFormatting.YELLOW));
        if (pathBox != null) {
            pathBox.text(current.toString());
        }

        listing.clearChildren();

        Path parentDirectory = current.getParent();
        if (parentDirectory != null) {
            listing.child(entryRow("..", parentDirectory, ChatFormatting.AQUA));
        }

        for (Path child : childDirectories(current)) {
            Path name = child.getFileName();
            listing.child(entryRow(name == null ? child.toString() : name.toString(), child, ChatFormatting.WHITE));
        }

        if (listing.children().isEmpty()) {
            listing.child(UIComponents.label(Component.literal("Nothing to open in here")
                    .withStyle(ChatFormatting.DARK_GRAY)));
        }

        showVerdict(RepositoryCheck.inspect(current));
    }

    /**
     * Hidden folders are skipped except for .git-bearing ones being irrelevant
     * here, because a listing full of dotfiles buries the folder someone wants.
     */
    private static List<Path> childDirectories(Path directory) {
        List<Path> directories = new ArrayList<>();
        try (Stream<Path> children = Files.list(directory)) {
            children.filter(Files::isDirectory)
                    .filter(path -> {
                        Path name = path.getFileName();
                        return name != null && !name.toString().startsWith(".");
                    })
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase()))
                    .limit(MAX_LISTED)
                    .forEach(directories::add);
        } catch (IOException | RuntimeException exception) {
            McMarkingsCompanion.LOGGER.debug("[mcmarkings] could not list {}", directory, exception);
        }
        return directories;
    }

    private FlowLayout entryRow(String label, Path target, ChatFormatting colour) {
        FlowLayout row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        row.padding(Insets.of(2, 2, 4, 4));
        row.cursorStyle(CursorStyle.HAND);
        row.child(UIComponents.label(Component.literal(label).withStyle(colour)));
        row.mouseDown().subscribe((event, doubled) -> {
            navigateTo(target);
            return true;
        });
        return row;
    }

    /**
     * Says what this folder is before it is committed to, so a wrong choice is
     * obvious here rather than as an empty browser later.
     */
    private void showVerdict(RepositoryCheck check) {
        verdictPanel.clearChildren();

        if (!check.usable()) {
            for (String note : check.notes()) {
                verdictPanel.child(UIComponents.label(Component.literal(note).withStyle(ChatFormatting.RED)));
            }
            return;
        }

        String summary = check.imageCount() > 0
                ? check.imageCount() + (check.imageCount() >= 500 ? "+" : "") + " image(s) found"
                : "No images found";
        verdictPanel.child(UIComponents.label(Component.literal(summary)
                .withStyle(check.imageCount() > 0 ? ChatFormatting.GREEN : ChatFormatting.YELLOW)));

        if (check.hasRemote()) {
            verdictPanel.child(UIComponents.label(Component.literal("Remote: " + check.remoteSlug())
                    .withStyle(ChatFormatting.GREEN)));
        }

        for (String note : check.notes()) {
            verdictPanel.child(UIComponents.label(Component.literal(note).withStyle(ChatFormatting.GRAY)));
        }
    }

    /**
     * A folder with no images or no remote is still accepted. It is a legitimate
     * starting point if signs are going to be generated into it, and refusing would
     * be guessing at intent.
     */
    private void choose() {
        RepositoryCheck check = RepositoryCheck.inspect(current);
        if (!check.usable()) {
            showVerdict(check);
            return;
        }
        onChosen.accept(current);
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
