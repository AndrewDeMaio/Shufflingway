package shufflingway;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TurnFlowGate} — the predicate {@code MainWindow.isBoardSettled()} consults
 * to decide whether timer-driven turn flow may advance. Tested here rather than through the UI
 * because the interleavings that matter (a choice opening while a trigger is still queued) are
 * exactly the ones that are impractical to stage against live Swing timers.
 */
public class TurnFlowGateTest {

    @Test
    void startsClear() {
        TurnFlowGate gate = new TurnFlowGate();
        assertTrue(gate.isClear());
        assertEquals(0, gate.openChoices());
        assertEquals(0, gate.pendingTriggers());
    }

    @Test
    void openChoiceBlocksUntilClosed() {
        TurnFlowGate gate = new TurnFlowGate();
        gate.beginChoice();
        assertFalse(gate.isClear(), "turn flow must not advance while a choice is on screen");
        gate.endChoice();
        assertTrue(gate.isClear());
    }

    @Test
    void pendingTriggerBlocksUntilStacked() {
        TurnFlowGate gate = new TurnFlowGate();
        gate.beginPendingTrigger();
        assertFalse(gate.isClear(), "a queued trigger leaves the stack empty but something owed");
        gate.endPendingTrigger();
        assertTrue(gate.isClear());
    }

    /** Resolving one ability can open a selection that opens another; the gate must not clear early. */
    @Test
    void nestedChoicesRequireMatchingEnds() {
        TurnFlowGate gate = new TurnFlowGate();
        gate.beginChoice();
        gate.beginChoice();
        gate.beginChoice();
        assertEquals(3, gate.openChoices());

        gate.endChoice();
        assertFalse(gate.isClear(), "still two choices deep");
        gate.endChoice();
        assertFalse(gate.isClear(), "still one choice deep");
        gate.endChoice();
        assertTrue(gate.isClear());
    }

    /** The two counters are independent: clearing one must not unblock the other. */
    @Test
    void countersAreIndependent() {
        TurnFlowGate gate = new TurnFlowGate();
        gate.beginChoice();
        gate.beginPendingTrigger();

        gate.endChoice();
        assertFalse(gate.isClear(), "the pending trigger still blocks");
        assertEquals(0, gate.openChoices());
        assertEquals(1, gate.pendingTriggers());

        gate.endPendingTrigger();
        assertTrue(gate.isClear());
    }

    /**
     * This is the Vivi EX Burst sequence: damage queues a trigger, the AI must not advance, the
     * trigger fires and opens a target choice, and only once the player has picked does the board
     * settle. The gate must stay blocked across the whole span, including the handover where the
     * trigger is retired while the choice it spawned is still open.
     */
    @Test
    void staysBlockedAcrossTriggerToChoiceHandover() {
        TurnFlowGate gate = new TurnFlowGate();

        gate.beginPendingTrigger();          // damage dealt, EX Burst queued behind the animation
        assertFalse(gate.isClear());

        gate.beginChoice();                  // burst resolves, "choose 1 Forward" opens
        gate.endPendingTrigger();            // trigger has reached the stack
        assertFalse(gate.isClear(), "the target choice is still outstanding");

        gate.endChoice();                    // player picks
        assertTrue(gate.isClear());
    }

    /**
     * An unbalanced end is a caller bug, but it must not drive the count negative — that would
     * report the board settled while a genuinely later choice is open, freezing the game rather
     * than merely mis-sequencing it.
     */
    @Test
    void unbalancedEndsClampAtZero() {
        TurnFlowGate gate = new TurnFlowGate();
        gate.endChoice();
        gate.endChoice();
        gate.endPendingTrigger();
        assertEquals(0, gate.openChoices());
        assertEquals(0, gate.pendingTriggers());

        gate.beginChoice();
        assertFalse(gate.isClear(), "a real choice still blocks after spurious ends");
        gate.endChoice();
        assertTrue(gate.isClear());
    }

    @Test
    void resetClearsWedgedCounters() {
        TurnFlowGate gate = new TurnFlowGate();
        gate.beginChoice();
        gate.beginChoice();
        gate.beginPendingTrigger();
        assertFalse(gate.isClear());

        gate.reset();
        assertTrue(gate.isClear(), "a wedged count must not outlive the game it belonged to");
        assertEquals(0, gate.openChoices());
        assertEquals(0, gate.pendingTriggers());
    }
}
