package dev.kierandrewett.mcmarkings.core;

/**
 * A map this client has created on the server, remembered so that a later pull
 * can refresh it.
 *
 * <p>Without this record there is no way back from "this PNG changed in the repo"
 * to "so run /imageframe refresh on that map", because ImageFrame names live on
 * the server and the repo knows nothing about them.
 */
public record MapEntry(
        String imageFrameName,
        String repoPath,
        GridSize grid,
        String commitSha,
        long createdAtEpochMillis) {
}
