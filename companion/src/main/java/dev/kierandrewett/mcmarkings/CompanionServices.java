package dev.kierandrewett.mcmarkings;

import dev.kierandrewett.mcmarkings.config.CompanionConfig;
import dev.kierandrewett.mcmarkings.imageframe.CommandSink;
import dev.kierandrewett.mcmarkings.js.GeneratorRuntime;
import dev.kierandrewett.mcmarkings.registry.MapRegistry;
import dev.kierandrewett.mcmarkings.repo.GitService;
import dev.kierandrewett.mcmarkings.repo.RepoService;
import dev.kierandrewett.mcmarkings.texture.ThumbnailCache;

/**
 * Composition root. Everything the screens need, resolved once at startup.
 *
 * <p>Fields are interface-typed so the UI never depends on how the repository is
 * walked, how git is driven, or which script engine renders a sign.
 */
public final class CompanionServices {

    public final CompanionConfig config;
    public final RepoService repo;
    public final GitService git;
    public final MapRegistry registry;
    public final CommandSink commands;
    public final ThumbnailCache thumbnails;
    public final GeneratorRuntime generators;

    public CompanionServices(
            CompanionConfig config,
            RepoService repo,
            GitService git,
            MapRegistry registry,
            CommandSink commands,
            ThumbnailCache thumbnails,
            GeneratorRuntime generators) {
        this.config = config;
        this.repo = repo;
        this.git = git;
        this.registry = registry;
        this.commands = commands;
        this.thumbnails = thumbnails;
        this.generators = generators;
    }
}
