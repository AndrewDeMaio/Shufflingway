package shufflingway;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * Parsed cast-time restrictions for a card, derived from "You can only cast [Name] …" sentences.
 * A {@code null} value on {@link CardData#castRestriction()} means no restriction beyond the
 * normal rules (main phase, affordability, etc.).
 *
 * <p>Covered restriction forms:
 * <ul>
 *   <li>{@link #castProhibited}        — "You cannot cast [Name]." (never castable from any zone)</li>
 *   <li>{@link #yourTurnOnly}          — "during your turn"</li>
 *   <li>{@link #mainPhaseOnly}         — "during your Main Phase"</li>
 *   <li>{@link #opponentTurnOnly}      — "during your opponent's turn" (Back Attack cards)</li>
 *   <li>{@link #requiresNoForwards}    — "if you don't control any Forwards"</li>
 *   <li>{@link #requiresAForward}      — "if you have a Forward" (must control ≥1 Forward)</li>
 *   <li>{@link #requiredBZTypes}       — one or more card types that must each be present in your Break Zone</li>
 *   <li>{@link #minBZAndRfpSummons}    — minimum combined Summon count across Break Zone and permanent RFP</li>
 *   <li>{@link #maxOpponentHandSize}   — opponent's hand must be ≤ this value; -1 means no restriction</li>
 *   <li>{@link #mustControlCondition}  — "You must control N or more Job X Forwards and/or Job Y
 *       Forwards", or the same count unqualified ("2 or more Forwards", Steiner 14-109C), or a
 *       single Category Forward; {@code null} = no restriction</li>
 *   <li>{@link #mustControlCosts}      — "You must control Characters of cost 1, 2, 3, 4, 5 and 6"; one Character per listed cost</li>
 *   <li>{@link #minEitherPlayerDamage} — "if either player has received N points of damage or
 *       more" (Sephiroth 11-130L); measured against the larger of the two damage zones, and 0
 *       means no such restriction</li>
 * </ul>
 */
public record CastRestriction(
        boolean          castProhibited,
        boolean          yourTurnOnly,
        boolean          mainPhaseOnly,
        boolean          opponentTurnOnly,
        boolean          requiresNoForwards,
        boolean          requiresAForward,
        Set<String>      requiredBZTypes,
        int              minBZAndRfpSummons,
        int              maxOpponentHandSize,
        ControlCondition mustControlCondition,
        Set<Integer>     mustControlCosts,
        boolean          requiresForwardPutToBZThisTurn,
        int              minEitherPlayerDamage
) {
    public CastRestriction {
        // Sorted, not Set.copyOf: the latter randomises iteration order per JVM run
        // (ImmutableCollections.SALT). Sorting is stable regardless of what the caller passes.
        requiredBZTypes  = Collections.unmodifiableSet(new TreeSet<>(requiredBZTypes));
        mustControlCosts = Collections.unmodifiableSet(new TreeSet<>(mustControlCosts));
    }

    /** Compatibility constructor preserving the prior 12-arg form; no either-player damage gate. */
    public CastRestriction(boolean castProhibited, boolean yourTurnOnly, boolean mainPhaseOnly,
            boolean opponentTurnOnly, boolean requiresNoForwards, boolean requiresAForward,
            Set<String> requiredBZTypes, int minBZAndRfpSummons, int maxOpponentHandSize,
            ControlCondition mustControlCondition, Set<Integer> mustControlCosts,
            boolean requiresForwardPutToBZThisTurn) {
        this(castProhibited, yourTurnOnly, mainPhaseOnly, opponentTurnOnly, requiresNoForwards,
                requiresAForward, requiredBZTypes, minBZAndRfpSummons, maxOpponentHandSize,
                mustControlCondition, mustControlCosts, requiresForwardPutToBZThisTurn, 0);
    }

    /** Compatibility constructor preserving the prior 11-arg form; no put-to-BZ requirement. */
    public CastRestriction(boolean castProhibited, boolean yourTurnOnly, boolean mainPhaseOnly,
            boolean opponentTurnOnly, boolean requiresNoForwards, boolean requiresAForward,
            Set<String> requiredBZTypes, int minBZAndRfpSummons, int maxOpponentHandSize,
            ControlCondition mustControlCondition, Set<Integer> mustControlCosts) {
        this(castProhibited, yourTurnOnly, mainPhaseOnly, opponentTurnOnly, requiresNoForwards,
                requiresAForward, requiredBZTypes, minBZAndRfpSummons, maxOpponentHandSize,
                mustControlCondition, mustControlCosts, false);
    }

    /** Compatibility constructor preserving the prior 10-arg form; no cost requirement. */
    public CastRestriction(boolean castProhibited, boolean yourTurnOnly, boolean mainPhaseOnly,
            boolean opponentTurnOnly, boolean requiresNoForwards, boolean requiresAForward,
            Set<String> requiredBZTypes, int minBZAndRfpSummons, int maxOpponentHandSize,
            ControlCondition mustControlCondition) {
        this(castProhibited, yourTurnOnly, mainPhaseOnly, opponentTurnOnly, requiresNoForwards,
                requiresAForward, requiredBZTypes, minBZAndRfpSummons, maxOpponentHandSize,
                mustControlCondition, Set.of(), false);
    }
}
