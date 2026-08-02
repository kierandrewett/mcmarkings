package dev.kierandrewett.mcmarkings.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A key combination, independent of any toolkit.
 *
 * <p>Deliberately holds GLFW key codes rather than characters. A shortcut has to
 * mean the same physical key whatever the keyboard layout, and matching on the
 * typed character breaks the moment someone is on AZERTY.
 *
 * @param keyCode  GLFW key code, as Minecraft and ImGui both report
 * @param control  Command on macOS, where that is the conventional modifier
 */
public record Shortcut(int keyCode, boolean control, boolean shift, boolean alt) {

    /** Matches the GLFW codes without depending on the class, which is client-only. */
    private static final List<Named> NAMED = buildNamedKeys();

    public static Shortcut of(int keyCode) {
        return new Shortcut(keyCode, false, false, false);
    }

    public static Shortcut control(int keyCode) {
        return new Shortcut(keyCode, true, false, false);
    }

    public static Shortcut controlShift(int keyCode) {
        return new Shortcut(keyCode, true, true, false);
    }

    public boolean matches(int pressedKey, boolean controlDown, boolean shiftDown, boolean altDown) {
        return keyCode == pressedKey && control == controlDown && shift == shiftDown && alt == altDown;
    }

    /**
     * How the shortcut is written in a menu.
     *
     * <p>Modifier order is fixed rather than following how it was constructed, so
     * the same combination always reads the same way in every list.
     */
    public String display() {
        StringBuilder text = new StringBuilder();
        if (control) {
            text.append("Ctrl+");
        }
        if (shift) {
            text.append("Shift+");
        }
        if (alt) {
            text.append("Alt+");
        }
        return text.append(keyName(keyCode)).toString();
    }

    public static String keyName(int keyCode) {
        for (Named named : NAMED) {
            if (named.code() == keyCode) {
                return named.name();
            }
        }
        // Printable ASCII shares its code with its uppercase character in GLFW.
        if (keyCode >= 32 && keyCode <= 126) {
            return String.valueOf((char) keyCode).toUpperCase(Locale.ROOT);
        }
        return "Key" + keyCode;
    }

    /** Parses "Ctrl+Shift+Z" back, for a rebinding stored in config. */
    public static Optional<Shortcut> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }

        boolean control = false;
        boolean shift = false;
        boolean alt = false;
        String key = null;

        for (String part : text.split("\\+")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            switch (token.toLowerCase(Locale.ROOT)) {
                case "ctrl", "control", "cmd", "command" -> control = true;
                case "shift" -> shift = true;
                case "alt", "option" -> alt = true;
                default -> key = token;
            }
        }

        if (key == null) {
            return Optional.empty();
        }
        Optional<Integer> code = codeFor(key);
        if (code.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Shortcut(code.get(), control, shift, alt));
    }

    private static Optional<Integer> codeFor(String key) {
        for (Named named : NAMED) {
            if (named.name().equalsIgnoreCase(key)) {
                return Optional.of(named.code());
            }
        }
        if (key.length() == 1) {
            return Optional.of((int) Character.toUpperCase(key.charAt(0)));
        }
        return Optional.empty();
    }

    private record Named(String name, int code) {
    }

    /**
     * The GLFW codes worth naming.
     *
     * <p>Written out rather than referenced from the GLFW class so this stays usable
     * from a plain unit test, which cannot load Minecraft's natives.
     */
    private static List<Named> buildNamedKeys() {
        List<Named> keys = new ArrayList<>();
        keys.add(new Named("Space", 32));
        keys.add(new Named("Escape", 256));
        keys.add(new Named("Enter", 257));
        keys.add(new Named("Tab", 258));
        keys.add(new Named("Backspace", 259));
        keys.add(new Named("Insert", 260));
        keys.add(new Named("Delete", 261));
        keys.add(new Named("Right", 262));
        keys.add(new Named("Left", 263));
        keys.add(new Named("Down", 264));
        keys.add(new Named("Up", 265));
        keys.add(new Named("PageUp", 266));
        keys.add(new Named("PageDown", 267));
        keys.add(new Named("Home", 268));
        keys.add(new Named("End", 269));
        for (int number = 1; number <= 12; number++) {
            keys.add(new Named("F" + number, 289 + number));
        }
        return List.copyOf(keys);
    }
}
