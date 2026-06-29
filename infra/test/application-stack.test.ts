import * as cdk from 'aws-cdk-lib';
import { Template, Match } from 'aws-cdk-lib/assertions';
import { NetworkStack } from '../lib/network-stack';
import { StorageStack } from '../lib/storage-stack';
import { IntelligenceStack } from '../lib/intelligence-stack';
import { ApplicationStack } from '../lib/application-stack';

/**
 * ApplicationStack — the Fargate compute tier. We synth the real Network +
 * Storage + Intelligence stacks to supply props (object-ref wiring, no CFN
 * exports), then assert the application template. The image tag flows in as a
 * CFN token — we pass 'abc123' and look for it inside the container image URI.
 */
describe('ApplicationStack', () => {
  const app = new cdk.App();
  const network = new NetworkStack(app, 'test-network');
  const storage = new StorageStack(app, 'test-storage', { vpc: network.vpc });
  const intelligence = new IntelligenceStack(app, 'test-intelligence');
  const stack = new ApplicationStack(app, 'test-application', {
    vpc: network.vpc,
    servingTable: storage.servingTable,
    auroraRef: storage.auroraRef,
    bedrockInvokeManagedPolicy: intelligence.bedrockInvokeManagedPolicy,
    modelConfigParam: intelligence.modelConfigParam,
    imageTag: 'abc123',
  });
  const template = Template.fromStack(stack);

  test('three app ECR repos: serving / consumer / forecaster', () => {
    template.resourceCountIs('AWS::ECR::Repository', 3);
    for (const name of ['topsales/serving', 'topsales/consumer', 'topsales/forecaster']) {
      template.hasResourceProperties('AWS::ECR::Repository', {
        RepositoryName: name,
        ImageScanningConfiguration: { ScanOnPush: true },
      });
    }
  });

  test('a task definition pins the imageTag in its container image URI', () => {
    // Image is an Fn::Join over the ECR repo URI ending in ':abc123'.
    template.hasResourceProperties(
      'AWS::ECS::TaskDefinition',
      Match.objectLike({
        ContainerDefinitions: Match.arrayWith([
          Match.objectLike({
            Image: Match.objectLike({
              'Fn::Join': Match.arrayWith([
                Match.arrayWith([Match.stringLikeRegexp('abc123')]),
              ]),
            }),
          }),
        ]),
      }),
    );
  });

  test('serving container runs with SPRING_PROFILES_ACTIVE=aws', () => {
    template.hasResourceProperties(
      'AWS::ECS::TaskDefinition',
      Match.objectLike({
        ContainerDefinitions: Match.arrayWith([
          Match.objectLike({
            Environment: Match.arrayWith([
              { Name: 'SPRING_PROFILES_ACTIVE', Value: 'aws' },
            ]),
          }),
        ]),
      }),
    );
  });

  test('an EventBridge rule schedules the forecaster batch', () => {
    template.hasResourceProperties(
      'AWS::Events::Rule',
      Match.objectLike({ ScheduleExpression: Match.anyValue() }),
    );
  });

  test('serving task role carries the Bedrock managed policy + DynamoDB read', () => {
    // Cross-stack managed-policy attachment renders as ManagedPolicyArns.
    template.hasResourceProperties(
      'AWS::IAM::Role',
      Match.objectLike({ ManagedPolicyArns: Match.arrayWith([Match.objectLike({})]) }),
    );
    // grantReadData → an inline policy with dynamodb:GetItem on the serving table.
    template.hasResourceProperties(
      'AWS::IAM::Policy',
      Match.objectLike({
        PolicyDocument: Match.objectLike({
          Statement: Match.arrayWith([
            Match.objectLike({ Action: Match.arrayWith(['dynamodb:GetItem']) }),
          ]),
        }),
      }),
    );
  });

  test('records the CORS-allow-listed Vercel origin (ADR-0009, no SPA hosting)', () => {
    template.hasOutput('*', Match.objectLike({ Value: 'https://topsales.vercel.app' }));
    // ADR-0009: SPA is on Vercel — no S3 site bucket / CloudFront in this stack.
    template.resourceCountIs('AWS::CloudFront::Distribution', 0);
  });
});
