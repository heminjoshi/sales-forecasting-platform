import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as ecsPatterns from 'aws-cdk-lib/aws-ecs-patterns';
import * as elbv2 from 'aws-cdk-lib/aws-elasticloadbalancingv2';
import * as acm from 'aws-cdk-lib/aws-certificatemanager';
import * as ecr from 'aws-cdk-lib/aws-ecr';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import * as events from 'aws-cdk-lib/aws-events';
import * as targets from 'aws-cdk-lib/aws-events-targets';
import { AuroraRef } from './storage-stack';

export interface ApplicationStackProps extends cdk.StackProps {
  /** Shared VPC from {@link NetworkStack}. */
  readonly vpc: ec2.Vpc;
  /** Serving table from {@link StorageStack} (read granted to the task role). */
  readonly servingTable: dynamodb.Table;
  /** Aurora cluster + secret from {@link StorageStack}. */
  readonly auroraRef: AuroraRef;
  /** Bedrock InvokeModel policy from {@link IntelligenceStack}. */
  readonly bedrockInvokeManagedPolicy: iam.ManagedPolicy;
  /** Model config SSM param from {@link IntelligenceStack}. */
  readonly modelConfigParam: ssm.StringParameter;
  /** Container image tag (git SHA in CI, `dev-local` for a bare synth). */
  readonly imageTag: string;
}

/**
 * The CORS allow-listed production SPA origin (ADR-0009). The prod React SPA is
 * hosted on Vercel — OUTSIDE the AWS account — so there are NO SPA hosting
 * resources here (no S3 site bucket, no CloudFront). The API simply allow-lists
 * this cross-origin host; we surface it as a CfnOutput for the deploy/runbook.
 */
const VERCEL_SPA_ORIGIN = 'https://topsales.vercel.app';

/**
 * ApplicationStack — the ECS Fargate compute tier, consuming Network + Storage +
 * Intelligence:
 *
 *  - **serving**: ALB-fronted Fargate service (the read API + dashboard), the one
 *    image actually runnable today; task role carries the Bedrock policy + table
 *    read + SSM read + Aurora secret read; `SPRING_PROFILES_ACTIVE=aws`.
 *  - **consumer**: headless Fargate service (no ALB) — the Kinesis→aggregate
 *    ingestion worker. DESIGNED-ONLY: `topsales-ingestion` has no boot main yet,
 *    so this is a placeholder image wired with the grants it will need.
 *  - **forecaster**: an EventBridge cron Rule firing a one-shot ECS task — the
 *    batch that writes versioned serving rows. DESIGNED-ONLY image.
 *
 * Images are referenced by ECR repo + `props.imageTag`; the tag flows into the
 * container image URI as a CFN token, so synth needs no registry/image to exist.
 */
export class ApplicationStack extends cdk.Stack {
  /**
   * The ALB-fronted Fargate serving service. Exposed so the Monitoring stack can
   * build RED dashboards/alarms off its target group + service metrics. (Type is
   * the ecs-patterns construct, which carries both `.service` and
   * `.loadBalancer`.)
   */
  public readonly servingService: ecsPatterns.ApplicationLoadBalancedFargateService;

