package dev.kierandrewett.mcmarkings.gui;

import dev.kierandrewett.mcmarkings.CompanionServices;
import dev.kierandrewett.mcmarkings.RepositoryCheck;
import dev.kierandrewett.mcmarkings.Workspace;
import io.wispforest.owo.ui.base.BaseOwoScreen;
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

import java.nio.file.Path;

/**
 * First run. Shown when no repository has been set up yet.
 *
 * <p>Having nothing configured is where everyone starts, so this explains what the
 * mod wants and hands over one obvious button, rather than reporting that a folder
 * is missing and leaving the player to find a config file.
 */
public class WelcomeScreen extends BaseOwoScreen<FlowLayout> {

    private final CompanionServices services;

    public WelcomeScreen(CompanionServices services) {
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
        root.horizontalAlignment(HorizontalAlignment.CENTER);
        root.verticalAlignment(VerticalAlignment.CENTER);

        FlowLayout panel = UIContainers.verticalFlow(Sizing.fill(62), Sizing.content());
        panel.surface(Surface.DARK_PANEL);
        panel.padding(Insets.of(14));
        panel.gap(6);
        panel.horizontalAlignment(HorizontalAlignment.CENTER);

        panel.child(UIComponents.label(Component.literal("Welcome to MCMarkings")
                .withStyle(ChatFormatting.YELLOW)));

        panel.child(UIComponents.label(Component.literal(
                "Put your own images on walls in game, without typing commands.")
                .withStyle(ChatFormatting.WHITE)));

        panel.child(UIComponents.label(Component.literal(
                "Point it at a folder of images and you can search them in game, work out how many "
                        + "item frames each one needs, and place one in a couple of clicks.")
                .withStyle(ChatFormatting.GRAY)));

        panel.child(UIComponents.label(Component.literal(" ")));

        panel.child(UIComponents.label(Component.literal("To get started")
                .withStyle(ChatFormatting.YELLOW)));
        panel.child(UIComponents.label(Component.literal(
                "1. Choose a folder of PNGs. A clone of a git repository works best, because the "
                        + "server fetches the images over the internet.")
                .withStyle(ChatFormatting.GRAY)));
        panel.child(UIComponents.label(Component.literal(
                "2. Add as many folders as you like. You can switch between them at any time.")
                .withStyle(ChatFormatting.GRAY)));

        panel.child(UIComponents.label(Component.literal(" ")));

        FlowLayout actions = UIContainers.horizontalFlow(Sizing.content(), Sizing.content());
        actions.gap(6);
        actions.child(UIComponents.button(Component.literal("Choose a folder"), button -> pickFolder()));
        actions.child(UIComponents.button(Component.literal("Not now"), button -> onClose()));
        panel.child(actions);

        if (!services.startupNotes().isEmpty()) {
            panel.child(UIComponents.label(Component.literal(" ")));
            for (String note : services.startupNotes()) {
                panel.child(UIComponents.label(Component.literal(note).withStyle(ChatFormatting.GOLD)));
            }
        }

        root.child(panel);
    }

    private void pickFolder() {
        Path start = Path.of(System.getProperty("user.home", "."));
        Minecraft.getInstance().setScreen(new DirectoryPickerScreen(this, start, this::adopt));
    }

    /**
     * Goes straight into the browser once a folder is added, since that is what the
     * player came for. A folder that opened with a warning still counts, and the
     * warning is carried into the browser rather than blocking it.
     */
    private void adopt(Path directory) {
        Workspace workspace = services.addRepository(directory);
        Minecraft client = Minecraft.getInstance();

        if (workspace.hasWarning()) {
            RepositoryCheck check = RepositoryCheck.inspect(directory);
            if (!check.usable()) {
                return;
            }
        }

        // The picker returns to its parent as soon as this callback has run, so
        // setting the screen here would be thrown away. Going through the client's
        // queue puts the move after that, which is the whole point of adding a
        // folder from the first-run screen.
        client.execute(() -> client.setScreen(new BrowserScreen(services)));
    }
}
