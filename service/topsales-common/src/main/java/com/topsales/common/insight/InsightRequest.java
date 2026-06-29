package com.topsales.common.insight;

import com.topsales.common.api.TopKItem;
import com.topsales.common.domain.Mode;
import com.topsales.common.domain.Window;

import java.util.List;

/**
 * Input to insight generation. Carries ONLY computed figures (category labels + value + delta) —
 * never raw user free-text. Category names are treated as untrusted (prompt-injection, §9, §11).
 */
public record InsightRequest(
        Mode mode,
        Window window,
        List<TopKItem> items) {
}
