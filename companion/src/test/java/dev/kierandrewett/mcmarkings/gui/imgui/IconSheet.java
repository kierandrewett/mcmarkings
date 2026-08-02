package dev.kierandrewett.mcmarkings.gui.imgui;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

class IconSheet {
    private static void draw(Graphics2D g, Icon icon, float x, float y, float size) {
        g.setStroke(new BasicStroke(Math.max(1.0f, Math.round(size / 12.0f)),
                BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
        for (float[] b : icon.boxes()) {
            int bx = Math.round(x + b[0] * size), by = Math.round(y + b[1] * size);
            int bw = Math.round(b[2] * size), bh = Math.round(b[3] * size);
            if (b[4] > 0.5f) { g.fillRect(bx, by, bw, bh); } else { g.drawRect(bx, by, bw, bh); }
        }
        for (float[] s : icon.strokes()) {
            g.drawLine(Math.round(x + s[0] * size), Math.round(y + s[1] * size),
                    Math.round(x + s[2] * size), Math.round(y + s[3] * size));
        }
    }

    @Test
    void sheet() throws Exception {
        Map<String, Icon> icons = new LinkedHashMap<>();
        for (Field f : Icon.class.getFields()) {
            if (f.getType() == Icon.class) {
                icons.put(f.getName().toLowerCase().replace("_", ""), (Icon) f.get(null));
            }
        }
        // The sizes the buttons actually give them now the padding is tighter.
        int[] sizes = {10, 16, 22, 44};
        int cell = 58;
        BufferedImage sheet = new BufferedImage(cell * icons.size(), 132, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        g.setColor(new Color(0x1E, 0x1E, 0x22));
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        int column = 0;
        for (Map.Entry<String, Icon> e : icons.entrySet()) {
            int x = column * cell;
            g.setColor(new Color(0xDE, 0xDE, 0xDE));
            float y = 6;
            for (int size : sizes) { draw(g, e.getValue(), x + (cell - size) / 2.0f, y, size); y += size + 5; }
            g.setFont(new Font("SansSerif", Font.PLAIN, 9));
            g.drawString(e.getKey().substring(0, Math.min(8, e.getKey().length())), x + 2, 128);
            column++;
        }
        g.dispose();
        Path dir = Path.of("build", "eyeball");
        Files.createDirectories(dir);
        ImageIO.write(sheet, "PNG", dir.resolve("icons-small.png").toFile());
        System.out.println("wrote " + icons.size() + " icons");
    }
}
