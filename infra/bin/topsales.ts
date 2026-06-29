#!/usr/bin/env node
import * as cdk from 'aws-cdk-lib';
import { resolveAppName, resolveImageTag } from '../lib/config';
import { NetworkStack } from '../lib/network-stack';
import { StorageStack } from '../lib/storage-stack';
import { IntelligenceStack } from '../lib/intelligence-stack';
import { ApplicationStack } from '../lib/application-stack';
import { MonitoringStack } from '../lib/monitoring-stack';

const app = new cdk.App();

const appName = resolveAppName(app);
const imageTag = resolveImageTag(app);

// ACCOUNT-AGNOSTIC SYNTH: we deliberately leave `env` UNSET on every stack.
// With no account/region, CDK synthesizes region-agnostic templates and never
// calls AWS (no `fromLookup`, no creds). This is a synth-only skeleton — nothing
// here is ever deployed — so an env would only invite environment-specific
// lookups we don't want. CI runs `cdk synth` with no AWS context.

// Single App: cross-stack references are wired by PROPS (object refs), not by
// CloudFormation exports. Dependency order falls out of the wiring below.
const network = new NetworkStack(app, `${appName}-network`);

const storage = new StorageStack(app, `${appName}-storage`, {
  vpc: network.vpc,
});

const intelligence = new IntelligenceStack(app, `${appName}-intelligence`);

const application = new ApplicationStack(app, `${appName}-application`, {
  vpc: network.vpc,
  servingTable: storage.servingTable,
  auroraRef: storage.auroraRef,
  bedrockInvokeManagedPolicy: intelligence.bedrockInvokeManagedPolicy,
  modelConfigParam: intelligence.modelConfigParam,
  imageTag,
});

new MonitoringStack(app, `${appName}-monitoring`, {
  servingService: application.servingService,
});

app.synth();
