package shufflingway;

/**
 * A "always-on" passive ability that is active while the card is on the field.
 *
 * <p>Field abilities have no activation cost and no event trigger — their effects
 * are continuously applied to the game state as long as the owning card remains on
 * the field.  When the card leaves the field the ability ceases to be active.
 *
 * <p>Examples:
 * <ul>
 *   <li>"All Forwards lose Haste."</li>
 *   <li>"Forwards cannot gain Haste."</li>
 * </ul>
 *
 * <p>Field abilities are identified by exclusion: any text segment in a card's
 * {@code text_en} that is not a trait keyword, an Auto ability ("When X Y, Z"),
 * an Action ability (cost : effect), or an alternate-cost declaration is treated
 * as a Field ability.
 */
/**
 * @param damageThreshold the number of damage counters the controlling player must have in their
 *                        Damage Zone before this ability is active; {@code 0} means always active.
 */
public record FieldAbility(String effectText, int damageThreshold) {}
