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
import java.nio.file.FileSystems;
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

    /**
     * The sandbox's own application id, or null when not sandboxed.
     *
     * <p>Flatpak sets FLATPAK_ID inside the sandbox, so the advice can name the
     * actual launcher rather than assuming one. Null on Windows, macOS, and any
     * unsandboxed Linux install, where none of this applies.
     */
    private static final String SANDBOX_APP_ID = System.getenv("FLATPAK_ID");

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
                "Pick the folder your images live in. A clone of a git repository works best, because the "
                        + "server fetches them over the internet.")
                .withStyle(ChatFormatting.GRAY)));

        if (SANDBOX_APP_ID != null) {
            // Worth saying up front, but only where it is true. In a sandbox the home
            // folder lists only what has been granted, so an otherwise correct setup
            // looks like an empty disk and the folder someone wants is not there to
            // click. The application id comes from the sandbox itself rather than
            // being assumed, since this is not specific to any one launcher.
            root.child(UIComponents.label(Component.literal(
                    "This launcher is sandboxed, so only folders you have granted are visible here.")
                    .withStyle(ChatFormatting.GOLD)));
            root.child(UIComponents.label(Component.literal(
                    "Grant one outside the sandbox with:  flatpak override --user "
                            + "--filesystem=/path/to/folder " + SANDBOX_APP_ID)
                    .withStyle(ChatFormatting.DARK_GRAY)));
        }

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
        } else {
            // At the top there is nowhere further up, and on Windows "the top" is a
            // drive rather than one filesystem root, so the other drives have to be
            // offered here or they are unreachable.
            for (Path root : FileSystems.getDefault().getRootDirectories()) {
                if (!root.equals(current)) {
                    listing.child(entryRow(root.toString(), root, ChatFormatting.AQUA));
                }
            }
        }

        for (Path child : childDirectories(current)) {
            Path name = child.getFileName();
            String label = name == null ? child.toString() : name.toString();

            // Marking the repositories saves walking into folders one at a time to
            // find out which is the one. A single stat per row, so it stays cheap.
            boolean repository = Files.exists(child.resolve(".git"));
            listing.child(entryRow(
                    repository ? label + "   (git repository)" : label,
                    child,
                    repository ? ChatFormatting.GREEN : ChatFormatting.WHITE));
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
     * One neutral line about the folder currently open.
     *
     * <p>Deliberately quiet. This runs on every step of the walk down to the folder
     * someone wants, and the folders passed through on the way are not candidates
     * and not mistakes. Reporting "not a git repository" at each one reads as a
     * repeated error for doing nothing wrong. The caveats that actually matter are
     * saved for {@link #choose()}, where they are about to mean something.
     */
    private void showVerdict(RepositoryCheck check) {
        verdictPanel.clearChildren();

        if (!check.usable()) {
            for (String note : check.notes()) {
                verdictPanel.child(UIComponents.label(Component.literal(note).withStyle(ChatFormatting.RED)));
            }
            return;
        }

        if (check.isGitRepository() && check.imageCount() > 0) {
            String remote = check.hasRemote() ? ", " + check.remoteSlug() : "";
            verdictPanel.child(UIComponents.label(Component.literal(
                    "This looks right: " + countText(check) + remote).withStyle(ChatFormatting.GREEN)));
            return;
        }

        if (check.imageCount() > 0) {
            verdictPanel.child(UIComponents.label(Component.literal(
                    countText(check) + " here, but no git repository").withStyle(ChatFormatting.YELLOW)));
            return;
        }

        verdictPanel.child(UIComponents.label(Component.literal(
                "Keep looking, or use this folder anyway if you plan to generate images into it.")
                .withStyle(ChatFormatting.DARK_GRAY)));
    }

    private static String countText(RepositoryCheck check) {
        return check.imageCount() + (check.imageCount() >= 500 ? "+" : "") + " image(s)";
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
