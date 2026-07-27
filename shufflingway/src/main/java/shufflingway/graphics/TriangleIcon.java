package shufflingway.graphics;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;

import javax.swing.Icon;
import javax.swing.UIManager;

/**
 * A solid triangle drawn with Java2D rather than typed as a character.
 *
 * <p>The pointer glyphs this replaces — ◄ (U+25C4) and ► (U+25BA) — are missing from the bundled
 * pixel fonts, and macOS has no system fallback for them either, so a button labelled with one
 * renders as an empty box there. Drawing the shape sidesteps font coverage entirely.
 *
 * <p>The icon follows its component: painted in the component's foreground colour, or the look and
 * feel's disabled-text colour while that component is disabled.
 */
public class TriangleIcon implements Icon {

    public enum Direction { LEFT, RIGHT, UP, DOWN }

    private final Direction direction;
    private final int width;
    private final int height;

    /** Equilateral-ish triangle {@code size} pixels on a side. */
    public TriangleIcon(Direction direction, int size) { this(direction, size, size); }

    public TriangleIcon(Direction direction, int width, int height) {
        this.direction = direction;
        this.width     = width;
        this.height    = height;
    }

    @Override public int getIconWidth()  { return width; }
    @Override public int getIconHeight() { return height; }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(colorFor(c));
        g2.fill(shape(x, y));
        g2.dispose();
    }

    private static Color colorFor(Component c) {
        if (c == null) return Color.BLACK;
        if (c.isEnabled()) return c.getForeground();
        Color disabled = UIManager.getColor("Button.disabledText");
        return disabled != null ? disabled : Color.GRAY;
    }

    private Path2D.Float shape(int x, int y) {
        Path2D.Float p = new Path2D.Float();
        float w = width, h = height;
        switch (direction) {
            case RIGHT -> { p.moveTo(x,     y);          p.lineTo(x + w,      y + h / 2f); p.lineTo(x,     y + h); }
            case LEFT  -> { p.moveTo(x + w, y);          p.lineTo(x,          y + h / 2f); p.lineTo(x + w, y + h); }
            case UP    -> { p.moveTo(x,     y + h);      p.lineTo(x + w / 2f, y);          p.lineTo(x + w, y + h); }
            case DOWN  -> { p.moveTo(x,     y);          p.lineTo(x + w / 2f, y + h);      p.lineTo(x + w, y); }
        }
        p.closePath();
        return p;
    }
}
