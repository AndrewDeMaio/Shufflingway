package shufflingway;

/**
 * The criteria of a "point that Summon or ability at something else" effect — which Stack
 * entries it may redirect, and what they end up choosing.
 *
 * <p>Five cards form this family, and they vary along three independent axes rather than being
 * five special cases:
 *
 * <table border="1">
 *   <caption>The family</caption>
 *   <tr><th>Card</th><th>Entries</th><th>Currently choosing</th><th>Redirected onto</th></tr>
 *   <tr><td>Faris 21-114L</td><td>any</td><td>the source itself</td><td>your pick, a Water Forward you control</td></tr>
 *   <tr><td>Edge 15-045H</td><td>any</td><td>a Wind Forward you control</td><td>the source</td></tr>
 *   <tr><td>Calbrena 20-024H</td><td>abilities</td><td>a Character on either field</td><td>the source</td></tr>
 *   <tr><td>Wicked Mask 20-038H</td><td>Summons</td><td>a Character in any zone</td><td>your pick, any Character</td></tr>
 *   <tr><td>Aemo 11-109R</td><td>abilities</td><td>anything at all</td><td>your pick, any Character</td></tr>
 * </table>
 *
 * <p>Deliberately carries no {@code Predicate<StackEntry>}: deciding which entries qualify means
 * resolving each stored {@link ForwardTarget} back to a card, which needs the board.
 * {@code MainWindow.redirectEligibility} builds the predicate from this record so the activation
 * gate and the resolution can never disagree about what is eligible.
 *
 * @param entryKind        which Stack entries may be redirected at all
 * @param eligibility      what the entry's single chosen target must be
 * @param eligibleElement  Element for {@link Eligibility#OWN_FORWARD_OF_ELEMENT}; else {@code null}
 * @param replacement      where the entry is pointed instead
 * @param newTargetElement Element for {@link Replacement#OWN_FORWARD_OF_ELEMENT}; else {@code null}
 * @param optional         {@code true} for the "You may choose…" wording, where declining is legal
 */
public record TargetRedirect(EntryKind entryKind, Eligibility eligibility, String eligibleElement,
        Replacement replacement, String newTargetElement, boolean optional) {

    /** Which Stack entries an effect in this family is allowed to touch. */
    public enum EntryKind {
        /** Summons only (Wicked Mask). */
        SUMMON,
        /** Abilities only — auto, action or special — never a Summon (Calbrena, Aemo). */
        ABILITY,
        /** "Summon or ability" (Faris, Edge). */
        ANY
    }

    /** What the entry must currently be choosing to be eligible. */
    public enum Eligibility {
        /** "choosing only [Self]" — the sole target is the source card (Faris). */
        SOURCE_ITSELF,
        /** "choosing only 1 [Element] Forward you control" (Edge). */
        OWN_FORWARD_OF_ELEMENT,
        /** "choosing only 1 Character either player controls" — on a field, not in a Break Zone (Calbrena). */
        ON_FIELD,
        /** "in any zone" / "has only one target" — any single stored target (Wicked Mask, Aemo). */
        ANY_ZONE
    }

    /** What the entry is made to choose instead. */
    public enum Replacement {
        /** "is now choosing [Self] instead" (Edge, Calbrena). */
        TO_SOURCE,
        /** "another [Element] Forward you control", picked by the player (Faris). */
        OWN_FORWARD_OF_ELEMENT,
        /**
         * "another Character" / "another target", picked by the player (Wicked Mask, Aemo).
         * Both collapse to the same thing here: every stored target is a card in a zone, so
         * "any target" and "any Character" describe the same candidate pool.
         */
        ANY_CHARACTER
    }

    /** Faris: eligible on entries choosing the source, replaced by a player-picked own Forward. */
    static TargetRedirect toChosenForward(String newTargetElement) {
        return new TargetRedirect(EntryKind.ANY, Eligibility.SOURCE_ITSELF, null,
                Replacement.OWN_FORWARD_OF_ELEMENT, newTargetElement, true);
    }

    /** Edge: eligible on entries choosing one of your {@code element} Forwards, redirected to the source. */
    static TargetRedirect toSource(String element) {
        return new TargetRedirect(EntryKind.ANY, Eligibility.OWN_FORWARD_OF_ELEMENT, element,
                Replacement.TO_SOURCE, null, false);
    }

    /** Calbrena: eligible on abilities choosing any Character on either field, redirected to the source. */
    static TargetRedirect onFieldToSource() {
        return new TargetRedirect(EntryKind.ABILITY, Eligibility.ON_FIELD, null,
                Replacement.TO_SOURCE, null, false);
    }

    /** Wicked Mask / Aemo: eligible on a single-target entry of {@code kind}, replaced by a player pick. */
    static TargetRedirect toAnyChosenCharacter(EntryKind kind) {
        return new TargetRedirect(kind, Eligibility.ANY_ZONE, null,
                Replacement.ANY_CHARACTER, null, true);
    }

    /** True when eligibility is "the entry is choosing only the source card". */
    public boolean eligibleOnSourceItself() { return eligibility == Eligibility.SOURCE_ITSELF; }

    /** True when the entry ends up choosing the source card. */
    public boolean toSource() { return replacement == Replacement.TO_SOURCE; }
}
