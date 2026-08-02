package dev.kierandrewett.mcmarkings.gui.imgui;

import cn.enaium.fabric.imgui.ImGuiRenderable;
import dev.kierandrewett.mcmarkings.CompanionServices;
import dev.kierandrewett.mcmarkings.McMarkingsCompanion;
import dev.kierandrewett.mcmarkings.Workspace;
import dev.kierandrewett.mcmarkings.core.GridSize;
import dev.kierandrewett.mcmarkings.core.PushState;
import dev.kierandrewett.mcmarkings.core.GridSuggestion;
import dev.kierandrewett.mcmarkings.core.MapEntry;
import dev.kierandrewett.mcmarkings.core.RepoImage;
import dev.kierandrewett.mcmarkings.gui.imgui.panel.EditorPanel;
import dev.kierandrewett.mcmarkings.gui.imgui.panel.ImageBrowserPanel;
import dev.kierandrewett.mcmarkings.gui.imgui.panel.GeneratorPanel;
import dev.kierandrewett.mcmarkings.gui.imgui.panel.Panel;
import dev.kierandrewett.mcmarkings.gui.imgui.panel.PlacedPanel;
import dev.kierandrewett.mcmarkings.gui.imgui.panel.WelcomePanel;
import dev.kierandrewett.mcmarkings.gui.imgui.panel.SettingsPanel;
import dev.kierandrewett.mcmarkings.gui.imgui.panel.RepositoriesPanel;
import dev.kierandrewett.mcmarkings.imageframe.ImageFrameCommands;
import dev.kierandrewett.mcmarkings.render.GridRecommender;
import dev.kierandrewett.mcmarkings.repo.GitException;
import dev.kierandrewett.mcmarkings.repo.PullResult;
import dev.kierandrewett.mcmarkings.repo.RawUrls;
import dev.kierandrewett.mcmarkings.command.Command;
import dev.kierandrewett.mcmarkings.command.CommandRegistry;
import dev.kierandrewett.mcmarkings.command.Shortcut;
import dev.kierandrewett.mcmarkings.gui.imgui.panel.CommandPalette;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiHoveredFlags;
import imgui.flag.ImGuiTabBarFlags;
import imgui.flag.ImGuiTabItemFlags;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map;

/**
 * The whole companion in one ImGui window.
 *
 * <p>There is a single key binding for the mod, so the screen it opens has to be
 * the map of everything else. The tab bar is therefore the one thing that is always
 * on screen: it is drawn before the panel body, the body lives in a child region
 * that scrolls inside itself, and a panel that throws is caught where it draws.
 * None of those three is decoration. Between them they mean nothing a panel does
 * can take the navigation away, which is what happened with the editors that
 * replaced the whole screen and left no way back.
 *
 * <p>Panels are held as {@link Panel}, so this class knows a title and a draw call
 * and nothing else about them. Browse is an {@link ImageBrowserPanel} and Editor is
 * an {@link EditorPanel}; the remaining three are shortcuts to the screens that
 * still do those jobs, until they are ported in turn.
 */
public class ImGuiShell extends Screen implements ImGuiRenderable {

    private static final String WINDOW_ID = "##mcmarkings-shell";
    private static final String TAB_BAR_ID = "##mcmarkings-tabs";

    /**
     * Tabs shrink to fit rather than scrolling out of reach. A scrolling tab bar
     * hides destinations behind an arrow, which is the problem this screen exists
     * to fix.
     */
    private static final int TAB_BAR_FLAGS = ImGuiTabBarFlags.FittingPolicyResizeDown
            | ImGuiTabBarFlags.DrawSelectedOverline;

    private final CompanionServices services;

    /** Drawn in place of the tabs until a repository exists. */
    private final WelcomePanel welcome;

    /** A tab asked for by name, selected on the next frame and then forgotten. */
    private String pendingTab;

    /** Map names already made from the selected image, or empty. */
    private List<String> placedAs = List.of();

    /** The registry generation {@link #placedAs} was worked out from. */
    private int placedGeneration = -1;

    private static final int KEY_P = 'P';

    /** GLFW modifier bits, which the key event carries verbatim. */
    private static final int MOD_SHIFT = 0x0001;

    private static final int MOD_CONTROL = 0x0002;

    private static final int MOD_ALT = 0x0004;

