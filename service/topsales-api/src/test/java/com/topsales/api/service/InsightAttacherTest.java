package com.topsales.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.topsales.common.api.TopKItem;
import com.topsales.common.api.TopKQuery;
import com.topsales.common.api.TopKResponse;
import com.topsales.common.domain.ChannelFilter;
import com.topsales.common.domain.Mode;
import com.topsales.common.domain.Status;
import com.topsales.common.domain.Window;
import com.topsales.common.insight.InsightGenerator;
import com.topsales.common.insight.InsightRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the read-edge insight attacher (generator faked). Proves it populates the
 * {@code insight} field from the response's own figures and stays total — never throwing on empty
 * items or a faulty generator.
 */
class InsightAttacherTest {

    private static final String TENANT = "t_demo";

    private TopKQuery query() {
        return new TopKQuery(TENANT, Window.MONTH, Mode.ACTUALS, 10, ChannelFilter.ALL);
    }

    private TopKResponse response(List<TopKItem> items) {
        return new TopKResponse(
                TENANT, Mode.ACTUALS, Window.MONTH, ChannelFilter.ALL, 10,
                Status.FRESH, Instant.now(), null, null, null, items);
    }

    private TopKItem item() {
        return new TopKItem(1, "cat_office", new BigDecimal("100.00"), null, null, null);
    }

    @Test
    void attach_populatesInsight_fromGeneratorOutput_andPreservesEverythingElse() {
        InsightGenerator gen = req -> "cat_office leads the month at 100.";
        InsightAttacher attacher = new InsightAttacher(gen);

        TopKResponse out = attacher.attach(query(), response(List.of(item())));

        assertThat(out.insight()).isEqualTo("cat_office leads the month at 100.");
        // Only insight is swapped; the rest of the response is carried through unchanged.
        assertThat(out.items()).hasSize(1);
        assertThat(out.status()).isEqualTo(Status.FRESH);
        assertThat(out.tenantId()).isEqualTo(TENANT);
    }

    @Test
    void attach_passesGroundedFigures_modeWindowItems_toGenerator() {
        InsightRequest[] seen = new InsightRequest[1];
        InsightGenerator gen =
                req -> {
                    seen[0] = req;
                    return "ok";
                };
        InsightAttacher attacher = new InsightAttacher(gen);

        attacher.attach(query(), response(List.of(item())));

        assertThat(seen[0].mode()).isEqualTo(Mode.ACTUALS);
        assertThat(seen[0].window()).isEqualTo(Window.MONTH);
        assertThat(seen[0].items()).hasSize(1);
    }

    @Test
    void attach_emptyItems_doesNotCallGenerator_leavesInsightUnset() {
        boolean[] called = {false};
        InsightGenerator gen =
                req -> {
                    called[0] = true;
                    return "should not run";
                };
        InsightAttacher attacher = new InsightAttacher(gen);

        TopKResponse out = attacher.attach(query(), response(List.of()));

        assertThat(called[0]).isFalse();
        assertThat(out.insight()).isNull();
        assertThat(out.items()).isEmpty();
    }

    @Test
    void attach_generatorThrows_returnsResponseUnchanged_neverThrows() {
        InsightGenerator gen =
                req -> {
                    throw new RuntimeException("model timeout");
                };
        InsightAttacher attacher = new InsightAttacher(gen);
        TopKResponse in = response(List.of(item()));

        TopKResponse[] out = new TopKResponse[1];
        assertThatCode(() -> out[0] = attacher.attach(query(), in)).doesNotThrowAnyException();
        // Decorative field left unset on fault; the ranked items still survive the read.
        assertThat(out[0].insight()).isNull();
        assertThat(out[0].items()).hasSize(1);
    }
}
