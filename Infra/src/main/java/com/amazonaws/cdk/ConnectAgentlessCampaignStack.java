/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: MIT-0
 */

package com.amazonaws.cdk;

import io.github.cdklabs.cdknag.NagPackSuppression;
import io.github.cdklabs.cdknag.NagSuppressions;
import software.amazon.awscdk.*;
import software.amazon.awscdk.services.connect.CfnContactFlow;
import software.amazon.awscdk.services.connect.CfnInstance;
import software.amazon.awscdk.services.connect.CfnInstanceStorageConfig;
import software.amazon.awscdk.services.connect.CfnPhoneNumber;
import software.amazon.awscdk.services.dynamodb.*;
import software.amazon.awscdk.services.iam.Policy;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.kms.Key;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.s3.BlockPublicAccess;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.BucketEncryption;
import software.amazon.awscdk.services.s3.deployment.BucketDeployment;
import software.amazon.awscdk.services.s3.deployment.Source;
import software.amazon.awscdk.services.stepfunctions.*;
import software.constructs.Construct;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
// import software.amazon.awscdk.Duration;
// import software.amazon.awscdk.services.sqs.Queue;

public class ConnectAgentlessCampaignStack extends Stack {
    public ConnectAgentlessCampaignStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public ConnectAgentlessCampaignStack(final Construct scope, final String id, final StackProps props) {

        super(scope, id, props);

        // Amazon Connect Instance Alias
        CfnParameter connectInstanceAlias = CfnParameter.Builder.create(this, "connectInstanceAlias")
                .description("Enter Unique Connect Instance Alias")
                .defaultValue("connect-" + System.currentTimeMillis())
                .type("String")
                .build();

        // Logging S3 Bucket's KMS Key
        Key loggingBucketKey = Key.Builder.create(this, "LoggingBucketKey")
                .alias("LoggingBucketKey4ConnectAgentlessCampaign")
                .enableKeyRotation(true)
                .pendingWindow(Duration.days(7))
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        // Logging S3 Bucket
        Bucket loggingBucket = Bucket.Builder.create(this, "LoggingBucket")
                .enforceSsl(true)
                .encryption(BucketEncryption.KMS)
                .encryptionKey(loggingBucketKey)
                .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .versioned(true)
                .removalPolicy(RemovalPolicy.DESTROY)
                .autoDeleteObjects(true)
                .build();

        // KMS Key for Amazon Connect Encryption
        Key amazonConnectManagedKeyAlias = Key.Builder.create(this, "AmazonConnectAgentlessManagedKeyAlias")
                .alias("AmazonConnectAgentlessCampaignKMSKey")
                .enableKeyRotation(true)
                .pendingWindow(Duration.days(7))
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        // Amazon Connect Instance
        CfnInstance amazonConnect = CfnInstance.Builder.create(this, "connect-example")
                .instanceAlias(connectInstanceAlias.getValueAsString())
                .attributes(CfnInstance.AttributesProperty.builder()
                        .autoResolveBestVoices(true)
                        .contactflowLogs(true)
                        .contactLens(true)
                        .inboundCalls(true)
                        .outboundCalls(true)
                        .build())
                .identityManagementType("CONNECT_MANAGED")
                .build();

        // Claim Phone Number for Amazon Connect Instance
        CfnPhoneNumber cfnPhoneNumber = CfnPhoneNumber.Builder.create(this, "connect-example-phone-number")
                .countryCode("US")
                .targetArn(amazonConnect.getAttrArn())
                .type("TOLL_FREE")
                .build();


        // Amazon Connect S3 Bucket
        Bucket amazonConnectS3Bucket = Bucket.Builder.create(this, "amazon-connect-s3-bucket")
                .bucketName("amazon-connect-" + connectInstanceAlias.getValueAsString())
                .enforceSsl(true)
                .encryption(BucketEncryption.KMS)
                .encryptionKey(amazonConnectManagedKeyAlias)
                .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .versioned(true)
                .serverAccessLogsBucket(loggingBucket)
                .serverAccessLogsPrefix("connectBucket/")
                // Below 2 options can be ignored for Prod Amazon Connect Instance.
                .removalPolicy(RemovalPolicy.DESTROY)
                .autoDeleteObjects(true)
                .build();

        createS3StorageConfig(amazonConnect, "CALL_RECORDINGS", "connect/" + amazonConnect.getInstanceAlias() + "/CallRecordings", amazonConnectManagedKeyAlias.getKeyArn(), amazonConnectS3Bucket);
        createS3StorageConfig(amazonConnect, "CHAT_TRANSCRIPTS", "connect/" + amazonConnect.getInstanceAlias() + "/ChatTranscripts", amazonConnectManagedKeyAlias.getKeyArn(), amazonConnectS3Bucket);
        createS3StorageConfig(amazonConnect, "SCHEDULED_REPORTS", "connect/" + amazonConnect.getInstanceAlias() + "/Reports", amazonConnectManagedKeyAlias.getKeyArn(), amazonConnectS3Bucket);

        // Create Amazon Connect Contact Flow
        CfnContactFlow cfnContactFlow = null;
        try {
            cfnContactFlow = CfnContactFlow.Builder.create(this, "amazon-connect-outbound-contactflow")
                    .name("AgentlessOutboundCampaign-English")
                    .content(Files.readString(Path.of("../Connect/AgentlessOutboundCampaign-English")))
                    .instanceArn(amazonConnect.getAttrArn())
                    .state("ACTIVE")
                    .type("CONTACT_FLOW")
                    .build();

            CfnOutput.Builder.create(this, "Amazon-Connect-Instance-ARN")
                    .description("Amazon Connect Instance ARN")
                    .value(amazonConnect.getAttrArn())
                    .build();

            CfnOutput.Builder.create(this, "Amazon-Connect-ContactFlow-ARN")
                    .description("Amazon Connect Contact Flow ARN")
                    .value(cfnContactFlow.getAttrContactFlowArn())
                    .build();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // Create S3 bucket for uploading the contact list
        Bucket agentlessCampaignS3 = Bucket.Builder.create(this, "AgentlessCampaignS3")
                .bucketName("amazon-connect-agentless-outbound-campaign-" + Instant.now().toEpochMilli())
                .enforceSsl(true)
                .encryption(BucketEncryption.S3_MANAGED)
                .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .versioned(true)
                .serverAccessLogsBucket(loggingBucket)
                .serverAccessLogsPrefix("connectAgentlessCampaignBucket/")
                // Below 2 options can be ignored for Prod Amazon Connect Instance.
                .removalPolicy(RemovalPolicy.DESTROY)
                .autoDeleteObjects(true)
                .build();


        // Create DynamoDB table to capture the execution Information
        TableProps tablePropsExec = TableProps.builder()
                .tableName("AmazonConnectAgentlessOutboundCampaign")
                .partitionKey(Attribute.builder()
                        .name("campaignExecutionId")
                        .type(AttributeType.STRING)
                        .build())
                .sortKey(Attribute.builder()
                        .name("phoneNumber")
                        .type(AttributeType.STRING)
                        .build())
                .removalPolicy(RemovalPolicy.DESTROY)
                .pointInTimeRecovery(true)
                .encryption(TableEncryption.CUSTOMER_MANAGED)
                .encryptionKey(amazonConnectManagedKeyAlias)
                .build();
        Table tableExec = new Table(this, "AgentlessCampaignDDB", tablePropsExec);

        // Create DynamoDB table to capture the contact information
        TableProps tablePropsContacts = TableProps.builder()
                .tableName("AmazonConnectAgentlessOutboundCampaign-Contacts")
                .partitionKey(Attribute.builder()
                        .name("phoneNumber")
                        .type(AttributeType.STRING)
                        .build())
                .sortKey(Attribute.builder()
                        .name("userLanguage")
                        .type(AttributeType.STRING)
                        .build())
                .removalPolicy(RemovalPolicy.DESTROY)
                .pointInTimeRecovery(true)
                .encryption(TableEncryption.CUSTOMER_MANAGED)
                .encryptionKey(amazonConnectManagedKeyAlias)
                .build();
        Table tableContacts = new Table(this, "AgentlessCampaignContactsDDB", tablePropsContacts);


        try {
            String intakeASL = Files.readString(Path.of("../StepFunction/IntakeProcess/AmazonConnectAgentlessOutboundCampaign-Intake.asl.json"));
            intakeASL = intakeASL.replace("<<DDB-CONTACTS-TABLE-NAME>>", tableContacts.getTableName());
            intakeASL = intakeASL.replace("<<S3-BUCKET-NAME>>", agentlessCampaignS3.getBucketName());

            // Create a new IAM role for the state machine
            Role stateMachineRoleIntake = Role.Builder.create(this, "stateMachineRoleIntake")
                    .assumedBy(new ServicePrincipal("states.amazonaws.com"))
                    .build();

//            stateMachineRoleIntake.addToPolicy(PolicyStatement.Builder.create()
//                    .actions(List.of("states:StartExecution", "states:DescribeExecution", "states:StopExecution"))
//                    .resources(List.of("*"))
//                    .build());

            StateMachine stepFunctionIntake = StateMachine.Builder.create(this, "AmazonConnectAgentlessOutboundCampaign-Intake")
                    .stateMachineName("AmazonConnectAgentlessOutboundCampaign-Intake-CDK")
                    .definitionBody(DefinitionBody.fromString(intakeASL))
                    .removalPolicy(RemovalPolicy.DESTROY)
                    .stateMachineType(StateMachineType.STANDARD)
                    .logs(LogOptions.builder()
                            .level(LogLevel.ALL)
                            .destination(LogGroup.Builder.create(this, "AmazonConnectAgentlessOutboundCampaign-Intake-LogGroup")
                                    .removalPolicy(RemovalPolicy.DESTROY)
                                    .build())
                            .includeExecutionData(true)
                            .build())
                    .role(stateMachineRoleIntake)
                    .tracingEnabled(true)
                    .build();

            agentlessCampaignS3.grantRead(stepFunctionIntake);
            tableContacts.grantReadWriteData(stepFunctionIntake);

            Policy intakeInlinePolicy = Policy.Builder.create(this, "AmazonConnectAgentlessOutboundCampaign-Intake-StepFunctionPolicy")
                    .policyName("AmazonConnectAgentlessOutboundCampaign-Intake-StepFunctionPolicy")
                    .statements(List.of(PolicyStatement.Builder.create()
                            .actions(List.of("states:StartExecution", "states:DescribeExecution", "states:StopExecution"))
                            .resources(List.of(stepFunctionIntake.getStateMachineArn()))
                            .build()))
                    .build();

            intakeInlinePolicy.attachToRole(stateMachineRoleIntake);

            CfnOutput.Builder.create(this, "AgentlessCampaignIntakeProcess-Name")
                    .description("AWS Step Function which process the Contact List from S3 and store it in DynamoDB")
                    .value(stepFunctionIntake.getStateMachineName())
                    .build();

        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try {
            String outReachASL = Files.readString(Path.of("../StepFunction/OutreachProcess/AmazonConnectAgentlessOutboundCampaign-Outreach.asl.json"));
            List<String> contactFlowARNSplit = Fn.split("/", cfnContactFlow.getAttrContactFlowArn());
            String contactFlowID = Fn.select(3, contactFlowARNSplit);
            outReachASL = outReachASL.replace("<<ContactFlowId>>", contactFlowID);
            outReachASL = outReachASL.replace("<<ConnectInstanceId>>", amazonConnect.getAttrId());
            outReachASL = outReachASL.replace("<<Connect_SourcePhoneNumber>>", cfnPhoneNumber.getAttrAddress());
            outReachASL = outReachASL.replace("<<DDB-CONTACTS-TABLE-NAME>>", tableContacts.getTableName());
            outReachASL = outReachASL.replace("<<DDB-EXECUTION-TABLE-NAME>>", tableExec.getTableName());

            // Create a new IAM role for the state machine
            Role stateMachineRoleOutreach = Role.Builder.create(this, "stateMachineRoleOutreach")
                    .assumedBy(new ServicePrincipal("states.amazonaws.com"))
                    .build();

            stateMachineRoleOutreach.addToPolicy(PolicyStatement.Builder.create()
                    .actions(List.of("states:StartExecution", "states:DescribeExecution", "states:StopExecution"))
                    .resources(List.of("*"))
                    .build());

            stateMachineRoleOutreach.addToPolicy(PolicyStatement.Builder.create()
                    .actions(List.of("connect:StartOutboundVoiceContact", "connect:StopContact"))
                    .resources(List.of("*"))
                    .build());

            StateMachine stepFunctionOutreach = StateMachine.Builder.create(this, "AmazonConnectAgentlessOutboundCampaign-Outreach")
                    .stateMachineName("AmazonConnectAgentlessOutboundCampaign-Outreach-CDK")
                    .definitionBody(DefinitionBody.fromString(outReachASL))
                    .removalPolicy(RemovalPolicy.DESTROY)
                    .stateMachineType(StateMachineType.STANDARD)
                    .logs(LogOptions.builder()
                            .level(LogLevel.ALL)
                            .destination(LogGroup.Builder.create(this, "AmazonConnectAgentlessOutboundCampaign-Outreach-LogGroup")
                                    .removalPolicy(RemovalPolicy.DESTROY)
                                    .build())
                            .includeExecutionData(true)
                            .build())
                    .role(stateMachineRoleOutreach)
                    .tracingEnabled(true)
                    .build();

            tableContacts.grantReadData(stepFunctionOutreach);
            tableExec.grantReadWriteData(stepFunctionOutreach);

            CfnOutput.Builder.create(this, "AgentlessCampaignOutreachProcess-Name")
                    .description("AWS Step Function which process the Contact List from DynamoDB and initiate the Outbound Campaign")
                    .value(stepFunctionOutreach.getStateMachineName())
                    .build();

        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        BucketDeployment agentlessCampaignS3Deployment = BucketDeployment.Builder.create(this, "AgentlessCampaignS3Deployment")
                .sources(List.of(Source.asset("../Connect/ContactList")))
                .destinationKeyPrefix("unprocessed/")
                .destinationBucket(agentlessCampaignS3)
                .build();


        CfnOutput.Builder.create(this, "AgentlessCampaignS3-Name")
                .description("Amazon S3 Bucket Name")
                .value(agentlessCampaignS3.getBucketName())
                .build();

        CfnOutput.Builder.create(this, "AgentlessCampaignDynamoDB-Contacts-Name")
                .description("Amazon DynamoDB Table Name for Contact Information")
                .value(tableContacts.getTableName())
                .build();

        CfnOutput.Builder.create(this, "AgentlessCampaignDynamoDB-Execution-Name")
                .description("Amazon DynamoDB Table Name for Execution Information")
                .value(tableExec.getTableName())
                .build();


        //CDK NAG Suppression's
        NagSuppressions.addResourceSuppressionsByPath(this, "/ConnectAgentlessCampaignStack/stateMachineRoleIntake/DefaultPolicy/Resource",
                List.of(NagPackSuppression.builder()
                        .id("AwsSolutions-IAM5")
                        .reason("Resource permission are granted via standard grant read and write methods")
                        .build()));

        NagSuppressions.addResourceSuppressionsByPath(this, "/ConnectAgentlessCampaignStack/stateMachineRoleOutreach/DefaultPolicy/Resource",
                List.of(NagPackSuppression.builder()
                        .id("AwsSolutions-IAM5")
                        .reason("Resource permission are granted via standard grant read and write methods")
                        .build()));

        NagSuppressions.addResourceSuppressionsByPath(this, "/ConnectAgentlessCampaignStack/Custom::CDKBucketDeployment8693BB64968944B69AAFB0CC9EB8756C/ServiceRole/Resource",
                List.of(NagPackSuppression.builder()
                        .id("AwsSolutions-IAM4")
                        .reason("Internal CDK lambda execution role")
                        .build()));

        NagSuppressions.addResourceSuppressionsByPath(this, "/ConnectAgentlessCampaignStack/Custom::CDKBucketDeployment8693BB64968944B69AAFB0CC9EB8756C/Resource",
                List.of(NagPackSuppression.builder()
                        .id("AwsSolutions-L1")
                        .reason("Internal CDK lambda runtime version")
                        .build()));

        NagSuppressions.addResourceSuppressionsByPath(this, "/ConnectAgentlessCampaignStack/Custom::CDKBucketDeployment8693BB64968944B69AAFB0CC9EB8756C/ServiceRole/DefaultPolicy/Resource",
                List.of(NagPackSuppression.builder()
                        .id("AwsSolutions-IAM5")
                        .reason("Internal CDK lambda needed to apply bucket notification configurations")
                        .build()));
    }

    private void createS3StorageConfig(CfnInstance amazonConnect, String resourceType, String prefix, String encryptionKeyARN, Bucket amazonConnectS3Bucket) {
        CfnInstanceStorageConfig.Builder.create(this, "connect-example-s3-storage-config-" + resourceType)
                .instanceArn(amazonConnect.getAttrArn())
                .s3Config(CfnInstanceStorageConfig.S3ConfigProperty.builder()
                        .bucketName(amazonConnectS3Bucket.getBucketName())
                        .bucketPrefix(prefix)
                        .encryptionConfig(CfnInstanceStorageConfig.EncryptionConfigProperty.builder()
                                .encryptionType("KMS")
                                .keyId(encryptionKeyARN)
                                .build())
                        .build())
                .resourceType(resourceType)
                .storageType("S3")
                .build();
    }
}
