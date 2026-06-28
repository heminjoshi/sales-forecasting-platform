/**
 * GenAI insight layer: {@code InsightGenerator} with a deterministic {@code TemplateInsightGenerator}
 * (local default) and a grounded {@code BedrockInsightGenerator} (cloud). Insights verbalize only
 * the computed numbers and are validated against them, with template fallback.
 *
 * <p>Populated in Phase 5.
 */
package com.topsales.insight;
