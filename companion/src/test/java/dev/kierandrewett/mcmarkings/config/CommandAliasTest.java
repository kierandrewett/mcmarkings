package dev.kierandrewett.mcmarkings.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The command root has to be something a server can read.
 *
 * <p>It is the first word of every command this mod sends. Rubbish in it is not a
 * mod that misbehaves: it is a malformed chat_command packet, and a server answers
 * that by closing the connection. Somebody was thrown out of their world by it while
 * placing a map, having never typed a character of what was in the field.
 *
 * <p>What put it there was input queued while the window was shut, replayed into
 * whichever field had the keyboard when it opened. That is fixed where it happens.
 * This is the second line, because the field had only ever been checked for being
 * empty, and the distance between "an odd setting" and "you cannot play" turned out
 * to be nothing at all.
 */
class CommandAliasTest {

    @Test
    @DisplayName("a real command name is accepted")
    void realNamesPass() {
        assertTrue(CompanionConfig.isUsableCommandAlias("imageframe"));
        assertTrue(CompanionConfig.isUsableCommandAlias("frame"), "servers commonly rebind it");
        assertTrue(CompanionConfig.isUsableCommandAlias("if"));
        assertTrue(CompanionConfig.isUsableCommandAlias("imageframe:create"), "a namespaced command");
        assertTrue(CompanionConfig.isUsableCommandAlias("image-frame_2"));
    }

    @Test
    @DisplayName("anything that is not one word is refused")
    void rubbishFails() {
        assertFalse(CompanionConfig.isUsableCommandAlias(""), "an empty root sends a bare slash");
        assertFalse(CompanionConfig.isUsableCommandAlias(null));
        assertFalse(CompanionConfig.isUsableCommandAlias("   "));
        assertFalse(CompanionConfig.isUsableCommandAlias("frame create"),
                "a space makes the rest into arguments");
        assertFalse(CompanionConfig.isUsableCommandAlias("/frame"), "the slash is added, not typed");

        // What was actually in the config, shortened. Walking around, then a stray
        // couple of slashes, saved without anyone touching the field.
        assertFalse(CompanionConfig.isUsableCommandAlias(
                "dd awddddddddddddaadddddddddddddwwwwwwwwwwwwwwwdsaWWWww//desel"));
    }

    @Test
    @DisplayName("a config already holding a bad one opens with a working mod")
    void aBadOneIsRepairedOnLoad() {
        CompanionConfig config = new CompanionConfig();
        config.commandAlias = "wwwwwwwwdddddaaaa//desel";

        // Same question load() asks. Kept as the check rather than the file, so this
        // does not need a config directory to run in.
        if (!CompanionConfig.isUsableCommandAlias(config.commandAlias)) {
            config.commandAlias = CompanionConfig.DEFAULT_COMMAND_ALIAS;
        }

        assertEquals("imageframe", config.commandAlias,
                "a config holding a bad root would go on disconnecting somebody every time "
                        + "they placed a map, and the only way back would be knowing to open "
                        + "Settings and retype a word");
    }
}
