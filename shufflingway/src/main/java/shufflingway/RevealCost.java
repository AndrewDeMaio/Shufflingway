package shufflingway;

/**
 * A "reveal N [card type] in your hand" payment cost on an Action Ability — Rinoa 18-097R's
 * Angelo Cannon, {@code 《S》, reveal 1 Forward in your hand:}.
 *
 * <p>Unlike the discard and put-into-the-Break-Zone costs it sits beside, revealing spends nothing:
 * the card is shown and stays in hand. What the cost is for is the effect that follows, which reads
 * a property off whatever was revealed — so the payment has to record its choice, not just make it.
 * {@code MainWindow.currentRevealedForwardPower} is where that lands.
 *
 * @param count    number of cards to reveal
 * @param cardType "Forward", "Backup", "Monster", "Summon", "Character", or {@code null} for any card
 */
public record RevealCost(int count, String cardType) {

    /** Whether {@code card} is eligible to pay this cost. */
    public boolean matches(CardData card) {
        if (card == null) return false;
        if (cardType == null) return true;
        return switch (cardType.toLowerCase(java.util.Locale.ROOT)) {
            case "forward"   -> card.isForward();
            case "backup"    -> card.isBackup();
            case "monster"   -> card.isMonster();
            case "summon"    -> card.isSummon();
            case "character" -> card.isForward() || card.isBackup() || card.isMonster();
            default          -> true;
        };
    }
}
