package shufflingway;

/**
 * A single "remove N [Name] Counter(s) from [CardName]" action-ability cost.
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code remove 2 Item Counters from cardName1}
 *       → {@code CounterCost("cardName1", "Item", 2)}</li>
 *   <li>{@code remove 1 Shuriken Counter from cardName2}
 *       → {@code CounterCost("cardName2", "Shuriken", 1)}</li>
 *   <li>{@code remove X Arise Counters from cardName3}
 *       → {@code CounterCost("cardName3", "Arise", 0, true)}</li>
 * </ul>
 *
 * <p>A {@link #variable} cost is the {@code X} form (Lenna 12-109L, Leo 13-067L): the player chooses
 * how many to remove when the ability is activated, and that number becomes the {@code X} the effect
 * reads — the same {@code xValue} a {@code 《X》} CP cost produces for Zemus 5-108L, which prints the
 * identical effect. {@link #count} is 0 and unused in that case; what gates activation is having at
 * least one counter to spend.
 */
public record CounterCost(
        String  cardName,     // card that the counters must be on (typically the source card)
        String  counterName,  // name of the counter type (e.g. "Item", "Shuriken", "Arise")
        int     count,        // number of counters to remove; unused when variable
        boolean variable      // true = "remove X …", the player picks the amount at activation
) {
    /** Convenience constructor for the fixed-count form. */
    public CounterCost(String cardName, String counterName, int count) {
        this(cardName, counterName, count, false);
    }
}
