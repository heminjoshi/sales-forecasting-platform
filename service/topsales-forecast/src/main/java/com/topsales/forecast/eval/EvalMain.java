package com.topsales.forecast.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point for {@code make eval} (wired in the pom as the {@code exec:java} main class). A
 * <b>pure-JVM</b> tool — no Spring context, no datasource — that backtests the baseline forecasters on
 * the committed seed and (re)writes two committed artifacts:
 *
 * <ul>
 *   <li>{@code docs/forecast-eval-report.md} — Section A (per-segment), B (naive-vs-HW per series),
 *       C (overall pooled rollup).
 *   <li>{@code data/eval/forecast-eval.csv} — the same numbers, machine-diffable.
 * </ul>
 *
 * Both paths are resolved relative to the repo root (found by walking up from {@code user.dir} to the
 * directory that contains {@code data/seed/seed-config.json}, like {@code SeedConfigLoader}). The
 * backtest window is fixed, so running this twice produces byte-identical files.
 */
public final class EvalMain {

    private EvalMain() {}

    public static void main(String[] args) throws IOException {
        Path repoRoot = repoRoot();

        EvalResult result = BacktestRunner.withDefaults(repoRoot).run();
        EvalReport report = new EvalReport();

        System.out.println(report.console(result));

        Path md = repoRoot.resolve("docs/forecast-eval-report.md");
        Path csv = repoRoot.resolve("data/eval/forecast-eval.csv");
        Files.createDirectories(md.getParent());
        Files.createDirectories(csv.getParent());
        Files.writeString(md, report.markdown(result));
        Files.writeString(csv, report.csv(result));

        System.out.println("Wrote " + md);
        System.out.println("Wrote " + csv);
    }

    /** Walk up from {@code user.dir} until {@code data/seed/seed-config.json} exists; return that dir. */
    static Path repoRoot() {
        Path marker = Path.of("data", "seed", "seed-config.json");
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve(marker))) {
                return p;
            }
        }
        throw new IllegalStateException(
                "repo root not found (searched up from " + dir + " for " + marker + ")");
    }
}
