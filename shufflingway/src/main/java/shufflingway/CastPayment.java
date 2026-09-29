package shufflingway;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The standard payment for a card cast from hand — what the payment dialog hands back — as an
 * answer that can cross the wire on its own.
 *
 * <p>A cast from hand normally carries this inside its PLAY_CARD. A cast an effect allows while it
 * resolves — "cast 1 Summon from your hand, its cost reduced by N" — happens with both clients
 * parked in the same resolution, so the payment crosses as a
 * {@link shufflingway.net.ChoiceKind#CAST_PAYMENT} instead and both clients cast from it.
 *
 * <p>Every index addresses the payer's own hand and Backup row, so nothing names a side.
 *
 * @param discards        hand indices discarded for CP, as the hand stood before the cast
 * @param backups         Backup slots dulled for CP
 * @param backupElements  the Element a dulled Backup produced, where the payer chose one
 * @param backupBreaks    Backups put into the Break Zone for CP (Sherlotta 8-053H), slot to Element
 */
record CastPayment(List<Integer> discards, List<Integer> backups,
        Map<Integer, String> backupElements, Map<Integer, String> backupBreaks) {

    /** A cast that cost nothing — distinct from a declined one, which has no payment at all. */
    static final CastPayment NOTHING = new CastPayment(List.of(), List.of(), Map.of(), Map.of());

    CastPayment {
        discards       = discards       == null ? List.of() : List.copyOf(discards);
        backups        = backups        == null ? List.of() : List.copyOf(backups);
        backupElements = backupElements == null ? Map.of()  : Map.copyOf(backupElements);
        backupBreaks   = backupBreaks   == null ? Map.of()  : Map.copyOf(backupBreaks);
    }

    /**
     * Flattens this payment into {@code [nDiscards, d…, nBackups, b…, nElements, slot, e…,
     * nBreaks, slot, e…]}, each Element a position in {@link Elements#ALL}. Never empty — an empty
     * answer is a declined cast. The two maps are written in slot order so the answer does not
     * depend on how the map happens to iterate.
     */
    List<Integer> toAnswer() {
        List<Integer> out = new ArrayList<>();
        out.add(discards.size());
        out.addAll(discards);
        out.add(backups.size());
        out.addAll(backups);
        appendElements(out, backupElements);
        appendElements(out, backupBreaks);
        return out;
    }

    private static void appendElements(List<Integer> out, Map<Integer, String> bySlot) {
        List<Integer> slots = new ArrayList<>(bySlot.keySet());
        slots.sort(null);
        out.add(slots.size());
        for (int slot : slots) {
            out.add(slot);
            out.add(Elements.ALL.indexOf(bySlot.get(slot)));
        }
    }

    /** Reads back a {@link #toAnswer}; {@code null} for an empty or malformed answer. */
    static CastPayment fromAnswer(List<Integer> answer) {
        if (answer == null || answer.isEmpty()) return null;
        int[] at = {0};
        List<Integer> discards = run(answer, at);
        List<Integer> backups  = run(answer, at);
        Map<Integer, String> elements = elements(answer, at);
        Map<Integer, String> breaks   = elements(answer, at);
        if (discards == null || backups == null || elements == null || breaks == null
                || at[0] != answer.size()) return null;
        return new CastPayment(discards, backups, elements, breaks);
    }

    private static List<Integer> run(List<Integer> answer, int[] at) {
        if (at[0] >= answer.size()) return null;
        int n = answer.get(at[0]++);
        if (n < 0 || at[0] + n > answer.size()) return null;
        List<Integer> out = new ArrayList<>(answer.subList(at[0], at[0] + n));
        at[0] += n;
        return out;
    }

    private static Map<Integer, String> elements(List<Integer> answer, int[] at) {
        if (at[0] >= answer.size()) return null;
        int n = answer.get(at[0]++);
        if (n < 0 || at[0] + 2 * n > answer.size()) return null;
        Map<Integer, String> out = new LinkedHashMap<>();
        for (int k = 0; k < n; k++) {
            int slot = answer.get(at[0]++);
            int elem = answer.get(at[0]++);
            if (elem < 0 || elem >= Elements.ALL.size()) return null;
            out.put(slot, Elements.ALL.get(elem));
        }
        return out;
    }
}
