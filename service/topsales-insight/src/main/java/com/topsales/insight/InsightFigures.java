package com.topsales.insight;

import com.topsales.common.api.TopKItem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure, dependency-free formatting of the <em>grounded numbers</em> that an insight line is allowed to
 * mention (Phase 5). Both {@code InsightGenerator} impls reuse it: the deterministic template renders
 * its figures through these methods, and the (later) output validator builds its allow-set from
 * {@link #groundedFigures(List)} — so the two agree on exactly one canonical rendering of every value
 * and delta. Grounding is the injection/hallucination guard: category names in {@link TopKItem} are
 * untrusted, and any numeric figure the model emits must trace back to this set.
 *
 * <p>No Spring, no AWS, no I/O — just {@link BigDecimal} math — so it is trivially unit-testable and
 * safe to call on the hot read path.
 */
public final class InsightFigures {

    private InsightFigures() {}

    /**
     * Canonical rendering of a top-k item's {@code value} (a sales total): the plain decimal with any
     * trailing zeros stripped (e.g. {@code 1234.50 -> "1234.5"}, {@code 100 -> "100"}). No thousands
     * grouping or currency symbol, so the string is a single matchable numeric token. Zero renders as
     * {@code "0"} (sidestepping {@link BigDecimal#stripTrailingZeros()}'s {@code "0.00"} quirk).
     */
    public static String formatValue(BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.signum() == 0) {
            return "0";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    /**
     * Canonical rendering of {@code deltaVsPrior} (a fraction, e.g. {@code 0.12 = +12%}) as a signed
     * whole-percent string: {@code 0.12 -> "+12%"}, {@code -0.05 -> "-5%"}, {@code 0 -> "+0%"}. Rounded
     * half-up to an integer percent for clean copy. Returns {@code null} for a {@code null} delta
     * (absent on degraded/pending items), so callers can skip it rather than print a placeholder.
     */
    public static String formatDelta(BigDecimal deltaVsPrior) {
        if (deltaVsPrior == null) {
            return null;
        }
        BigDecimal percent =
                deltaVsPrior.movePointRight(2).setScale(0, RoundingMode.HALF_UP);
        String sign = percent.signum() < 0 ? "" : "+"; // negative carries its own '-'
        return sign + percent.toPlainString() + "%";
    }

    /**
     * The complete allow-set of number-strings an insight may legitimately contain, in first-seen
     * order with duplicates removed: each item's {@link #formatValue(BigDecimal) formatted value} plus
     * each non-null {@link #formatDelta(BigDecimal) formatted delta}. The validator treats any numeric
     * figure outside this set as a hallucination and falls back to the deterministic template.
     */
    public static List<String> groundedFigures(List<TopKItem> items) {
        Set<String> figures = new LinkedHashSet<>();
        if (items != null) {
            for (TopKItem item : items) {
                String value = formatValue(item.value());
                if (value != null) {
                    figures.add(value);
                }
                String delta = formatDelta(item.deltaVsPrior());
                if (delta != null) {
                    figures.add(delta);
                }
            }
        }
        return new ArrayList<>(figures);
    }
}
