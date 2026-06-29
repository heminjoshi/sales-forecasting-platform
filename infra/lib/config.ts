import { Construct } from 'constructs';

/**
 * Static application identifier. Mirrors the `appName` context value in cdk.json
 * and is the prefix used for stack ids / resource naming across all five stacks.
 */
export const APP_NAME = 'topsales';

/**
 * CloudWatch metric namespace for the serving API's custom Phase-6 meters.
 * Micrometer's CloudWatch registry publishes the `topsales.*` meters under this
 * namespace (see {@link ./metric-names}); the Monitoring stack reads from it.
 */
export const METRIC_NAMESPACE = 'topsales-api';

/**
 * Resolve the container image tag to deploy.
 *
 * A bare `cdk synth` (no context) must stay green, so this defaults to
 * `dev-local`. CI passes the git SHA via `cdk synth -c imageTag=<gitsha>`,
 * which `node.tryGetContext('imageTag')` picks up.
 *
 * @param scope any construct in the tree (the App works) — used to read context.
 */
export function resolveImageTag(scope: Construct): string {
  return (scope.node.tryGetContext('imageTag') as string | undefined) ?? 'dev-local';
}

/**
 * Resolve the application name from context, falling back to {@link APP_NAME}.
 */
export function resolveAppName(scope: Construct): string {
  return (scope.node.tryGetContext('appName') as string | undefined) ?? APP_NAME;
}
