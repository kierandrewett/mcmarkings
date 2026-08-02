package dev.kierandrewett.mcmarkings.gui.imgui;

import dev.kierandrewett.mcmarkings.CompanionServices;
import dev.kierandrewett.mcmarkings.McMarkingsCompanion;
import dev.kierandrewett.mcmarkings.core.GridSize;
import dev.kierandrewett.mcmarkings.core.PushState;
import dev.kierandrewett.mcmarkings.core.MapEntry;
import dev.kierandrewett.mcmarkings.imageframe.ImageFrameCommands;
import dev.kierandrewett.mcmarkings.repo.GitException;
import dev.kierandrewett.mcmarkings.repo.RawUrls;
import net.minecraft.client.Minecraft;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Write a generated image into the repository, push it, and point ImageFrame at
 * the commit it landed in.
 *
 * <p>Every step here is slow: encoding a PNG, two or three git subprocesses, and
 * a network push. None of it may happen on the render thread, so the whole
 * sequence runs on a virtual thread and only the UI-visible results are marshalled
 * back through {@link Minecraft#execute}.
 *
 * <p>The URL is pinned to the commit the push produced rather than to the branch,
 * because forges cache branch raw URLs for minutes and ImageFrame would fetch the
 * previous image or a 404.
 */
public final class PublishFlow {

    /** What the caller wants published. {@code layoutJson} is null for generators. */
    public record Request(String name, BufferedImage image, GridSize grid, String layoutJson) {
    }

    /** Where it ended up, so the caller can copy the command or re-publish. */
    public record Result(String name, String repoPath, String commitSha, String url, GridSize grid) {
    }

    private final CompanionServices services;
    private final ImGuiScreens.Status status;

    /**
     * Read every frame from the render thread and written from a worker, so it
     * has to be volatile even though the writes themselves are marshalled back.
     */
    private volatile boolean running;

    private Result lastResult;

    public PublishFlow(CompanionServices services, ImGuiScreens.Status status) {
        this.services = services;
        this.status = status;
    }

    public boolean running() {
        return running;
    }

    public Optional<Result> lastResult() {
        return Optional.ofNullable(lastResult);
    }

    /**
     * Start a publish. Returns immediately; {@code onSuccess} runs on the client
     * thread and only when everything landed.
     */
    public void publish(Request request, Consumer<Result> onSuccess) {
        if (running) {
            status.bad("A publish is already running");
            return;
        }
        if (request.image() == null) {
            status.bad("Nothing to publish yet");
            return;
        }

        String name = ImageFrameCommands.sanitiseName(request.name());
        running = true;
        status.info("Publishing " + name + "...");

        Thread.ofVirtual().start(() -> runPublish(name, request, onSuccess));
    }

    private void runPublish(String name, Request request, Consumer<Result> onSuccess) {
        try {
            String directory = normaliseDirectory(services.config.generatedDirectory);
            Path generatedDirectory = services.repoRoot().resolve(directory);
            Files.createDirectories(generatedDirectory);

            // Both written through a temporary and moved into place, as the config,
            // the registry, the recovery snapshot and the templates already were. This
            // was the one write that did not, and it is the one that matters most: the
            // commit happens moments later, so an interrupted write here does not
            // leave a half a file lying about, it gets committed and pushed to the
            // address a server fetches from.
            Path pngPath = generatedDirectory.resolve(name + ".png");
            if (!writePngAtomically(request.image(), pngPath)) {
                fail("No PNG writer available");
                return;
            }

            List<Path> files = new ArrayList<>();
            files.add(pngPath);

            if (request.layoutJson() != null) {
                Path layoutPath = generatedDirectory.resolve(name + ".layout.json");
                writeAtomically(layoutPath, request.layoutJson());
                files.add(layoutPath);
            }

            // Resolved before the push so a bad remote or an unusable template fails
            // before anything is committed, rather than leaving a commit with no
            // usable URL.
            RawUrls.Target target = services.rawUrls();

            String commitSha = services.git().commitAndPush(files, "feat(generated): add " + name);

            String repoPath = directory.isEmpty() ? name + ".png" : directory + "/" + name + ".png";
            String url = target.pinned(commitSha, repoPath);

            rescanQuietly();

            Result result = new Result(name, repoPath, commitSha, url, request.grid());
            Minecraft.getInstance().execute(() -> finish(result, onSuccess));
        } catch (GitException exception) {
            // git's own words, verbatim. Never retried: a failed push usually means
            // diverged history or missing credentials, and repeating it makes both worse.
            McMarkingsCompanion.LOGGER.error("[mcmarkings] publish failed at git", exception);
            fail("git: " + exception.describe());
        } catch (IOException exception) {
            McMarkingsCompanion.LOGGER.error("[mcmarkings] publish failed writing files", exception);
            fail("Write failed: " + exception.getMessage());
        } catch (RuntimeException exception) {
            McMarkingsCompanion.LOGGER.error("[mcmarkings] publish failed", exception);
            fail("Publish failed: " + exception);
        }
    }

    /**
     * The command sink, the registry and the status line are all client-thread
     * state, so this half runs there.
     */
    private void finish(Result result, Consumer<Result> onSuccess) {
        try {
            // ImageFrame rejects create on a name it already knows. Re-publishing a
            // sign is the normal case here, so an existing name becomes a refresh.
            boolean exists = services.registry.byName(result.name()).isPresent();
            String command = exists
                    ? ImageFrameCommands.refresh(services.config.commandAlias, result.name(), result.url())
                    : ImageFrameCommands.create(services.config.commandAlias, result.name(), result.url(),
                            result.grid());
            services.commands.send(command);

            services.registry.put(new MapEntry(result.name(), services.activeRepositoryId(), result.repoPath(), result.grid(),
                    result.commitSha(), System.currentTimeMillis()));
            saveRegistryQuietly();

            // Everything local has just gone up, including whatever was sitting behind
            // this commit, so the warning about unpushed work stops being true at
            // exactly this moment.
            services.setPushState(PushState.CLEAN);

            lastResult = result;
            status.good((exists ? "Refreshed " : "Created ") + result.name()
                    + " at " + shortSha(result.commitSha()));

            if (onSuccess != null) {
                onSuccess.accept(result);
            }
        } finally {
            running = false;
        }
    }

    /**
     * Writes a PNG through a temporary file in the same directory.
     *
     * <p>Same directory on purpose: a move across a filesystem boundary is a copy and
     * stops being atomic, which is the whole point of doing it this way.
     *
     * @return false when there is no PNG writer, matching {@link ImageIO#write}
     */
    private static boolean writePngAtomically(BufferedImage image, Path target) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            if (!ImageIO.write(image, "PNG", temporary.toFile())) {
                Files.deleteIfExists(temporary);
                return false;
            }
            move(temporary, target);
            return true;
        } catch (IOException failure) {
            Files.deleteIfExists(temporary);
            throw failure;
        }
    }

    private static void writeAtomically(Path target, String contents) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.writeString(temporary, contents, StandardCharsets.UTF_8);
            move(temporary, target);
        } catch (IOException failure) {
            Files.deleteIfExists(temporary);
            throw failure;
        }
    }

    private static void move(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicNotSupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void fail(String message) {
        Minecraft.getInstance().execute(() -> {
            status.bad(message);
            running = false;
        });
    }

    /** A stale index costs the browser a missing thumbnail, not the publish. */
    private void rescanQuietly() {
        try {
            services.repo().rescan();
        } catch (IOException | RuntimeException exception) {
            McMarkingsCompanion.LOGGER.warn("[mcmarkings] could not rescan after publish", exception);
        }
    }

    private void saveRegistryQuietly() {
        // Through the one saver, so a publish and a settings edit cannot reach the
        // same file at the same moment.
        services.saveRegistry();
    }

    /** Config holds a repo-relative directory; tolerate stray or reversed slashes. */
    private static String normaliseDirectory(String directory) {
        if (directory == null) {
            return "";
        }
        String cleaned = directory.replace('\\', '/');
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        while (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned;
    }

    static String shortSha(String sha) {
        if (sha == null) {
            return "";
        }
        return sha.length() > 7 ? sha.substring(0, 7) : sha;
    }
}
