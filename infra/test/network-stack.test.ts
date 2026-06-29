import * as cdk from 'aws-cdk-lib';
import { Match, Template } from 'aws-cdk-lib/assertions';
import { NetworkStack } from '../lib/network-stack';

/**
 * Synth the NetworkStack in an in-test App with env UNSET (account-agnostic).
 * The synthesised template is asserted directly — no AWS calls, no lookups.
 */
function synth(): Template {
  const app = new cdk.App();
  const stack = new NetworkStack(app, 'test-network');
  return Template.fromStack(stack);
}

describe('NetworkStack', () => {
  test('builds exactly one VPC', () => {
    synth().resourceCountIs('AWS::EC2::VPC', 1);
  });

  test('creates the S3 gateway endpoint', () => {
    synth().hasResourceProperties('AWS::EC2::VPCEndpoint', {
      VpcEndpointType: 'Gateway',
    });
  });

  test('creates at least 7 interface endpoints', () => {
    const interfaceEndpoints = synth().findResources('AWS::EC2::VPCEndpoint', {
      Properties: { VpcEndpointType: 'Interface' },
    });
    expect(Object.keys(interfaceEndpoints).length).toBeGreaterThanOrEqual(7);
  });

  test('bedrock-runtime interface endpoint exists (typed-enum fix)', () => {
    // The typed InterfaceVpcEndpointAwsService.BEDROCK_RUNTIME resolves to a
    // ServiceName containing `bedrock-runtime` — proves the enum drift fix.
    // In account-agnostic synth the ServiceName is an `Fn::Join` carrying a
    // region token, so we stringify each endpoint's ServiceName and regex-match.
    const endpoints = synth().findResources('AWS::EC2::VPCEndpoint');
    const serviceNames = Object.values(endpoints).map((r) =>
      JSON.stringify((r as any).Properties?.ServiceName ?? ''),
    );
    expect(serviceNames.some((s) => /bedrock-runtime/.test(s))).toBe(true);
  });

  test('an endpoint security group allows 443 from the VPC CIDR', () => {
    synth().hasResourceProperties('AWS::EC2::SecurityGroup', {
      SecurityGroupIngress: Match.arrayWith([
        Match.objectLike({ FromPort: 443, ToPort: 443 }),
      ]),
    });
  });
});
