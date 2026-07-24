package shufflingway;

/**
 * A special ability activated this turn, recorded for Gogo's "Mimic" special ability
 * ("Use 1 special ability that a Character has used this turn ... without paying the cost").
 *
 * @param source  the Character that used the ability (its name is what a mimic substitutes for)
 * @param ability the activated special ability (its {@code abilityName} and {@code effectText})
 */
public record UsedSpecialAbility(CardData source, ActionAbility ability) {}
