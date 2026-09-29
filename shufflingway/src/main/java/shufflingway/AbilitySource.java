package shufflingway;

/**
 * Where the card an action ability was activated from sits, as one client sees it.
 *
 * <p>Wider than a {@link ForwardTarget}, which only names field slots: an ability can also be used
 * from the Break Zone or from hand, and a primed Forward's slot holds two cards, only the top one
 * of which is the source. Every position is one both clients hold in the same order, so a source
 * crosses the wire as this and nothing else — the far client flips {@code ownerIsP1} and finds the
 * same card.
 *
 * @param zone      the zone the card is in
 * @param idx       its slot or position in that zone
 * @param ownerIsP1 whose zone it is
 * @param primedTop the top card of a primed Forward stack rather than the Forward beneath it;
 *                  only ever set with {@link Zone#FORWARD}
 */
record AbilitySource(Zone zone, int idx, boolean ownerIsP1, boolean primedTop) {

    enum Zone { FORWARD, BACKUP, MONSTER, BREAK_ZONE, HAND }

    /** A card that is itself in a field slot. */
    static AbilitySource onField(ForwardTarget t) {
        return new AbilitySource(Zone.valueOf(t.zone().name()), t.idx(), t.isP1(), false);
    }

    /** The field slot this source occupies, or {@code null} when it is not on the field. */
    ForwardTarget fieldSlot() {
        return switch (zone) {
            case FORWARD -> new ForwardTarget(ownerIsP1, idx, ForwardTarget.CardZone.FORWARD);
            case BACKUP  -> new ForwardTarget(ownerIsP1, idx, ForwardTarget.CardZone.BACKUP);
            case MONSTER -> new ForwardTarget(ownerIsP1, idx, ForwardTarget.CardZone.MONSTER);
            case BREAK_ZONE, HAND -> null;
        };
    }

    /** The same card as the other client holds it: their own is this one's opponent's. */
    AbilitySource flipped() {
        return new AbilitySource(zone, idx, !ownerIsP1, primedTop);
    }
}