    /** Command on a Mac, where it is what Control is everywhere else. */
    private static final int MOD_SUPER = 0x0008;

    /** The window's own actions, as opposed to whatever the visible tab offers. */
    private final CommandRegistry commands = new CommandRegistry();

    private final CommandPalette palette = new CommandPalette("Commands##shell-palette", this::commandScope);
    private final ImGuiScreens.Status status = new ImGuiScreens.Status();

    private final ImageBrowserPanel browser;
    private final EditorPanel editor;
    private final List<Panel> panels;

    /**
     * Which tab was drawn last frame.
     *
     * <p>Only needed so a key press reaches the panel that is actually on screen.
     * Routing Ctrl+Z to the editor while someone is browsing images would undo an
     * edit they cannot see.
     */
    private Panel activePanel;

    /** Last message reported per panel, so a failing panel logs on change, not per frame. */
    private final Map<String, String> panelErrors = new HashMap<>();

    /** Whichever image the action buttons were last built for, and its grid choices. */
    private RepoImage actionImage;
    private GridSize grid;
    private List<GridSuggestion> suggestions = List.of();

    /**
     * Both halves of a pinned raw URL, resolved off-thread on open.
     *
     * <p>Null until they land, which is why every action that needs a URL is
     * disabled rather than merely failing when pressed.
     */
    private RawUrls.Target rawUrls;
    private String headSha;

    private boolean pulling;

    private ImGuiIO io;
    private String renderError;

    public ImGuiShell(CompanionServices services) {
        super(Component.literal("MCMarkings"));
        this.services = services;

        this.browser = new ImageBrowserPanel(services, "browse", "Browse")
                .onDetail(this::drawImageActions);

        this.editor = new EditorPanel(services);
        this.welcome = new WelcomePanel(services);

        this.panels = List.of(
                browser,
                editor,
                new GeneratorPanel(services, () -> pendingTab = "Editor"),
                new PlacedPanel(services, () -> pendingTab = "Editor"),
                new RepositoriesPanel(services),
                new SettingsPanel(services));

        registerCommands();
        warnAboutShortcutClashes();
    }

    /**
     * Says so if two commands answer to the same keys.
     *
     * <p>The window dispatches before the visible tab, so a clash does not crash or
     * misbehave: the window simply wins and the tab's command silently never fires.
     * That is the worst shape a bug can take, because the shortcut appears in the
     * palette next to the key that no longer works, and the only symptom is
     * something not happening.
     *
     * <p>A log line rather than a failure. Nobody should lose the window over it, and
     * it is the sort of thing that shows up the moment a panel adds a command.
     */
    /**
     * The window's own actions.
     *
     * <p>Jumping to a tab is here rather than in each panel because it is about the
     * window rather than about any one of them, and because typing "settings" should
     * get you there from wherever you are. That is the whole point of the palette:
     * knowing a thing exists is easy, remembering which tab it lives behind is what
     * sends people back to the mouse.
     */
    private void registerCommands() {
        commands.register(Command.of("shell.palette", "Command palette").category("Window")
                .hint("Search everything this window and the visible tab can do")
                .shortcut(Shortcut.control(KEY_P))
                .does(palette::open));

        for (int index = 0; index < panels.size(); index++) {
            String title = panels.get(index).title();
            Command.Builder command = Command.of("shell.tab." + title.toLowerCase(Locale.ROOT), "Go to " + title)
                    .category("Window")
                    .hint("Show the " + title + " tab")
                    .enabledWhen(services::hasConfiguredRepositories);

            // Ctrl and a digit, which is what tabs do everywhere else. Only for the
            // first nine, because Ctrl+10 is not a key.
            if (index < 9) {
                command = command.shortcut(Shortcut.control('1' + index));
            }
            commands.register(command.does(() -> pendingTab = title));
        }

        commands.register(Command.of("shell.pull", "Pull").category("Repository")
                .hint("Fetch the latest images for the active repository")
                .enabledWhen(() -> !pulling && services.hasRepositories())
                .does(this::pull));

        commands.register(Command.of("shell.rescan", "Rescan").category("Repository")
                .hint("Read the repository folder again, after changing files outside the game")
                .enabledWhen(() -> !services.isLoading() && services.hasRepositories())
                .does(this::rescan));

        commands.register(Command.of("shell.close", "Close the window").category("Window")
                .hint("Back to the game")
                .does(this::onClose));
    }

