package shufflingway.graphics;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import shufflingway.CardData;
import shufflingway.CardState;
import static shufflingway.graphics.CardAnimation.CARD_H;
import static shufflingway.graphics.CardAnimation.CARD_W;

/**
 * Small rectangular tabs that peek out from behind a field card, one per trait the card
 * currently has, each carrying a vector-drawn glyph (Haste, Brave, First Strike, Cannot Be
 * Broken).
 *
 * <p>Tabs are composited onto the square {@code CARD_H x CARD_H} field-card canvas built by
 * {@link CardAnimation#renderBackupCard}, the same way the damage, power and counter overlays
 * are. They always attach to the same edge of the card — its left — but that edge lands
 * somewhere different on the canvas depending on the card's state:
 * <ul>
 *   <li>{@link CardState#ACTIVE} — art is inset by {@link CardAnimation#LEFT_GUTTER}, so the
 *       card's left edge is at {@code LEFT_GUTTER} and tabs poke leftwards into the gutter,
 *       stacking downwards.</li>
 *   <li>{@link CardState#DULL} — art is rotated 90° CW and pinned bottom-left, which puts the
 *       card's left edge along the TOP of the art: tabs poke upwards into the strip above,
 *       stacking rightwards (mirrored, since the rotation reverses that axis, so a given trait
 *       keeps its position relative to the card).</li>
 * </ul>
 * The glyph is drawn upright in both cases.
 *
 * <p>Drawing is clipped to the free strip, so the half of each tab that would cover the art is
 * hidden and the tab reads as sitting behind the card — no reordering of the render pipeline
 * needed, since the overlay still runs after the art is composited.
 *
 * <p>The stack is centred on the card edge and capped at {@link #MAX_TABS}; past that the last
 * slot becomes a "more traits" indicator instead of a glyph.
 *
 * <p>Geometry is authored against a 140px-wide card and scaled by the live {@code CARD_W}, so
 * tabs track the UI scale along with everything else.
 */
public final class TraitTab {

    private TraitTab() {}

    // -- Visual constants (mirrors the HTML/CSS prototype) --------------------------------
    private static final Color BG_FILL      = new Color(0x40, 0x40, 0x46);
    private static final Color BEZEL        = new Color(0x8a, 0x8a, 0x8f);
    private static final Color ICON_LINE    = Color.WHITE;
    private static final Color ARROW_FILL   = new Color(0x5b, 0xc8, 0xe8);
    private static final Color ARROW_STROKE = Color.BLACK;
    private static final Color HEART_FILL   = new Color(0xe8, 0x45, 0x5a);
    private static final Color BAR_FILL     = new Color(0xb6, 0xb6, 0xbc);
    private static final Color GEM_FILL     = new Color(0xe8, 0xb4, 0x4a);

    /** Card width the tab geometry below was authored against; everything scales off it. */
    private static final int DESIGN_CARD_W = 140;

    /**
     * Most tabs one card can show. The stack runs along a {@code CARD_H} axis in both
     * orientations, and {@code n} tabs span {@code n*TAB_SHORT + (n-1)*TAB_GAP} — which at
     * 28/10 is 180 for five (fits 205 with room) and 218 for six (does not).
     */
    private static final int MAX_TABS = 5;

    private static final float TAB_LONG  = 48f;  // along the poke-out axis; half of it stays hidden
    private static final float TAB_SHORT = 28f;  // along the card edge
    private static final float TAB_GAP   = 10f;  // between stacked tabs
    private static final float BEZEL_W   = 2f;
    private static final float ICON_SIZE = 20f;
    private static final float ICON_INSET = 3f;  // glyph margin from the tab's outer edge

    /** Returns true if {@code trait} has a tab glyph; traits without one are never drawn. */
    public static boolean hasGlyph(CardData.Trait trait) {
        return trait == CardData.Trait.HASTE
            || trait == CardData.Trait.BRAVE
            || trait == CardData.Trait.FIRST_STRIKE
            || trait == CardData.Trait.CANNOT_BE_BROKEN;
    }

