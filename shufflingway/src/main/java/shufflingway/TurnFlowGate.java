package shufflingway;

/**
 * Tracks whether turn flow may advance, so timer-driven progression (the AI's step chain, the
 * auto-pass handoff, delayed reveals) does not run while the game is mid-decision.
 *
 * <p>The problem this solves: blocking player choices — {@code selectFieldTargetsInPlace}'s
 * {@code SecondaryLoop}, and every modal choice dialog — keep pumping the AWT event queue while
 * they wait, by design, so the board stays clickable. That means Swing {@code Timer}s keep firing
 * underneath an unresolved choice, and turn flow can advance past it. Two counters close the two
 * windows the older {@code isResolvingStack} flag missed:
 *
 * <ul>
 *   <li>{@link #beginChoice} / {@link #endChoice} — a blocking selection is on screen.</li>
 *   <li>{@link #beginPendingTrigger} / {@link #endPendingTrigger} — an ability has been queued
 *       but has not reached the stack yet, so the stack looks empty while something is still
 *       owed. An EX Burst waiting out the damage-slide animation is the motivating case.</li>
 * </ul>
 *
 * <p>Counters rather than booleans, because choices nest: resolving one ability can open a
 * selection that opens another. Callers must pair each begin with an end in a {@code finally},
 * since an effect that throws midway would otherwise wedge turn flow permanently.
 *
 * <p>Not thread-safe, and deliberately so: every caller is on the EDT.
 */
final class TurnFlowGate {

	private int openChoices;
	private int pendingTriggers;

	/** Registers that a blocking player choice is now on screen. */
	void beginChoice() {
		openChoices++;
	}

	/**
	 * Registers that a blocking player choice has closed. Clamped at zero: an unbalanced end is a
	 * bug, but letting the count go negative would report the board settled while a later choice
	 * is genuinely open, which freezes the game instead of merely mis-sequencing it.
	 */
	void endChoice() {
		if (openChoices > 0) openChoices--;
	}

	/** Registers an ability that has been queued to fire but has not reached the stack yet. */
	void beginPendingTrigger() {
		pendingTriggers++;
	}

	/** Registers that a queued ability has reached the stack (or been dropped). Clamped at zero. */
	void endPendingTrigger() {
		if (pendingTriggers > 0) pendingTriggers--;
	}

	/** True when nothing this gate tracks is outstanding, so turn flow may advance. */
	boolean isClear() {
		return openChoices == 0 && pendingTriggers == 0;
	}

	/** Number of blocking choices currently open; for diagnostics and tests. */
	int openChoices() {
		return openChoices;
	}

	/** Number of queued-but-unstacked triggers; for diagnostics and tests. */
	int pendingTriggers() {
		return pendingTriggers;
	}

	/** Clears all counters. Called when a new game starts, so a wedged count cannot outlive it. */
	void reset() {
		openChoices = 0;
		pendingTriggers = 0;
	}

	@Override public String toString() {
		return "TurnFlowGate[choices=" + openChoices + ", pendingTriggers=" + pendingTriggers + "]";
	}
}
