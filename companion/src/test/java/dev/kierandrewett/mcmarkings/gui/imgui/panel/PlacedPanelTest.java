package dev.kierandrewett.mcmarkings.gui.imgui.panel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Finding the document saved beside a published sign.
 *
 * <p>Get this wrong and a sign that has a document reports that it has none, which
 * reads as the work having been lost rather than as a path being built badly.
 */
class PlacedPanelTest {

    @Test
    @DisplayName("the extension is swapped, not the path mangled")
    void ordinaryPaths() {
        assertEquals("generated/plate.layout.json", PlacedPanel.documentPathFor("generated/plate.png"));
        assertEquals("plate.layout.json", PlacedPanel.documentPathFor("plate.png"));
    }

    @Test
    @DisplayName("a dot in a folder name is not an extension")
    void dottedDirectories() {
        // The first version took the last dot anywhere in the path, which turns
        // "my.signs/plate" into "my.layout.json": a lookup in a directory that does
        // not exist, reported as a sign with no document.
        assertEquals("my.signs/plate.layout.json", PlacedPanel.documentPathFor("my.signs/plate"));
        assertEquals("my.signs/plate.layout.json", PlacedPanel.documentPathFor("my.signs/plate.png"));
    }

    @Test
    @DisplayName("a file with no extension just gains one")
    void noExtension() {
        assertEquals("generated/plate.layout.json", PlacedPanel.documentPathFor("generated/plate"));
    }

    @Test
    @DisplayName("a dotfile keeps its name")
    void leadingDot() {
        // ".plate" is a name, not an empty stem with an extension.
        assertEquals("generated/.plate.layout.json", PlacedPanel.documentPathFor("generated/.plate"));
    }
}
