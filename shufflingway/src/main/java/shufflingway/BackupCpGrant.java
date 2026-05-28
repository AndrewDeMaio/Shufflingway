package shufflingway;

/**
 * A field ability that grants "can produce CP of any Element" to matching Backups.
 *
 * <p>Active while the source card is on the field.  Null filter fields mean "no restriction":
 * a {@code BackupCpGrant(null, null, null)} applies to every Backup.
 *
 * <p>Examples:
 * <ul>
 *   <li>"Backups you control can produce CP of any Element."     → all three nulls</li>
 *   <li>"The Job Knight Backups you control can produce CP …"    → jobFilter = "Knight"</li>
 *   <li>"The Category VI Backups you control can produce CP …"   → categoryFilter = "VI"</li>
 *   <li>"The Earth Backups you control can produce CP …"         → elementFilter = "Earth"</li>
 * </ul>
 *
 * @param jobFilter      specific job name, or {@code null} for any job
 * @param categoryFilter specific category substring, or {@code null} for any category
 * @param elementFilter  specific element name, or {@code null} for any element
 */
public record BackupCpGrant(String jobFilter, String categoryFilter, String elementFilter) {

    /** Returns true if this grant applies to {@code backup}. */
    public boolean appliesTo(CardData backup) {
        return CardFilters.meetsJobFilter(backup, jobFilter)
            && CardFilters.meetsCategoryFilter(backup, categoryFilter)
            && CardFilters.meetsElementFilter(backup, elementFilter);
    }
}
