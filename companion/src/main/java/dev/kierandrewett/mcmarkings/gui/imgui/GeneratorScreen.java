package dev.kierandrewett.mcmarkings.gui.imgui;

import cn.enaium.fabric.imgui.ImGuiRenderable;
import dev.kierandrewett.mcmarkings.CompanionServices;
import dev.kierandrewett.mcmarkings.McMarkingsCompanion;
import dev.kierandrewett.mcmarkings.core.GridSize;
import dev.kierandrewett.mcmarkings.core.GridSuggestion;
import dev.kierandrewett.mcmarkings.core.RepoImage;
import dev.kierandrewett.mcmarkings.gui.BrowserScreen;
import dev.kierandrewett.mcmarkings.imageframe.ImageFrameCommands;
import dev.kierandrewett.mcmarkings.js.GeneratorDef;
import dev.kierandrewett.mcmarkings.js.GeneratorException;
import dev.kierandrewett.mcmarkings.js.ParamDef;
import dev.kierandrewett.mcmarkings.render.GridRecommender;
import dev.kierandrewett.mcmarkings.texture.TextureHandle;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiInputTextFlags;
import imgui.type.ImBoolean;
import imgui.type.ImFloat;
import imgui.type.ImInt;
import imgui.type.ImString;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The generator form: pick a script, fill in its parameters, watch the sign
 * redraw, then publish it.
 *
 * <p>This exists in ImGui rather than owo because of one widget. Generated images
 * are commonly a stack of text lines, and a real multiline text box with a caret
 * is what makes that bearable to type; Minecraft's own toolkit has nothing like
 * it.
 *
 * <p>The form is built entirely from {@link ParamDef}, so a generator can add a
 * field by editing its script and reloading, with no Java change.
 */
public class GeneratorScreen extends Screen implements ImGuiRenderable {

    /**
     * Re-rendering runs a JS script. At 60fps a keystroke-per-frame rebuild would
     * run it dozens of times a second, so edits settle first.
     */
    private static final long PREVIEW_DEBOUNCE_MILLIS = 300L;

    private static final float LIST_WIDTH = 220.0f;
    private static final float FORM_WIDTH = 360.0f;
    private static final int TEXT_BUFFER = 512;
    private static final int LINES_BUFFER = 4096;
    private static final String PICKER_POPUP = "Pick repository image";

    private final CompanionServices services;
    private final ImGuiScreens.Status status = new ImGuiScreens.Status();
    private final PublishFlow publish;

    private List<GeneratorDef> generators = List.of();
    private GeneratorDef selected;
    private List<Field> fields = List.of();

    private final ImString name = new ImString("", TEXT_BUFFER);
    private boolean nameAutoFilled = true;

    /** Zero means clean; otherwise the moment the last edit landed. */
    private long dirtyAtMillis;

    /**
     * One generator run at a time. The script engine is shared and its
     * thread-safety is not part of its contract, so overlapping runs are not worth
     * the risk for a preview nobody is waiting on.
     */
    private final AtomicBoolean rendering = new AtomicBoolean();

    private BufferedImage previewImage;
    private TextureHandle previewTexture;
    private String previewKey;
    private int previewSequence;
    private String generatorError;

    private GridSize grid;
    private boolean gridPinned;
    private List<GridSuggestion> suggestions = List.of();

    private Field pickerTarget;
    private final ImString pickerQuery = new ImString("", TEXT_BUFFER);

    /**
     * ImGuiIO is only meaningful once the wrapper has run a frame, and the input
     * overrides below are called before that on the very first tick.
     */
    private ImGuiIO io;

    private String renderError;

    public GeneratorScreen(CompanionServices services) {
        super(Component.literal("MCMarkings generator"));
        this.services = services;
        this.publish = new PublishFlow(services, status);
    }

    @Override
    protected void init() {
        reloadGeneratorList();
    }

    @Override
    public void removed() {
        // The preview is a GPU texture nobody else references; without this it
        // survives every open of the screen for the rest of the session.
        if (previewKey != null) {
            services.thumbnails.evict(previewKey);
            previewKey = null;
            previewTexture = null;
        }
        super.removed();
    }