    /**
     * Says so if two commands answer to the same keys.
     *
     * <p>A log line rather than a failure. Nobody should lose the window over a
     * shortcut, and it is the sort of thing that shows up the moment a panel adds a
     * command rather than something anyone would go looking for.
     */
    private void warnAboutShortcutClashes() {
        List<CommandRegistry> registries = new ArrayList<>();
        registries.add(commands);
        panels.stream().map(Panel::commands).forEach(registries::add);

        for (List<Command> clash : CommandRegistry.conflictsAcross(registries)) {
            // The first is the one that actually runs, since registries are given in
            // dispatch order.
            McMarkingsCompanion.LOGGER.warn("[mcmarkings] {} is claimed by {}; only {} will fire",
                    clash.getFirst().shortcut().display(),
                    clash.stream().map(Command::id).toList(),
                    clash.getFirst().id());
        }
    }

    /**
     * What the palette searches this frame: the window, then the visible tab.
     *
     * <p>Read per frame rather than assembled once, because which tab is visible is
     * the whole point and a panel's registry can gain commands after it is built.
     */
    private List<CommandRegistry> commandScope() {
        return activePanel == null
                ? List.of(commands)
                : java.util.Arrays.asList(commands, activePanel.commands());
    }

    @Override
    protected void init() {
        // Opening runs in the background, so the first frames may have no images at
        // all. whenReady fires straight away when there is nothing left to wait for,
        // which is also what makes this correct on a resize.
        services.whenReady(() -> {
            browser.refresh();
            resolveIdentity();
        });
    }

    /** Whether this window was built against the services still in use. */
    public boolean belongsTo(CompanionServices candidate) {
        return services == candidate;
    }

    /**
     * Lets every panel go of what it is holding.
     *
     * <p>Deliberately not {@code removed()}. The window is kept between openings, and
     * closing it is something that happens constantly in a game, so freeing textures
     * there would throw away the previews and thumbnails on every glance at the world
     * and rebuild them on the way back in. This runs when the window is genuinely
     * being discarded, which is only when the services behind it are.
     *
     * <p>Two panels keep a GPU texture that nothing else references. Going through the
     * list rather than naming them means the next panel to hold something is covered
     * by having said so, not by someone remembering to edit this.
     */
    public void dispose() {
        for (Panel panel : panels) {
            try {
                panel.onRemoved();
            } catch (RuntimeException failure) {
                McMarkingsCompanion.LOGGER.warn("[mcmarkings] panel " + panel.title()
                        + " failed to clean up", failure);
            }
        }
    }

