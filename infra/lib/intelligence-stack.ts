import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import * as ecr from 'aws-cdk-lib/aws-ecr';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as sagemaker from 'aws-cdk-lib/aws-sagemaker';
import * as bedrock from 'aws-cdk-lib/aws-bedrock';

/**
 * IntelligenceStack — the ML/GenAI control plane (the AI/ML governance surface):
 *
 *  - the least-privilege ManagedPolicy for the single Bedrock `InvokeModel` call
 *    (scoped to a foundation-model ARN pattern, never `*`) + `ApplyGuardrail` on
 *    the guardrail we own;
 *  - a Bedrock **Guardrail** enforcing the "descriptive, not advisory" + prompt-
 *    injection-safe insight design (deny financial/investment advice, content
 *    filters, PII handling);
 *  - the SSM-held model config the serving service reads at runtime;
 *  - the SageMaker model registry + execution role and the ML image/artifact
 *    stores (designed-only path for the ML forecaster).
 *
 * Account-agnostic: `env` is unset on every stack, so region/account resolve to
 * CFN pseudo-params (tokens) and nothing is ever looked up at synth.
 */
export class IntelligenceStack extends cdk.Stack {
  /** Attach to the serving task role to permit the one Bedrock InvokeModel call. */
  public readonly bedrockInvokeManagedPolicy: iam.ManagedPolicy;

  /** SSM parameter holding the active model id / insight config (read at runtime). */
  public readonly modelConfigParam: ssm.StringParameter;

  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

    // ---------------------------------------------------------------------
    // Bedrock Guardrail (L1).
    //
    // We use the L1 `CfnGuardrail` from `aws-cdk-lib/aws-bedrock` rather than the
    // alpha L2 (`@aws-cdk/aws-bedrock-alpha`). That's the "CfnGuardrail drift"
    // fix: the alpha L2 API churns between releases and isn't pinned in our
    // synth-only lib set, so we bind to the stable CloudFormation-backed L1.
    //
    // The guardrail encodes the Phase-5 insight contract at the platform edge:
    //  - topicPolicyConfig DENYs financial / investment advice ("descriptive not
    //    advisory" — the model narrates computed numbers, it never recommends);
    //  - contentPolicyConfig filters abusive/injection-style input (category
    //    names are untrusted);
    //  - sensitiveInformationPolicyConfig anonymizes PII that should never appear
    //    in a top-k narrative.
    // ---------------------------------------------------------------------
    const guardrail = new bedrock.CfnGuardrail(this, 'InsightGuardrail', {
      name: `${this.stackName}-insight-guardrail`,
      description:
        'Topsales insight guardrail — descriptive-only narration over computed top-k numbers; ' +
        'denies financial/investment advice, filters injection-style input, anonymizes PII.',
      blockedInputMessaging:
        'This request was blocked. Insights describe computed sales figures only.',
      blockedOutputsMessaging:
        'This response was blocked. Insights describe computed sales figures only and never give advice.',
      contentPolicyConfig: {
        filtersConfig: [
          { type: 'PROMPT_ATTACK', inputStrength: 'HIGH', outputStrength: 'NONE' },
          { type: 'HATE', inputStrength: 'HIGH', outputStrength: 'HIGH' },
          { type: 'INSULTS', inputStrength: 'HIGH', outputStrength: 'HIGH' },
          { type: 'MISCONDUCT', inputStrength: 'HIGH', outputStrength: 'HIGH' },
        ],
      },
      topicPolicyConfig: {
        topicsConfig: [
          {
            name: 'FinancialAdvice',
            type: 'DENY',
            definition:
              'Investment, trading, or financial recommendations / advice, or any forward-looking ' +
              'guidance to buy, sell, hold, or allocate. Insights are descriptive only.',
            examples: [
              'Should we invest more in this category?',
              'What should we buy next quarter?',
              'Is now a good time to expand this product line?',
            ],
          },
        ],
      },
      sensitiveInformationPolicyConfig: {
        piiEntitiesConfig: [
          { type: 'EMAIL', action: 'ANONYMIZE' },
          { type: 'PHONE', action: 'ANONYMIZE' },
          { type: 'CREDIT_DEBIT_CARD_NUMBER', action: 'BLOCK' },
        ],
      },
    });

