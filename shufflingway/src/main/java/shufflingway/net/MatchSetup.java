package shufflingway.net;

import java.util.List;
import java.util.Random;

/**
 * Everything two clients must agree on before a networked game can start, as produced by the
 * lobby handshake and consumed by {@code MainWindow.startMultiplayerGame}.
 *
 * <p>Both clients build an identical game from this: each shows its own player as P1 and the
 * opponent as P2, so the two boards are mirror images of one another rather than copies.
 *
 * @param localDeckId    the local player's chosen deck, read from the local deck database
 * @param remoteSerials  the opponent's deck, one entry per copy, ordered by serial
 * @param remoteDeckName the opponent's deck name, for the game log
 * @param seed           shared shuffle seed; see {@link #hostDeckRandom()}
 * @param localIsHost    true on the client that hosted the lobby
 * @param hostGoesFirst  whether the host takes the first turn (the host's coin flip)
 */
public record MatchSetup(int localDeckId, List<String> remoteSerials, String remoteDeckName,
                         long seed, boolean localIsHost, boolean hostGoesFirst) {

	public MatchSetup {
		remoteSerials = List.copyOf(remoteSerials);
	}

	/** True when the local player takes the first turn. */
	public boolean localGoesFirst() {
		return localIsHost == hostGoesFirst;
	}

	/**
	 * The shuffle stream for the host's deck.
	 *
	 * <p>Streams are keyed by <em>who owns the deck</em>, not by P1/P2, because the two clients
	 * disagree about which of those the same deck is. Both clients shuffle the host's deck with
	 * this stream and the joiner's with {@link #joinerDeckRandom()}, so both arrive at the same
	 * order for both decks.
	 *
	 * <p>This relies on {@code Collections.shuffle(list, rnd)} and {@code java.util.Random}
	 * both being specified algorithms — same seed, same sequence, on any JVM.
	 */
	public Random hostDeckRandom() {
		return new Random(seed);
	}

	/** The shuffle stream for the joiner's deck. @see #hostDeckRandom() */
	public Random joinerDeckRandom() {
		return new Random(seed + 1);
	}

	/** The shuffle stream for the local player's deck. */
	public Random localDeckRandom() {
		return localIsHost ? hostDeckRandom() : joinerDeckRandom();
	}

	/** The shuffle stream for the opponent's deck. */
	public Random remoteDeckRandom() {
		return localIsHost ? joinerDeckRandom() : hostDeckRandom();
	}
}