    /**
     * Composites a tab onto {@code canvas} for each of {@code traits} that {@link #hasGlyph}
     * can draw, in the given order, centred on the card edge. A no-op when none are drawable.
     * Beyond {@link #MAX_TABS} drawable traits the last slot shows the overflow dots instead
     * of a glyph, so the count of hidden traits is at least visible.
     *
     * @param canvas the square field-card canvas from {@link CardAnimation#renderBackupCard}
     * @param state  the card's state, which decides where the free strip is
     */
    public static void renderTraitTabs(BufferedImage canvas, CardState state,
            List<CardData.Trait> traits) {
        List<CardData.Trait> drawable = new ArrayList<>();
        for (CardData.Trait t : traits) if (hasGlyph(t)) drawable.add(t);
        if (drawable.isEmpty()) return;

        boolean overflow = drawable.size() > MAX_TABS;
        int slots  = Math.min(drawable.size(), MAX_TABS);
        int glyphs = overflow ? MAX_TABS - 1 : slots;   // last slot is the indicator when overflowing

        float s        = CARD_W / (float) DESIGN_CARD_W;
        float tabLong  = TAB_LONG * s;
        float tabShort = TAB_SHORT * s;
        float step     = tabShort + TAB_GAP * s;
        // Centre the stack on the card edge; the axis is CARD_H long in both orientations.
        float lead     = (CARD_H - (slots * tabShort + (slots - 1) * TAB_GAP * s)) / 2f;
        boolean dull   = state == CardState.DULL;
        // Free space outside the card's left edge: the gutter when active, the strip above the
        // rotated art when dull (the same card edge, since a CW rotation sends left to top).
        int room       = dull ? CARD_H - CARD_W : CardAnimation.LEFT_GUTTER;

        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setClip(dull ? new Rectangle(0, 0, CARD_H, room)
                       : new Rectangle(0, 0, room, CARD_H));
        for (int i = 0; i < slots; i++) {
            // The rotation reverses the along-edge axis, so mirror the dull stack to keep each
            // trait at the same spot on the card.
            float along = dull ? CARD_H - (lead + i * step) - tabShort : lead + i * step;
            // Straddle the art edge: half the tab lands in the free space, half is clipped away.
            float x = dull ? along : room - tabLong / 2f;
            float y = dull ? room - tabLong / 2f : along;
            float w = dull ? tabShort : tabLong;
            float h = dull ? tabLong  : tabShort;
            drawTab(g, i < glyphs ? drawable.get(i) : null, x, y, w, h, s, dull);
        }
        g.dispose();
    }

    /**
     * Draws one tab's chrome plus its glyph, hugging whichever half of the tab stays visible.
     * A {@code null} trait draws the overflow indicator instead.
     */
    private static void drawTab(Graphics2D g, CardData.Trait trait,
            float x, float y, float w, float h, float s, boolean dull) {
        float bezel = BEZEL_W * s;
        RoundRectangle2D.Float rr = new RoundRectangle2D.Float(
                x + bezel / 2, y + bezel / 2, w - bezel, h - bezel, 3 * s, 3 * s);
        g.setColor(BG_FILL);
        g.fill(rr);
        g.setStroke(new BasicStroke(bezel));
        g.setColor(BEZEL);
        g.draw(rr);

        // Keep the glyph in the half that survives the clip — the outer one either way: the top
        // half when the tab pokes up from a dull card, the left half when it pokes out to the side.
        float icon = ICON_SIZE * s;
        float inset = ICON_INSET * s;
        float ix = dull ? x + (w - icon) / 2f : x + inset;
        float iy = dull ? y + inset           : y + (h - icon) / 2f;
        if (trait == null) drawOverflowDots(g, ix, iy, icon);
        else               drawGlyph(g, trait, ix, iy, icon);
    }

    /** Dispatches to the vector drawing for {@code trait}; silent for traits without a glyph. */
    private static void drawGlyph(Graphics2D g, CardData.Trait trait, float x, float y, float size) {
        switch (trait) {
            case HASTE            -> drawHasteIcon(g, x, y, size);
            case BRAVE            -> drawBraveIcon(g, x, y, size);
            case FIRST_STRIKE     -> drawFirstStrikeIcon(g, x, y, size);
            case CANNOT_BE_BROKEN -> drawCannotBeBrokenIcon(g, x, y, size);
            default               -> { }
        }
    }

