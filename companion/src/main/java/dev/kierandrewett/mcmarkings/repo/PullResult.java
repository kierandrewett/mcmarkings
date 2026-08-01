package dev.kierandrewett.mcmarkings.repo;

import java.util.List;

/**
 * What a {@code git pull} moved.
 *
 * <p>{@code changedPaths} is repo-relative and already filtered to PNGs, since
 * that is all the refresh pass can act on.
 */
public record PullResult(String oldHead, String newHead, List<String> changedPaths) {

    public boolean changed() {
        return !oldHead.equals(newHead);
    }
}