    // Immutable published version pinned by the runtime (DRAFT otherwise floats).
    const guardrailVersion = new bedrock.CfnGuardrailVersion(this, 'InsightGuardrailVersion', {
      guardrailIdentifier: guardrail.attrGuardrailId,
      description: 'Initial published version of the topsales insight guardrail.',
    });
    guardrailVersion.addDependency(guardrail);

    // ---------------------------------------------------------------------
    // Least-privilege Bedrock InvokeModel policy.
    //
    // Foundation-model ARNs carry NO account id (they're AWS-owned), so the
    // resource is `arn:<partition>:bedrock:<region>::foundation-model/*`. Both
    // region & partition are tokens under account-agnostic synth. ApplyGuardrail
    // is scoped to the guardrail we just created.
    // ---------------------------------------------------------------------
    const foundationModelArn = cdk.Arn.format(
      { service: 'bedrock', account: '', resource: 'foundation-model', resourceName: '*' },
      this,
    );

    this.bedrockInvokeManagedPolicy = new iam.ManagedPolicy(this, 'BedrockInvokePolicy', {
      description: 'Permits the serving task the single Bedrock InvokeModel call + ApplyGuardrail.',
      statements: [
        new iam.PolicyStatement({
          sid: 'InvokeFoundationModel',
          effect: iam.Effect.ALLOW,
          actions: ['bedrock:InvokeModel', 'bedrock:InvokeModelWithResponseStream'],
          resources: [foundationModelArn],
        }),
        new iam.PolicyStatement({
          sid: 'ApplyInsightGuardrail',
          effect: iam.Effect.ALLOW,
          actions: ['bedrock:ApplyGuardrail'],
          resources: [guardrail.attrGuardrailArn],
        }),
      ],
    });

    // ---------------------------------------------------------------------
    // Runtime model config (read by the serving service via SSM at startup).
    // ---------------------------------------------------------------------
    this.modelConfigParam = new ssm.StringParameter(this, 'ModelConfig', {
      parameterName: '/topsales/intelligence/model-config',
      description: 'Active Bedrock model id + insight generation config for the serving service.',
      stringValue: JSON.stringify({
        modelId: 'anthropic.claude-3-haiku-20240307-v1:0',
        region: this.region,
        maxTokens: 512,
        temperature: 0.2,
        guardrailId: guardrail.attrGuardrailId,
      }),
    });

    // ---------------------------------------------------------------------
    // ML artifact stores (designed-only ML forecaster path).
    // ---------------------------------------------------------------------
    const mlImageRepo = new ecr.Repository(this, 'MlModelRepo', {
      repositoryName: 'topsales/ml-model',
      imageScanOnPush: true,
      lifecycleRules: [{ maxImageCount: 10 }],
      removalPolicy: cdk.RemovalPolicy.DESTROY,
      emptyOnDelete: true,
    });

    const artifactBucket = new s3.Bucket(this, 'MlArtifactBucket', {
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      encryption: s3.BucketEncryption.S3_MANAGED,
      enforceSSL: true,
      versioned: true,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
      autoDeleteObjects: true,
    });

    // SageMaker model registry (versioned model packages live under this group).
    new sagemaker.CfnModelPackageGroup(this, 'ModelPackageGroup', {
      modelPackageGroupName: 'topsales-forecaster',
      modelPackageGroupDescription: 'Registry for topsales demand-forecaster model packages.',
    });

    // SageMaker execution role: training/inference jobs assume this to read model
    // artifacts (S3), pull the ML image (ECR), and write logs (CloudWatch).
    const sagemakerRole = new iam.Role(this, 'SageMakerExecutionRole', {
      assumedBy: new iam.ServicePrincipal('sagemaker.amazonaws.com'),
      description: 'SageMaker execution role — S3 artifacts, ECR pull, CloudWatch logs.',
    });
    artifactBucket.grantReadWrite(sagemakerRole);
    mlImageRepo.grantPull(sagemakerRole);
    sagemakerRole.addToPolicy(
      new iam.PolicyStatement({
        sid: 'CloudWatchLogs',
        effect: iam.Effect.ALLOW,
        actions: [
          'logs:CreateLogGroup',
          'logs:CreateLogStream',
          'logs:PutLogEvents',
          'logs:DescribeLogStreams',
        ],
        resources: [
          cdk.Arn.format(
            {
              service: 'logs',
              resource: 'log-group',
              resourceName: '/aws/sagemaker/*',
              arnFormat: cdk.ArnFormat.COLON_RESOURCE_NAME,
            },
            this,
          ),
        ],
      }),
    );
  }
}
