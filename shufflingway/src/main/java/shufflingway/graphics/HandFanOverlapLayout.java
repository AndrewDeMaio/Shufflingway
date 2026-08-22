package shufflingway.graphics;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;

import javax.swing.JComponent;

/**
 * Lays out the bottom band of P1's board: the backup row, the hand fan hanging off the bottom edge,
 * and the "playable cards" button tucked in beside the fan.
 *
 * <p>A {@code BorderLayout} would do all of this but one thing, which is the whole reason this
 * exists: the fan has to be <em>taller</em> than the strip it is given, so a hovered card has room
 * to rise, and BorderLayout gives no child bounds that overlap a sibling's. Here the content keeps
 * exactly the height it had before the fan existed — the band less one peek — while the fan is laid
 * out from the bottom edge upward at its full height, overlapping the content by the difference.
 *
 * <p>Nothing is painted into that overlap until a card is hovered, and
 * {@link PlayerHandFanPanel#contains(int, int)} keeps the mouse falling through it to the backups
 * underneath, so at rest the overlap is invisible in both senses.
 */
public class HandFanOverlapLayout implements LayoutManager {

	/** Gap held between the button and the left edge, and between it and the bottom of the band. */
	private static final int BUTTON_MARGIN = 10;

	private final JComponent content;
	private final PlayerHandFanPanel fan;
	private final JComponent overlay;

	/**
	 * @param content the row that owns the band's height — P1's backups
	 * @param fan     the hand, hung from the bottom edge and allowed to overlap {@code content}
	 * @param overlay a small control parked at the left of the fan's strip; may be {@code null}
	 */
	public HandFanOverlapLayout(JComponent content, PlayerHandFanPanel fan, JComponent overlay) {
		this.content = content;
		this.fan     = fan;
		this.overlay = overlay;
	}

	/** The band is as tall as its content plus the one peek of hand that shows at rest. */
	@Override
	public Dimension preferredLayoutSize(Container parent) {
		synchronized (parent.getTreeLock()) {
			Insets in = parent.getInsets();
			Dimension c = content.getPreferredSize();
			return new Dimension(c.width + in.left + in.right,
					c.height + HandFanLayout.peekHeight() + in.top + in.bottom);
		}
	}

	@Override
	public Dimension minimumLayoutSize(Container parent) {
		synchronized (parent.getTreeLock()) {
			Insets in = parent.getInsets();
			Dimension c = content.getMinimumSize();
			return new Dimension(c.width + in.left + in.right,
					c.height + HandFanLayout.peekHeight() + in.top + in.bottom);
		}
	}

	@Override
	public void layoutContainer(Container parent) {
		synchronized (parent.getTreeLock()) {
			Insets in = parent.getInsets();
			int x = in.left;
			int y = in.top;
			int w = Math.max(0, parent.getWidth()  - in.left - in.right);
			int h = Math.max(0, parent.getHeight() - in.top  - in.bottom);

			int peek   = HandFanLayout.peekHeight();
			int fanH   = Math.min(h, PlayerHandFanPanel.panelHeight());
			int stripY = y + Math.max(0, h - peek);

			// Content stops where the resting fan begins, exactly as it did when the fan's strip was
			// a plain SOUTH child. The fan then starts higher than that and covers the difference.
			content.setBounds(x, y, w, Math.max(0, h - peek));
			fan.setBounds(x, y + Math.max(0, h - fanH), w, fanH);

			if (overlay != null) {
				Dimension b = overlay.getPreferredSize();
				int bh = Math.min(b.height, peek);
				overlay.setBounds(x + BUTTON_MARGIN,
						stripY + Math.max(0, peek - bh - BUTTON_MARGIN),
						Math.min(b.width, w - 2 * BUTTON_MARGIN), bh);
			}
		}
	}

	@Override public void addLayoutComponent(String name, Component comp) {}
	@Override public void removeLayoutComponent(Component comp) {}
}
