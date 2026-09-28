package shufflingway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Keeps {@link ActionResolver#dispatch}'s site labels honest.
 *
 * <p>Every name {@link ActionResolver#matchedPatternName} reports is looked up from the label of the
 * site that claimed the text, so a label that does not match its call names the wrong parser for
 * every card that site claims. Each label is written beside its call by hand; this reads the source
 * and checks the two agree, the way the characterization test checks behaviour.
 */
class ActionResolverDispatchTest {

	private static final Path RESOLVER = Path.of("src/main/java/shufflingway/ActionResolver.java");

	private static final Pattern CALL =
			Pattern.compile("^\\s*result\\s*=\\s*(?:\\w+\\.)?tryParse(\\w+)\\(.*\\);\\s*$");
	private static final Pattern CLAIM =
			Pattern.compile("^\\s*if\\s*\\(result\\s*!=\\s*null\\)\\s*return\\s+claim\\(\"(\\w+)\",.*$");

	/** dispatch()'s body, declaration through closing brace. */
	private static List<String> dispatchBody() throws Exception {
		List<String> lines = Files.readAllLines(RESOLVER, StandardCharsets.UTF_8);
		int start = -1;
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).contains("static Dispatch dispatch(String effectText, CardData source, int xValue)")) {
				start = i;
				break;
			}
		}
		assertTrue(start >= 0, "dispatch() not found in " + RESOLVER);
		int depth = 0;
		boolean opened = false;
		for (int j = start; j < lines.size(); j++) {
			String l = lines.get(j);
			depth += l.chars().filter(c -> c == '{').count() - l.chars().filter(c -> c == '}').count();
			if (l.indexOf('{') >= 0) opened = true;
			if (opened && depth <= 0) return lines.subList(start, j + 1);
		}
		throw new AssertionError("unterminated dispatch() body");
	}

	@Test
	void everyClaimIsLabelledWithTheParserItFollows() throws Exception {
		List<String> body = dispatchBody();
		List<String> mismatches = new ArrayList<>();
		String pending = null;
		int claims = 0;
		for (String line : body) {
			Matcher call = CALL.matcher(line);
			if (call.matches()) {
				pending = call.group(1);
				continue;
			}
			Matcher claim = CLAIM.matcher(line);
			if (claim.matches()) {
				claims++;
				if (!claim.group(1).equals(pending))
					mismatches.add("claim(\"" + claim.group(1) + "\") after tryParse" + pending);
				pending = null;
			}
		}
		assertTrue(claims > 400, "found only " + claims + " claims; has dispatch() changed shape?");
		assertEquals(List.of(), mismatches);
	}

	@Test
	void everyAliasNamesASiteThatExists() throws Exception {
		Set<String> labels = new LinkedHashSet<>();
		for (String line : dispatchBody()) {
			Matcher claim = CLAIM.matcher(line);
			if (claim.matches()) labels.add(claim.group(1));
		}
		List<String> orphans = new ArrayList<>();
		for (String site : ActionResolver.SITE_ALIASES.keySet())
			if (!labels.contains(site)) orphans.add(site);
		assertEquals(List.of(), orphans, "aliases for sites dispatch() no longer has");
	}

	/**
	 * fullDescription() keys each describer on {@code site.equals("X")}. A label that matches no
	 * claim never fires, and its site silently falls back to the bare name.
	 */
	@Test
	void everyDescribedSiteExists() throws Exception {
		Set<String> labels = new LinkedHashSet<>(List.of("SentenceFallback", "HasAllElements"));
		for (String line : dispatchBody()) {
			Matcher claim = CLAIM.matcher(line);
			if (claim.matches()) labels.add(claim.group(1));
		}
		String source = Files.readString(RESOLVER, StandardCharsets.UTF_8);
		Matcher described = Pattern.compile("site\\.equals\\(\"(\\w+)\"\\)").matcher(source);
		List<String> unknown = new ArrayList<>();
		int count = 0;
		while (described.find()) {
			count++;
			if (!labels.contains(described.group(1))) unknown.add(described.group(1));
		}
		assertTrue(count > 50, "found only " + count + " describers; has fullDescription() changed shape?");
		assertEquals(List.of(), unknown, "describers keyed on sites dispatch() never claims");
	}
}
