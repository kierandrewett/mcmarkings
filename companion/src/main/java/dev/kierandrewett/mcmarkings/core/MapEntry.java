package dev.kierandrewett.mcmarkings.core;

/**
 * A map this client has created on the server, remembered so that a later pull
 * can refresh it.
 *
 * <p>Without this record there is no way back from "this PNG changed in the repo"
 * to "so run /imageframe refresh on that map", because ImageFrame names live on
 * the server and the repo knows nothing about them.
 *
 * <p>{@code repositoryId} scopes {@code repoPath}, which is only meaningful
 * relative to one repository; two repositories can easily both hold a
 * {@code signs/stop.png}.
 */
public record MapEntry(
        String imageFrameName,
        String repositoryId,
        String repoPath,
        GridSize grid,
        String commitSha,
        long createdAtEpochMillis) {
}
