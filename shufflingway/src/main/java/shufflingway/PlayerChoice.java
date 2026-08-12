package shufflingway;

import java.util.List;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;
import java.util.function.Supplier;

import shufflingway.net.ChoiceKind;

/**
 * One question put to one seat, carrying everything needed to answer it whoever is sitting there.
 *
 * <p>Three kinds of player can occupy a seat and each answers differently: the local human picks
 * in a dialog, the AI runs a heuristic, and a remote human answers on their own client and sends
 * it across. Before this existed every effect that asked a question spelled all three out inline
 * and had to remember to transmit the local answer — which is how an ability ends up quietly
 * working against the AI and diverging against a human. Describing the question once and handing
 * it to {@link MainWindow#decide} keeps that routing in a single place.
 *
 * <p>The answer is always a list of small integers, because it has to survive the wire. What they
 * index is fixed by the {@link ChoiceKind}.
 *
 * <p><b>The seat is not the player.</b> Each client seats its own player as P1, so
 * {@code chooserIsP1} means exactly "the local player is the one being asked"; the other seat is
 * the AI or the remote human depending on the game. That is also why the same effect resolving on
 * both clients asks opposite seats and the two agree without either being told: the ability's
 * controller is P1 on their own client and P2 on the other.
 *
 * @param chooserIsP1  the seat being asked
 * @param kind         what the answer's integers mean, and the key a waiting client matches on
 * @param waitPrompt   shown to this client while a remote player answers
 * @param localAnswer  asks the local human; the answer is transmitted when an opponent is waiting
 * @param cpuAnswer    the AI's answer, reached only when the other seat is the built-in opponent
 * @param fromWire     rewrites a remote answer into this client's frame of reference — identity
 *                     for anything indexed by hand or slot, a side flip for a field code
 * @param legal        which answers this client will act on, applied after {@code fromWire}; an
 *                     illegal one is a desync to report rather than a move to make. It sees the
 *                     whole answer at once because some are only legal together — a deck-look
 *                     answer has to be a permutation, which no per-card check could tell
 * @param legalityNote completes that desync report — what this client believes instead
 */
record PlayerChoice(boolean chooserIsP1, ChoiceKind kind, String waitPrompt,
                    Supplier<List<Integer>> localAnswer, Supplier<List<Integer>> cpuAnswer,
                    IntUnaryOperator fromWire, Predicate<List<Integer>> legal, String legalityNote) {

	/**
	 * Starts a question to the seat at {@code chooserIsP1}. Every answer defaults to "nothing
	 * chosen", so a caller that forgets to describe a branch declines rather than inventing a move.
	 */
	static PlayerChoice by(boolean chooserIsP1, ChoiceKind kind) {
		return new PlayerChoice(chooserIsP1, kind, "Waiting for your opponent...",
				List::of, List::of, IntUnaryOperator.identity(), answer -> true, "");
	}

	/** What this client is told while a remote player is deciding. */
	PlayerChoice prompting(String waitPrompt) {
		return new PlayerChoice(chooserIsP1, kind, waitPrompt, localAnswer, cpuAnswer,
				fromWire, legal, legalityNote);
	}

	/** How the local human is asked. */
	PlayerChoice locally(Supplier<List<Integer>> localAnswer) {
		return new PlayerChoice(chooserIsP1, kind, waitPrompt, localAnswer, cpuAnswer,
				fromWire, legal, legalityNote);
	}

	/** How the AI answers when it holds the seat. */
	PlayerChoice byCpu(Supplier<List<Integer>> cpuAnswer) {
		return new PlayerChoice(chooserIsP1, kind, waitPrompt, localAnswer, cpuAnswer,
				fromWire, legal, legalityNote);
	}

	/** How to read an answer written from the far client's point of view. */
	PlayerChoice arrivingAs(IntUnaryOperator fromWire) {
		return new PlayerChoice(chooserIsP1, kind, waitPrompt, localAnswer, cpuAnswer,
				fromWire, legal, legalityNote);
	}

	/** Which answers this client will accept from a remote player, and what to say when it will not. */
	PlayerChoice legalWhen(Predicate<List<Integer>> legal, String legalityNote) {
		return new PlayerChoice(chooserIsP1, kind, waitPrompt, localAnswer, cpuAnswer,
				fromWire, legal, legalityNote);
	}
}
