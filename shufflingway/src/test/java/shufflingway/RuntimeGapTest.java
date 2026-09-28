package shufflingway;

import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Runs every ability {@link ActionResolver#parse} claims in the corpus against a
 * {@link RecordingContext}, under both boolean answers, and fails on any run that logs a gap
 * ("not yet implemented", "unrecognized") or throws — outside an allowlist that may only shrink.
 *
 * <p>This is the runtime complement to the characterization test, which is blind here: a parser
 * can claim a text, the golden file can record a name and a description for it, and the branch it
 * lands in can still do nothing. The Choose chain resolves the choice and then logs "followup not
 * yet implemented", and neither the parse outcome nor the description moves.
 *
 * <p>The allowlist lives in {@code src/test/resources/runtime-gaps-allowlist.txt}, gitignored for
 * the same reason as the golden file: it carries card serials. It is keyed on serial and slot
 * ({@code auto#0}, {@code action#1}, {@code summon}, {@code summon(paid)}, …); the text and the
 * logged gaps under each key are there for reading and are not compared. The test fails when
 *
 * <ul>
 *   <li>an ability outside the list logs a gap — a new one; fix it, or it is a parser that now
 *       claims text it cannot run, which is the thing to fix; and</li>
 *   <li>an ability on the list no longer does — it was fixed; take it off with
 *       <pre>  mvn test -Dtest=RuntimeGapTest -DruntimeGaps.shrink=true</pre>
 *       which drops fixed entries and never adds one.</li>
 * </ul>
 *
 * <p>When the list is absent it is written from the current corpus and the test passes. The card
 * database is not checked in, so the test skips when it is absent.
 */
public class RuntimeGapTest {

	static final Path ALLOWLIST = Path.of("src", "test", "resources", "runtime-gaps-allowlist.txt");

	private static final Pattern GAP =
			Pattern.compile("(?i)not yet implemented|unrecognized|unrecognised|^" + Pattern.quote(RecordingContext.THREW));

	/** One ability whose run logged a gap. {@code key} is {@code serial \t slot}. */
	record Gap(String key, String name, String text, List<String> messages) {
		String render() {
			StringBuilder sb = new StringBuilder(key).append('\t').append(name).append('\n');
			sb.append('\t').append(oneLine(text)).append('\n');
			for (String m : messages) sb.append("\t-> ").append(oneLine(m)).append('\n');
			return sb.toString();
		}

		/** A newline would start a line the allowlist reader takes for a key. */
		private static String oneLine(String s) {
			return s.replaceAll("\\R", " ");
		}
	}

	/** The result of one sweep: how many abilities ran, and the ones that logged a gap. */
	record Sweep(int abilitiesRun, Map<String, Gap> gaps) {}

	@Test
	void noRuntimeGapsOutsideTheAllowlist() throws Exception {
		List<CardCorpus.Entry> corpus = CardCorpus.load();
		if (corpus.isEmpty()) {
			System.out.println("[runtime-gaps] " + CardCorpus.dbFile() + " not found or empty — skipping.");
			return;
		}
		Sweep sweep = sweep(corpus);

		if (!Files.exists(ALLOWLIST)) {
			write(sweep.gaps().values());
			System.out.printf("[runtime-gaps] wrote %s (%d of %d abilities)%n",
					ALLOWLIST, sweep.gaps().size(), sweep.abilitiesRun());
			return;
		}

		Set<String> allowed = readKeys();
		List<Gap> added = new ArrayList<>();
		for (Gap g : sweep.gaps().values()) if (!allowed.contains(g.key())) added.add(g);
		List<String> fixed = new ArrayList<>();
		for (String k : allowed) if (!sweep.gaps().containsKey(k)) fixed.add(k);

		if (Boolean.getBoolean("runtimeGaps.shrink") && !fixed.isEmpty()) {
			List<Gap> kept = new ArrayList<>();
			for (Gap g : sweep.gaps().values()) if (allowed.contains(g.key())) kept.add(g);
			write(kept);
			System.out.printf("[runtime-gaps] dropped %d fixed entries from %s; %d remain%n",
					fixed.size(), ALLOWLIST, kept.size());
			fixed.clear();
		}

		System.out.printf("[runtime-gaps] %d abilities run, %d log a gap, %d allowlisted%n",
				sweep.abilitiesRun(), sweep.gaps().size(), allowed.size());
		if (added.isEmpty() && fixed.isEmpty()) return;

		StringBuilder sb = new StringBuilder();
		if (!added.isEmpty()) {
			sb.append(added.size()).append(" ability(ies) log a runtime gap and are not on the allowlist.\n")
			  .append("  A parser claims text it cannot run. Fix the branch; do not add these to the list.\n\n");
			for (Gap g : added) sb.append(g.render()).append('\n');
		}
		if (!fixed.isEmpty()) {
			sb.append(fixed.size()).append(" allowlisted ability(ies) no longer log a gap. Take them off with\n")
			  .append("  mvn test -Dtest=RuntimeGapTest -DruntimeGaps.shrink=true\n\n");
			for (String k : fixed) sb.append('\t').append(k).append('\n');
		}
		fail(sb.toString());
	}

	/** Every parsed ability in {@code corpus}, run under both boolean answers. */
	static Sweep sweep(List<CardCorpus.Entry> corpus) {
		int run = 0;
		Map<String, Gap> gaps = new LinkedHashMap<>();
		for (CardCorpus.Entry e : corpus) {
			CardData source = e.card();
			Map<String, String> texts = new LinkedHashMap<>();
			for (int i = 0; i < source.autoAbilities().size(); i++)
				texts.put("auto#" + i, source.autoAbilities().get(i).effectText());
			for (int i = 0; i < source.actionAbilities().size(); i++)
				texts.put("action#" + i, source.actionAbilities().get(i).effectText());
			// Gated on type: summonEffect() returns a non-Summon's whole text. An extra-cost clause
			// is run both ways, as the cast path resolves it.
			if ("Summon".equalsIgnoreCase(source.type()) && source.summonEffect() != null
					&& !source.summonEffect().isBlank()) {
				String raw  = source.summonEffect();
				String paid = ActionResolver.applyExtraCostPaid(raw);
				if (paid.equals(raw)) texts.put("summon", raw);
				else {
					texts.put("summon(unpaid)", ActionResolver.stripExtraCostClause(raw));
					texts.put("summon(paid)", paid);
				}
			}
			for (Map.Entry<String, String> t : texts.entrySet()) {
				Consumer<GameContext> fn;
				try { fn = ActionResolver.parse(t.getValue(), source); } catch (Exception ex) { fn = null; }
				if (fn == null) continue;
				run++;
				Set<String> messages = new LinkedHashSet<>();
				for (boolean b : new boolean[]{false, true})
					for (String c : RecordingContext.record(fn, b))
						if (GAP.matcher(c).find()) messages.add(c);
				if (messages.isEmpty()) continue;
				String key = e.serial() + '\t' + t.getKey();
				gaps.put(key, new Gap(key, source.name(), t.getValue(), List.copyOf(messages)));
			}
		}
		return new Sweep(run, gaps);
	}

	private static Set<String> readKeys() throws Exception {
		Set<String> keys = new LinkedHashSet<>();
		for (String line : Files.readAllLines(ALLOWLIST, StandardCharsets.UTF_8)) {
			if (line.isBlank() || line.startsWith("#") || Character.isWhitespace(line.charAt(0))) continue;
			String[] f = line.split("\t", 3);
			if (f.length >= 2) keys.add(f[0] + '\t' + f[1]);
		}
		return keys;
	}

	private static void write(Iterable<Gap> gaps) throws Exception {
		StringBuilder sb = new StringBuilder()
				.append("# Abilities whose run logs a runtime gap. Shrink-only: see RuntimeGapTest.\n")
				.append("# Keyed on the first two tab fields (serial, slot); indented lines are for reading.\n\n");
		for (Gap g : gaps) sb.append(g.render()).append('\n');
		Files.createDirectories(ALLOWLIST.getParent());
		Files.writeString(ALLOWLIST, sb.toString(), StandardCharsets.UTF_8);
	}
}
