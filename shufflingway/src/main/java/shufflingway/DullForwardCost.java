package shufflingway;

/**
 * An ability cost that requires dulling N forwards currently on the field.
 * Represents a "Dull N [condition] [element] Forward(s)" or
 * "Dull N [condition] Card Name X Forward" cost phrase.
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code "Dull 1 active Forward"} → {@code DullForwardCost(1, "active", null, null)}
 *   <li>{@code "Dull 1 active Card Name Cecil Forward"} → {@code DullForwardCost(1, "active", null, "Cecil")}
 * </ul>
 *
 * @param cardName non-null → must dull the named card specifically; null → any matching element/type
 */
public record DullForwardCost(int count, String condition, String element, String cardName) {}
