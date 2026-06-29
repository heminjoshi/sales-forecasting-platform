package com.topsales.forecast.eval;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure function: a date range {@code [rangeStart, rangeEnd]} (inclusive) plus a {@link CvConfig} →
 * the ordered list of expanding-window folds.
 *
 * <p>Fold {@code k} (0-indexed): training runs {@code [rangeStart … rangeStart + initialTrainDays +
 * k·stepDays − 1]} and the test is the next {@code testHorizonDays} days. A fold whose test span
 * would run past {@code rangeEnd} (a short trailing fold) is dropped; at most {@code maxFolds} folds
 * are returned. A history shorter than {@code initialTrainDays + testHorizonDays} yields zero folds.
 */
public final class FoldSplitter {

    private FoldSplitter() {}

    public static List<Fold> split(LocalDate rangeStart, LocalDate rangeEnd, CvConfig cfg) {
        List<Fold> folds = new ArrayList<>();
        for (int k = 0; k < cfg.maxFolds(); k++) {
            long trainLen = (long) cfg.initialTrainDays() + (long) k * cfg.stepDays();
            LocalDate trainEnd = rangeStart.plusDays(trainLen - 1);
            LocalDate testStart = trainEnd.plusDays(1);
            LocalDate testEnd = testStart.plusDays(cfg.testHorizonDays() - 1L);
            if (testEnd.isAfter(rangeEnd)) {
                break; // short trailing fold — drop it (and everything after, since they only grow).
            }
            folds.add(new Fold(k, rangeStart, trainEnd, testStart, testEnd));
        }
        return folds;
    }
}
