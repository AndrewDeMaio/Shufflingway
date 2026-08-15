package shufflingway;

/**
 * A passive, always-on grant conditioned on a named counter:
 * "Each Forward you control with a [counterName] Counter on it gains [+N power | \"ability\"]."
 *
 * <p>Active while the owning card is on the field; it applies to each Forward the same player
 * controls that currently carries at least one counter named {@link #counterName}. Exactly one of
 * {@link #powerBonus} (non-zero) or {@link #grantedAbilityText} (non-null) is populated:
 * <ul>
 *   <li>a power grant — e.g. Legendary Turk: "…gains +5000 power.";</li>
 *   <li>an ability grant — e.g. Kimahri: "…gains \"If this Forward is dealt damage by your
 *       opponent's Summons or abilities, the damage becomes 0 instead.\""</li>
 * </ul>
 *
 * <p>{@link #perCounter} and {@link #affectsOpponent} carry the one printed variant that differs on
 * both axes: "The Forwards opponent controls lose 2000 power for each Poison Counter on them."
 * (Gargas 17-045R), which scales with the counter count instead of triggering at one or more, and
 * reaches across the field instead of applying to its controller's own Forwards.
 *
 * <p>{@link #selfOnly} carries the self-named printing: "If a Barrier Counter is placed on Number 24,
 * Number 24 gains \"…\"" (20-036H). It reads as a trigger but behaves as the same standing grant —
 * the ability is present exactly while the counter is — so it rides this record rather than the
 * auto-ability path, with the flag confining it to the carrier instead of every Forward alongside it.
 * Only the ability-grant form is printed that way, so {@link #powerBonus} is always 0 when it is set
 * and the power readers need no matching guard.
 *
 * <p>{@link #minCount} carries the threshold form: "The Forwards with 2 or more EXP Counters on them
 * you control gain …" (Palom 23-018R, Porom 23-110R). Every other printing pays out at one or more,
 * which is what the default of 1 says, so readers can compare against it unconditionally.
 */
public record CounterGrant(
        String  counterName,        // e.g. "Turks", "Guardian", "Ronso", "Poison", "Barrier", "EXP"
        int     powerBonus,         // 0 when this grant is an ability grant; negative for a debuff
        String  grantedAbilityText, // null when this grant is a power grant; else the granted ability text
        boolean perCounter,         // true = powerBonus applies once per counter; false = once at minCount or more
        boolean affectsOpponent,    // true = applies to the opposing player's Forwards
        boolean selfOnly,           // true = applies only to the card printing it, not to its neighbours
        int     minCount            // counters required before the grant applies; 1 for every unthresholded printing
) {
    public CounterGrant {
        if (minCount < 1) minCount = 1;
    }

    /** Convenience constructor preserving the prior 6-arg form; defaults {@code minCount} to 1. */
    public CounterGrant(String counterName, int powerBonus, String grantedAbilityText,
            boolean perCounter, boolean affectsOpponent, boolean selfOnly) {
        this(counterName, powerBonus, grantedAbilityText, perCounter, affectsOpponent, selfOnly, 1);
    }

    /** Convenience constructor for the same-side, at-least-one-counter, field-wide form. */
    public CounterGrant(String counterName, int powerBonus, String grantedAbilityText) {
        this(counterName, powerBonus, grantedAbilityText, false, false, false, 1);
    }
}
