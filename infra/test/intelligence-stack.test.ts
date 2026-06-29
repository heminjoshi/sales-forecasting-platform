import * as cdk from 'aws-cdk-lib';
import { Template, Match } from 'aws-cdk-lib/assertions';
import { IntelligenceStack } from '../lib/intelligence-stack';

/**
 * IntelligenceStack — the AI/ML governance surface. We validate that the
 * guardrail, the least-privilege Bedrock policy, the model-config SSM param, and
 * the SageMaker/ML registry pieces all materialize. Account-agnostic synth (no
 * `env`), so resources resolve against CFN pseudo-params only.
 */
describe('IntelligenceStack', () => {
  const app = new cdk.App();
  const stack = new IntelligenceStack(app, 'test-intelligence');
  const template = Template.fromStack(stack);

  test('exactly one Bedrock Guardrail with content + topic policies', () => {
    template.resourceCountIs('AWS::Bedrock::Guardrail', 1);
    template.hasResourceProperties(
      'AWS::Bedrock::Guardrail',
      Match.objectLike({
        // Content filters (injection/abuse) present.
        ContentPolicyConfig: Match.objectLike({
          FiltersConfig: Match.arrayWith([
            Match.objectLike({ Type: 'PROMPT_ATTACK' }),
          ]),
        }),
        // "Descriptive not advisory" — financial-advice topic DENY present.
        TopicPolicyConfig: Match.objectLike({
          TopicsConfig: Match.arrayWith([
            Match.objectLike({ Type: 'DENY' }),
          ]),
        }),
      }),
    );
  });

  test('a published Guardrail version is pinned', () => {
    template.resourceCountIs('AWS::Bedrock::GuardrailVersion', 1);
  });

  test('model-config SSM String parameter at the expected name', () => {
    template.hasResourceProperties('AWS::SSM::Parameter', {
      Name: '/topsales/intelligence/model-config',
      Type: 'String',
    });
  });

  test('Bedrock managed policy permits InvokeModel + ApplyGuardrail, not "*"', () => {
    template.hasResourceProperties(
      'AWS::IAM::ManagedPolicy',
      Match.objectLike({
        PolicyDocument: Match.objectLike({
          Statement: Match.arrayWith([
            Match.objectLike({
              Action: Match.arrayWith(['bedrock:InvokeModel']),
            }),
          ]),
        }),
      }),
    );
    // ApplyGuardrail statement is present too.
    template.hasResourceProperties(
      'AWS::IAM::ManagedPolicy',
      Match.objectLike({
        PolicyDocument: Match.objectLike({
          Statement: Match.arrayWith([
            Match.objectLike({ Action: 'bedrock:ApplyGuardrail' }),
          ]),
        }),
      }),
    );
  });

  test('InvokeModel is scoped to the single insight model, not foundation-model/*', () => {
    // Least privilege: the resource ARN ends in the concrete model id; a bare
    // `foundation-model/*` (any model) must NOT appear.
    const policies = template.findResources('AWS::IAM::ManagedPolicy');
    const rendered = JSON.stringify(policies);
    expect(rendered).toContain('foundation-model/anthropic.claude-3-haiku-20240307-v1:0');
    expect(rendered).not.toContain('foundation-model/*');
  });

  test('SageMaker model registry + execution role (sagemaker trust)', () => {
    template.resourceCountIs('AWS::SageMaker::ModelPackageGroup', 1);
    template.hasResourceProperties('AWS::SageMaker::ModelPackageGroup', {
      ModelPackageGroupName: 'topsales-forecaster',
    });
    template.hasResourceProperties(
      'AWS::IAM::Role',
      Match.objectLike({
        AssumeRolePolicyDocument: Match.objectLike({
          Statement: Match.arrayWith([
            Match.objectLike({
              Principal: { Service: 'sagemaker.amazonaws.com' },
            }),
          ]),
        }),
      }),
    );
  });

  test('ML ECR repo (scan-on-push) + versioned artifact bucket', () => {
    template.hasResourceProperties('AWS::ECR::Repository', {
      RepositoryName: 'topsales/ml-model',
      ImageScanningConfiguration: { ScanOnPush: true },
    });
    template.hasResourceProperties(
      'AWS::S3::Bucket',
      Match.objectLike({
        VersioningConfiguration: { Status: 'Enabled' },
      }),
    );
  });
});
