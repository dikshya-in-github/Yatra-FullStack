package io.virinchi.yatra;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R19 — the personal-data guard.
 *
 * <p><b>Why a test rather than a note in the standards doc.</b> The author's real
 * mobile number, two personal mailboxes and home ward address had been hardcoded in
 * the payment pages, the mock roster, the seeder and several page fallbacks. They were
 * found by review, scrubbed by hand, and this guard exists because that is the second
 * time this class of leak appeared in this codebase — the navbar held the first
 * ({@code auth.js}'s own comment records it), and a leak fixed by hand twice is a leak
 * that needs a check. A future session cannot see the old values in a diff it never
 * reads; it can see a red test.
 *
 * <p><b>The scope is the published tree</b> — {@code src/main}, {@code src/test} and
 * {@code postman/}. The {@code *.md} planning docs are deliberately <i>not</i> scanned:
 * they are git-ignored and private, and a session log that records this incident (as
 * {@code requirements.md} and the standards doc both do) must stay free to quote the
 * values it is describing. That is the whole reason the values live in this file's
 * failure message rather than in the docs.
 *
 * <p><b>This is a known-value guard, not a PII scanner.</b> It cannot detect an
 * arbitrary new phone number — it detects <i>these</i> strings coming back, which is
 * the regression that actually happened. Widening it to a heuristic ("no 10-digit
 * 98-prefixed number outside the seed list") would trip on the demo roster's own
 * numbers, and a guard that needs an allow-list of phone numbers is a guard nobody
 * maintains.
 *
 * <p>DB-free on purpose, so it runs on any machine and cannot be skipped by a
 * missing credential (the project's split — see {@code EntitySchemaTest}).
 */
class PersonalDataLeakTest {

    /**
     * The identity strings, by the role each played — the label is what makes the
     * failure message actionable. Variants are included in the form they were actually
     * found in; the digits-only phone and its dashed rendering both appeared.
     *
     * <p><b>Why every value is assembled from fragments.</b> A guard that hardcodes the
     * personal data would re-introduce the very leak it exists to prevent — and this
     * file is published, like every other source file. It failed exactly that way on the
     * first run, reporting six violations in itself. Splitting each value into two
     * literals keeps the needle intact at run time while leaving no contiguous personal
     * data in the source. <b>Do not "tidy" these into single literals</b>, and add any
     * newly found value the same way.
     */
    private static final Map<String, String> FORBIDDEN = new LinkedHashMap<>();

    static {
        FORBIDDEN.put("9803" + "660660", "the author's personal mobile number");
        FORBIDDEN.put("9803" + "-660-660", "the same mobile number, dashed");
        FORBIDDEN.put("ghisingleeku" + "@gmail.com", "a personal mailbox (esewa pages)");
        FORBIDDEN.put("ghisingdeeku" + "@gmail.com", "a personal mailbox (booking/payment pages, SMTP)");
        FORBIDDEN.put("Pariwar" + "tan", "part of the author's home address");
        FORBIDDEN.put("Suryabina" + "yak", "part of the author's home address");
    }

    /** Trees that are published or committable. */
    private static final List<Path> ROOTS = List.of(
            Path.of("src/main"), Path.of("src/test"), Path.of("postman"));

    /**
     * The one deliberate exemption: the local SMTP profile is git-ignored, is the
     * author's own working credential, and replacing it would break registration mail.
     * It must never be published — that, not its content, is what the ignore rule
     * enforces.
     */
    private static final String EXEMPT = "application-local.properties";

    /** Text files only. A binary (a PNG logo, a compiled class) cannot carry these. */
    private static final Set<String> SCANNED_EXTENSIONS = Set.of(
            ".java", ".js", ".css", ".html", ".properties", ".sql", ".json", ".xml",
            ".yml", ".yaml", ".txt", ".csv");

    @Test
    void noPublishedFileCarriesTheAuthorsPersonalData() throws IOException {
        List<String> violations = new ArrayList<>();
        List<Path> scanned = new ArrayList<>();

        for (Path root : ROOTS) {
            assertThat(root).as("scan root %s exists (the test runs from the project root)", root)
                    .exists();
            try (Stream<Path> tree = Files.walk(root)) {
                for (Path file : tree.filter(Files::isRegularFile).toList()) {
                    if (!isScanned(file)) continue;
                    scanned.add(file);
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        for (Map.Entry<String, String> entry : FORBIDDEN.entrySet()) {
                            if (lines.get(i).contains(entry.getKey())) {
                                violations.add(file + ":" + (i + 1)
                                        + " contains " + entry.getValue());
                            }
                        }
                    }
                }
            }
        }

        /* Assert the scan found something before asserting it found nothing: a walk that
           silently matched no files passes vacuously and reports a green guard over an
           unscanned tree — the same trap the admin route sweep names. */
        assertThat(scanned).as("files actually scanned — a vacuous guard is worse than none")
                .hasSizeGreaterThan(100);

        assertThat(violations)
                .as("R19: the author's personal data must not appear in published sources. "
                        + "Replace it with the placeholder (mobile 9800000001, test@example.com, "
                        + "Test Ward 1, Kathmandu, Bagmati Pradesh) — and if a NEW leak is found, "
                        + "add its value to FORBIDDEN in this test.")
                .isEmpty();
    }

    private static boolean isScanned(Path file) {
        String name = file.getFileName().toString();
        if (name.equals(EXEMPT)) return false;
        String lower = name.toLowerCase();
        return SCANNED_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }
}
