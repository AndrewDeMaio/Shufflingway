package shufflingway;

/**
 * A passive always-on cost reduction: while this card is on the field, the cost to cast matching
 * cards is reduced by {@code amountPerUnit} (optionally scaled by the number of forwards with
 * {@code scalingJobFilter} that the casting player controls).
 *
 * <p>Examples:
 * <ul>
 *   <li>"The cost required to cast [Card Name (Bahamut)] is reduced by 2 (it cannot become 0)."</li>
 *   <li>"The cost required to cast your Water Summons is reduced by 1 (it cannot become 0)."</li>
 *   <li>"The cost required to cast your Summons is reduced by 1 for each Job Summoner forward you control (it cannot become 0)."</li>
 *   <li>"The cost required to cast your Job Kingsglaive is reduced by 1."</li>
 * </ul>
 *
 * @param amountPerUnit   flat reduction amount; if {@code scalingJobFilter} is set, this is
 *                        multiplied by the count of qualifying forwards the caster controls
 * @param floorAtOne      if {@code true}, cost cannot be reduced below 1
 * @param ownerOnly       if {@code true}, only the owning player's casts are reduced;
 *                        if {@code false}, the reduction applies to both players
 * @param inclForwards    applies when the card being cast is a Forward
 * @param inclBackups     applies when the card being cast is a Backup
 * @param inclMonsters    applies when the card being cast is a Monster
 * @param inclSummons     applies when the card being cast is a Summon
 * @param elementFilter   required element on the card being cast; {@code null} = any
 * @param jobFilter       required job on the card being cast; {@code null} = any
 * @param cardNameFilter  required name of the card being cast; {@code null} = any
 * @param scalingJobFilter if non-null, multiply {@code amountPerUnit} by the number of active
 *                         forwards with this job the casting player controls
 */
public record FieldCostReduction(
        int     amountPerUnit,
        boolean floorAtOne,
        boolean ownerOnly,
        boolean inclForwards,
        boolean inclBackups,
        boolean inclMonsters,
        boolean inclSummons,
        String  elementFilter,
        String  jobFilter,
        String  cardNameFilter,
        String  scalingJobFilter
) {
    /** Returns {@code true} if this reduction can apply to {@code card}. */
    public boolean matchesCard(CardData card) {
        if (card.isForward() && !inclForwards) return false;
        if (card.isBackup()  && !inclBackups)  return false;
        if (card.isMonster() && !inclMonsters) return false;
        if (card.isSummon()  && !inclSummons)  return false;
        if (elementFilter  != null && !card.containsElement(elementFilter))         return false;
        if (jobFilter      != null && !card.job().equalsIgnoreCase(jobFilter))       return false;
        if (cardNameFilter != null && !card.name().equalsIgnoreCase(cardNameFilter)) return false;
        return true;
    }

    /** Applies the flat reduction (ignores any scaling job). */
    public int apply(int originalCost) {
        return apply(originalCost, 1);
    }

    /** Applies the reduction scaled by {@code units} (use 1 for non-scaling reductions). */
    public int apply(int originalCost, int units) {
        int reduced = originalCost - amountPerUnit * units;
        return floorAtOne ? Math.max(1, reduced) : Math.max(0, reduced);
    }
}