    /**
     * The wrapper chains GLFW callbacks rather than consuming them, so Minecraft
     * still sees keys typed into an ImGui text box. Escape is the one that hurts:
     * it would close the screen mid-sentence.
     */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (io != null && io.getWantCaptureKeyboard()) {
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
        this.io = frameIo;
        try {
            maybeRenderPreview();
            ImGuiScreens.fullViewportWindow("##mcmarkings-generator", this::drawBody);
            renderError = null;
        } catch (Throwable throwable) {
            renderError = String.valueOf(throwable);
            McMarkingsCompanion.LOGGER.error("[mcmarkings] generator screen render failed", throwable);
        }
    }

    private void drawBody() {
        drawHeader();
        ImGui.separator();

        // ImGui reads a negative child size as "the remaining space minus this", so
        // a window too small for the panes would silently invert the layout.
        float bodyHeight = Math.max(64.0f, ImGui.getContentRegionAvailY() - ImGui.getFrameHeightWithSpacing());
        ImGuiScreens.child("##generators", LIST_WIDTH, bodyHeight, this::drawGeneratorList);
        ImGui.sameLine();
        ImGuiScreens.child("##form", FORM_WIDTH, bodyHeight, this::drawForm);
        ImGui.sameLine();
        ImGuiScreens.child("##preview", 0.0f, bodyHeight, this::drawPreview);

        status.draw();
    }

    private void drawHeader() {
        // This is an ImGui window, so none of the mod's normal navigation is on
        // screen. Without a way back the only exit is closing the game's screen
        // entirely, which reads as being stranded.
        if (ImGui.button("< Back")) {
            Minecraft.getInstance().setScreen(new BrowserScreen(services));
        }
        ImGui.sameLine();
        ImGui.text("Generator");
        ImGui.sameLine();
        if (ImGui.button("Reload scripts")) {
            reloadScripts();
        }
        ImGui.sameLine();
        if (ImGui.button("Close")) {
            onClose();
        }
        if (renderError != null) {
            ImGui.sameLine();
            ImGui.textColored(0.95f, 0.45f, 0.45f, 1.0f, "render error: "
                    + ImGuiScreens.truncate(renderError, 120));
        }
    }

    private void drawGeneratorList() {
        if (generators.isEmpty()) {
            ImGui.textWrapped("No generators found in " + services.config.generatorDirectory);
            return;
        }

        for (GeneratorDef generator : generators) {
            boolean active = selected != null && selected.id().equals(generator.id());
            if (ImGui.selectable(ImGuiScreens.truncate(generator.title(), 28) + "##" + generator.id(), active)) {
                select(generator);
            }
            if (ImGui.isItemHovered() && generator.description() != null) {
                ImGui.setTooltip(generator.description());
            }
        }
    }

    private void drawForm() {
        if (selected == null) {
            ImGui.textDisabled("Select a generator");
            return;
        }

        ImGui.textWrapped(selected.title());
        if (selected.description() != null && !selected.description().isBlank()) {
            ImGui.textDisabled(selected.description());
        }
        ImGui.separator();

        for (Field field : fields) {
            drawField(field);
        }

        drawImagePicker();

        ImGui.separator();
        drawGridChoice();

        ImGui.separator();
        ImGui.setNextItemWidth(-1.0f);
        if (ImGui.inputText("##name", name)) {
            nameAutoFilled = false;
        }
        ImGui.textDisabled("Map name");

        drawActions();
    }

    private void drawField(Field field) {
        ParamDef def = field.def;
        String id = "##" + def.key();
        ImGui.setNextItemWidth(-1.0f);

        boolean changed = switch (def.type()) {
            case TEXT -> ImGui.inputText(id, field.text);
            case LINES -> ImGui.inputTextMultiline(id, field.text, -1.0f, 110.0f,
                    ImGuiInputTextFlags.AllowTabInput);
            case SELECT -> field.options.length == 0
                    ? falseWithNotice(def, "no options declared")
                    : ImGui.combo(id, field.choice, field.options);
            case NUMBER -> ImGui.inputFloat(id, field.number);
            case BOOLEAN -> ImGui.checkbox(id, field.flag);
            case COLOUR -> ImGui.colorEdit3(id, field.colour);
            case IMAGE -> drawImageField(field);
        };

        ImGui.text(def.label() == null ? def.key() : def.label());
        if (def.help() != null && !def.help().isBlank()) {
            ImGui.textDisabled(ImGuiScreens.truncate(def.help(), 90));
        }
        ImGui.spacing();

        if (changed) {
            markDirty();
        }
    }

