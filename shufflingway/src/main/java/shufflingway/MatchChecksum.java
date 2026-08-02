package shufflingway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;

/**
 * Hashes the parts of a networked game that both clients must agree on, so a divergence is
 * caught where it happened instead of surfacing ten turns later as an impossible board.
 *
 * <p>Digests are taken from the <em>host/joiner</em> point of view rather than P1/P2: each
 * client shows itself as P1, so the two disagree about which player any given deck belongs to.
 * Feeding them in owner order makes the two digests directly comparable.
 *
 * <p>Cards are identified by the fields that a shuffle can reorder — name, cost, element, type.
 * {@link CardData} carries no serial, and this only has to detect ordering divergence, not
 * authenticate card identity; the lobby's card-database checksum already does that.
 */
final class MatchChecksum {

	private MatchChecksum() {}

	/**
	 * Digests both decks in their freshly shuffled order plus the agreed first player.
	 * Call before any card is drawn — this is the assertion that the deal itself matched.
	 *
	 * @param localIsHost   whether this client hosted, i.e. whether P1 here is the host
	 * @param hostGoesFirst the agreed coin flip
	 */
	static String ofOpeningDeal(GameState state, boolean localIsHost, boolean hostGoesFirst) {
		Collection<CardData> hostDeck   = localIsHost ? state.getP1MainDeck() : state.getP2MainDeck();
		Collection<CardData> joinerDeck = localIsHost ? state.getP2MainDeck() : state.getP1MainDeck();
		Collection<CardData> hostLb     = localIsHost ? state.getP1LbDeck()   : state.getP2LbDeck();
		Collection<CardData> joinerLb   = localIsHost ? state.getP2LbDeck()   : state.getP1LbDeck();

		StringBuilder sb = new StringBuilder();
		sb.append("first=").append(hostGoesFirst ? "host" : "joiner").append('\n');
		appendZone(sb, "hostDeck",   hostDeck);
		appendZone(sb, "joinerDeck", joinerDeck);
		appendZone(sb, "hostLb",     hostLb);
		appendZone(sb, "joinerLb",   joinerLb);
		return sha256(sb.toString());
	}

	/**
	 * Digests the whole board at the start of a turn: zone sizes on both sides plus the actual
	 * contents of the field. Cheap enough to run every turn, and catches drift as soon as it
	 * appears rather than at whatever later point it first becomes visible.
	 *
	 * @param localIsHost whether P1 on this client is the host
	 */
	static String ofTurnStart(MainWindow mw, boolean localIsHost, int turn) {
		StringBuilder sb = new StringBuilder();
		sb.append("turn=").append(turn).append('\n');
		appendSide(sb, "host",   mw, localIsHost);
		appendSide(sb, "joiner", mw, !localIsHost);
		return sha256(sb.toString());
	}

	/** Appends one player's zones, chosen by whether they sit in the P1 or P2 seat here. */
	private static void appendSide(StringBuilder sb, String label, MainWindow mw, boolean isP1Seat) {
		GameState state = mw.gameState;
		sb.append(label)
		  .append(" deck=").append(isP1Seat ? state.getP1MainDeck().size()  : state.getP2MainDeck().size())
		  .append(" hand=").append(isP1Seat ? state.getP1Hand().size()      : state.getP2Hand().size())
		  .append(" break=").append(isP1Seat ? state.getP1BreakZone().size(): state.getP2BreakZone().size())
		  .append(" damage=").append(isP1Seat ? state.getP1DamageZone().size() : state.getP2DamageZone().size())
		  .append('\n');
		appendZone(sb, label + "Forwards", isP1Seat ? mw.p1ForwardCards : mw.p2ForwardCards);
		appendZone(sb, label + "Monsters", isP1Seat ? mw.p1MonsterCards : mw.p2MonsterCards);
		CardData[] backups = isP1Seat ? mw.p1BackupCards : mw.p2BackupCards;
		sb.append(label).append("Backups\n");
		for (CardData c : backups) sb.append(c == null ? "-" : c.name()).append('\n');
	}

	private static void appendZone(StringBuilder sb, String label, Collection<CardData> cards) {
		sb.append(label).append('[').append(cards.size()).append("]\n");
		for (CardData c : cards) {
			sb.append(c.name()).append('|').append(c.cost()).append('|')
			  .append(c.element()).append('|').append(c.type()).append('\n');
		}
	}

	private static String sha256(String input) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for (byte b : digest) hex.append(String.format("%02x", b));
			return hex.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}
}
