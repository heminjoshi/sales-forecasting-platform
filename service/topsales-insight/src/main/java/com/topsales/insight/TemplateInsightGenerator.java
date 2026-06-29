package com.topsales.insight;

import com.topsales.common.api.TopKItem;
import com.topsales.common.domain.Window;
import com.topsales.common.insight.InsightGenerator;
import com.topsales.common.insight.InsightRequest;

import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Deterministic, always-on insight floor (DR-6): the local default {@link InsightGenerator} and the
 * fallback the Bedrock impl degrades to on timeout/validation failure. It composes a single sentence
 * purely from the top-k items' already-computed figures, rendering every number through
 * {@link InsightFigures} so it is <em>inherently grounded</em> — there is no model, so nothing can be
 * hallucinated and no figure can appear that did not come from the request.
 *
 * <p><strong>Threat model.</strong> Category names in {@link TopKItem} are untrusted (prompt-injection,
 * §9/§11). This generator only echoes them as a plain label inside a fixed template — they are never
 * interpreted as instructions, and no model sees them — so verbatim echo is safe here. (The Bedrock
 * impl, which does feed a model, is the one that must additionally validate output via
 * {@link GroundingValidator}.)
 *
 * <p>A plain {@code @Component}: the only built impl on the local classpath. Bedrock will be wired as
 * {@code @Primary} + {@code @ConditionalOnProperty} alongside it.
 */
@Component
public class TemplateInsightGenerator implements InsightGenerator {

    @Override
    public String generate(InsightRequest req) {
        List<TopKItem> items = req == null ? null : req.items();
        String window = windowLabel(req);

        if (items == null || items.isEmpty()) {
            return "No category data available for this " + window + ".";
        }

        TopKItem top = items.get(0);
        StringBuilder sb = new StringBuilder();
        sb.append(top.category()).append(" leads this ").append(window);

        // formatDelta returns null when deltaVsPrior is absent (degraded/pending) — omit the clause.
        String topDelta = InsightFigures.formatDelta(top.deltaVsPrior());
        if (topDelta != null) {
            sb.append(" (~").append(topDelta).append(')');
        }

        if (items.size() > 1) {
            sb.append("; ").append(items.get(1).category()).append(" follows");
        }

        return sb.append('.').toString();
    }

    /** Lowercase wire label for the window ({@code week|month|year}); a neutral word if absent. */
    private static String windowLabel(InsightRequest req) {
        Window window = req == null ? null : req.window();
        return window == null ? "period" : window.wire();
    }
}
