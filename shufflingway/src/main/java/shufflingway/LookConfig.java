package shufflingway;

/**
 * Describes the effect of a "Look at / Reveal the top N cards of your deck" ability.
 *
 * @param count          how many cards from the top of the deck to look at
 * @param action         what the player may do with those cards after looking
 * @param elementFilter  null = no filter; non-null = only cards of this element may be added to hand
 * @param categoryFilter null = no filter; non-null = only cards of this category may be added to
 *                       hand. An alternative to {@code elementFilter}, not a companion — no card in
 *                       the corpus states both
 * @param reveal         {@code true} for "Reveal …", which shows the cards to both players;
 *                       {@code false} for "Look at …", which keeps them private to the controller.
 *                       Only the wording differs — the cards move the same way either way — so this
 *                       controls solely whether the card names reach the shared game log when the
 *                       opponent is the one looking
 */
public record LookConfig(int count, LookConfig.LookAction action, String elementFilter,
                         String categoryFilter, boolean reveal) {

    /** Convenience constructor for a private "look at" with no filter on the hand-add. */
    public LookConfig(int count, LookAction action) { this(count, action, null, null, false); }

    /** Convenience constructor for a private "look at" with an element filter on the hand-add. */
    public LookConfig(int count, LookAction action, String elementFilter) {
        this(count, action, elementFilter, null, false);
    }

    /**
     * True when {@code card} is one the player may take into hand.  Unfiltered effects accept
     * everything, so this is the single gate the dialogs and the AI both ask.
     */
    public boolean eligibleForHand(CardData card) {
        return CardFilters.meetsElementFilter(card, elementFilter)
            && CardFilters.meetsCategoryFilter(card, categoryFilter);
    }

    /** Names the hand-add restriction for dialogs and logs; {@code null} when there is none. */
    public String handFilterLabel() {
        if (elementFilter  != null) return elementFilter;
        if (categoryFilter != null) return "Category " + categoryFilter;
        return null;
    }

    public enum LookAction {
        /** Just view the card(s); they remain on top of the deck in original order. */
        PEEK,

        /** View the top card; player may put it into the Break Zone or keep it on top. */
        BREAK_OR_KEEP,

        /** View the top card; player may place it at the bottom of their deck instead. */
        BOTTOM_OR_KEEP,

        /** View N cards and return them all to the top in any player-chosen order. */
        RETURN_TOP_ORDERED,

        /**
         * View N cards; the player picks 1 to add to their hand, then orders the
         * remaining cards to go to the bottom of the deck.
         */
        ADD_TO_HAND_REST_BOTTOM,

        /**
         * View N cards; the player picks 1 to add to their hand; the rest go to
         * the Break Zone (no ordering required).
         */
        ADD_TO_HAND_REST_BREAK,

        /**
         * View N cards; the player picks 1 to add to their hand, picks 1 to put into
         * the Break Zone, then orders the remaining cards to the bottom of the deck.
         */
        ADD_TO_HAND_ONE_TO_BREAK_REST_BOTTOM,

        /**
         * View N cards; the player drags each card to either a "Top of Deck" zone
         * (left of the deck icon) or a "Bottom of Deck" zone (right of the deck icon)
         * and orders each zone independently.  Against a live opponent a 20-second
         * countdown auto-resolves any unassigned cards to the top when it expires;
         * against the CPU there is nobody waiting, so no countdown runs.
         */
        TOP_OR_BOTTOM_ORDERED,

        /**
         * View N cards; the player picks exactly 1 to put on top of the deck.  All
         * remaining cards go to the bottom of the deck (order preserved from the peek).
         * For the canonical N=2 form this is just "pick which one stays on top; the
         * other goes to the bottom."
         */
        PICK_ONE_TOP_REST_BOTTOM,
    }
}
