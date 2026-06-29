package com.topsales.api.config;

import com.topsales.common.config.TopsalesProperties;
import com.topsales.common.insight.InsightGenerator;
import com.topsales.insight.BedrockInsightGenerator;
import com.topsales.insight.GroundingValidator;
import com.topsales.insight.TemplateInsightGenerator;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import tools.jackson.databind.ObjectMapper;

/**
 * Wires the designed (cloud) insight path (Phase 5): a Bedrock-backed {@link InsightGenerator} that
 * decorates the deterministic {@link TemplateInsightGenerator} floor. This is the {@code aws} half of
 * the {@code InsightGenerator} seam (CLAUDE.md core principle); selection is by
 * {@link ConditionalOnProperty} on {@code topsales.insight.provider}, not a Spring {@code @Profile}.
 *
 * <p><b>Why guarded at the class level.</b> The whole {@code @Configuration} (and its
 * {@code software.amazon.awssdk} imports) is gated on {@code provider=bedrock}. When it isn't bedrock
 * — the local default — the condition is evaluated from ASM metadata during component scan, so this
 * class is never class-loaded and the AWS SDK (which is {@code optional} on the {@code topsales-api}
 * classpath) is never referenced. The only {@link InsightGenerator} on the local graph is then the
 * component-scanned {@code TemplateInsightGenerator}. When it <em>is</em> bedrock, the bean is
 * {@code @Primary} so it wins injection over that floor.
 *
 * <p>The {@code BedrockRuntimeClient} resolves its region and credentials from the standard AWS
 * provider chain ({@code AWS_REGION}/profile/instance role), so no cloud config is hardcoded here; the
 * model id and per-call timeout come from {@code topsales.insight.*}.
 */
@Configuration
@ConditionalOnProperty(prefix = "topsales.insight", name = "provider", havingValue = "bedrock")
public class InsightWiring {

    /**
     * The Bedrock decorator. Takes the template floor and the grounding validator (both
     * component-scanned beans) plus the shared Jackson {@link ObjectMapper}, and reads its model id +
     * timeout from {@link TopsalesProperties.Insight}.
     *
     * <p>Phase 6: the {@link MeterRegistry} (from actuator autoconfig) backs a {@code onFallback}
     * callback that increments {@code topsales.insight.fallback.total} whenever the decorator serves the
     * template instead of a grounded Bedrock result — so the breaker/retry fail-soft is observable. The
     * resilience4j and AWS SDK symbols stay inside {@code BedrockInsightGenerator.create(...)}; this
     * module never names them.
     */
    @Bean
    @Primary
    public InsightGenerator bedrockInsightGenerator(
            TemplateInsightGenerator template,
            GroundingValidator validator,
            TopsalesProperties props,
            ObjectMapper mapper,
            MeterRegistry meterRegistry) {
        TopsalesProperties.Insight cfg = props.insight();
        Runnable onFallback = () -> meterRegistry.counter("topsales.insight.fallback.total").increment();
        return BedrockInsightGenerator.create(
                template, validator, mapper, cfg.modelId(), cfg.timeout(), onFallback);
    }
}
