# infra — AWS CDK (TypeScript)

Production-topology infrastructure for the topsales platform, expressed as AWS
CDK in TypeScript. Five stacks (see Design-Doc §19):

```
NetworkStack ──▶ StorageStack (vpc)            ┐
             └─▶ ApplicationStack (vpc, …)     ├─▶ MonitoringStack (servingService)
IntelligenceStack ─────────────────────────────┘
```

| Stack | Purpose | Public fields (the stage-2 contract) |
|---|---|---|
| `NetworkStack` | VPC (+ endpoints, stage-2) | `vpc: ec2.Vpc` |
| `StorageStack` | S3 raw log, DynamoDB serving table, Aurora | `servingTable: dynamodb.Table`, `auroraRef: { cluster, secret }`, `rawLogBucket: s3.Bucket` |
| `IntelligenceStack` | Bedrock policy + model config | `bedrockInvokeManagedPolicy: iam.ManagedPolicy`, `modelConfigParam: ssm.StringParameter` |
| `ApplicationStack` | ECS Fargate serving service (ALB) | `servingService: ecsPatterns.ApplicationLoadBalancedFargateService` |
| `MonitoringStack` | CloudWatch dashboards/alarms | _(none)_ |

## SYNTH-ONLY — nothing is ever deployed

This project is **synth-only**. It exists to demonstrate and validate the
production topology; it is **never deployed**, has **no `cdk deploy`**, and
needs **no AWS account or credentials**.

It is **account-agnostic**: every stack is instantiated with `env` **unset**.
With no account/region, CDK produces region-agnostic CloudFormation and never
calls AWS — no `fromLookup`, no credential resolution. CI runs a bare
`cdk synth` (optionally `-c imageTag=<gitsha>`) and asserts the templates.

## L1 vs L2 idiom (Spring → CDK note)

CDK constructs come in layers. **L1** (`Cfn*`) are 1:1 with raw CloudFormation —
verbose, every property explicit. **L2** are the curated, higher-level
constructs (e.g. `s3.Bucket`, `dynamodb.Table`) with sane defaults, helper
methods (`grantRead`, `grantReadWriteData`), and auto-wired IAM. We prefer **L2**
and drop to L1 only when an L2 escape hatch is needed. (Think L2 ≈ Spring Boot
starter autoconfig, L1 ≈ hand-rolled bean wiring.)

## How to run

```bash
npm ci                       # reproducible install from package-lock.json
npx cdk synth                # bare synth → cdk.out/ (imageTag defaults to dev-local)
npx cdk synth -c imageTag=$(git rev-parse --short HEAD)   # CI form
npm test                     # jest assertions over the synthesized templates
npm run build                # tsc type-check (no emit needed for synth)
```

## Pinned versions

`aws-cdk-lib` is pinned with **no caret** (exact `2.177.0`) and the `aws-cdk`
CLI devDependency is pinned to the **same** version — CLI/library version drift
is the classic synth break, so they move together in lockstep.
