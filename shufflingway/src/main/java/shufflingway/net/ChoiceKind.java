package shufflingway.net;

/**
 * What a {@link ActionType#CHOICE} answer means.
 *
 * <p>A CHOICE is one player's answer to a question the <em>other</em> client is parked on while an
 * effect resolves. Both clients run the same ability over the same board, so only the answer
 * crosses the wire — never the reasoning behind it, and never the state it produces. The kind is
 * how a waiting client tells whether the answer that just landed is the one it is holding for.
 *
 * <p>Every answer is a list of small integers; what those integers index is fixed per kind and
 * documented below. Two frames of reference are in play and the difference is load bearing. Hand
 * and slot indices are <em>shared</em> — both clients hold each zone in the same order, so index
 * <i>n</i> names the same card on both — while a field code names a <em>side</em>, and the
 * sender's own side is the receiver's opponent. Field codes are therefore always written from the
 * sender's point of view and flipped on arrival.
 *
 * <p><b>One question at a time.</b> A kind is identity enough only because neither client sends a
 * second question before the first is answered. Two questions of the same kind travelling in the
 * same direction within one effect would be indistinguishable, so an effect that needs that has to
 * introduce a kind for the second.
 */
public enum ChoiceKind {

	/** The cards the sender chose to show from their own hand; indices into that hand. */
	REVEAL_HAND,

	/**
	 * The one card the sender picked out of what they were shown. It indexes their opponent's hand
	 * — the receiver's own — because that is the hand the card is discarded from, not the subset it
	 * was chosen out of.
	 */
	SELECT_REVEALED,

	/**
	 * A card the sender picked from their own field: "each player selects 1 Forward", and the
	 * effects that make a player break something of their own. Carried as {@code ForwardTarget}
	 * choice codes, which name a side and so are flipped into the receiver's frame on arrival.
	 */
	OWN_FIELD_CARD,

	/**
	 * What the sender did with the cards they looked at on top of their own deck — a
	 * {@code DeckLookDecision}, flattened. The indices address the peeked cards, which both
	 * clients take off the same seeded deck in the same order, so they need no flip.
	 */
	DECK_LOOK
}
