package shufflingway.graphics;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
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
 * currently has, carrying a vector-drawn status glyph (so far only Haste).
 *
 * <p>Tabs are composited onto the square {@code CARD_H x CARD_H} field-card canvas built by
 * {@link CardAnimation#renderBackupCard}, the same way the damage, power and counter overlays
 * are. That canvas always leaves one empty strip beside the art, and which strip depends on
 * the card's state:
 * <ul>
 *   <li>{@link CardState#ACTIVE} — art is pinned top-left at {@code CARD_W x CARD_H}, so the
 *       free strip is to the RIGHT: tabs poke rightwards and stack downwards.</li>
 *   <li>{@link CardState#DULL} — art is rotated and pinned bottom-left at
 *       {@code CARD_H x CARD_W}, so the free strip is ABOVE: tabs poke upwards and stack
 *       rightwards.</li>
 * </ul>
 * No card edge has clearance in both states, which is why the tabs change edge when a card
 * dulls rather than rotating with it. The glyph is drawn upright either way.
 *
 * <p>Drawing is clipped to the free strip, so the half of each tab that would cover the art is
 * hidden and the tab reads as sitting behind the card — no reordering of the render pipeline
 * needed, since the overlay still runs after the art is composited.
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

    /** Card width the tab geometry below was authored against; everything scales off it. */
    private static final int DESIGN_CARD_W = 140;

    private static final float TAB_LONG  = 48f;  // along the poke-out axis; half of it stays hidden
    private static final float TAB_SHORT = 28f;  // along the card edge
    private static final float TAB_GAP   = 10f;  // between stacked tabs
    private static final float TAB_FIRST = 20f;  // offset of the first tab along the card edge
    private static final float BEZEL_W   = 2f;
    private static final float ICON_SIZE = 20f;
    private static final float ICON_INSET = 3f;  // glyph margin from the tab's outer edge

    /** Returns true if {@code trait} has a tab glyph; traits without one are never drawn. */
    public static boolean hasGlyph(CardData.Trait trait) {
        return trait == CardData.Trait.HASTE;
    }

    /**
     * Composites a tab onto {@code canvas} for each of {@code traits} that {@link #hasGlyph}
     * can draw, in the given order. A no-op when none of them are drawable.
     *
     * @param canvas the square field-card canvas from {@link CardAnimation#renderBackupCard}
     * @param state  the card's state, which decides where the free strip is
     */
    public static void renderTraitTabs(BufferedImage canvas, CardState state,
            List<CardData.Trait> traits) {
        List<CardData.Trait> drawable = new ArrayList<>();
        for (CardData.Trait t : traits) if (hasGlyph(t)) drawable.add(t);
        if (drawable.isEmpty()) return;

        float s        = CARD_W / (float) DESIGN_CARD_W;
        float tabLong  = TAB_LONG * s;
        float tabShort = TAB_SHORT * s;
        float step     = tabShort + TAB_GAP * s;
        float first    = TAB_FIRST * s;
        boolean dull   = state == CardState.DULL;
        int strip      = CARD_H - CARD_W;   // thickness of the free strip, both orientations

        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setClip(dull ? new Rectangle(0, 0, CARD_H, strip)
                       : new Rectangle(CARD_W, 0, strip, CARD_H));
        for (int i = 0; i < drawable.size(); i++) {
            // Straddle the art edge: half the tab lands in the strip, half is clipped away.
            float x = dull ? first + i * step : CARD_W - tabLong / 2f;
            float y = dull ? strip - tabLong / 2f : first + i * step;
            float w = dull ? tabShort : tabLong;
            float h = dull ? tabLong  : tabShort;
            drawTab(g, drawable.get(i), x, y, w, h, s, dull);
        }
        g.dispose();
    }

    /** Draws one tab's chrome plus its glyph, hugging whichever half of the tab stays visible. */
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

        // Keep the glyph in the half that survives the clip: the outer (top) half when the tab
        // pokes up from a dull card, the outer (right) half when it pokes out from an active one.
        float icon = ICON_SIZE * s;
        float inset = ICON_INSET * s;
        float ix = dull ? x + (w - icon) / 2f : x + w - icon - inset;
        float iy = dull ? y + inset           : y + (h - icon) / 2f;
        drawGlyph(g, trait, ix, iy, icon);
    }

    /** Dispatches to the vector drawing for {@code trait}; silent for traits without a glyph. */
    private static void drawGlyph(Graphics2D g, CardData.Trait trait, float x, float y, float size) {
        if (trait == CardData.Trait.HASTE) drawHasteIcon(g, x, y, size);
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
}
