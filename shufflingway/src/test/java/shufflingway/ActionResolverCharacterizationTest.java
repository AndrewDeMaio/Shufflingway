package shufflingway;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Locks {@link ActionResolver}'s behaviour over the whole card corpus so it can be
 * restructured safely.
 *
 * <p>{@code ActionResolver} dispatches through long ordered if-chains in which position is
 * load-bearing: matchers use {@code find()}, so a general pattern placed ahead of a specific
 * one silently claims text that belongs to the specific one. Only a handful of those ordering
 * constraints are documented in comments, which makes the chains impossible to reorganise by
 * inspection. This test records what the resolver actually decides for every ability on every
 * card, so any behaviour change during a refactor shows up as a diff rather than as a bug
 * discovered later.
 *
 * <p>For each ability it records the parse outcome, the matched pattern name and the full
 * description — the three observable outputs — including thrown exceptions, so current
 * failure behaviour is pinned too.
 *
 * <p>Regenerate deliberately, after reviewing the diff, with:
 * <pre>  mvn test -Dtest=ActionResolverCharacterizationTest -Dcharacterization.regenerate=true</pre>
 *
 * <p>The card database is not checked in, so this test skips when it is absent.
 */
public class ActionResolverCharacterizationTest {

	private static final Path GOLDEN =
			Path.of("src", "test", "resources", "actionresolver-characterization.txt");

	/** Written next to the golden file's target dir on mismatch, for diffing. */
	private static final Path ACTUAL =
			Path.of("target", "actionresolver-characterization.actual.txt");

	private static final int MAX_REPORTED_DIFFS = 25;

	@Test
	void resolverBehaviourMatchesGoldenFile() throws Exception {
		List<CardCorpus.Entry> corpus = CardCorpus.load();
		if (corpus.isEmpty()) {
			System.out.println("[characterization] " + CardCorpus.dbFile()
					+ " not found or empty — skipping.");
			return;
		}

		List<String> actual = record(corpus);

		if (Boolean.getBoolean("characterization.regenerate") || !Files.exists(GOLDEN)) {
			Files.createDirectories(GOLDEN.getParent());
			Files.write(GOLDEN, actual, StandardCharsets.UTF_8);
			System.out.printf("[characterization] wrote %s (%d records from %d cards)%n",
					GOLDEN, actual.size() - 1, corpus.size());
			return;
		}

		List<String> expected = Files.readAllLines(GOLDEN, StandardCharsets.UTF_8);
		if (expected.equals(actual)) return;

		Files.createDirectories(ACTUAL.getParent());
		Files.write(ACTUAL, actual, StandardCharsets.UTF_8);
		fail(describeDiff(expected, actual));
	}

	/** One line per ability, plus a leading header so a truncated file is obvious. */
	private static List<String> record(List<CardCorpus.Entry> corpus) {
		List<String> out = new ArrayList<>();
		int abilities = 0;
		List<String> body = new ArrayList<>();

		for (CardCorpus.Entry entry : corpus) {
			CardData card = entry.card();
			String type = card.type();

			List<ActionAbility> actions = card.actionAbilities();
			for (int i = 0; i < actions.size(); i++) {
				final String text = actions.get(i).effectText();
				body.add(line(entry.serial(), "action", i,
						call(() -> ActionResolver.parse(text, card) != null ? "parsed" : "unparsed"),
						call(() -> ActionResolver.matchedPatternName(text, card)),
						call(() -> ActionResolver.fullDescription(text, card))));
				abilities++;
			}

			List<AutoAbility> autos = card.autoAbilities();
			for (int i = 0; i < autos.size(); i++) {
				final String text = autos.get(i).effectText();
				body.add(line(entry.serial(), "auto", i,
						call(() -> ActionResolver.parse(text, card) != null ? "parsed" : "unparsed"),
						call(() -> ActionResolver.matchedPatternName(text, card)),
						call(() -> ActionResolver.fullDescription(text, card))));
				abilities++;
			}

			List<FieldAbility> fields = card.fieldAbilities();
			for (int i = 0; i < fields.size(); i++) {
				FieldAbility fa = fields.get(i);
				body.add(line(entry.serial(), "field", i,
						call(() -> FieldAbilityParsingTest.isFieldAbilityRecognized(fa, card, type)
								? "parsed" : "unparsed"),
						"-",
						call(() -> FieldAbilityParsingTest.describeFieldAbility(fa, card, type))));
				abilities++;
			}
		}

		out.add("# ActionResolver characterization: " + corpus.size()
				+ " cards, " + abilities + " abilities");
		out.addAll(body);
		return out;
	}

	private static String line(String serial, String kind, int idx,
	                           String parsed, String pattern, String desc) {
		return String.join("\t", serial, kind + "#" + idx, parsed, norm(pattern), norm(desc));
	}

	/**
	 * Runs one resolver call, converting a thrown exception into a recorded value so current
	 * failure behaviour is pinned alongside success behaviour.
	 */
	private static String call(ThrowingSupplier body) {
		try {
			String v = body.get();
			return v == null ? "(null)" : v;
		} catch (Exception | StackOverflowError e) {
			return "!!" + e.getClass().getSimpleName();
		}
	}

	/** Collapses whitespace so every record stays on one tab-separated line. */
	private static String norm(String s) {
		if (s == null) return "(null)";
		String v = s.replaceAll("\\s+", " ").trim();
		return v.isEmpty() ? "(empty)" : v;
	}

	private static String describeDiff(List<String> expected, List<String> actual) {
		StringBuilder sb = new StringBuilder();
		sb.append("ActionResolver behaviour changed against ").append(GOLDEN).append('\n');
		sb.append("  expected ").append(expected.size())
		  .append(" records, actual ").append(actual.size()).append('\n');
		sb.append("  full output written to ").append(ACTUAL).append('\n');

		int shown = 0;
		for (int i = 0; i < Math.max(expected.size(), actual.size()); i++) {
			String e = i < expected.size() ? expected.get(i) : "(missing)";
			String a = i < actual.size() ? actual.get(i) : "(missing)";
			if (e.equals(a)) continue;
			if (shown++ >= MAX_REPORTED_DIFFS) {
				sb.append("  ... further differences suppressed\n");
				break;
			}
			sb.append("  line ").append(i + 1).append('\n')
			  .append("    expected: ").append(e).append('\n')
			  .append("    actual  : ").append(a).append('\n');
		}
		return sb.toString();
	}

	@FunctionalInterface
	private interface ThrowingSupplier {
		String get() throws Exception;
	}

	/** Convenience for regenerating outside Maven; see the class javadoc for the usual route. */
	public static void main(String[] args) throws IOException, Exception {
		System.setProperty("characterization.regenerate", "true");
		new ActionResolverCharacterizationTest().resolverBehaviourMatchesGoldenFile();
	}
}