    /**
     * The wrapper chains GLFW callbacks rather than consuming them, so Minecraft
     * still sees keys typed into an ImGui text box. Escape is the one that hurts:
     * it would close the screen mid-search.
     */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (io != null && io.getWantCaptureKeyboard()) {
            return true;
        }
        // Only once ImGui has said it does not want the key, and only for the tab on
        // screen. Otherwise typing into a text box fires editor shortcuts.
        int modifiers = event.modifiers();
        if (commands.handleKey(event.key(), (modifiers & (MOD_CONTROL | MOD_SUPER)) != 0,
                (modifiers & MOD_SHIFT) != 0, (modifiers & MOD_ALT) != 0)) {
            return true;
        }
        if (activePanel == editor && editor.handleKey(event.key(), event.modifiers())) {
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (io != null && io.getWantCaptureMouse()) {
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (io != null && io.getWantCaptureMouse()) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    /**
     * Called by the wrapper's mixin at the end of each frame. Nothing may escape:
     * an exception here is thrown into the game's frame loop.
     */
    @Override
    public void render(ImGuiIO frameIo) {
        ImGuiScreens.applyMinecraftTheme();
        ImGuiScreens.matchGameGuiScale();

        // Off for the editor, which already binds Tab and the arrows. Read from the
        // previous frame's tab, which is what there is at this point; the flags are
        // consumed at the start of the next frame anyway, so the lag cancels out.
        ImGuiScreens.setKeyboardNavigation(activePanel != editor);
        this.io = frameIo;
        try {
            ImGuiScreens.fullViewportWindow(WINDOW_ID, this::drawBody);
            renderError = null;
        } catch (Throwable throwable) {
            String message = String.valueOf(throwable);
            // Logged on change only, as the per-panel handler already did. A window
            // that fails once fails sixty times a second, and a line per frame buries
            // the first one, which is the only one that says where it started.
            if (!message.equals(renderError)) {
                McMarkingsCompanion.LOGGER.error("[mcmarkings] shell render failed", throwable);
            }
            renderError = message;
        }
    }

    private void drawBody() {
        // First, deliberately. It used to sit at the end of the top bar, which is fine
        // for a failure in a tab and useless for one in the top bar itself: that would
        // throw at the same place every frame, never reach the line that reports it,
        // and leave a half-drawn window explaining nothing. Reporting before anything
        // that can fail means the message survives whatever comes after it.
        drawRenderError();

        drawTopBar();
        ImGui.separator();

        // The first run is a state of this window, not a screen in front of it.
        // Adding a folder puts the tabs here on the very next frame, so there is no
        // handover and nothing flashes.
        if (services.hasConfiguredRepositories()) {
            drawTabs();
        } else {
            welcome.draw();
        }

        status.draw();
        palette.draw();
    }

    private void drawTopBar() {
        drawRepositoryPicker();

        // The press is acted on after the disabled block closes, not inside it. An
        // action that threw would otherwise leave ImGui's disabled stack unbalanced
        // and take out the following frame.
        ImGui.sameLine();
        ImGui.beginDisabled(pulling || !services.hasRepositories());
        boolean pullPressed = ImGui.button("Pull");
        ImGui.endDisabled();
        if (ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled)) {
            ImGui.setTooltip("Fetches the repository, then refreshes every placed map whose image moved."
                    + (pulling ? "\n\nAlready pulling." : "")
                    + (services.hasRepositories() ? "" : "\n\nThere is no repository to pull."));
        }

        ImGui.sameLine();
        ImGui.beginDisabled(services.isLoading() || !services.hasRepositories());
        boolean rescanPressed = ImGui.button("Rescan");
        ImGui.endDisabled();
        if (ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled)) {
            ImGui.setTooltip("Reads the folder again, after adding or changing images outside the game."
                    + (services.isLoading() ? "\n\nAlready reading the folder." : "")
                    + (services.hasRepositories() ? "" : "\n\nThere is no repository to read."));
        }

        if (pullPressed) {
            pull();
        }
        if (rescanPressed) {
            rescan();
        }

        ImGui.sameLine();
        if (ImGui.button("Commands")) {
            palette.open();
        }
        if (ImGui.isItemHovered()) {
            // A palette nobody can find is a palette nobody uses, and the shortcut is
            // only obvious to people who already expected it to be there.
            // The binding, not a copy of it. A tooltip naming a key it does not read
            // is right until the day the key moves, and then it is wrong in the one
            // place someone looks to find out what the key is.
            String keys = commands.byId("shell.palette")
                    .map(command -> command.shortcut() == null ? "" : "  (" + command.shortcut().display() + ")")
                    .orElse("");
            ImGui.setTooltip("Search everything this window and the visible tab can do" + keys);
        }

        ImGui.sameLine();
        if (ImGui.button("Close")) {
            onClose();
        }

        if (services.isLoading()) {
            ImGui.sameLine();
            ImGui.textDisabled("Opening repositories...");
        }
        if (pulling) {
            ImGui.sameLine();
            ImGui.textDisabled("Pulling...");
        }

        // The sink has always known this and nothing ever asked. Commands go out at a
        // few a second, so a pull that refreshes fifty maps is half a minute of the
        // window looking finished while it is still working. Someone who cannot see
        // that either presses the button again or walks away before it is done.
        // Visible from every tab, because the editor's own status line is not. Someone
        // browsing images has no way to know they left something unsaved two tabs
        // over, and the tab label cannot say so: ImGui keys a tab's selected state off
        // its label, so changing it would deselect the tab under them.
        if (services.hasUnsavedEdits()) {
            ImGui.sameLine();
            Notice.warning("unsaved");
            if (ImGui.isItemHovered()) {
                // Checked rather than written from memory: closing keeps the window, a
                // crash is covered by the snapshot, and reloading asks before
                // discarding and writes the snapshot on the way out.
                ImGui.setTooltip("The editor has changes not saved to a template.\n"
                        + "They survive closing this window and a crash. Reloading asks first.");
            }
        }

        int queued = services.commands.pending();
        if (queued > 0) {
            ImGui.sameLine();
            ImGui.textDisabled(queued + " command(s) queued");
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Sent a few a second so the server does not drop them. "
                        + "Closing this window does not stop them.");
            }
        }
    }

    private void drawRenderError() {
        if (renderError == null) {
            return;
        }
        Notice.errorWrapped("The window failed to draw: " + ImGuiScreens.truncate(renderError, 140));
        ImGui.textDisabled("The full stack trace is in the log. Closing and reopening usually clears it.");
        ImGui.separator();
    }

    /**
     * Which repository everything acts on, and how to change it.
     *
     * <p>Deliberately the first thing in the bar. The most confusing state in a
     * multi-repository tool is doing the right thing to the wrong folder.
     */
    private void drawRepositoryPicker() {
        String activeId = services.activeRepositoryId();
        // Spelled out rather than left as a bare name, because a folder name on its
        // own does not tell anyone what the control does.
        String label = services.active()
                .map(workspace -> "Repository: " + displayName(workspace))
                .orElse(services.isLoading() ? "Opening repositories..." : "No repository yet");

        ImGui.setNextItemWidth(Math.max(160.0f, ImGui.getTextLineHeight() * 20.0f));
        if (!ImGui.beginCombo("##repository", ImGuiScreens.truncate(label, 40))) {
            return;
        }
        try {
            List<Workspace> workspaces = services.workspaces();
            if (workspaces.isEmpty()) {
                ImGui.textDisabled(services.isLoading() ? "Still opening..." : "Nothing set up yet");
                return;
            }
            for (Workspace workspace : workspaces) {
                boolean current = workspace.id().equals(activeId);
                String name = displayName(workspace) + (workspace.hasWarning() ? "  (needs attention)" : "");
                if (ImGui.selectable(ImGuiScreens.truncate(name, 40) + "##repo-" + workspace.id(), current)
                        && !current) {
                    switchTo(workspace);
                }
                if (ImGui.isItemHovered()) {
                    // Says what needs attention, not just that something does. The
                    // reason was worked out when the folder was opened and was only
                    // ever shown on another tab, so the marker here was a dead end.
                    ImGui.setTooltip(workspace.entry().root()
                            + (workspace.hasWarning()
                                    ? "\n\n" + workspace.warning() + "\n\nRepositories tab to fix it."
                                    : ""));
                }
            }
        } finally {
            ImGui.endCombo();
        }
    }

    /** The configured name wins, since renaming does not rebuild the workspace. */
    private String displayName(Workspace workspace) {
        return services.config.byId(workspace.id())
                .map(entry -> entry.displayName())
                .orElseGet(() -> workspace.entry().displayName());
    }

    private void switchTo(Workspace workspace) {
        services.setActive(workspace.id());
        browser.refresh();
        actionImage = null;
        resolveIdentity();
        status.info("Now acting on " + displayName(workspace));
    }

    private void drawTabs() {
        if (!ImGui.beginTabBar(TAB_BAR_ID, TAB_BAR_FLAGS)) {
            return;
        }
        try {
            for (Panel panel : panels) {
                drawTab(panel);
            }
        } finally {
            ImGui.endTabBar();
        }
    }

    private void drawTab(Panel panel) {
        // A panel can hand its work to another one, and the tab has to follow it.
        // Sending someone to the editor and leaving them looking at the generator
        // would read as nothing having happened.
        int flags = panel.title().equals(pendingTab) ? ImGuiTabItemFlags.SetSelected : ImGuiTabItemFlags.None;
        if (panel.title().equals(pendingTab)) {
            pendingTab = null;
        }

        if (!ImGui.beginTabItem(panel.title(), flags)) {
            return;
        }
        activePanel = panel;
        try {
            // Reserves the status line, and gives the panel a child that scrolls
            // inside itself rather than scrolling the window and taking the tab bar
            // with it.
            float bodyHeight = Math.max(64.0f,
                    ImGui.getContentRegionAvailY() - ImGui.getFrameHeightWithSpacing());
            ImGuiScreens.child("##panel-" + panel.title(), 0.0f, bodyHeight, () -> drawPanel(panel));
        } finally {
            ImGui.endTabItem();
        }
    }

    /**
     * Draws a panel, reporting a failure as text instead of letting it out.
     *
     * <p>Letting it out would abort the loop over the panels and the tabs after this
     * one would not be submitted at all, so a bug in one panel would take the
     * navigation off screen. That is the exact failure the tab bar is here to avoid.
     */
    private void drawPanel(Panel panel) {
        try {
            panel.draw();
            panelErrors.remove(panel.title());
        } catch (Throwable throwable) {
            String message = String.valueOf(throwable);
            // Logged on change only. A panel that fails once fails sixty times a
            // second, and a log line per frame buries the first one.
            String previous = panelErrors.put(panel.title(), message);
            if (!message.equals(previous)) {
                McMarkingsCompanion.LOGGER.error("[mcmarkings] panel {} failed to draw", panel.title(), throwable);
            }
            Notice.error("This panel failed to draw.");
            ImGui.textWrapped(message);
        }
    }

    /**
     * The actions the browser's preview offers for the selected image.
     *
     * <p>Passed to the browser as a callback rather than built into it: putting an
     * image on a wall is this screen's business, and the browser has to stay usable
     * anywhere an image needs choosing.
     */
    private void drawImageActions(RepoImage image) {
        // Recomputed when the image changes or when the registry has, which covers
        // placing from here, and also deleting or forgetting a map on the Placed tab
        // and coming back to an image that is still selected.
        if (!image.equals(actionImage) || placedGeneration != services.registry.generation()) {
            actionImage = image;
            grid = GridRecommender.best(image.width(), image.height());
            suggestions = GridRecommender.top(image.width(), image.height(), 3);

            refreshPlacedAs(image);
        }

        // The registry has known this since the beginning and only Pull ever asked.
        // Browsing eleven hundred signs without being told which are already on a
        // wall is how the same one gets placed twice.
        if (!placedAs.isEmpty()) {
            String names = String.join(", ", placedAs);
            ImGui.textDisabled("Already placed as " + ImGuiScreens.truncate(names, 48));
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(names + "\n\nCreating it again refreshes that map "
                        + "rather than making a second one.");
            }
        }

        ImGui.text("Frame size " + grid + ", " + grid.frameCount() + " frames");

        // What it becomes, not what it is. A tall sign on a short grid loses most of
        // its detail, and the source dimensions above give no hint of that: 128 pixels
        // per frame is all a wall ever has.
        int wallWidth = grid.columns() * GridSize.MAP_PIXELS;
        int wallHeight = grid.rows() * GridSize.MAP_PIXELS;
        ImGui.textDisabled(wallWidth + " x " + wallHeight + " on the wall");
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Every item frame shows a " + GridSize.MAP_PIXELS + " pixel map, so this "
                    + "is the detail the sign really has however large the source image is.");
        }
        for (GridSuggestion suggestion : suggestions) {
            String label = suggestion.grid() + "  " + suggestion.grid().frameCount() + " frames"
                    + (suggestion.isComfortable() ? "" : "  " + suggestion.distortionPercent() + "% stretch");
            if (ImGui.button(label + "##grid-" + suggestion.grid(), -1.0f, 0.0f)) {
                grid = suggestion.grid();
                status.info("Frame size " + grid);
            }
        }

        ImGui.separator();

        // Presses are collected first and acted on below, so an action that threw
        // cannot leave ImGui's disabled stack unbalanced for the next frame.
        boolean pinnable = rawUrls != null && headSha != null;
        ImGui.beginDisabled(!pinnable);
        boolean create = ImGui.button("Create map", -1.0f, 0.0f);
        ImGui.endDisabled();
        if (ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled)) {
            ImGui.setTooltip("Places this on the wall you are looking at." + pinnableReason());
        }

        boolean frames = ImGui.button("Get frames", -1.0f, 0.0f);

        boolean toEditor = ImGui.button("Add to editor", -1.0f, 0.0f);
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Puts this on the editor's canvas, centred, and takes you there.");
        }

        ImGui.beginDisabled(!pinnable);
        boolean copy = ImGui.button("Copy command", -1.0f, 0.0f);
        ImGui.endDisabled();
        if (ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled)) {
            ImGui.setTooltip("Puts the ImageFrame command on the clipboard, to run yourself."
                    + pinnableReason());
        }

        if (toEditor) {
            editor.addImage(image);
            pendingTab = "Editor";
            status.good("Added " + image.displayName() + " to the editor");
        }
        if (create) {
            createMap(image);
        }
        if (frames) {
            services.commands.send(ImageFrameCommands.giveInvisibleFrames(
                    services.config.commandAlias, services.config.glowingFrames, grid.frameCount()));
            status.good("Requested " + grid.frameCount() + " invisible frames");
        }
        if (copy) {
            copyCommand(image);
        }

        if (!pinnable) {
            ImGui.textDisabled("Waiting on this repository's git identity");
        }
    }

    private void createMap(RepoImage image) {
        String name = ImageFrameCommands.sanitiseName(image.name());
        String url = rawUrls.pinned(headSha, image.path());

        // ImageFrame rejects create for a name it already knows, so placing the same
        // image twice failed on the server with nothing here explaining why. Publishing
        // has always handled this and said so in a comment; this path, which is the one
        // most people use, sent create every time.
        boolean exists = services.registry.byName(name).isPresent();
        services.commands.send(exists
                ? ImageFrameCommands.refresh(services.config.commandAlias, name, url)
                : ImageFrameCommands.create(services.config.commandAlias, name, url, grid));

        services.registry.put(new MapEntry(name, services.activeRepositoryId(), image.path(), grid, headSha,
                System.currentTimeMillis()));
        saveRegistry();


        status.good((exists ? "Refreshing " : "Creating ") + name + " at " + grid);
    }

    /**
     * Which maps already come from this image.
     *
     * <p>Worked out when the answer can change rather than every frame. It reads a
     * list the registry already holds, which is cheap, but doing it sixty times a
     * second for something that changes when you click or place is waste.
     */
    private void refreshPlacedAs(RepoImage image) {
        placedGeneration = services.registry.generation();
        placedAs = services.registry.byRepoPath(image.path()).stream()
                .map(MapEntry::imageFrameName)
                .toList();
    }

    private void copyCommand(RepoImage image) {
        String name = ImageFrameCommands.sanitiseName(image.name());
        String url = rawUrls.pinned(headSha, image.path());

        // The same choice the button makes. A copied create for a name the server
        // already knows is a command that fails when pasted, which is worse than the
        // button failing: by then the mod is not involved and there is nothing to
        // explain it.
        boolean exists = services.registry.byName(name).isPresent();
        String command = "/" + (exists
                ? ImageFrameCommands.refresh(services.config.commandAlias, name, url)
                : ImageFrameCommands.create(services.config.commandAlias, name, url, grid));

        Minecraft.getInstance().keyboardHandler.setClipboard(command);
        status.good("Copied the " + (exists ? "refresh" : "create") + " command");
    }

    private void saveRegistry() {
        services.saveRegistry();
    }

    /**
     * Why the two publishing buttons are dead, when they are.
     *
     * <p>They depend on a git lookup that runs when the window opens, and until it
     * lands there is nothing to build a URL from. Both buttons used to grey out with
     * no explanation at all, which is the worst moment in the mod to say nothing:
     * you have found the image you wanted and the two things that would put it on a
     * wall are dead. A second of "still working this out" reads completely
     * differently from a permanent "broken".
     *
     * <p>Empty when they work, so the tooltip is only about what the button does.
     */
    private String pinnableReason() {
        if (rawUrls != null && headSha != null) {
            return "";
        }
        return "\n\nStill working out where this repository's files are served from. "
                + "If this does not clear, the status line at the bottom says what git could not do.";
    }

    /**
     * Works out which forge serves this repository and which commit a URL may pin
     * to.
     *
     * <p>Both shell out to git, so neither may happen on the render thread. The
     * result is discarded if the repository was switched while it was resolving,
     * because it would then describe the wrong folder.
     */
    private void resolveIdentity() {
        rawUrls = null;
        headSha = null;
        // Cleared with the rest of the repository's identity. It is answered below by
        // a lookup that can fail, and the answer for the folder just switched away
        // from is worse than no answer at all.
        services.setPushState(PushState.UNKNOWN);
        if (!services.hasRepositories()) {
            return;
        }

        String repoId = services.activeRepositoryId();
        Thread.ofVirtual().name("mcmarkings-identity").start(() -> {
            try {
                RawUrls.Target target = services.rawUrls();
                // The newest commit known to be on the remote, not HEAD: the server
                // fetches these URLs over HTTP, so an unpushed commit is a 404.
                String head = services.git().pinnableCommit();

                // The same two calls answer whether anything local is unpushed, and
                // both are already on a worker here. Placing a sign pushes the branch,
                // so the publish controls need to be able to say so with a straight
                // face rather than after the fact.
                services.setPushState(PushState.of(!head.equals(services.git().head())));

                // Never shown until now, and the mod commits and pushes to whatever
                // branch is checked out. Working on one branch while believing you are
                // on another is how a sign ends up pushed somewhere nobody looks.
                String branch = branchOrBlank();
                Minecraft.getInstance().execute(() -> {
                    if (!repoId.equals(services.activeRepositoryId())) {
                        return;
                    }
                    rawUrls = target;
                    headSha = head;
                    status.info(services.repo().images().size() + " images, "
                            + (branch.isEmpty() ? "" : "on " + branch + ", ")
                            + "at " + shortSha(head) + " via " + target.describe());
                });
            } catch (GitException exception) {
                Minecraft.getInstance().execute(() -> {
                    if (repoId.equals(services.activeRepositoryId())) {
                        status.bad("git: " + exception.describe());
                    }
                });
            }
        });
    }

    /**
     * Pull, then re-issue a refresh for every map whose backing PNG moved.
     *
     * <p>Without the second half the server keeps serving the old image, because
     * ImageFrame only fetches when it is told to.
     */
    /**
     * The checked-out branch, or blank when it cannot be read.
     *
     * <p>A detached head or a repository mid-rebase has no branch name, and that is
     * not worth failing the whole identity lookup over: the commit and the URL
     * template are the parts that actually matter for placing a sign.
     */
    private String branchOrBlank() {
        try {
            String branch = services.git().currentBranch();
            return branch == null ? "" : branch.trim();
        } catch (GitException unavailable) {
            return "";
        }
    }

    private void pull() {
        pulling = true;
        status.info("Pulling...");

        RawUrls.Target target = rawUrls;
        Thread.ofVirtual().name("mcmarkings-pull").start(() -> {
            try {
                PullResult result = services.git().pull();
                services.repo().rescan();

                // Not the head the pull produced. That is the local branch tip, and a
                // pull onto unpushed commits leaves a merge commit that exists only
                // here, so every URL built from it is a 404 and the signs come back
                // blank. This is the newest commit known to be on the remote, which is
                // what the identity lookup already used and what pull quietly undid.
                String pinnable = services.git().pinnableCommit();

                // Recomputed here too. A pull moves both sides, so a flag worked out
                // when the repository was opened is stale from this point on, and a
                // warning about unpushed work that is no longer true teaches people to
                // ignore the one that is.
                services.setPushState(PushState.of(!pinnable.equals(services.git().head())));

                List<String> commands = target == null ? List.of() : result.changedPaths().stream()
                        .flatMap(path -> services.registry.byRepoPath(path).stream())
                        .map(entry -> ImageFrameCommands.refresh(services.config.commandAlias,
                                entry.imageFrameName(),
                                target.pinned(pinnable, entry.repoPath())))
                        .toList();

                Minecraft.getInstance().execute(() -> {
                    pulling = false;
                    headSha = pinnable;
                    browser.refresh();
                    services.commands.sendAll(commands);
                    status.good(result.changed()
                            ? result.changedPaths().size() + " images changed, refreshing "
                                    + commands.size() + " maps"
                            : "Already up to date");
                });
            } catch (GitException exception) {
                Minecraft.getInstance().execute(() -> {
                    pulling = false;
                    status.bad("git: " + exception.describe());
                });
            } catch (Exception exception) {
                McMarkingsCompanion.LOGGER.error("[mcmarkings] pull failed", exception);
                Minecraft.getInstance().execute(() -> {
                    pulling = false;
                    status.bad("Pull failed: " + exception.getMessage());
                });
            }
        });
    }

    /** Picks up images added or removed outside the game. Walks the tree, so off-thread. */
    private void rescan() {
        status.info("Rescanning...");
        services.reloadAsync(services.activeRepositoryId());
        services.whenReady(() -> {
            browser.refresh();
            status.good("Rescanned, " + services.repo().images().size() + " images");
        });
    }

    private static String shortSha(String sha) {
        return sha.length() > 7 ? sha.substring(0, 7) : sha;
    }

}
