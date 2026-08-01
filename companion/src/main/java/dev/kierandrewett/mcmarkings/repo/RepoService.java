package dev.kierandrewett.mcmarkings.repo;

import dev.kierandrewett.mcmarkings.core.RepoImage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Read-only view of the image repository on disk. */
public interface RepoService {

    Path root();

    /** Absolute path for a repo-relative path. */
    Path resolve(String repoPath);

    /** Every PNG found by the last scan, in stable order. */
    List<RepoImage> images();

    Optional<RepoImage> byPath(String repoPath);

    /**
     * Case-insensitive search over path, name, description and TSRGD diagram.
     * An empty query returns the head of {@link #images()}.
     */
    List<RepoImage> search(String query, int limit);

    /** Re-walk the working tree. Call after a pull or a generator save. */
    void rescan() throws IOException;
}
