package com.topsales.insight;

import com.topsales.common.api.TopKItem;
import com.topsales.common.insight.InsightRequest;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Output guard for model-generated insight: returns {@code true} only when <em>every</em> number-looking
 * token in the candidate text traces back to a figure derived from the request. The Bedrock impl calls
 * this on each generation and falls back to {@link TemplateInsightGenerator} when it fails, so a model
 * can never surface a figure it was not given.
 *
 * <p><strong>Threat model — numbers-only grounding.</strong> The allow-set is exactly
 * {@link InsightFigures#groundedFigures(java.util.List)}: each item's canonical value plus each non-null
 * canonical delta. A model that invents a metric, inflates a total, or — via an injected category name
 * (category labels in {@link TopKItem} are untrusted, §9/§11) — smuggles in a number that is not a real
 * computed figure will emit a numeric token outside the allow-set and be rejected. Matching is
 * deliberately <em>conservative</em>: any unrecognized numeric token fails the whole output, and since
 * untrusted category names are <em>not</em> part of the allow-set, a digit that appears only inside a
 * category (e.g. {@code "9000"} in an injected label) is treated as ungrounded and rejected. We validate
 * numbers, not prose: non-numeric text (including category labels) is not vetted here.
 *
 * <p>Renderings must match {@link InsightFigures} exactly (stripped decimals, signed whole-percent),
 * so the validator and the template agree on one canonical form of every figure.
 */
@Component
public class GroundingValidator {

    /**
     * A single number-looking token: optional sign, integer digits, optional decimal fraction, optional
     * trailing percent — covering both {@code formatValue} ({@code "1234.5"}, {@code "0"}) and
     * {@code formatDelta} ({@code "+12%"}, {@code "-5%"}) renderings. No thousands grouping, matching
     * {@link InsightFigures}'s plain-string output.
     */
    private static final Pattern NUMERIC = Pattern.compile("[+-]?\\d+(?:\\.\\d+)?%?");

    /**
     * @return {@code true} iff every numeric token in {@code output} is one of the figures derivable
     *     from {@code req}. A {@code null} output is not grounded; an output with no numeric tokens is
     *     vacuously grounded.
     */
    public boolean isGrounded(String output, InsightRequest req) {
        if (output == null) {
            return false;
        }
        Set<String> allowed =
                new HashSet<>(InsightFigures.groundedFigures(req == null ? null : req.items()));
        Matcher m = NUMERIC.matcher(output);
        while (m.find()) {
            if (!allowed.contains(m.group())) {
                return false;
            }
        }
        return true;
    }
}
