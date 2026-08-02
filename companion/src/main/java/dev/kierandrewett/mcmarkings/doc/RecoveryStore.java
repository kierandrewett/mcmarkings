package dev.kierandrewett.mcmarkings.doc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Keeps the document being edited recoverable if the game does not come back.
 *
 * <p>Minecraft crashes, drivers fall over, and people alt-F4 the wrong window. An
 * hour of composing lost to any of those is the thing most likely to stop someone
 * ever opening the editor again, and it is entirely avoidable.
 *
 * <p>Deliberately not a save. It lives in the mod's config directory rather than
 * the repository, is never committed, and holds exactly one document: the one in
 * progress. An explicit save clears it, so it only ever describes work that would
 * otherwise be gone.
 *
 * <p>Recording is cheap and does no IO. Writing happens at most once per interval,
 * from whoever calls {@link #flushIfDue}, which is expected to be a background
 * thread. The point is that saving your work must never be a reason for the game
 * to stutter, or people will turn it off.
 */
public final class RecoveryStore {

    /**
     * How often the snapshot is written at most.
     *
     * <p>Long enough that a busy edit does not mean constant disk writes, short
     * enough that a crash costs seconds of work rather than minutes.
     */
    public static final long INTERVAL_MILLIS = 15_000;

    private static final int FORMAT_VERSION = 1;

    private final Path file;

    private volatile Document pendingDocument;

    private volatile String pendingRepositoryId = "";

    private volatile long lastWriteMillis;

    private volatile boolean dirty;

    public RecoveryStore(Path file) {
        this.file = file;
    }

    /** What was recovered, and enough context to reopen it in the right place. */
    public record Recovered(Document document, String repositoryId, long savedAtMillis) {
    }

    /**
     * Notes the current state. Cheap, and safe to call on every edit.
     *
     * <p>Holds a reference rather than serialising, because documents are immutable
     * records and the expensive part is the file, not the object.
     */
    public void record(Document document, String repositoryId) {
        if (document == null) {
            return;
        }
        this.pendingDocument = document;
        this.pendingRepositoryId = repositoryId == null ? "" : repositoryId;
        this.dirty = true;
    }

    /**
     * Writes the snapshot if enough time has passed. True when it wrote.
     *
     * <p>Call from a background thread. Time is passed in rather than read so the
     * interval can be tested without waiting for it.
     */
    public boolean flushIfDue(long nowMillis) throws IOException {
        if (!dirty || nowMillis - lastWriteMillis < INTERVAL_MILLIS) {
            return false;
        }
        write(nowMillis);
        return true;
    }

    /** Writes immediately, for closing the editor or leaving the world. */
    public void flushNow(long nowMillis) throws IOException {
        if (dirty) {
            write(nowMillis);
        }
    }

    private void write(long nowMillis) throws IOException {
        Document document = pendingDocument;
        if (document == null) {
            return;
        }

        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("version", FORMAT_VERSION);
        wrapper.addProperty("repositoryId", pendingRepositoryId);
        wrapper.addProperty("savedAt", nowMillis);
        wrapper.add("document", JsonParser.parseString(DocumentJson.write(document)));

        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        // Through a temporary file, because the one moment this must not be
        // half-written is a crash, which is exactly when it is being written.
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, wrapper.toString(), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicNotSupported) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }

        lastWriteMillis = nowMillis;
        dirty = false;
    }

    /**
     * Work left behind by a previous session, if any.
     *
     * <p>A snapshot that will not parse comes back empty rather than throwing. It
     * is already the fallback for a bad outcome, and failing here would turn a
     * recoverable session into a broken editor.
     */
    public Optional<Recovered> pending() {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }

        try {
            JsonObject wrapper = JsonParser.parseString(
                    Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!wrapper.has("document")) {
                return Optional.empty();
            }

            Document document = DocumentJson.read(wrapper.getAsJsonObject("document").toString());
            String repositoryId = wrapper.has("repositoryId") ? wrapper.get("repositoryId").getAsString() : "";
            long savedAt = wrapper.has("savedAt") ? wrapper.get("savedAt").getAsLong() : 0L;

            return Optional.of(new Recovered(document, repositoryId, savedAt));
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    /** Forgets the snapshot, once the work is safely somewhere else. */
    public void clear() {
        pendingDocument = null;
        dirty = false;
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A snapshot that cannot be deleted is offered again next time, which is
            // mildly annoying but never destructive.
        }
    }

    /** True when there is unwritten work, for showing an indicator. */
    public boolean hasUnsavedChanges() {
        return dirty;
    }
}
