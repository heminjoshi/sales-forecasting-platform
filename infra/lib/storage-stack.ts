import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as rds from 'aws-cdk-lib/aws-rds';
import * as kinesis from 'aws-cdk-lib/aws-kinesis';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import * as elasticache from 'aws-cdk-lib/aws-elasticache';
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager';

/**
 * Reference passed by props to the Application stack so the serving service can
 * reach Aurora (cluster endpoint + the generated credentials secret) without a
 * cross-stack CloudFormation export — it's all one App, wired by object refs.
 */
export interface AuroraRef {
  readonly cluster: rds.IDatabaseCluster;
  readonly secret: secretsmanager.ISecret;
}

export interface StorageStackProps extends cdk.StackProps {
  /** Shared VPC from {@link NetworkStack}. */
  readonly vpc: ec2.Vpc;
}

/**
 * StorageStack — durable state: S3 raw log, Kinesis ingest stream (+ DLQ),
 * DynamoDB serving table, Aurora aggregates, and the ElastiCache Redis used by
 * the Phase-4 cache-aside read path.
 *
 * STAGE-2: real keys/indexes, encryption, removal policies, PITR, and the
 * isolated-subnet placement for the stateful stores.
 */
export class StorageStack extends cdk.Stack {
  /** DynamoDB serving table (precomputed top-k / forecast serving rows). */
  public readonly servingTable: dynamodb.Table;

  /** Aurora cluster + its credentials secret (consumed by ApplicationStack). */
  public readonly auroraRef: AuroraRef;

  /** S3 bucket backing the raw event log (the `aws`-profile raw sink). */
  public readonly rawLogBucket: s3.Bucket;

  constructor(scope: Construct, id: string, props: StorageStackProps) {
    super(scope, id, props);

    // ---- Raw event log ----------------------------------------------------
    // Versioned + RETAIN: the raw log is the system of record for replay, so it
    // must survive a stack delete and keep prior object versions.
    this.rawLogBucket = new s3.Bucket(this, 'RawLogBucket', {
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      encryption: s3.BucketEncryption.S3_MANAGED,
      enforceSSL: true,
      versioned: true,
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });

    // ---- Ingestion stream + dead-letter queue -----------------------------
    // ON_DEMAND keeps the synth account-agnostic (no shard-count tuning) and
    // matches the bursty ingestion profile. The DLQ receives events the
    // consumer can't process after retries (parked for quarantine inspection).
    const ingestStream = new kinesis.Stream(this, 'IngestStream', {
      streamMode: kinesis.StreamMode.ON_DEMAND,
    });

    const ingestDlq = new sqs.Queue(this, 'IngestDlq', {
      retentionPeriod: cdk.Duration.days(14),
      enforceSSL: true,
    });
    // Reference both so they are not pruned as unused at synth.
    void ingestStream;
    void ingestDlq;

    // ---- Serving table ----------------------------------------------------
    // PK = tenant#category#channel, SK = horizon#version — the composite serving
    // key for the precomputed top-k / forecast rows. PAY_PER_REQUEST avoids
    // capacity planning; PITR gives 35-day continuous backup for the read plane.
    this.servingTable = new dynamodb.Table(this, 'ServingTable', {
      partitionKey: { name: 'pk', type: dynamodb.AttributeType.STRING },
      sortKey: { name: 'sk', type: dynamodb.AttributeType.STRING },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      pointInTimeRecovery: true,
    });

    // ---- Aurora PostgreSQL (aggregates) -----------------------------------
    // Fixed engine version (no lookup), isolated subnets (no internet route),
    // generated credentials stored in Secrets Manager (no plaintext password).
    const cluster = new rds.DatabaseCluster(this, 'Aurora', {
      engine: rds.DatabaseClusterEngine.auroraPostgres({
        version: rds.AuroraPostgresEngineVersion.VER_16_4,
      }),
      vpc: props.vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_ISOLATED },
      credentials: rds.Credentials.fromGeneratedSecret('topsales'),
      serverlessV2MinCapacity: 0.5,
      serverlessV2MaxCapacity: 2,
      writer: rds.ClusterInstance.serverlessV2('Writer'),
    });

    this.auroraRef = {
      cluster,
      // DatabaseCluster with generated credentials always has a secret.
      secret: cluster.secret!,
    };

    // ---- ElastiCache Redis (cache-aside read path) ------------------------
    // L1 CfnReplicationGroup (no stable L2 in this CDK version) in the isolated
    // subnets, locked down by its own SG that only accepts 6379 from the VPC.
    const redisSubnetGroup = new elasticache.CfnSubnetGroup(this, 'RedisSubnetGroup', {
      description: 'Isolated subnets for the topsales Redis replication group',
      subnetIds: props.vpc.selectSubnets({
        subnetType: ec2.SubnetType.PRIVATE_ISOLATED,
      }).subnetIds,
    });

    const redisSecurityGroup = new ec2.SecurityGroup(this, 'RedisSg', {
      vpc: props.vpc,
      description: 'Allow Redis (6379) from within the VPC',
      allowAllOutbound: true,
    });
    redisSecurityGroup.addIngressRule(
      ec2.Peer.ipv4(props.vpc.vpcCidrBlock),
      ec2.Port.tcp(6379),
      'Redis from within the VPC CIDR',
    );

    const redis = new elasticache.CfnReplicationGroup(this, 'Redis', {
      replicationGroupDescription: 'topsales cache-aside read-path cache',
      engine: 'redis',
      cacheNodeType: 'cache.t4g.micro',
      numCacheClusters: 2,
      automaticFailoverEnabled: true,
      atRestEncryptionEnabled: true,
      transitEncryptionEnabled: true,
      cacheSubnetGroupName: redisSubnetGroup.ref,
      securityGroupIds: [redisSecurityGroup.securityGroupId],
    });
    redis.addDependency(redisSubnetGroup);
  }
}