    /** Draws three centred white dots indicating that further traits are hidden. */
    public static void drawOverflowDots(Graphics2D g0, float x, float y, float size) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float s = size / 24f;
        g.translate(x, y);
        g.scale(s, s);

        float r = 2f, gap = 6f;   // radius and centre-to-centre spacing, 24x24 grid
        g.setColor(ICON_LINE);
        for (int i = -1; i <= 1; i++)
            g.fill(new Ellipse2D.Float(12 + i * gap - r, 12 - r, r * 2, r * 2));

        g.dispose();
    }

    /**
     * Draws the clock-face + broken-ring + up-arrow "Haste" glyph, upright, into the box
     * {@code [x, y, x + size, y + size]}. Coordinates below are authored in a 24x24 logical
     * grid and scaled to fit.
     */
    public static void drawHasteIcon(Graphics2D g0, float x, float y, float size) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float s = size / 24f;
        g.translate(x, y);
        g.scale(s, s);

        // Broken ring: full circle minus a gap at lower-left where the arrow pokes through.
        // Center (12,12) r=8.5, gap between ~115deg and ~165deg.
        Arc2D.Float ring = new Arc2D.Float(12 - 8.5f, 12 - 8.5f, 17f, 17f, 165f, -310f, Arc2D.OPEN);
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(ICON_LINE);
        g.draw(ring);

        // Hour hand (12 o'clock, short) and minute hand (3 o'clock, long).
        g.draw(new Line2D.Float(12, 12, 12, 7.5f));
        g.draw(new Line2D.Float(12, 12, 17.5f, 12));

        // Up-arrow, poking through the ring gap at lower-left.
        Path2D.Float arrow = new Path2D.Float();
        arrow.moveTo(3.8f, 20.3f);
        arrow.lineTo(3.8f, 16.2f);
        arrow.lineTo(0.7f, 16.2f);
        arrow.lineTo(6f, 11.6f);
        arrow.lineTo(11.3f, 16.2f);
        arrow.lineTo(8.2f, 16.2f);
        arrow.lineTo(8.2f, 20.3f);
        arrow.closePath();
        g.setColor(ARROW_FILL);
        g.fill(arrow);
        g.setStroke(new BasicStroke(0.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(ARROW_STROKE);
        g.draw(arrow);

        g.dispose();
    }

    /**
     * Draws the "Brave" glyph — a chestplate outline with a heart at its center — into the box
     * {@code [x, y, x + size, y + size]}. Same 24x24 logical grid as {@link #drawHasteIcon}.
     */
    public static void drawBraveIcon(Graphics2D g0, float x, float y, float size) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float s = size / 24f;
        g.translate(x, y);
        g.scale(s, s);

        Path2D.Float plate = new Path2D.Float();
        plate.moveTo(12, 5);
        plate.lineTo(8, 3);
        plate.lineTo(4, 4);
        plate.lineTo(3, 8);
        plate.lineTo(5, 10);
        plate.lineTo(4, 16);
        plate.lineTo(12, 22);
        plate.lineTo(20, 16);
        plate.lineTo(19, 10);
        plate.lineTo(21, 8);
        plate.lineTo(20, 4);
        plate.lineTo(16, 3);
        plate.closePath();
        g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(ICON_LINE);
        g.draw(plate);

        Path2D.Float heart = new Path2D.Float();
        heart.moveTo(12, 16.5f);
        heart.curveTo(9, 14, 8, 12, 8, 10.3f);
        heart.curveTo(8, 8.9f, 9.1f, 8, 10.3f, 8);
        heart.curveTo(11.2f, 8, 12, 8.8f, 12, 9.6f);
        heart.curveTo(12, 8.8f, 12.8f, 8, 13.7f, 8);
        heart.curveTo(14.9f, 8, 16, 8.9f, 16, 10.3f);
        heart.curveTo(16, 12, 15, 14, 12, 16.5f);
        heart.closePath();
        g.setColor(HEART_FILL);
        g.fill(heart);
        g.setStroke(new BasicStroke(0.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(Color.BLACK);
        g.draw(heart);

        g.dispose();
    }

    /**
     * Draws the "Cannot Be Broken" glyph — a shield outline with a cut gem at its centre — into
     * the box {@code [x, y, x + size, y + size]}. Same 24x24 logical grid as
     * {@link #drawHasteIcon}, and the same two-part construction as {@link #drawBraveIcon}: a
     * white line-art shape with one filled, black-stroked accent inside it.
     *
     * <p>The gem carries the meaning — a shield alone reads as generic protection, while the
     * hardest thing there is reads as "cannot be broken" specifically.
     */
    public static void drawCannotBeBrokenIcon(Graphics2D g0, float x, float y, float size) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float s = size / 24f;
        g.translate(x, y);
        g.scale(s, s);

        // Heater shield: flat shoulders, straight sides, curving to a point at the bottom.
        Path2D.Float shield = new Path2D.Float();
        shield.moveTo(12, 3.2f);
        shield.lineTo(20, 5.8f);
        shield.lineTo(20, 12);
        shield.curveTo(20, 17, 16.2f, 20.2f, 12, 21.6f);
        shield.curveTo(7.8f, 20.2f, 4, 17, 4, 12);
        shield.lineTo(4, 5.8f);
        shield.closePath();
        g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(ICON_LINE);
        g.draw(shield);

        // Cut gem, centred on the shield's visual mass rather than its bounding box.
        Path2D.Float gem = new Path2D.Float();
        gem.moveTo(12, 7.8f);
        gem.lineTo(15.5f, 11.4f);
        gem.lineTo(12, 16.4f);
        gem.lineTo(8.5f, 11.4f);
        gem.closePath();
        g.setColor(GEM_FILL);
        g.fill(gem);
        g.setStroke(new BasicStroke(0.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(Color.BLACK);
        g.draw(gem);

        // Girdle facet: one line is enough to read as cut stone rather than a plain lozenge.
        g.setStroke(new BasicStroke(0.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(ICON_LINE);
        g.draw(new Line2D.Float(8.5f, 11.4f, 15.5f, 11.4f));

        g.dispose();
    }

    /**
     * Draws the "First Strike" glyph — a white sword-point triangle breaking through a jagged
     * horizontal bar — into the box {@code [x, y, x + size, y + size]}. Same 24x24 logical
     * grid as {@link #drawHasteIcon}.
     * NOTE: I don't like this one, gonna redesign it at some point.
     */
    public static void drawFirstStrikeIcon(Graphics2D g0, float x, float y, float size) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float s = size / 24f;
        g.translate(x, y);
        g.scale(s, s);

        Path2D.Float barLeft = new Path2D.Float();
        barLeft.moveTo(2, 10);
        barLeft.lineTo(9.5f, 10);
        barLeft.lineTo(11, 11.5f);
        barLeft.lineTo(9.5f, 13);
        barLeft.lineTo(2, 13);
        barLeft.closePath();

        Path2D.Float barRight = new Path2D.Float();
        barRight.moveTo(22, 10);
        barRight.lineTo(14.5f, 10);
        barRight.lineTo(13, 11.5f);
        barRight.lineTo(14.5f, 13);
        barRight.lineTo(22, 13);
        barRight.closePath();

        g.setStroke(new BasicStroke(0.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (Path2D.Float bar : new Path2D.Float[]{ barLeft, barRight }) {
            g.setColor(BAR_FILL);
            g.fill(bar);
            g.setColor(ICON_LINE);
            g.draw(bar);
        }

        Path2D.Float point = new Path2D.Float();
        point.moveTo(12, 2);
        point.lineTo(16, 21.5f);
        point.lineTo(8, 21.5f);
        point.closePath();
        g.setColor(ICON_LINE);
        g.fill(point);

        g.dispose();
    }
}
