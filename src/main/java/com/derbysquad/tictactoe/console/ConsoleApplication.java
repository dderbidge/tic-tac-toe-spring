package com.derbysquad.tictactoe.console;

import com.derbysquad.tictactoe.domain.Ai;
import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * Console entry point. Port of the wiring in C# {@code Program.Main} +
 * {@code Renderer.Initialize} ({@code ../legacy-csharp/console/}).
 *
 * <p>Mirrors the original's UTF-8 output, color auto-detection (off when output is
 * redirected, when {@code NO_COLOR} is set, or when {@code TERM=dumb}), and the
 * {@code TICTACTOE_SEED} determinism hook — but seeds an injected {@link Random}
 * rather than a static field.
 */
public final class ConsoleApplication {

    private ConsoleApplication() {
    }

    public static void main(String[] args) {
        // Force UTF-8 on stdout to match C# `Console.OutputEncoding = Encoding.UTF8`.
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

        ConsolePalette palette = new ConsolePalette(shouldUseColor());
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        Renderer renderer = new Renderer(out, in, palette);
        GameConsole console = new GameConsole(renderer, System.err, new Ai(createRng()));

        int exitCode = console.run();
        out.flush();
        System.exit(exitCode);
    }

    private static boolean shouldUseColor() {
        boolean disable =
                System.console() == null
                        || !isNullOrEmpty(System.getenv("NO_COLOR"))
                        || "dumb".equalsIgnoreCase(System.getenv("TERM"));
        return !disable;
    }

    /** Seeds from {@code TICTACTOE_SEED} when set and parseable; otherwise non-deterministic. */
    private static Random createRng() {
        String seed = System.getenv("TICTACTOE_SEED");
        if (seed != null) {
            try {
                return new Random(Integer.parseInt(seed.trim()));
            } catch (NumberFormatException ignored) {
                // fall through to non-deterministic
            }
        }
        return new Random();
    }

    private static boolean isNullOrEmpty(String s) {
        return s == null || s.isEmpty();
    }
}
