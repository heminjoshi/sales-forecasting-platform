import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as ec2 from 'aws-cdk-lib/aws-ec2';

/**
 * NetworkStack — the VPC the serving tier and data stores live in.
 *
 * STAGE-2: a 2-AZ VPC with a three-tier subnet strategy, the S3 gateway
 * endpoint, and the full set of interface endpoints so the serving/ingestion/ML
 * planes reach AWS APIs privately (no public egress required for AWS calls).
 *
 * Account-agnostic: maxAzs:2 with an explicit `subnetConfiguration` means CDK
 * never calls `Vpc.fromLookup`/AZ-context lookups at synth — the template stays
 * region-agnostic and CI synthesises with no AWS creds.
 */
export class NetworkStack extends cdk.Stack {
  /** Shared VPC consumed by Storage, Application (and Intelligence endpoints). */
  public readonly vpc: ec2.Vpc;

  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

    // Three-tier subnet layout across 2 AZs:
    //  - PUBLIC: ALB + NAT gateway.
    //  - PRIVATE_WITH_EGRESS: Fargate tasks (egress via NAT for non-AWS pulls).
    //  - PRIVATE_ISOLATED: Aurora + ElastiCache (no route to the internet).
    this.vpc = new ec2.Vpc(this, 'Vpc', {
      maxAzs: 2,
      natGateways: 1,
      subnetConfiguration: [
        {
          name: 'public',
          subnetType: ec2.SubnetType.PUBLIC,
          cidrMask: 24,
        },
        {
          name: 'private-egress',
          subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS,
          cidrMask: 24,
        },
        {
          name: 'isolated',
          subnetType: ec2.SubnetType.PRIVATE_ISOLATED,
          cidrMask: 24,
        },
      ],
    });

    // S3 is a GATEWAY endpoint (route-table entry, no ENI/hourly cost) — used by
    // the raw-log bucket reads/writes and ECR layer pulls (layers live in S3).
    this.vpc.addGatewayEndpoint('S3Gateway', {
      service: ec2.GatewayVpcEndpointAwsService.S3,
    });

    // One SG shared by the interface endpoints: allow 443 from anywhere in the
    // VPC so any subnet (Fargate, batch, etc.) can reach the endpoint ENIs.
    const endpointSecurityGroup = new ec2.SecurityGroup(this, 'EndpointSg', {
      vpc: this.vpc,
      description: 'Allow HTTPS from within the VPC to interface VPC endpoints',
      allowAllOutbound: true,
    });
    endpointSecurityGroup.addIngressRule(
      ec2.Peer.ipv4(this.vpc.vpcCidrBlock),
      ec2.Port.tcp(443),
      'HTTPS from within the VPC CIDR',
    );

    // Interface endpoints (AWS PrivateLink ENIs) for every AWS API the platform
    // calls. Using the TYPED `InterfaceVpcEndpointAwsService` enums (rather than
    // hand-built service-name strings) is the drift fix: e.g. BEDROCK_RUNTIME
    // resolves to the correct `bedrock-runtime` service name for the synth region.
    const interfaceServices: Record<string, ec2.InterfaceVpcEndpointAwsService> = {
      Ecr: ec2.InterfaceVpcEndpointAwsService.ECR,
      EcrDocker: ec2.InterfaceVpcEndpointAwsService.ECR_DOCKER,
      CloudWatchLogs: ec2.InterfaceVpcEndpointAwsService.CLOUDWATCH_LOGS,
      CloudWatchMonitoring: ec2.InterfaceVpcEndpointAwsService.CLOUDWATCH_MONITORING,
      Ssm: ec2.InterfaceVpcEndpointAwsService.SSM,
      SecretsManager: ec2.InterfaceVpcEndpointAwsService.SECRETS_MANAGER,
      KinesisStreams: ec2.InterfaceVpcEndpointAwsService.KINESIS_STREAMS,
      BedrockRuntime: ec2.InterfaceVpcEndpointAwsService.BEDROCK_RUNTIME,
      Bedrock: ec2.InterfaceVpcEndpointAwsService.BEDROCK,
      SageMakerApi: ec2.InterfaceVpcEndpointAwsService.SAGEMAKER_API,
      SageMakerRuntime: ec2.InterfaceVpcEndpointAwsService.SAGEMAKER_RUNTIME,
    };

    for (const [id, service] of Object.entries(interfaceServices)) {
      this.vpc.addInterfaceEndpoint(`${id}Endpoint`, {
        service,
        securityGroups: [endpointSecurityGroup],
        // Endpoint ENIs live in the egress-private subnets alongside the tasks.
        subnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      });
    }
  }
}
