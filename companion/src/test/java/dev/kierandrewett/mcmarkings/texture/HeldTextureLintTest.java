package dev.kierandrewett.mcmarkings.texture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A panel that keeps a texture keeps it pinned.
 *
 * <p>Three bugs of one shape, found one holder at a time over a fortnight. The
 * browser's preview shelf shared an entry with the pool and had it freed from under
 * it; {@code evictAll} then released the same entry twice; and the generator and the
 * editor each held a handle the pool was free to evict, which showed as a preview
 * drawn as somebody else's road sign.
 *
 * <p>They are all the same question. An upload joins a least-recently-used pool,
 * which is right for a thumbnail nobody is looking at and wrong for anything a panel
 * keeps. A field holding a handle is exactly that: it outlives the frame, so the pool
 * must not be allowed to free it.
 *
 * <p>Two fields hold one today and both are right. This is here so a third does not
 * have to be found the way the first three were.
 */
class HeldTextureLintTest {

    /** A field, rather than a local that is fetched again next frame. */
    private static final Pattern HELD_FIELD =
            Pattern.compile("^\\s*private\\s+(?:volatile\\s+)?TextureHandle\\s+(\\w+)\\s*;", Pattern.MULTILINE);

    @Test
    @DisplayName("every held texture comes from a pinned upload")
    void heldTexturesArePinned() throws IOException {
        List<String> unpinned = new ArrayList<>();
        Path root = Path.of("src/main/java/dev/kierandrewett/mcmarkings/gui");

        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                var fields = HELD_FIELD.matcher(source);
                while (fields.find()) {
                    // The same file has to be the one that filled it. A panel holding a
                    // handle it did not upload is a different question and there are
                    // none of those.
                    if (!source.contains("uploadPinned(")) {
                        unpinned.add("  " + file.getFileName() + "  holds " + fields.group(1)
                                + " but never asks for a pinned upload");
                    }
                }
            }
        }

        assertTrue(unpinned.isEmpty(), () -> """
                A panel keeps a TextureHandle in a field, so it outlives the frame, and \
                nothing pinned it. The pool is least-recently-used: browse a few hundred \
                images and it will free that texture while the panel is still drawing it, \
                and what appears is whatever the driver put in the slot next. Upload it \
                with uploadPinned and evict it in onRemoved.
                """ + String.join("\n", unpinned));
    }

    /**
     * And that the rule still has something to check. A refactor that turned both
     * fields into locals would leave this passing over nothing at all.
     */
    @Test
    @DisplayName("the rule is still looking at the panels that hold one")
    void theRuleStillAppliesToSomething() throws IOException {
        int held = 0;
        Path root = Path.of("src/main/java/dev/kierandrewett/mcmarkings/gui");

        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                var fields = HELD_FIELD.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (fields.find()) {
                    held++;
                }
            }
        }

        assertTrue(held >= 2,
                "expected the editor's canvas and the generator's preview, found " + held
                        + " held textures, so this rule has stopped matching them");
    }
}
