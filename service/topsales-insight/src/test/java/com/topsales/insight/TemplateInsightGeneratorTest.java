package com.topsales.insight;

import static org.assertj.core.api.Assertions.assertThat;

import com.topsales.common.api.TopKItem;
import com.topsales.common.domain.Mode;
import com.topsales.common.domain.Window;
import com.topsales.common.insight.InsightRequest;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The deterministic floor always returns a non-empty, inherently grounded sentence. */
class TemplateInsightGeneratorTest {

    private final TemplateInsightGenerator generator = new TemplateInsightGenerator();
    private final GroundingValidator validator = new GroundingValidator();

    private static TopKItem item(int rank, String category, String value, String delta) {
        return new TopKItem(
                rank,
                category,
                new BigDecimal(value),
                delta == null ? null : new BigDecimal(delta),
                null,
                null);
    }

    @Test
    void leadsWithTopCategory_andEchoesDelta_andIsSelfGrounded() {
        InsightRequest req =
                new InsightRequest(
                        Mode.FORECAST,
                        Window.MONTH,
                        List.of(
                                item(1, "Electronics", "1000", "0.12"),
                                item(2, "Toys", "800", "-0.05")));

        String out = generator.generate(req);

        assertThat(out)
                .isNotBlank()
                .contains("Electronics leads this month")
                .contains("(~+12%)")
                .contains("Toys follows");
        // The floor renders only provided figures, so its own output must pass the grounding guard.
        assertThat(validator.isGrounded(out, req)).isTrue();
    }

    @Test
    void singleItem_hasNoFollowsClause() {
        InsightRequest req =
                new InsightRequest(
                        Mode.FORECAST, Window.WEEK, List.of(item(1, "Electronics", "1000", "0.20")));

        String out = generator.generate(req);

        assertThat(out)
                .isNotBlank()
                .contains("Electronics leads this week")
                .contains("(~+20%)")
                .doesNotContain("follows");
        assertThat(validator.isGrounded(out, req)).isTrue();
    }

    @Test
    void nullDelta_omitsTheDeltaClause() {
        InsightRequest req =
                new InsightRequest(
                        Mode.ACTUALS,
                        Window.YEAR,
                        List.of(item(1, "Electronics", "1000", null), item(2, "Toys", "800", null)));

        String out = generator.generate(req);

        assertThat(out)
                .isNotBlank()
                .contains("Electronics leads this year")
                .contains("Toys follows")
                .doesNotContain("%"); // no delta available -> no percent clause
        assertThat(validator.isGrounded(out, req)).isTrue();
    }

    @Test
    void emptyItems_returnsHonestNonEmptySentence() {
        InsightRequest req = new InsightRequest(Mode.ACTUALS, Window.MONTH, List.of());

        String out = generator.generate(req);

        assertThat(out).isNotBlank().contains("this month");
        assertThat(validator.isGrounded(out, req)).isTrue();
    }

    @Test
    void nullRequest_doesNotThrow() {
        assertThat(generator.generate(null)).isNotBlank();
    }
}
