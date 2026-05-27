package shufflingway;

import java.util.Set;

/**
 * A passive always-on grant: "The [Job X / Category Y] [type] [other than Z] you control
 * gain +N power [and Trait]."
 *
 * <p>Active while the owning card is on the field, scoped to the same player's side.
 * When the owning card leaves the field the bonus ceases immediately.
 */
public record FieldPowerGrant(
        String  jobFilter,       // null = any job
        String  categoryFilter,  // null = any category
        boolean inclForwards,
        boolean inclBackups,
        boolean inclMonsters,
        String  exceptCardName,  // excluded target name ("other than X"); "" = none
        int     powerBonus,
        Set<CardData.Trait> grantedTraits
) {
    public FieldPowerGrant {
        grantedTraits = Set.copyOf(grantedTraits);
        if (exceptCardName == null) exceptCardName = "";
    }

    /**
     * Returns {@code true} if this grant applies to {@code card}.
     * Does not check which side of the field the card is on — callers must ensure
     * the card and the grant source belong to the same player.
     */
    public boolean appliesToCard(CardData card) {
        if (!exceptCardName.isEmpty() && CardFilters.meetsCardNameFilter(card, exceptCardName)) return false;
        boolean typeOk = (inclForwards && card.isForward())
                      || (inclBackups  && card.isBackup())
                      || (inclMonsters && (card.isMonster() || card.alsoCountsAsMonster()));
        if (!typeOk) return false;
        return CardFilters.meetsJobFilter(card, jobFilter)
            && CardFilters.meetsCategoryFilter(card, categoryFilter);
    }
}
