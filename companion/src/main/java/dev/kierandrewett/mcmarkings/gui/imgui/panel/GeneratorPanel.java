package dev.kierandrewett.mcmarkings.gui.imgui.panel;

import dev.kierandrewett.mcmarkings.CompanionServices;
import dev.kierandrewett.mcmarkings.McMarkingsCompanion;
import dev.kierandrewett.mcmarkings.core.GridSize;
import dev.kierandrewett.mcmarkings.core.GridSuggestion;
import dev.kierandrewett.mcmarkings.doc.Document;
import dev.kierandrewett.mcmarkings.gui.imgui.ImGuiScreens;
import dev.kierandrewett.mcmarkings.gui.imgui.PublishFlow;
import dev.kierandrewett.mcmarkings.imageframe.ImageFrameCommands;
import dev.kierandrewett.mcmarkings.js.GeneratorDef;
import dev.kierandrewett.mcmarkings.js.GeneratorException;
import dev.kierandrewett.mcmarkings.js.ParamDef;
import dev.kierandrewett.mcmarkings.render.GridRecommender;
import dev.kierandrewett.mcmarkings.texture.TextureHandle;
import imgui.ImGui;
import imgui.flag.ImGuiInputTextFlags;
import imgui.type.ImBoolean;
import imgui.type.ImFloat;
import imgui.type.ImInt;
import imgui.type.ImString;
import net.minecraft.client.Minecraft;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The generator form: pick a script, fill in its parameters, watch the sign
 * redraw, then publish it.
 *
 * <p>Still its own screen rather than a panel in the shell, which is the last
 * place the tab bar disappears. Porting it is worth doing for that reason alone.
 *
 * <p>The form is built entirely from {@link ParamDef}, so a generator can add a
 * field by editing its script and reloading, with no Java change.
 */
public final class GeneratorPanel implements Panel {

    /**
     * Re-rendering runs a JS script. At 60fps a keystroke-per-frame rebuild would
     * run it dozens of times a second, so edits settle first.
     */
    private static final long PREVIEW_DEBOUNCE_MILLIS = 300L;

    private static final float LIST_WIDTH = 220.0f;
    private static final float FORM_WIDTH = 360.0f;
    private static final int TEXT_BUFFER = 512;
    private static final int LINES_BUFFER = 4096;

    private final CompanionServices services;

    /** Brings the editor to the front once a generator's layers are in it. */
    private final Runnable showEditor;
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

    /** Whether the generator list has been read yet. See {@link #draw()}. */
    private boolean loadedOnce;

    /** True while document() is running, so the button cannot be pressed twice. */
    private boolean opening;

    private GridSize grid;
    private boolean gridPinned;
    private List<GridSuggestion> suggestions = List.of();

    /** The shared browser, as a modal. Its own instance so its search is its own. */
    private final ImageBrowserPanel picker;


    public GeneratorPanel(CompanionServices services, Runnable showEditor) {
        this.services = services;
        this.showEditor = showEditor;
        this.publish = new PublishFlow(services, status);
        this.picker = new ImageBrowserPanel(services, "generator-pick", "Images");
    }

    @Override
    public String title() {
        return "Generate";
    }

    @Override
    public void onRemoved() {
        // The preview is a GPU texture nobody else references; without this it
        // survives for the rest of the session.
        if (previewKey != null) {
            services.thumbnails.evict(previewKey);
            previewKey = null;
            previewTexture = null;
        }
    }

    @Override
    public void draw() {
        // A panel has no init, and reading the list on the first frame rather than in
        // the constructor means a repository that is still opening when the window
        // appears is picked up as soon as it is there.
        if (!loadedOnce && !services.isLoading()) {
            loadedOnce = true;
            reloadGeneratorList();
        }

        // Kicking the preview off here rather than from the shell keeps the whole
        // debounce in one place: nothing outside this panel knows the form is dirty.
        maybeRenderPreview();
        drawBody();
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
        ImGui.text("Generator");
        ImGui.sameLine();
        if (ImGui.button("Reload scripts")) {
            reloadScripts();
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
            openImagePicker(field);
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

    /**
     * The image picker, which is the shared browser rather than a list of paths.
     *
     * <p>Picking an image for a generator is the same job as picking one anywhere
     * else, and doing it from a plain list meant choosing a sign by reading its file
     * name. This is the reason the browser was written as a component.
     */
    private void drawImagePicker() {
        picker.drawPicker();
    }

    private void openImagePicker(Field field) {
        picker.openPicker(image -> {
            // The generator can be switched while the picker is open, which would
            // leave this pointing at a field the form no longer shows.
            if (fields.contains(field)) {
                field.text.set(image.path());
                markDirty();
            }
        });
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

        drawOpenInEditor();

        if (busy) {
            ImGui.textDisabled("Publishing...");
        }
    }

    /**
     * Hands the generator's output to the editor as layers.
     *
     * <p>This is the difference between a generator being a starting point and being
     * a dead end. Without it you run a script, get a flat image, and if one arrow
     * wants moving two pixels the only options are editing the script or starting
     * again by hand.
     *
     * <p>Only offered by scripts that describe themselves that way. Running one to
     * find out would mean a button that sometimes does nothing.
     */
    private void drawOpenInEditor() {
        if (selected == null || !selected.editable()) {
            return;
        }

        ImGui.sameLine();
        ImGui.beginDisabled(opening);
        boolean pressed = ImGui.button("Open in editor");
        ImGui.endDisabled();

        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Puts this in the editor as layers you can move, restyle and save.");
        }
        if (pressed) {
            openInEditor();
        }
    }

    private void openInEditor() {
        GeneratorDef generator = selected;
        Map<String, Object> params = collectParams();
        String documentName = name.get();

        opening = true;
        status.info("Building layers...");

        // Running the script is the same work as a preview, so it belongs on a worker
        // for the same reason.
        Thread.ofVirtual().name("mcmarkings-generator-document").start(() -> {
            try {
                Optional<Document> built = services.generators().document(generator.id(), params);
                Minecraft.getInstance().execute(() -> {
                    opening = false;
                    if (built.isEmpty()) {
                        status.bad("This generator did not describe any layers.");
                        return;
                    }

                    Document document = built.get();
                    if (!documentName.isBlank()) {
                        document = new Document(documentName, document.grid(), document.pixelsPerFrame(),
                                document.background(), document.layers());
                    }

                    // Pushed rather than replacing the editor's history, so whatever was
                    // already on the canvas is one undo away.
                    services.editing.push(document, "Generate " + generator.title(), null);
                    services.editing.endGesture();
                    status.good("Opened in the editor.");
                    showEditor.run();
                });
            } catch (GeneratorException failure) {
                Minecraft.getInstance().execute(() -> {
                    opening = false;
                    status.bad(failure.getMessage());
                });
            }
        });
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
