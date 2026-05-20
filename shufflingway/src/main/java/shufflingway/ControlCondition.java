package shufflingway;

import java.util.List;

/**
 * Parsed "You can only use this ability if you control [X]" restriction on an action ability.
 *
 * <p>Two modes:
 * <ul>
 *   <li><b>Named mode</b> ({@link #isNamedMode()}): every card name in {@link #requiredCardNames}
 *       must be present on the controlling player's field.</li>
 *   <li><b>Count mode</b>: at least {@link #minCount} field cards that satisfy all of the
 *       optional {@link #element}, {@link #job}, {@link #category}, {@link #cardType}, and
 *       {@link #minPower} filters must be present.  When {@link #exactCount} is {@code true}
 *       the count must be exactly {@link #minCount}, not "or more".  {@link #orCardNames} lists
 *       card-name alternatives that each individual card may satisfy instead of the job/element
 *       filters (e.g. "Job Samurai or Card Name Samurai").</li>
 * </ul>
 */
public record ControlCondition(
        List<String> requiredCardNames, // named mode: all must be on controlling player's field
        int          minCount,          // count mode: minimum matching cards (1 = "a/an")
        boolean      exactCount,        // true = exactly minCount ("only N"), not "N or more"
        String       cardType,          // null | "Forward" | "Monster" | "Backup" | "Character"
        String       element,           // null or element name (e.g. "Fire")
        String       job,               // null or job name (e.g. "Scion of the Seventh Dawn")
        String       category,          // null or category name (e.g. "DFF")
        int          minPower,          // 0 = no power filter; > 0 = card power must be ≥ this
        List<String> orCardNames        // per-card OR alternative: also matches if name is in this list
) {
    public ControlCondition {
        requiredCardNames = List.copyOf(requiredCardNames);
        orCardNames       = List.copyOf(orCardNames);
    }

    /** Returns {@code true} when this condition checks for specific named cards rather than a count. */
    public boolean isNamedMode() { return !requiredCardNames.isEmpty(); }
}