    private boolean drawImageField(Field field) {
        boolean changed = ImGui.inputText("##" + field.def.key() + "-path", field.text);
        if (ImGui.button("Pick...##" + field.def.key())) {
            pickerTarget = field;
            pickerQuery.set("");
            ImGui.openPopup(PICKER_POPUP);
        }
        ImGui.sameLine();
        if (ImGui.button("Clear##" + field.def.key())) {
            field.text.set("");
            changed = true;
        }
        return changed;
    }

    private boolean falseWithNotice(ParamDef def, String reason) {
        ImGui.textDisabled(def.key() + ": " + reason);
        return false;
    }

    private void drawImagePicker() {
        ImGui.setNextWindowSize(380.0f, 420.0f);
        if (!ImGui.beginPopup(PICKER_POPUP)) {
            return;
        }
        try {
            ImGui.setNextItemWidth(-1.0f);
            ImGui.inputTextWithHint("##picker-query", "Search images", pickerQuery);

            for (RepoImage image : services.repo().search(pickerQuery.get(), 60)) {
                if (!ImGui.selectable(ImGuiScreens.truncate(image.path(), 46) + "##pick-" + image.path())) {
                    continue;
                }
                // The generator can be switched while the popup is open, which would
                // leave the target pointing at a field the form no longer shows.
                if (pickerTarget != null && fields.contains(pickerTarget)) {
                    pickerTarget.text.set(image.path());
                    markDirty();
                }
                ImGui.closeCurrentPopup();
            }
        } finally {
            ImGui.endPopup();
        }
    }

    private void drawGridChoice() {
        if (grid == null) {
            ImGui.textDisabled("Frame size decided once a preview exists");
            return;
        }

        ImGui.text("Frame size " + grid + "  (" + grid.frameCount() + " frames)");
        for (GridSuggestion suggestion : suggestions) {
            String label = suggestion.grid() + "  " + suggestion.grid().frameCount() + " frames"
                    + (suggestion.isComfortable() ? "" : "  " + suggestion.distortionPercent() + "% stretch");
            if (ImGui.button(label + "##grid-" + suggestion.grid())) {
                grid = suggestion.grid();
                gridPinned = true;
                status.info("Frame size " + grid);
            }
            ImGui.sameLine();
        }
        ImGui.newLine();
    }

    private void drawActions() {
        boolean busy = publish.running();
        ImGui.beginDisabled(busy || previewImage == null || grid == null);
        if (ImGui.button("Save & publish")) {
            publish.publish(new PublishFlow.Request(name.get(), previewImage, grid, null), null);
        }
        ImGui.endDisabled();

        ImGui.sameLine();
        ImGui.beginDisabled(grid == null);
        if (ImGui.button("Get frames")) {
            services.commands.send(ImageFrameCommands.giveInvisibleFrames(
                    services.config.commandAlias, services.config.glowingFrames, grid.frameCount()));
            status.good("Requested " + grid.frameCount() + " invisible frames");
        }
        ImGui.endDisabled();

        ImGui.sameLine();
        if (ImGui.button("Copy command")) {
            copyCommand();
        }

        if (busy) {
            ImGui.textDisabled("Publishing...");
        }
    }

    private void drawPreview() {
        if (generatorError != null) {
            ImGui.textColored(0.95f, 0.45f, 0.45f, 1.0f, "Generator failed");
            ImGui.separator();
            ImGui.textWrapped(generatorError);
            return;
        }

        if (previewImage == null) {
            ImGui.textDisabled(rendering.get() ? "Rendering..." : "No preview yet");
            return;
        }

        int width = previewImage.getWidth();
        int height = previewImage.getHeight();
        ImGui.text(width + " x " + height + " px");
        if (rendering.get()) {
            ImGui.sameLine();
            ImGui.textDisabled("(updating)");
        }
        ImGui.separator();

        float scale = ImGuiScreens.fitScale(width, height,
                Math.max(32.0f, ImGui.getContentRegionAvailX()),
                Math.max(32.0f, ImGui.getContentRegionAvailY()));
        ImGuiScreens.image(previewTexture, width * scale, height * scale);
    }

