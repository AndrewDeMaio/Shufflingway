package shufflingway.graphics;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.function.Supplier;

import javax.swing.JComponent;

import shufflingway.AppSettings;

/**
 * A player's hand drawn as a fan of face-down card backs peeking in from the board's outer edge.
 *
 * <p>Only the innermost {@link #PEEK_FRACTION} of each card is inside the component; the rest is
 * clipped away against the screen edge. The cards splay along a shallow arc and tilt progressively
 * toward the ends, so the row reads as a held hand rather than a stack.
 *
 * <p>The component is seat-agnostic: {@code isP1} mirrors the tilt direction, the arc direction and
 * the edge the cards hang from, so P1's fan peeks up from the bottom and P2's peeks down from the
 * top. Nothing else differs between the two.
 *
 * <p>Contents are never revealed — a hand is only ever a count — so the exact number lives in the
 * tooltip and the fan itself carries no text.
 */
public class HandFanPanel extends JComponent {

	/** Fraction of {@code CARD_H} that stays visible past the screen edge. Tune here. */
	public static final double PEEK_FRACTION = 0.40;

	/** Per-card tilt for small hands, in degrees; the fan opens this much wider per extra card. */
	private static final double MAX_STEP_DEG = 5.0;
	/** Cap on half the total fan angle, so big hands splay wide but never curl over. */
	private static final double MAX_HALF_SPREAD = 26.0;
	/** Horizontal step between neighbouring cards for small hands, as a fraction of CARD_W. */
	private static final double MAX_DX_FRACTION = 0.42;
	/** Depth of the arc — how far the outermost cards retreat toward the edge, as a fraction of CARD_H. */
	private static final double ARC_LIFT_FRACTION = 0.06;
	/** Breathing room kept at each end so a wide fan never touches the neighbouring column. */
	private static final int SIDE_PAD = 8;

	// Every back is the same image, so without separation the overlaps read as one dark mass. A
	// plain outline is not enough: it has to work against the near-black default art *and* against
	// a light custom cardback. A drop shadow cast onto the card behind, plus a light edge, reads on
	// both — the shadow supplies depth where the art is light, the edge where the art is dark.
	private static final Color SHADOW = new Color(0, 0, 0, 110);
	private static final Color EDGE   = new Color(255, 255, 255, 60);
	/** Shadow offset, as a fraction of CARD_W — scales with the cards rather than the screen. */
	private static final double SHADOW_OFFSET_FRACTION = 0.018;

	private final boolean         isP1;
	private final Supplier<Image> cardback;

	private int    count;
	/** Cardback pre-scaled to card size, built lazily on first paint. See {@link #cardbackStale()}. */
	private BufferedImage back;
	/** Identity of whatever {@link #back} was built from; a mismatch invalidates the cache. */
	private String backKey = "";

	/** Height the fan claims in its parent — i.e. how much of each card stays visible. */
	public static int peekHeight() {
		return (int) Math.round(CardAnimation.CARD_H * PEEK_FRACTION);
	}

	/**
	 * @param isP1     true for the bottom seat (cards peek up), false for the top seat (peek down)
	 * @param cardback supplies the raw cardback image; {@code MainWindow::loadCardbackImage} honours
	 *                 the custom-cardback preference, so this is re-consulted whenever it changes
	 */
	public HandFanPanel(boolean isP1, Supplier<Image> cardback) {
		this.isP1     = isP1;
		this.cardback = cardback;
		// Width 0 mirrors the forward zone's scroll pane: claim height only, never widen the column.
		setPreferredSize(new Dimension(0, peekHeight()));
		setMinimumSize(new Dimension(0, peekHeight()));
		setOpaque(false);
		setCount(0);
	}

	/** Updates the card count, refreshes the tooltip, and repaints only if something changed. */
	public final void setCount(int n) {
		boolean changed = (n != count);
		count = n;
		// Assigned unconditionally: the constructor's seeding call must leave a correct tooltip.
		setToolTipText((isP1 ? "P1" : "P2") + " Hand: " + n);
		if (cardbackStale() || changed) repaint();
	}

	/**
	 * Detects a cardback change and drops the cache if it finds one.
	 *
	 * <p>Nothing notifies us when the preference changes, so — like the deck labels, which simply
	 * reload on every refresh — we re-derive from {@link AppSettings} instead of being told. The
	 * file's length and timestamp join the path because Preferences copies the chosen image to a
	 * fixed destination name: re-picking a <em>different</em> file with the <em>same</em> filename
	 * yields an identical path. Card size joins it so a UI-scale change rebuilds too.
	 */
	private boolean cardbackStale() {
		String path = AppSettings.getCustomCardbackPath();
		String size = "|" + CardAnimation.CARD_W + "x" + CardAnimation.CARD_H;
		String key;
		if (path.isEmpty()) {
			key = "default" + size;
		} else {
			File f = new File(path);
			key = path + "|" + f.length() + "|" + f.lastModified() + size;
		}
		if (key.equals(backKey)) return false;
		backKey = key;
		back    = null;   // rebuilt lazily on the next paint
		return true;
	}

