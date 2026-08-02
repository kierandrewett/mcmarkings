package dev.kierandrewett.mcmarkings.doc;

import dev.kierandrewett.mcmarkings.render.ImageComposer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the image paths written in a document against a repository on disk.
 *
 * <p>Shared rather than written twice because the preview and the published PNG
 * have to resolve identically. If they drift, the sign someone spent an hour on
 * looks right in the editor and wrong on the wall, which is the worst way to find
 * out about a bug.
 *
 * <p>The messages are aimed at whoever is composing, not at a log. "no image at
 * signs/foo.png in this repository" tells them what to fix; a stack trace does not.
 */
public final class RepositoryImages {

    private RepositoryImages() {
    }

    /**
     * A resolver reading from {@code root}.
     *
     * <p>Decoding is cached by path and modification time inside the composer, so a
     * document using one image on ten layers reads the file once, and editing that
     * file outside the game is picked up without a restart.
     */
    public static DocumentRenderer.ImageResolver in(ImageComposer composer, Path root) {
        return repoPath -> load(composer, root, repoPath);
    }

    private static java.awt.image.BufferedImage load(ImageComposer composer, Path root, String repoPath)
            throws IOException {
        if (repoPath == null || repoPath.isBlank()) {
            throw new IOException("this layer has no image chosen");
        }
        Path path = root.resolve(repoPath);
        if (!Files.isRegularFile(path)) {
            throw new IOException("no image at \"" + repoPath + "\" in this repository");
        }
        return composer.load(path);
    }
}
