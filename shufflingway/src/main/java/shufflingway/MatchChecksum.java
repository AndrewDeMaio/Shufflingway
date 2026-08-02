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
