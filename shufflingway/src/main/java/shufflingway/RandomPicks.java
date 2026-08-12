package shufflingway;

import java.util.ArrayList;
import java.util.List;

/**
 * Cards taken at random out of a pool, as positions rather than as cards.
 *
 * <p>"Your opponent discards 2 cards at random" is not a decision anyone makes, but its result has
 * to cross the wire all the same: two clients each rolling their own numbers over the same hand
 * discard different cards, and neither finds out until a checksum fails several turns later. One
 * client rolls, both apply.
 *
 * <p><b>The pool shrinks between picks.</b> Each index is a position in the pool <em>as it stood
 * for that pick</em> — after the previous card was already taken out — so a run of picks is not a
 * set of independent positions in the original hand and cannot be validated as one. Picking twice
 * out of three cards legally yields {@code [2, 1]}: the second 1 addresses a pool of two.
 */
final class RandomPicks {

	private RandomPicks() {}

	/** {@code count} picks out of {@code poolSize}, each against what is left by then. */
	static List<Integer> roll(int count, int poolSize) {
		int rolls = Math.min(count, poolSize);
		List<Integer> out = new ArrayList<>(Math.max(0, rolls));
		for (int i = 0; i < rolls; i++) out.add((int) (Math.random() * (poolSize - i)));
		return out;
	}

	/**
	 * Whether {@code picks} could have come out of a pool of {@code poolSize} — walked in order,
	 * against the pool each one was actually taken from.
	 *
	 * <p>A run that does not fit means the two clients disagree about how many cards were there,
	 * which is a desync to report rather than a discard to make.
	 */
	static boolean fitPool(List<Integer> picks, int poolSize) {
		if (picks.size() > poolSize) return false;
		for (int i = 0; i < picks.size(); i++)
			if (picks.get(i) < 0 || picks.get(i) >= poolSize - i) return false;
		return true;
	}
}
