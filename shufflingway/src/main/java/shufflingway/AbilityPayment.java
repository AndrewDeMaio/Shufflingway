package shufflingway;

import java.util.List;
import java.util.Map;

/**
 * What a player paid to activate an action ability.
 *
 * <p>The third of the payment records, beside {@link AltPayment} and {@link ExtraPayment}, and
 * carried for the same reason: every field here is a choice its payer made, so the other client
 * cannot work it out and has to be told. What the ability <em>costs</em> is read off the card at
 * both ends — including the discounts and surcharges the board applies to it, which both clients
 * compute from the same board.
 *
 * <p>Only the choices settled <em>before</em> the payment commits live here, because those are the
 * ones a player can still back out of: a pick that crossed as a {@code CHOICE} and was then
 * abandoned would sit buffered on the far client for the next question of its kind. Choices made
 * after the commit — which Forwards to dull, which card to remove from the game — cross as
 * {@code CHOICE}s while both clients stand at the same point in the payment.
 *
 * <p>Every index addresses the payer's own zones, so nothing here names a side.
 *
 * @param discards         hand slots discarded for CP
 * @param backupDulls      Backup slots dulled for CP
 * @param bzTargets        field cards put into the Break Zone to pay a "put X into the Break Zone"
 *                         cost. Resolved by the activator before the payment runs — mostly forced,
 *                         but a genuine choice when more cards qualify than the cost takes, which is
 *                         why the answer travels rather than being worked out again at the far end
 * @param xValue           the X chosen for an 《X》 cost, 0 when the ability prints none
 * @param sCostHandIdx     the hand slot discarded for a Special ability's 《S》 cost,
 *                         {@code AbilityPaymentDialog.S_COST_CRYSTAL} when a Crystal paid it, or
 *                         -1 when there was none to pay or it is still to be settled
 * @param backupBreaks     Backups put into the Break Zone for CP as part of this payment, slot to the
 *                         Element each produces (Sherlotta 8-053H)
 * @param discardCostPicks the hand slots discarded for each of the ability's discard costs, in the
 *                         order it prints them; empty when it prints none or they are still to be
 *                         picked. Slots address the hand as it stood before any of this payment was
 *                         spent — the CP discards above shift it
 * @param counterWaiver    the whole cost was waived by removing counters instead (Wakka 16-138S)
 */
public record AbilityPayment(List<Integer> discards, List<Integer> backupDulls,
        List<ForwardTarget> bzTargets, int xValue, int sCostHandIdx,
        Map<Integer, String> backupBreaks, List<List<Integer>> discardCostPicks,
        boolean counterWaiver) {

    /** Defensive copies, so a caller cannot mutate a payment after it has been applied or sent. */
    public AbilityPayment {
        discards     = discards     == null ? List.of() : List.copyOf(discards);
        backupDulls  = backupDulls  == null ? List.of() : List.copyOf(backupDulls);
        bzTargets    = bzTargets    == null ? List.of() : List.copyOf(bzTargets);
        backupBreaks = backupBreaks == null ? Map.of()  : Map.copyOf(backupBreaks);
        discardCostPicks = discardCostPicks == null ? List.of()
                : discardCostPicks.stream().map(List::copyOf).toList();
    }

    /** A payment with no discard-cost picks settled yet and no waiver — what a payment dialog produces. */
    public AbilityPayment(List<Integer> discards, List<Integer> backupDulls,
            List<ForwardTarget> bzTargets, int xValue, int sCostHandIdx,
            Map<Integer, String> backupBreaks) {
        this(discards, backupDulls, bzTargets, xValue, sCostHandIdx, backupBreaks, List.of(), false);
    }

    /** A payment of nothing: a zero-cost ability, or one whose costs were all waived. */
    public static AbilityPayment none() {
        return new AbilityPayment(List.of(), List.of(), List.of(), 0, -1, Map.of());
    }

    /** This payment with the choices the payment itself settled before committing. */
    AbilityPayment settled(int sCostHandIdx, List<List<Integer>> discardCostPicks) {
        return new AbilityPayment(discards, backupDulls, bzTargets, xValue, sCostHandIdx,
                backupBreaks, discardCostPicks, counterWaiver);
    }

    /** This payment marked as the counter waiver. */
    AbilityPayment waivedByCounters() {
        return new AbilityPayment(discards, backupDulls, bzTargets, xValue, sCostHandIdx,
                backupBreaks, discardCostPicks, true);
    }
}