  constructor(scope: Construct, id: string, props: ApplicationStackProps) {
    super(scope, id, props);

    const cluster = new ecs.Cluster(this, 'Cluster', { vpc: props.vpc });

    // -----------------------------------------------------------------
    // Three app images. scanOnPush + a keep-N lifecycle on each.
    // -----------------------------------------------------------------
    const lifecycleRules: ecr.LifecycleRule[] = [{ maxImageCount: 20 }];
    const servingRepo = new ecr.Repository(this, 'ServingRepo', {
      repositoryName: 'topsales/serving',
      imageScanOnPush: true,
      lifecycleRules,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
      emptyOnDelete: true,
    });
    const consumerRepo = new ecr.Repository(this, 'ConsumerRepo', {
      repositoryName: 'topsales/consumer',
      imageScanOnPush: true,
      lifecycleRules,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
      emptyOnDelete: true,
    });
    const forecasterRepo = new ecr.Repository(this, 'ForecasterRepo', {
      repositoryName: 'topsales/forecaster',
      imageScanOnPush: true,
      lifecycleRules,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
      emptyOnDelete: true,
    });

    // -----------------------------------------------------------------
    // serving: ALB-fronted Fargate service.
    // The git-sha imageTag becomes part of the image URI token at synth — no
    // image needs to exist in the repo for the template to render.
    //
    // The API must be internet-reachable (the prod React SPA on Vercel calls it
    // cross-origin), so the ALB is public — but TLS-terminated, never plaintext:
    // an HTTPS:443 listener with a recommended TLS policy, and HTTP:80 redirects
    // to it. The ACM certificate is injected per-env via `-c apiCertArn=...`; a
    // synth-time placeholder ARN keeps account-agnostic synth green (CloudFormation
    // does not resolve the ARN at synth — only at deploy).
    // -----------------------------------------------------------------
    const apiCertArn =
      (this.node.tryGetContext('apiCertArn') as string | undefined) ??
      cdk.Arn.format(
        {
          service: 'acm',
          resource: 'certificate',
          resourceName: 'REPLACE-WITH-REAL-API-CERT',
          arnFormat: cdk.ArnFormat.SLASH_RESOURCE_NAME,
        },
        this,
      );

    this.servingService = new ecsPatterns.ApplicationLoadBalancedFargateService(this, 'Serving', {
      cluster,
      cpu: 512,
      memoryLimitMiB: 1024,
      desiredCount: 2,
      protocol: elbv2.ApplicationProtocol.HTTPS,
      sslPolicy: elbv2.SslPolicy.RECOMMENDED_TLS,
      certificate: acm.Certificate.fromCertificateArn(this, 'ApiCert', apiCertArn),
      redirectHTTP: true, // HTTP:80 -> HTTPS:443
      taskImageOptions: {
        image: ecs.ContainerImage.fromEcrRepository(servingRepo, props.imageTag),
        containerPort: 8080,
        environment: {
          SPRING_PROFILES_ACTIVE: 'aws',
          MODEL_CONFIG_PARAM: props.modelConfigParam.parameterName,
          SERVING_TABLE_NAME: props.servingTable.tableName,
          AURORA_SECRET_ARN: props.auroraRef.secret.secretArn,
        },
      },
    });

    const servingTaskRole = this.servingService.taskDefinition.taskRole;
    props.servingTable.grantReadData(servingTaskRole);
    servingTaskRole.addManagedPolicy(props.bedrockInvokeManagedPolicy);
    props.modelConfigParam.grantRead(servingTaskRole);
    props.auroraRef.secret.grantRead(servingTaskRole);

    // -----------------------------------------------------------------
    // consumer: headless Fargate service (no ALB). DESIGNED-ONLY image —
    // topsales-ingestion has no boot main yet.
    // -----------------------------------------------------------------
    const consumerTaskDef = new ecs.FargateTaskDefinition(this, 'ConsumerTaskDef', {
      cpu: 512,
      memoryLimitMiB: 1024,
    });
    consumerTaskDef.addContainer('consumer', {
      image: ecs.ContainerImage.fromEcrRepository(consumerRepo, props.imageTag),
      logging: ecs.LogDrivers.awsLogs({ streamPrefix: 'consumer' }),
      environment: { SPRING_PROFILES_ACTIVE: 'aws' },
    });
    // Kinesis read + DLQ grants. No stream/queue is provisioned here (designed
    // in storage stage-2), so we scope to ARN patterns under this account/region.
    consumerTaskDef.addToTaskRolePolicy(
      new iam.PolicyStatement({
        sid: 'KinesisRead',
        effect: iam.Effect.ALLOW,
        actions: [
          'kinesis:GetRecords',
          'kinesis:GetShardIterator',
          'kinesis:DescribeStream',
          'kinesis:ListShards',
        ],
        resources: [
          cdk.Arn.format({ service: 'kinesis', resource: 'stream', resourceName: 'topsales-*' }, this),
        ],
      }),
    );
    consumerTaskDef.addToTaskRolePolicy(
      new iam.PolicyStatement({
        sid: 'DlqSend',
        effect: iam.Effect.ALLOW,
        actions: ['sqs:SendMessage', 'sqs:GetQueueUrl'],
        resources: [cdk.Arn.format({ service: 'sqs', resource: 'topsales-*-dlq' }, this)],
      }),
    );
    props.servingTable.grantWriteData(consumerTaskDef.taskRole);
    new ecs.FargateService(this, 'Consumer', {
      cluster,
      taskDefinition: consumerTaskDef,
      desiredCount: 1,
    });

    // -----------------------------------------------------------------
    // forecaster: EventBridge cron -> one-shot ECS task. DESIGNED-ONLY image.
    // Writes versioned serving rows; reads aggregates from Aurora.
    // -----------------------------------------------------------------
    const forecasterTaskDef = new ecs.FargateTaskDefinition(this, 'ForecasterTaskDef', {
      cpu: 1024,
      memoryLimitMiB: 2048,
    });
    forecasterTaskDef.addContainer('forecaster', {
      image: ecs.ContainerImage.fromEcrRepository(forecasterRepo, props.imageTag),
      logging: ecs.LogDrivers.awsLogs({ streamPrefix: 'forecaster' }),
      environment: { SPRING_PROFILES_ACTIVE: 'aws' },
    });
    props.servingTable.grantWriteData(forecasterTaskDef.taskRole);
    props.auroraRef.secret.grantRead(forecasterTaskDef.taskRole);

    new events.Rule(this, 'ForecasterSchedule', {
      description: 'Nightly batch forecaster run — writes versioned serving rows.',
      schedule: events.Schedule.cron({ minute: '0', hour: '3' }),
      targets: [
        new targets.EcsTask({
          cluster,
          taskDefinition: forecasterTaskDef,
          taskCount: 1,
          subnetSelection: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
        }),
      ],
    });

    // -----------------------------------------------------------------
    // ADR-0009: prod SPA is on Vercel (outside AWS). No SPA hosting resources;
    // just record the CORS allow-listed origin for the API config / runbook.
    // -----------------------------------------------------------------
    new cdk.CfnOutput(this, 'CorsAllowedOrigin', {
      value: VERCEL_SPA_ORIGIN,
      description: 'CORS allow-listed prod SPA origin (Vercel, ADR-0009 — no AWS SPA hosting).',
    });
  }
}
