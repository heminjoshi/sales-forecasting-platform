package com.topsales.api.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * IT-RS-06 (scoped): the heavyweight AWS SDK must stay confined to {@code topsales-insight} and never
 * leak onto the local-runnable {@code topsales-api} classpath. {@code bedrockruntime} is declared
 * {@code optional} in {@code topsales-insight}, so it is not a transitive dependency here — this pins
 * that invariant at runtime (an accidental non-optional add would make these classes resolvable and
 * fail the test).
 *
 * <p><b>Scope note.</b> resilience4j is intentionally a <em>non-optional</em> dependency of
 * {@code topsales-insight} (it pulls only slf4j + vavr, no AWS/Spring), so its symbols <em>are</em>
 * transitively present on this classpath by design. It is therefore deliberately not asserted absent.
 */
class BedrockDependencyConfinementTest {

    @Test
    void awsBedrockSdkIsNotResolvableFromTheApiModule() {
        assertThatThrownBy(
                        () ->
                                Class.forName(
                                        "software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(
                        () ->
                                Class.forName(
                                        "software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest"))
                .isInstanceOf(ClassNotFoundException.class);
    }
}