	@Override
	protected void paintComponent(Graphics g0) {
		super.paintComponent(g0);
		int w = getWidth(), h = getHeight();
		if (count <= 0 || w <= 0 || h <= 0) return;

		// Built here rather than in the constructor: tests construct the window without ever
		// painting it, and an eager decode would cost every one of them an image load.
		if (back == null) {
			Image raw = cardback.get();
			if (raw == null) return;
			back = CardAnimation.toARGB(raw, CardAnimation.CARD_W, CardAnimation.CARD_H);
		}

		Graphics2D g = (Graphics2D) g0.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,   RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,  RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_RENDERING,      RenderingHints.VALUE_RENDER_QUALITY);

		int    cw   = CardAnimation.CARD_W;
		int    ch   = CardAnimation.CARD_H;
		double half = (count - 1) / 2.0;

		// n == 1 falls out as 0°: a single upright back, no special case needed.
		double halfSpread = Math.toRadians(
				Math.min(MAX_HALF_SPREAD, MAX_STEP_DEG * (count - 1) / 2.0));

		// Budget the horizontal room against the *rotated* footprint, not the upright one. The
		// outermost cards are the most tilted, and a tilted card is markedly wider than cw — at the
		// 26° cap it reaches roughly 1.5x. Measuring the upright width instead lets the ends of a
		// large fan clip off against the neighbouring column.
		double halfExtent = (cw / 2.0) * Math.cos(halfSpread) + (ch / 2.0) * Math.sin(halfSpread);

		// Small hands get a comfortable fixed step; large ones compress to fit. The min() alone
		// keeps the fan inside the panel, so no lower bound is needed.
		double room = Math.max(0, w - 2 * (halfExtent + SIDE_PAD));
		double dx   = count > 1 ? Math.min(cw * MAX_DX_FRACTION, room / (count - 1)) : 0;

		double lift = ch * ARC_LIFT_FRACTION;
		// One flag mirrors both the tilt and the arc. Java2D rotates clockwise for positive angles,
		// so the sign that splays the *visible* end of the rightmost card to the right differs by
		// seat: P2's cards hang from above (visible end at the bottom), P1's from below.
		double dir = isP1 ? 1 : -1;
		// Puts the visible edge exactly on the panel edge; the remainder clips off past it.
		double baseCy = isP1 ? ch / 2.0 : h - ch / 2.0;

		double diameter = Math.min(cw, ch) * CardAnimation.CORNER_RADIUS_FRACTION * 2.0;
		RoundRectangle2D outline = new RoundRectangle2D.Double(0, 0, cw - 1, ch - 1, diameter, diameter);
		double shadowOff = cw * SHADOW_OFFSET_FRACTION;

		// Left to right, so each card overlaps the one before it and the rightmost sits on top.
		for (int i = 0; i < count; i++) {
			double t     = (half == 0) ? 0 : (i - half) / half;   // -1 .. +1 across the fan
			double theta = t * halfSpread * dir;
			double cx    = w / 2.0 + (i - half) * dx;
			double cy    = baseCy + dir * lift * t * t;           // parabola: ends retreat to the edge

			// Pivot on the card's own centre. This keeps the three knobs orthogonal — dx is spread,
			// theta is tilt, lift is arc depth — which is what makes the look tunable. The physical
			// alternative (pivot on the off-screen far end, arc for free from the rotation) couples
			// spread and arc; try it if the fan ever reads too flat.
			AffineTransform tx = AffineTransform.getRotateInstance(theta, cx, cy);
			tx.translate(cx - cw / 2.0, cy - ch / 2.0);

			// Shadow first, so it falls on the card already drawn to the left; the card then covers
			// all of its own shadow but the offset sliver. Cast away from the screen edge, i.e. in
			// the direction the cards actually stand out.
			AffineTransform sx = new AffineTransform(tx);
			sx.preConcatenate(AffineTransform.getTranslateInstance(shadowOff, dir * shadowOff));
			g.setColor(SHADOW);
			g.fill(sx.createTransformedShape(outline));

			g.drawImage(back, tx, null);
			g.setColor(EDGE);
			g.draw(tx.createTransformedShape(outline));
		}

		g.dispose();
	}
}
