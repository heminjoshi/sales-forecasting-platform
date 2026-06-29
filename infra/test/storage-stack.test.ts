import * as cdk from 'aws-cdk-lib';
import { Match, Template } from 'aws-cdk-lib/assertions';
import { NetworkStack } from '../lib/network-stack';
import { StorageStack } from '../lib/storage-stack';

/**
 * Synth the StorageStack in an in-test App with env UNSET. StorageStack needs a
 * VPC, so we wire a real NetworkStack vpc by object ref (same as bin/topsales.ts).
 */
function synth(): Template {
  const app = new cdk.App();
  const network = new NetworkStack(app, 'test-network');
  const storage = new StorageStack(app, 'test-storage', { vpc: network.vpc });
  return Template.fromStack(storage);
}

describe('StorageStack', () => {
  test('serving table is PAY_PER_REQUEST with PITR enabled', () => {
    synth().hasResourceProperties('AWS::DynamoDB::Table', {
      BillingMode: 'PAY_PER_REQUEST',
      PointInTimeRecoverySpecification: {
        PointInTimeRecoveryEnabled: true,
      },
    });
  });

  test('raw-log bucket blocks all public access, is versioned, and retains', () => {
    const template = synth();
    template.hasResourceProperties('AWS::S3::Bucket', {
      PublicAccessBlockConfiguration: {
        BlockPublicAcls: true,
        BlockPublicPolicy: true,
        IgnorePublicAcls: true,
        RestrictPublicBuckets: true,
      },
      VersioningConfiguration: { Status: 'Enabled' },
    });
    // RETAIN surfaces as a DeletionPolicy on the resource (not in Properties).
    template.hasResource('AWS::S3::Bucket', {
      DeletionPolicy: 'Retain',
    });
  });

  test('Aurora cluster is aurora-postgresql', () => {
    synth().hasResourceProperties('AWS::RDS::DBCluster', {
      Engine: 'aurora-postgresql',
    });
  });

  test('an SQS queue (the ingest DLQ) exists', () => {
    synth().resourceCountIs('AWS::SQS::Queue', 1);
  });

  test('a Kinesis ingest stream exists', () => {
    synth().resourceCountIs('AWS::Kinesis::Stream', 1);
  });

  test('an ElastiCache replication group exists', () => {
    synth().hasResourceProperties('AWS::ElastiCache::ReplicationGroup', {
      Engine: 'redis',
      CacheSubnetGroupName: Match.anyValue(),
    });
  });
});
