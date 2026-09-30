package shufflingway;

import java.util.List;
import java.util.Set;

/**
 * One copy of {@link MainWindow}'s {@code lastCastPayment*} record — how a card was paid for —
 * taken so it can be put back later. Not to be confused with {@link CastPayment}, the payment a
 * player chooses, as it crosses the wire; this is what the engine remembers once it is paid.
 *
 * <p>The record on {@code MainWindow} is "the last cast", which is the right answer for a
 * Character, whose arrival-time gates read it at once. A Summon is read when it resolves, and a
 * cast made in response on top of it has overwritten the record by then. So a Summon's own
 * record is taken as it goes on the Stack ({@code MainWindow.pushSummonOnStack}) and restored for
 * its resolution: 6-074C Cactuar's "for each CP of a different Element you paid to cast Cactuar",
 * the Opus 16 "paid with CP of 3 or more different Elements" Summons and the Opus 22 "only
 * produced by Backups" ones all read the record, and all read their own cast this way.
 *
 * <p>{@link #unpaid} is the record of a Summon that went on the Stack without being paid for —
 * cast for free by an effect, or an EX Burst — so it answers "no CP" rather than whatever the
 * previous cast paid.
 */
record CastPaymentRecord(
		CardData       card,
		int            distinctElements,
		Set<String>    elements,
		Set<String>    actualElements,
		int            discardCount,
		int            discardTotalCost,
		List<CardData> discards,
		boolean        paidByBackupsOnly,
		List<CardData> backups) {

	CastPaymentRecord {
		elements       = Set.copyOf(elements);
		actualElements = Set.copyOf(actualElements);
		discards       = List.copyOf(discards);
		backups        = List.copyOf(backups);
	}

	/** The record as it stands on {@code mw} now. */
	static CastPaymentRecord capture(MainWindow mw) {
		return new CastPaymentRecord(mw.lastCastPaymentCard, mw.lastCastPaymentDistinctElements,
				mw.lastCastPaymentElements, mw.lastCastActualPaymentElements,
				mw.lastCastPaymentDiscardCount, mw.lastCastPaymentDiscardTotalCost,
				mw.lastCastPaymentDiscards, mw.lastCastWasPaidByBackupsOnly, mw.lastCastPaymentBackups);
	}

	/** {@code card} put on the Stack with nothing paid for it; {@code null} for no card at all. */
	static CastPaymentRecord unpaid(CardData card) {
		return new CastPaymentRecord(card, 0, Set.of(), Set.of(), 0, 0, List.of(), false, List.of());
	}

	/** Writes this record back onto {@code mw}. */
	void restore(MainWindow mw) {
		mw.lastCastPaymentCard             = card;
		mw.lastCastPaymentDistinctElements = distinctElements;
		mw.lastCastPaymentElements.clear();
		mw.lastCastPaymentElements.addAll(elements);
		mw.lastCastActualPaymentElements.clear();
		mw.lastCastActualPaymentElements.addAll(actualElements);
		mw.lastCastPaymentDiscardCount     = discardCount;
		mw.lastCastPaymentDiscardTotalCost = discardTotalCost;
		mw.lastCastPaymentDiscards.clear();
		mw.lastCastPaymentDiscards.addAll(discards);
		mw.lastCastWasPaidByBackupsOnly    = paidByBackupsOnly;
		mw.lastCastPaymentBackups.clear();
		mw.lastCastPaymentBackups.addAll(backups);
	}
}
