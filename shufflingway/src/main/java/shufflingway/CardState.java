package shufflingway;

public enum CardState {
    ACTIVE,     // card is undulled and can act
    DULL        // card is dulled and cannot act
    // A Brave attacker used to get its own BRAVE_ATTACKED state. It no longer does: Brave only
    // means the Character does not dull when it attacks, so it simply stays ACTIVE, and what stops
    // it attacking again is MainWindow.hasAttackRemaining — the same per-turn attack count that
    // lets "can attack twice"/"can attack once more this turn" work on Brave Characters at all.
}
