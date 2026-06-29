package com.topsales.common.insight;

/**
 * Bounded, grounded NL insight — verbalizes ONLY the provided numbers. The seam that lets
 * template ↔ Bedrock swap and guarantees a deterministic floor (DR-6). Built impl:
 * TemplateInsightGenerator; designed: BedrockInsightGenerator. Impls land in topsales-insight (Phase 5).
 */
public interface InsightGenerator {
    String generate(InsightRequest req);
}
