package shufflingway.graphics;

/**
 * Where each card sits in a fanned hand.
 *
 * <p>Split out of {@link HandFanPanel} so the two seats cannot drift apart: P2's fan of card backs
 * and P1's face-up, interactive fan are different components with different painting and different
 * input, but a hand should read the same shape on both sides of the board. Everything that decides
 * that shape lives here; everything a seat does with it lives in the panel.
 *
 * <p>The geometry is pure — no Swing, no state — so the interactive panel can also use it to hit
 * test, which is what keeps the card you click the same as the card you see on top.
 */
public final class HandFanLayout {

    /**
     * Fraction of {@code CARD_H} that stays visible past the screen edge. Tune here.
     *
     * <p>Both seats pay this out of the same fixed board height, so the ceiling is roughly
     * {@code (boardHeight - bothZonesWithoutFans) / 2} — around 0.30 at 1080p. Past that the two
     * zones meet and overlap rather than degrading gracefully.
     */
    public static final double PEEK_FRACTION = 0.28;

    /** Per-card tilt for small hands, in degrees; the fan opens this much wider per extra card. */
    private static final double MAX_STEP_DEG = 2.5;
    /** Cap on half the total fan angle, so big hands splay wide but never curl over. */
    private static final double MAX_HALF_SPREAD = 13.0;
    /**
     * Horizontal step between neighbouring cards for small hands, as a fraction of CARD_W. The
     * complement is how far each card buries the one before it — 0.65 here leaves 35% covered.
     *
     * <p>That figure is picked for the hit test as much as the look. A hovered card is raised, so
     * its grab shape covers the whole panel height at its own width; leaving it sideways must land
     * in its immediate neighbour rather than past it. That holds while a card reaches less than
     * halfway into the one two along — i.e. while this stays above 0.5. At the 0.42 it used to be,
     * sliding right off a raised card skipped its neighbour entirely and grabbed the card beyond.
     */
    private static final double MAX_DX_FRACTION = 0.65;
    /** Depth of the arc — how far the outermost cards retreat toward the edge, as a fraction of CARD_H. */
    private static final double ARC_LIFT_FRACTION = 0.06;
    /** Breathing room kept at each end so a wide fan never touches the neighbouring column. */
    private static final int SIDE_PAD = 8;

    private HandFanLayout() {}

    /** Height the fan claims in its parent — i.e. how much of each card stays visible. */
    public static int peekHeight() {
        return (int) Math.round(CardAnimation.CARD_H * PEEK_FRACTION);
    }

    /**
     * One card's placement: the centre it pivots on and how far it is tilted, in radians.
     * Card size is always {@code CARD_W} × {@code CARD_H}, so it is not repeated per slot.
     */
    public record Slot(double cx, double cy, double theta) {}

    /**
     * Places {@code count} cards across a panel {@code w} × {@code h}, left to right.
     *
     * <p>{@code restTop} is where the top edge of an untilted card sits, measured down from the top
     * of the panel. P2's fan hangs from the top of the screen, so its cards run off the top and
     * {@code restTop} is negative; P1's hang from the bottom, so theirs is the panel height less
     * the peek — zero when the panel is exactly a peek tall, and positive when it is taller to
     * leave room for a card to rise.
     *
     * <p>{@code dir} mirrors both the tilt and the arc in one flag. Java2D rotates clockwise for
     * positive angles, so the sign that splays the <em>visible</em> end of the rightmost card to the
     * right differs by seat: P2's cards hang from above (visible end at the bottom), P1's from below.
     */
    public static Slot[] slots(int count, int w, boolean isP1, double restTop) {
        Slot[] out = new Slot[Math.max(0, count)];
        if (out.length == 0) return out;

        int    cw   = CardAnimation.CARD_W;
        int    ch   = CardAnimation.CARD_H;
        double half = (count - 1) / 2.0;

        // n == 1 falls out as 0°: a single upright card, no special case needed.
        double halfSpread = Math.toRadians(
                Math.min(MAX_HALF_SPREAD, MAX_STEP_DEG * (count - 1) / 2.0));

        // Budget the horizontal room against the *rotated* footprint, not the upright one. The
        // outermost cards are the most tilted, and a tilted card is wider than cw — at the 13° cap
        // it reaches roughly 1.3x. Measuring the upright width instead lets the ends of a large fan
        // clip off against the neighbouring column.
        double halfExtent = (cw / 2.0) * Math.cos(halfSpread) + (ch / 2.0) * Math.sin(halfSpread);

        // Small hands get a comfortable fixed step; large ones compress to fit. The min() alone
        // keeps the fan inside the panel, so no lower bound is needed.
        double room = Math.max(0, w - 2 * (halfExtent + SIDE_PAD));
        double dx   = count > 1 ? Math.min(cw * MAX_DX_FRACTION, room / (count - 1)) : 0;

        double arc    = ch * ARC_LIFT_FRACTION;
        double dir    = isP1 ? 1 : -1;
        double baseCy = restTop + ch / 2.0;

        for (int i = 0; i < count; i++) {
            double t     = (half == 0) ? 0 : (i - half) / half;   // -1 .. +1 across the fan
            double theta = t * halfSpread * dir;
            double cx    = w / 2.0 + (i - half) * dx;
            double cy    = baseCy + dir * arc * t * t;            // parabola: ends retreat to the edge
            out[i] = new Slot(cx, cy, theta);
        }
        return out;
    }

    /**
     * Where an untilted card's top edge sits at rest, for a seat's panel of height {@code h}.
     *
     * <p>P1 hangs its cards off the bottom of the panel, so any height beyond a single peek is
     * headroom above them; P2 hangs its off the top, so the card runs upward out of the panel.
     */
    public static double restTop(boolean isP1, int h) {
        return isP1 ? h - peekHeight() : h - CardAnimation.CARD_H;
    }

    /**
     * The transform that puts a card into its slot: rotate about the slot centre, then translate
     * the card's own top-left corner there. Pivoting on the card's centre keeps the three knobs
     * orthogonal — spread, tilt and arc depth are independent — which is what makes the look
     * tunable. The physical alternative (pivot on the off-screen far end, arc for free from the
     * rotation) couples spread and arc; try it if the fan ever reads too flat.
     */
    public static java.awt.geom.AffineTransform transformFor(Slot slot) {
        java.awt.geom.AffineTransform tx =
                java.awt.geom.AffineTransform.getRotateInstance(slot.theta(), slot.cx(), slot.cy());
        tx.translate(slot.cx() - CardAnimation.CARD_W / 2.0, slot.cy() - CardAnimation.CARD_H / 2.0);
        return tx;
    }
}
