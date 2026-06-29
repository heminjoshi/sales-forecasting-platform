package com.topsales.api.service;

import com.topsales.common.api.TopKQuery;
import com.topsales.common.api.TopKResponse;
import com.topsales.common.insight.InsightGenerator;
import com.topsales.common.insight.InsightRequest;

import org.springframework.stereotype.Component;

/**
 * Decorates a resolved {@link TopKResponse} with the grounded NL insight line (Phase 5, §9). Sits at
 * the read edge so the generated string is cached together with the top-k under the same key — the
 * controller calls it inside the forecast cache boundary (one generation per cache miss) and inline on
 * the cache-bypassed actuals branch.
 *
 * <p>The {@link InsightGenerator} seam guarantees a deterministic template floor, so {@code attach}
 * never depends on a model being online. It is still written to be total: insight is decorative — the
 * ranked items are the contract — so an empty top-k or any generator fault yields the response with an
 * unset insight (the dashboard simply hides the line) rather than an exception out of the read path.
 */
@Component
public class InsightAttacher {

    private final InsightGenerator generator;

    public InsightAttacher(InsightGenerator generator) {
        this.generator = generator;
    }

    /**
     * Return a copy of {@code response} with its {@code insight} populated from the response's own
     * computed figures (only labels + value + delta reach the generator — never raw free-text, §9,
     * §11). Never throws: on empty items or any generator fault the response is returned unchanged
     * (insight left unset).
     *
     * @param query the resolved read request (kept for symmetry with the read seam; the grounded
     *     figures come from {@code response})
     */
    public TopKResponse attach(TopKQuery query, TopKResponse response) {
        if (response.items() == null || response.items().isEmpty()) {
            return response; // nothing to verbalize — leave insight unset (line hidden)
        }
        String insight;
        try {
            insight =
                    generator.generate(
                            new InsightRequest(response.mode(), response.window(), response.items()));
        } catch (RuntimeException e) {
            // Insight is decorative; a generator fault must never fail an otherwise-good read.
            return response;
        }
        return withInsight(response, insight);
    }

    /** Records are immutable — rebuild via the canonical constructor with only {@code insight} swapped. */
    private static TopKResponse withInsight(TopKResponse r, String insight) {
        return new TopKResponse(
                r.tenantId(),
                r.mode(),
                r.window(),
                r.channel(),
                r.k(),
                r.status(),
                r.asOf(),
                r.windowFrom(),
                r.windowTo(),
                insight,
                r.items());
    }
}
