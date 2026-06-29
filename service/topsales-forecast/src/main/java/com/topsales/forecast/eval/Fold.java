package com.topsales.forecast.eval;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * One expanding-window CV fold: a contiguous training span {@code [trainStart, trainEnd]} (inclusive)
 * and the immediately following test span {@code [testStart, testEnd]} (inclusive). Every test day is
 * strictly after every training day — no leakage. {@code trainStart} is constant across folds (the
 * window only ever grows on the right), so larger {@code index} means more history.
 */
public record Fold(
        int index, LocalDate trainStart, LocalDate trainEnd, LocalDate testStart, LocalDate testEnd) {

    /** Number of days scored by this fold ({@code testEnd − testStart + 1}). */
    public int testHorizonDays() {
        return (int) ChronoUnit.DAYS.between(testStart, testEnd) + 1;
    }

    /** Number of training days ({@code trainEnd − trainStart + 1}). */
    public int trainDays() {
        return (int) ChronoUnit.DAYS.between(trainStart, trainEnd) + 1;
    }
}