    private void copyCommand() {
        var result = publish.lastResult();
        if (result.isEmpty()) {
            status.bad("Save & publish first, the URL needs a commit to pin to");
            return;
        }
        PublishFlow.Result published = result.get();
        String command = "/" + ImageFrameCommands.create(services.config.commandAlias,
                published.name(), published.url(), published.grid());
        Minecraft.getInstance().keyboardHandler.setClipboard(command);
        status.good("Copied to clipboard");
    }

    private void select(GeneratorDef generator) {
        selected = generator;
        fields = buildFields(generator);
        gridPinned = false;
        generatorError = null;

        if (nameAutoFilled || name.get().isBlank()) {
            name.set(ImageFrameCommands.sanitiseName(generator.id()));
            nameAutoFilled = true;
        }

        markDirty();
    }

    private void markDirty() {
        dirtyAtMillis = System.currentTimeMillis();
    }

    private void maybeRenderPreview() {
        if (selected == null || dirtyAtMillis == 0L) {
            return;
        }
        if (System.currentTimeMillis() - dirtyAtMillis < PREVIEW_DEBOUNCE_MILLIS) {
            return;
        }
        if (!rendering.compareAndSet(false, true)) {
            return;
        }
        dirtyAtMillis = 0L;

        String generatorId = selected.id();
        Map<String, Object> params = collectParams();

        Thread.ofVirtual().start(() -> {
            try {
                BufferedImage image = services.generators().render(generatorId, params);
                Minecraft.getInstance().execute(() -> onPreview(generatorId, image));
            } catch (GeneratorException exception) {
                Minecraft.getInstance().execute(() -> onPreviewFailed(generatorId, exception.getMessage()));
            } catch (RuntimeException exception) {
                McMarkingsCompanion.LOGGER.error("[mcmarkings] generator " + generatorId + " threw", exception);
                Minecraft.getInstance().execute(() -> onPreviewFailed(generatorId, String.valueOf(exception)));
            } finally {
                rendering.set(false);
            }
        });
    }

    private void onPreview(String generatorId, BufferedImage image) {
        if (!isCurrent(generatorId) || image == null) {
            return;
        }

        generatorError = null;
        previewImage = image;
        suggestions = GridRecommender.top(image.getWidth(), image.getHeight(), 3);
        if (!gridPinned) {
            grid = GridRecommender.best(image.getWidth(), image.getHeight());
        }
        uploadPreview(generatorId, image);
    }

    private void onPreviewFailed(String generatorId, String message) {
        if (!isCurrent(generatorId)) {
            return;
        }
        generatorError = message == null ? "Generator failed with no message" : message;
    }

    private boolean isCurrent(String generatorId) {
        return selected != null && selected.id().equals(generatorId);
    }

    /**
     * Each preview gets a fresh cache key rather than overwriting the last one,
     * because the cache is free to treat a repeated key as a hit and hand back the
     * stale texture. The previous key is dropped once the new one is resident.
     */
    private void uploadPreview(String generatorId, BufferedImage image) {
        String previousKey = previewKey;
        String key = "generator-preview/" + generatorId + "/" + (++previewSequence);
        previewKey = key;

        services.thumbnails.upload(key, image)
                .thenAccept(handle -> Minecraft.getInstance().execute(() -> {
                    if (!key.equals(previewKey)) {
                        services.thumbnails.evict(key);
                        return;
                    }
                    previewTexture = handle;
                    if (previousKey != null) {
                        services.thumbnails.evict(previousKey);
                    }
                }))
                .exceptionally(throwable -> {
                    McMarkingsCompanion.LOGGER.error("[mcmarkings] preview upload failed", throwable);
                    return null;
                });
    }

