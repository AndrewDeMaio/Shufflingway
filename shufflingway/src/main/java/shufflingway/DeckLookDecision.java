package shufflingway;

import java.util.ArrayList;
import java.util.List;

/**
 * Where each of the cards a player just looked at ends up.
 *
 * <p>Every "look at / reveal the top N cards" effect in the game is some arrangement of the same
 * five destinations, so one answer shape covers all of {@link LookConfig.LookAction} and the
 * reveal-and-play effects besides: keeping the cards on top in a chosen order, taking one to hand,
 * breaking the rest, putting one onto the field, and every combination in between. Separating that
 * answer from the act of applying it is what lets the same decision come from a dialog, from the
 * AI, or off the wire and still move the same cards.
 *
 * <p>Each list holds <b>indices into the peeked cards</b> — never {@code CardData} — because the
 * answer may have to cross the wire, and both clients peek the same cards off the same seeded deck
 * in the same order. Every index appears exactly once across the five lists.
 *
 * <p>Order within a list is the order the cards arrive: {@code toTop} is topmost first, and
 * {@code toBottom} is the card that reaches the bottom first.
 *
 * <p><b>Why the field needs no more than an index.</b> Playing a card is not a zone append — it
 * takes a slot, and it can fire an entry trigger that asks its own question. None of that has to
 * travel: which slot a revealed card lands in is derived the same way on both clients, and an
 * entry trigger asking something is simply the next question, put to its own controller on their
 * own client. What the player decided here is only <em>which</em> card, and that is the index.
 *
 * @param toHand   cards taken into the looking player's hand
 * @param toBreak  cards put into their Break Zone
 * @param toTop    cards returned to the top of their deck, topmost first
 * @param toBottom cards sent to the bottom of their deck, in arrival order
 * @param toField  cards played onto their field, in the order they are played
 */
record DeckLookDecision(List<Integer> toHand, List<Integer> toBreak, List<Integer> toTop,
                        List<Integer> toBottom, List<Integer> toField) {

	/** The arrangement of an effect that cannot play a card onto the field — most of them. */
	DeckLookDecision(List<Integer> toHand, List<Integer> toBreak,
			List<Integer> toTop, List<Integer> toBottom) {
		this(toHand, toBreak, toTop, toBottom, List.of());
	}

	/** The do-nothing answer: every card goes back on top in the order it was peeked. */
	static DeckLookDecision keepOnTop(int n) {
		List<Integer> all = new ArrayList<>(n);
		for (int i = 0; i < n; i++) all.add(i);
		return new DeckLookDecision(List.of(), List.of(), all, List.of());
	}

	/** The card that reached hand, or -1 — the riders that ask ("if it has an EX Burst") want it. */
	int handCard() {
		return toHand.isEmpty() ? -1 : toHand.get(0);
	}

	/**
	 * Flattens this into the integer list a choice answer travels as:
	 * {@code [handCount, breakCount, topCount, fieldCount, ...every index in destination order]}.
	 *
	 * <p>The four counts split the permutation that follows; whatever is left over after them is
	 * the bottom-of-deck group. Counts rather than per-card destination tags because order within a
	 * destination is itself part of the answer — a player who ordered three cards onto the top of
	 * their deck decided something a set could not carry.
	 */
	List<Integer> toAnswer() {
		List<Integer> out = new ArrayList<>(HEADER + toHand.size() + toBreak.size()
				+ toTop.size() + toField.size() + toBottom.size());
		out.add(toHand.size());
		out.add(toBreak.size());
		out.add(toTop.size());
		out.add(toField.size());
		out.addAll(toHand);
		out.addAll(toBreak);
		out.addAll(toTop);
		out.addAll(toField);
		out.addAll(toBottom);
		return out;
	}

	/** How many counts precede the permutation in {@link #toAnswer()}. */
	private static final int HEADER = 4;

	/**
	 * Reads back a {@link #toAnswer()}, or {@code null} when it does not describe a legal
	 * arrangement of {@code n} cards.
	 *
	 * <p>Legal means exactly this: the counts fit, and the indices are a permutation of
	 * {@code 0..n-1}. A remote client that sent anything else has a different idea of what was on
	 * top of the deck, which is a desync for the caller to report — and rejecting it here is what
	 * stops a duplicated index putting the same card in two zones at once.
	 */
	static DeckLookDecision fromAnswer(List<Integer> answer, int n) {
		if (answer.size() != n + HEADER) return null;
		int hand = answer.get(0), brk = answer.get(1), top = answer.get(2), field = answer.get(3);
		if (hand < 0 || brk < 0 || top < 0 || field < 0 || hand + brk + top + field > n) return null;

		List<Integer> order = answer.subList(HEADER, answer.size());
		boolean[] seen = new boolean[n];
		for (int i : order) {
			if (i < 0 || i >= n || seen[i]) return null;
			seen[i] = true;
		}
		int breakAt = hand + brk, topAt = breakAt + top, fieldAt = topAt + field;
		return new DeckLookDecision(
				List.copyOf(order.subList(0, hand)),
				List.copyOf(order.subList(hand, breakAt)),
				List.copyOf(order.subList(breakAt, topAt)),
				List.copyOf(order.subList(fieldAt, n)),
				List.copyOf(order.subList(topAt, fieldAt)));
	}
}
