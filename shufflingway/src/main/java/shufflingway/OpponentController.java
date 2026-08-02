package shufflingway;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Every decision the opponent (P2) makes, behind one interface.
 *
 * <p>{@link ComputerPlayer} is the local AI implementation; a networked implementation will
 * later answer the same calls by round-tripping to a remote human. That is the reason every
 * decision here is expressed as <em>request + callback</em> rather than as a return value: a
 * remote opponent cannot answer inline on the EDT, so no caller may assume the answer is
 * available when the call returns.
 *
 * <p>{@code ComputerPlayer} deliberately invokes each callback <em>synchronously</em>, before
 * its {@code request…} method returns. That keeps AI games running in exactly the control flow
 * they had before this seam existed. Callers must still not depend on it.
 *
 * <p>Only decisions belong here. Applying a decision to the board stays in {@link MainWindow}
 * with the rest of the rules, so both a local and a remote opponent drive identical state
 * changes through the same {@code placeP2Card…} / {@code resolveCombat} paths.
 */
interface OpponentController {

	/** Drives the opponent's entire turn, beginning at their Active Phase. */
	void runTurn();

	/** Permanently stops this controller; pending and future steps become no-ops. */
	void cancel();

	/** True when this opponent is the built-in AI rather than a remote human. */
	boolean isCpu();

	/**
	 * Asks the opponent to declare a blocker against a single attacker.
	 *
	 * @param effectiveAttackerPower the attacker's power after all modifiers
	 * @param attacker               the attacking card's field position
	 * @param forcedBlock            true when a "must block if possible" ability applies
	 * @param onChosen               receives the chosen blocker, or {@code null} for no block
	 */
	void requestBlocker(int effectiveAttackerPower, ForwardTarget attacker, boolean forcedBlock,
	                    Consumer<ForwardTarget> onChosen);

	/** As {@link #requestBlocker(int, ForwardTarget, boolean, Consumer)} with no forced block. */
	default void requestBlocker(int effectiveAttackerPower, ForwardTarget attacker,
	                            Consumer<ForwardTarget> onChosen) {
		requestBlocker(effectiveAttackerPower, attacker, false, onChosen);
	}

	/**
	 * Asks the opponent to declare a blocker against a party attack.
	 *
	 * @param attackerIndices indices into {@code p1ForwardCards} of the attacking party
	 * @param combinedPower   the party's combined power
	 * @param onChosen        receives the blocking P2 Forward's index, or {@code null} for no block
	 */
	void requestPartyBlocker(List<Integer> attackerIndices, int combinedPower, Consumer<Integer> onChosen);

	/**
	 * Asks the opponent how to spread its blocker's damage across a blocked party.
	 *
	 * @param attackerIndices indices into {@code p1ForwardCards} of the attacking party
	 * @param blockerPower    the blocker's power, i.e. the damage available to assign
	 * @param onAssigned      receives attacker index → damage; an empty map assigns nothing
	 */
	void requestPartyBlockerDamage(List<Integer> attackerIndices, int blockerPower,
	                               Consumer<Map<Integer, Integer>> onAssigned);

	/**
	 * Offers the opponent a window to activate reactive "damage becomes 0" shield abilities
	 * while the local player holds priority, then runs {@code onDone}.
	 */
	void requestReactiveShields(Runnable onDone);
}