    private Map<String, Object> collectParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        for (Field field : fields) {
            params.put(field.def.key(), field.value());
        }
        return params;
    }

    private void reloadGeneratorList() {
        generators = List.copyOf(services.generators().generators());
        if (generators.isEmpty()) {
            status.info("No generators loaded");
            return;
        }
        if (selected == null) {
            select(generators.getFirst());
        }
    }

    /** Reading and parsing every script is disk plus JS work, so not on this thread. */
    private void reloadScripts() {
        status.info("Reloading scripts...");
        Thread.ofVirtual().start(() -> {
            try {
                services.generators().reload();
                Minecraft.getInstance().execute(() -> {
                    String previousId = selected == null ? null : selected.id();
                    selected = null;
                    reloadGeneratorList();
                    if (previousId != null) {
                        services.generators().byId(previousId).ifPresent(this::select);
                    }
                    status.good("Reloaded " + generators.size() + " generators");
                });
            } catch (GeneratorException exception) {
                Minecraft.getInstance().execute(() -> status.bad(exception.getMessage()));
            } catch (RuntimeException exception) {
                McMarkingsCompanion.LOGGER.error("[mcmarkings] generator reload failed", exception);
                Minecraft.getInstance().execute(() -> status.bad("Reload failed: " + exception));
            }
        });
    }

    private static List<Field> buildFields(GeneratorDef generator) {
        List<Field> built = new ArrayList<>();
        if (generator.params() == null) {
            return List.copyOf(built);
        }
        for (ParamDef def : generator.params()) {
            if (def != null) {
                built.add(new Field(def));
            }
        }
        return List.copyOf(built);
    }

    /**
     * One parameter's live widget state.
     *
     * <p>ImGui edits through mutable boxes rather than returning values, so each
     * type needs its own holder. Only the one matching the declared type is used;
     * allocating all of them keeps the class free of nulls and casts.
     */
    private static final class Field {

        private final ParamDef def;
        private final ImString text;
        private final ImInt choice = new ImInt();
        private final ImFloat number = new ImFloat();
        private final ImBoolean flag = new ImBoolean();
        private final float[] colour = new float[3];
        private final String[] options;

        private Field(ParamDef def) {
            this.def = def;
            this.options = def.options() == null
                    ? new String[0]
                    : def.options().toArray(new String[0]);
            this.text = new ImString(def.type() == ParamDef.ParamType.LINES ? LINES_BUFFER : TEXT_BUFFER);
            // Long sign text would otherwise be silently cut at the buffer size.
            this.text.inputData.isResizable = true;
            applyDefault(def.defaultValue());
        }

        private void applyDefault(String defaultValue) {
            String value = defaultValue == null ? "" : defaultValue;
            switch (def.type()) {
                case TEXT, LINES, IMAGE -> text.set(value);
                case SELECT -> choice.set(Math.max(0, indexOf(value)));
                case NUMBER -> number.set(parseFloat(value));
                case BOOLEAN -> flag.set(Boolean.parseBoolean(value));
                case COLOUR -> parseColour(value, colour);
            }
        }

        private int indexOf(String value) {
            for (int index = 0; index < options.length; index++) {
                if (options[index].equals(value)) {
                    return index;
                }
            }
            return 0;
        }

        /** The value shape {@code GeneratorRuntime#render} documents for this type. */
        private Object value() {
            return switch (def.type()) {
                case TEXT, IMAGE -> text.get();
                case LINES -> text.get().lines().toList();
                case SELECT -> options.length == 0
                        ? ""
                        : options[Math.clamp(choice.get(), 0, options.length - 1)];
                case NUMBER -> Double.valueOf(number.get());
                case BOOLEAN -> Boolean.valueOf(flag.get());
                case COLOUR -> toHex(colour);
            };
        }

        private static float parseFloat(String value) {
            try {
                return value.isBlank() ? 0.0f : Float.parseFloat(value.trim());
            } catch (NumberFormatException exception) {
                return 0.0f;
            }
        }

        private static void parseColour(String value, float[] target) {
            String hex = value.startsWith("#") ? value.substring(1) : value;
            if (hex.length() < 6) {
                return;
            }
            try {
                int packed = Integer.parseInt(hex.substring(0, 6), 16);
                target[0] = ((packed >> 16) & 0xFF) / 255.0f;
                target[1] = ((packed >> 8) & 0xFF) / 255.0f;
                target[2] = (packed & 0xFF) / 255.0f;
            } catch (NumberFormatException exception) {
                // Leave black; a malformed default is not worth failing the form over.
            }
        }

        private static String toHex(float[] rgb) {
            return String.format(Locale.ROOT, "#%02X%02X%02X",
                    channel(rgb[0]), channel(rgb[1]), channel(rgb[2]));
        }

        private static int channel(float value) {
            return Math.clamp(Math.round(value * 255.0f), 0, 255);
        }
    }
}
