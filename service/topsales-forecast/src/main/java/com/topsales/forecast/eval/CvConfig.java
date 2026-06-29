package com.topsales.forecast.eval;

/**
 * Expanding-window time-series cross-validation parameters (ADR-0005, Phase-3 decision 9). These
 * mirror {@code topsales.forecast.eval.*} in {@code application.yml} but are kept here as plain
 * documented constants because the eval is a pure-JVM tool — it never boots Spring, so it does not
 * read that config.
 *
 * <ul>
 *   <li>{@code initialTrainDays} — first fold's training length (≥2 monthly cycles so Holt-Winters
 *       has enough history to seed level/trend/seasonal).
 *   <li>{@code testHorizonDays} — each fold scores the next this-many days (one week).
 *   <li>{@code stepDays} — how far the training window expands between folds.
 *   <li>{@code maxFolds} — cap on the number of folds (keeps the report bounded + fast).
 * </ul>
 */
public record CvConfig(int initialTrainDays, int testHorizonDays, int stepDays, int maxFolds) {

    /** The locked Phase-3 defaults: 84 / 7 / 7 / 12. */
    public static CvConfig defaults() {
        return new CvConfig(84, 7, 7, 12);
    }
}
