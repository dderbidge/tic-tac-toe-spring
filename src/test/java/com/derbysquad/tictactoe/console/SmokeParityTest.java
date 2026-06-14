package com.derbysquad.tictactoe.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.derbysquad.tictactoe.domain.Ai;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Parity port of the C# PowerShell smoke checklist
 * ({@code ../legacy-csharp/console/tests/smoke.ps1}).
 *
 * <p>Each scenario pipes the original test-input file into the ported console in-process
 * and asserts the same output-substring counts the PowerShell script asserted.
 *
 * <p><b>Seed note:</b> the original ran with {@code TICTACTOE_SEED=13}. Only the
 * {@code captain} scenario depends on the RNG (the AI's random opener), and
 * {@code java.util.Random} produces a different sequence than .NET's {@code Random}.
 * Java seed {@code 1536} reproduces the same benign opener cell (C2) that .NET's seed 13
 * produced, so the two forced blocks (A3, then C1) occur identically. All other
 * scenarios are RNG-independent (hot-seat is human-only; Warlord is deterministic minimax).
 */
class SmokeParityTest {

    /** Mirrors the PowerShell global TICTACTOE_SEED, adapted to java.util.Random. */
    private static final long SEED = 1536L;

    record Scenario(String name, String resource, Map<String, Integer> expects) {
    }

    static List<Scenario> scenarios() {
        return List.of(
                new Scenario("row win", "rowwin.txt", Map.of(
                        "The Ascendant holds the line", 1,
                        "Stalemates 0", 1)),
                new Scenario("column win", "colwin.txt", Map.of(
                        "The Ascendant holds the line", 1)),
                new Scenario("diagonal win", "diagwin.txt", Map.of(
                        "The Ascendant holds the line", 1)),
                new Scenario("draw", "draw.txt", Map.of(
                        "Stalemate\\.", 1,
                        "Stalemates 1", 1)),
                new Scenario("invalid input recovery", "invalid.txt", Map.of(
                        "Invalid coordinates", 3,
                        "already claimed", 1,
                        "holds the line", 1)),
                new Scenario("Captain blocks both threats", "captain.txt", Map.of(
                        "The Crimson Vanguard claims sector A3", 1,
                        "The Crimson Vanguard claims sector C1", 1)),
                new Scenario("Warlord (column-A attack) never loses", "warlord.txt", Map.of(
                        "The Ascendant holds the line", 0)),
                new Scenario("Warlord (center attack) never loses", "warlord-center.txt", Map.of(
                        "The Ascendant holds the line", 0)),
                new Scenario("Warlord (corner attack) never loses", "warlord-corner.txt", Map.of(
                        "The Ascendant holds the line", 0)),
                new Scenario("quit from menu (no board)", "quit.txt", Map.of(
                        "┌───┬───┬───┐", 0,
                        "ORDER VS REBELLION", 1)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    @DisplayName("console output matches the original smoke checklist")
    void scenarioMatchesOriginalCounts(Scenario scenario) throws IOException {
        String output = runConsole(scenario.resource());
        scenario.expects().forEach((pattern, expectedCount) -> {
            int actual = countMatches(output, pattern);
            assertEquals(expectedCount, actual,
                    () -> "scenario '" + scenario.name() + "': pattern '" + pattern
                            + "' = " + actual + " (expected " + expectedCount + ")\n--- output ---\n" + output);
        });
    }

    /** Drives the ported console in-process with the given input file as stdin, returns captured stdout. */
    private static String runConsole(String resource) throws IOException {
        String input = readResource("/smoke/" + resource);
        BufferedReader in = new BufferedReader(new StringReader(input));
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);

        Renderer renderer = new Renderer(out, in, new ConsolePalette(false)); // color off, like a redirected stream
        GameConsole console = new GameConsole(renderer, out, new Ai(new Random(SEED)));
        console.run();

        out.flush();
        return captured.toString(StandardCharsets.UTF_8);
    }

    private static int countMatches(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        int count = 0;
        while (m.find()) {
            count++;
        }
        return count;
    }

    private static String readResource(String path) throws IOException {
        try (InputStream is = SmokeParityTest.class.getResourceAsStream(path)) {
            assertNotNull(is, "missing test resource: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
