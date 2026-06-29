import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as cloudwatch from 'aws-cdk-lib/aws-cloudwatch';
import * as cwActions from 'aws-cdk-lib/aws-cloudwatch-actions';
import * as sns from 'aws-cdk-lib/aws-sns';
import * as ecsPatterns from 'aws-cdk-lib/aws-ecs-patterns';
import { METRIC_NAMESPACE } from './config';
import { METRIC_NAMES } from './metric-names';

export interface MonitoringStackProps extends cdk.StackProps {
  /**
   * The serving service to monitor (optional so the stack can synth in
   * isolation). Read for its ALB/target-group on the RED dashboards.
   */
  readonly servingService?: ecsPatterns.ApplicationLoadBalancedFargateService;
}

/**
 * MonitoringStack — CloudWatch dashboard + alarms over the RED signals
 * (`http.server.requests`) and the custom Phase-6 ML-quality meters
 * (see {@link METRIC_NAMES}), all published under the {@link METRIC_NAMESPACE}
 * namespace by Micrometer's CloudWatch registry.
 *
 * METRIC CONTRACT (stability-critical): every alarm's metric is built from the
 * EXACT dotted meter names in {@link METRIC_NAMES} — never a literal here — so a
 * rename in the serving API breaks the build (and `monitoring-stack.test.ts`)
 * instead of silently un-wiring an alarm. The test asserts one alarm per dotted
 * name; keep them in lock-step.
 *
 * The SNS topic is the designed alarm action (the on-call fan-out). No
 * subscription is wired — this is a synth-only skeleton — but every alarm routes
 * its ALARM transition to the topic so the wiring is real in the template.
 */
export class MonitoringStack extends cdk.Stack {
  /** Designed on-call fan-out. Alarms publish their ALARM transition here. */
  public readonly alarmTopic: sns.Topic;

  /** The single RED + ML-quality dashboard. */
  public readonly dashboard: cloudwatch.Dashboard;

  constructor(scope: Construct, id: string, props?: MonitoringStackProps) {
    super(scope, id, props);

    void props?.servingService;

    this.alarmTopic = new sns.Topic(this, 'AlarmTopic', {
      displayName: `${METRIC_NAMESPACE} alarms`,
    });
    const action = new cwActions.SnsAction(this.alarmTopic);

    // --- Metrics: ONE source of truth (METRIC_NAMES), dotted, under the
    // topsales-api namespace exactly as the CloudWatch registry publishes them.

    // RED latency — high-percentile request duration.
    const httpLatency = new cloudwatch.Metric({
      namespace: METRIC_NAMESPACE,
      metricName: METRIC_NAMES.HTTP_SERVER_REQUESTS,
      statistic: 'p99',
      period: cdk.Duration.minutes(1),
    });

    // RED errors — server-error responses (Micrometer's `outcome` tag).
    const httpErrors = new cloudwatch.Metric({
      namespace: METRIC_NAMESPACE,
      metricName: METRIC_NAMES.HTTP_SERVER_REQUESTS,
      statistic: 'Sum',
      period: cdk.Duration.minutes(1),
      dimensionsMap: { outcome: 'SERVER_ERROR' },
    });

    // Read-path fail-soft — a swallowed serving fault is a dropped degradation
    // rung. Any non-zero count is worth a page.
    const providerFaults = new cloudwatch.Metric({
      namespace: METRIC_NAMESPACE,
      metricName: METRIC_NAMES.PROVIDER_FAULT,
      statistic: 'Sum',
      period: cdk.Duration.minutes(5),
    });

    // Forecast staleness — seconds since the newest serving-row as_of (gauge).
    const forecastFreshness = new cloudwatch.Metric({
      namespace: METRIC_NAMESPACE,
      metricName: METRIC_NAMES.FORECAST_FRESHNESS_SECONDS,
      statistic: 'Maximum',
      period: cdk.Duration.minutes(5),
    });

    // Insight degradation — generations that fell back to the template floor.
    const insightFallback = new cloudwatch.Metric({
      namespace: METRIC_NAMESPACE,
      metricName: METRIC_NAMES.INSIGHT_FALLBACK,
      statistic: 'Sum',
      period: cdk.Duration.minutes(5),
    });

    // Read mix — degraded-status reads (status/mode dims). A spike means the
    // degradation chain is leaning on its lower rungs.
    const degradedReads = new cloudwatch.Metric({
      namespace: METRIC_NAMESPACE,
      metricName: METRIC_NAMES.READ_TOTAL,
      statistic: 'Sum',
      period: cdk.Duration.minutes(5),
      dimensionsMap: { status: 'degraded', mode: 'forecast' },
    });

    // --- Alarms: one per signal, each fanning out to the SNS topic. ---

    const alarms: cloudwatch.Alarm[] = [
      new cloudwatch.Alarm(this, 'HttpLatencyHigh', {
        alarmDescription: 'p99 HTTP request latency above SLO (ms).',
        metric: httpLatency,
        threshold: 1000,
        evaluationPeriods: 5,
        comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
        treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
      }),
      new cloudwatch.Alarm(this, 'HttpErrorRateHigh', {
        alarmDescription: 'Sustained 5xx server errors on the serving API.',
        metric: httpErrors,
        threshold: 5,
        evaluationPeriods: 5,
        comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
        treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
      }),
      new cloudwatch.Alarm(this, 'ReadFailSoft', {
        alarmDescription: 'Serving-read faults swallowed by the fail-soft read path.',
        metric: providerFaults,
        threshold: 0,
        evaluationPeriods: 1,
        comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
        treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
      }),
      new cloudwatch.Alarm(this, 'ForecastStale', {
        alarmDescription: 'Newest serving row older than the staleness SLA (3h).',
        metric: forecastFreshness,
        threshold: cdk.Duration.hours(3).toSeconds(),
        evaluationPeriods: 1,
        comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
        treatMissingData: cloudwatch.TreatMissingData.BREACHING,
      }),
      new cloudwatch.Alarm(this, 'InsightDegradation', {
        alarmDescription: 'Insight generation falling back to the deterministic template.',
        metric: insightFallback,
        threshold: 10,
        evaluationPeriods: 3,
        comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
        treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
      }),
      new cloudwatch.Alarm(this, 'DegradedReadMix', {
        alarmDescription: 'Read mix tilting to degraded status (status/mode dims).',
        metric: degradedReads,
        threshold: 50,
        evaluationPeriods: 3,
        comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
        treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
      }),
    ];

    for (const alarm of alarms) {
      alarm.addAlarmAction(action);
      alarm.addOkAction(action);
    }

    // --- Dashboard: RED row + ML-quality row. ---

    this.dashboard = new cloudwatch.Dashboard(this, 'Dashboard', {
      dashboardName: `${METRIC_NAMESPACE}-overview`,
    });

    this.dashboard.addWidgets(
      new cloudwatch.GraphWidget({
        title: 'HTTP latency (p99)',
        left: [httpLatency],
        width: 12,
      }),
      new cloudwatch.GraphWidget({
        title: 'HTTP server errors (5xx)',
        left: [httpErrors],
        width: 12,
      }),
    );
    this.dashboard.addWidgets(
      new cloudwatch.GraphWidget({
        title: 'Forecast freshness (s)',
        left: [forecastFreshness],
        width: 8,
      }),
      new cloudwatch.GraphWidget({
        title: 'Read-path provider faults',
        left: [providerFaults],
        width: 8,
      }),
      new cloudwatch.GraphWidget({
        title: 'Read mix / insight fallback',
        left: [degradedReads, insightFallback],
        width: 8,
      }),
    );
  }
}
