import * as cdk from 'aws-cdk-lib';
import { Template } from 'aws-cdk-lib/assertions';
import { MonitoringStack } from '../lib/monitoring-stack';
import { METRIC_NAMESPACE } from '../lib/config';
import { METRIC_NAMES } from '../lib/metric-names';

/**
 * Metric-contract regression gate.
 *
 * The Phase-6 serving API emits a fixed set of dotted meter names (see
 * `lib/metric-names.ts`). These tests pin every one of them to a CloudWatch
 * alarm under the `topsales-api` namespace, so a drift in the meter names —
 * here OR in the serving code that feeds this object — fails CI instead of
 * silently un-wiring an alarm. Each name is sourced from METRIC_NAMES, never a
 * literal, so the assertion and the stack can't disagree.
 */
describe('MonitoringStack metric contract', () => {
  const synth = () => {
    const app = new cdk.App();
    const stack = new MonitoringStack(app, 'monitoring-test');
    return Template.fromStack(stack);
  };

  // Every dotted name the alarms MUST cover, in contract order.
  const CONTRACT: ReadonlyArray<readonly [string, string]> = [
    ['http.server.requests', METRIC_NAMES.HTTP_SERVER_REQUESTS],
    ['topsales.read.total', METRIC_NAMES.READ_TOTAL],
    ['topsales.insight.fallback.total', METRIC_NAMES.INSIGHT_FALLBACK],
    ['topsales.forecast.freshness.seconds', METRIC_NAMES.FORECAST_FRESHNESS_SECONDS],
    ['topsales.forecast.provider.faults.total', METRIC_NAMES.PROVIDER_FAULT],
  ];

  it('namespace constant is the published topsales-api namespace', () => {
    expect(METRIC_NAMESPACE).toBe('topsales-api');
  });

  it.each(CONTRACT)('alarms on %s under the topsales-api namespace', (literal, fromConstant) => {
    // Guard: the constant still equals the dotted literal the contract pins.
    expect(fromConstant).toBe(literal);

    const template = synth();
    template.hasResourceProperties('AWS::CloudWatch::Alarm', {
      Namespace: METRIC_NAMESPACE,
      MetricName: fromConstant,
    });
  });

  it('publishes exactly one dashboard', () => {
    synth().resourceCountIs('AWS::CloudWatch::Dashboard', 1);
  });

  it('wires an SNS topic as the designed alarm action', () => {
    synth().resourceCountIs('AWS::SNS::Topic', 1);
  });
});
