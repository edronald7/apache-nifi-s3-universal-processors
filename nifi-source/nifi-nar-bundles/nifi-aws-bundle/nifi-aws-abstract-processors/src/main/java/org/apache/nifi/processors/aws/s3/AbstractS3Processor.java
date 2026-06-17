/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.aws.s3;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.regions.Region;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.S3ClientOptions;
import com.amazonaws.services.s3.model.AccessControlList;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.CanonicalGrantee;
import com.amazonaws.services.s3.model.EmailAddressGrantee;
import com.amazonaws.services.s3.model.Grantee;
import com.amazonaws.services.s3.model.Owner;
import com.amazonaws.services.s3.model.Permission;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.processors.aws.AbstractAWSCredentialsProviderProcessor;
import org.apache.nifi.processors.aws.AwsPropertyDescriptors;
import org.apache.nifi.processors.aws.signer.AwsCustomSignerUtil;
import org.apache.nifi.processors.aws.signer.AwsSignerType;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import static org.apache.nifi.processors.aws.signer.AwsSignerType.AWS_S3_V2_SIGNER;
import static org.apache.nifi.processors.aws.signer.AwsSignerType.AWS_S3_V4_SIGNER;
import static org.apache.nifi.processors.aws.signer.AwsSignerType.CUSTOM_SIGNER;
import static org.apache.nifi.processors.aws.signer.AwsSignerType.DEFAULT_SIGNER;

public abstract class AbstractS3Processor extends AbstractAWSCredentialsProviderProcessor<AmazonS3Client> {

    private static final Pattern REGION_FROM_URL_PATTERN =
            Pattern.compile("[./-]([a-z]{2,3}-(?:[a-z]+-)*\\d+)[./-]");

    public static final PropertyDescriptor REGION_NAME = new PropertyDescriptor.Builder()
            .name("region-name")
            .displayName("Region Name")
            .description("Nombre o código de la región del servicio S3 (ej. us-east-1, ap-south-2, me-central-1). " +
                    "Para servicios S3-compatibles como OCI, ingresar la URL de endpoint completa " +
                    "(ej. https://namespace.compat.objectstorage.us-ashburn-1.oraclecloud.com). " +
                    "Si el valor contiene '://' se trata como URL de endpoint; " +
                    "en caso contrario se construye el endpoint como https://s3.<region>.amazonaws.com.")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .build();


    public static final PropertyDescriptor FULL_CONTROL_USER_LIST = new PropertyDescriptor.Builder()
            .name("FullControl User List")
            .required(false)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .description("A comma-separated list of Amazon User ID's or E-mail addresses that specifies who should have Full Control for an object")
            .defaultValue("${s3.permissions.full.users}")
            .build();
    public static final PropertyDescriptor READ_USER_LIST = new PropertyDescriptor.Builder()
            .name("Read Permission User List")
            .required(false)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .description("A comma-separated list of Amazon User ID's or E-mail addresses that specifies who should have Read Access for an object")
            .defaultValue("${s3.permissions.read.users}")
            .build();
    public static final PropertyDescriptor WRITE_USER_LIST = new PropertyDescriptor.Builder()
            .name("Write Permission User List")
            .required(false)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .description("A comma-separated list of Amazon User ID's or E-mail addresses that specifies who should have Write Access for an object")
            .defaultValue("${s3.permissions.write.users}")
            .build();
    public static final PropertyDescriptor READ_ACL_LIST = new PropertyDescriptor.Builder()
            .name("Read ACL User List")
            .required(false)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .description("A comma-separated list of Amazon User ID's or E-mail addresses that specifies who should have permissions to read the Access Control List for an object")
            .defaultValue("${s3.permissions.readacl.users}")
            .build();
    public static final PropertyDescriptor WRITE_ACL_LIST = new PropertyDescriptor.Builder()
            .name("Write ACL User List")
            .required(false)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .description("A comma-separated list of Amazon User ID's or E-mail addresses that specifies who should have permissions to change the Access Control List for an object")
            .defaultValue("${s3.permissions.writeacl.users}")
            .build();
    public static final PropertyDescriptor CANNED_ACL = new PropertyDescriptor.Builder()
            .name("canned-acl")
            .displayName("Canned ACL")
            .required(false)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .description("Amazon Canned ACL for an object, one of: BucketOwnerFullControl, BucketOwnerRead, LogDeliveryWrite, AuthenticatedRead, PublicReadWrite, PublicRead, Private; " +
                    "will be ignored if any other ACL/permission/owner property is specified")
            .defaultValue("${s3.permissions.cannedacl}")
            .build();
    public static final PropertyDescriptor OWNER = new PropertyDescriptor.Builder()
            .name("Owner")
            .required(false)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .description("The Amazon ID to use for the object's owner")
            .defaultValue("${s3.owner}")
            .build();
    public static final PropertyDescriptor BUCKET = new PropertyDescriptor.Builder()
            .name("Bucket")
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();
    public static final PropertyDescriptor KEY = new PropertyDescriptor.Builder()
            .name("Object Key")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .defaultValue("${filename}")
            .build();
    public static final PropertyDescriptor SIGNER_OVERRIDE = new PropertyDescriptor.Builder()
            .name("Signer Override")
            .description("The AWS S3 library uses Signature Version 4 by default but this property allows you to specify the Version 2 signer to support older S3-compatible services" +
                    " or even to plug in your own custom signer implementation.")
            .required(false)
            .allowableValues(EnumSet.of(
                            DEFAULT_SIGNER,
                            AWS_S3_V4_SIGNER,
                            AWS_S3_V2_SIGNER,
                            CUSTOM_SIGNER))
            .defaultValue(DEFAULT_SIGNER.getValue())
            .build();

    public static final PropertyDescriptor S3_CUSTOM_SIGNER_CLASS_NAME = new PropertyDescriptor.Builder()
            .fromPropertyDescriptor(AwsPropertyDescriptors.CUSTOM_SIGNER_CLASS_NAME)
            .dependsOn(SIGNER_OVERRIDE, CUSTOM_SIGNER)
            .build();

    public static final PropertyDescriptor S3_CUSTOM_SIGNER_MODULE_LOCATION = new PropertyDescriptor.Builder()
            .fromPropertyDescriptor(AwsPropertyDescriptors.CUSTOM_SIGNER_MODULE_LOCATION)
            .dependsOn(SIGNER_OVERRIDE, CUSTOM_SIGNER)
            .build();

    public static final PropertyDescriptor ENCRYPTION_SERVICE = new PropertyDescriptor.Builder()
            .name("encryption-service")
            .displayName("Encryption Service")
            .description("Specifies the Encryption Service Controller used to configure requests. " +
                    "PutS3Object: For backward compatibility, this value is ignored when 'Server Side Encryption' is set. " +
                    "FetchS3Object: Only needs to be configured in case of Server-side Customer Key, Client-side KMS and Client-side Customer Key encryptions.")
            .required(false)
            .identifiesControllerService(AmazonS3EncryptionService.class)
            .build();
    public static final PropertyDescriptor USE_CHUNKED_ENCODING = new PropertyDescriptor.Builder()
            .name("use-chunked-encoding")
            .displayName("Use Chunked Encoding")
            .description("Enables / disables chunked encoding for upload requests. Set it to false only if your endpoint does not support chunked uploading.")
            .allowableValues("true", "false")
            .defaultValue("true")
            .build();
    public static final PropertyDescriptor USE_PATH_STYLE_ACCESS = new PropertyDescriptor.Builder()
            .name("use-path-style-access")
            .displayName("Use Path Style Access")
            .description("Path-style access can be enforced by setting this property to true. Set it to true if your endpoint does not support " +
                    "virtual-hosted-style requests, only path-style requests.")
            .allowableValues("true", "false")
            .defaultValue("false")
            .build();

    /**
     * Create client using credentials provider. This is the preferred way for creating clients.
     * Uses REGION_NAME to configure the endpoint dynamically, supporting both standard AWS regions
     * and S3-compatible endpoints (e.g. OCI Object Storage).
     *
     * All S3ClientOptions (path-style access, chunked encoding) are configured on the builder
     * before calling build(), because clients created via AmazonS3ClientBuilder are immutable
     * and do not allow calling setS3ClientOptions() after construction.
     */
    @Override
    protected AmazonS3Client createClient(final ProcessContext context, final AWSCredentialsProvider credentialsProvider, final ClientConfiguration config) {
        getLogger().info("Creating client with credentials provider");
        initializeSignerOverride(context, config);

        AmazonS3EncryptionService encryptionService = context.getProperty(ENCRYPTION_SERVICE).asControllerService(AmazonS3EncryptionService.class);
        AmazonS3Client s3 = null;

        if (encryptionService != null) {
            s3 = encryptionService.createEncryptionClient(credentialsProvider, config);
            // Encryption clients are not created via builder — apply options via setS3ClientOptions
            configureClientOptionsLegacy(context, s3);
        }

        if (s3 == null) {
            final String regionOrEndpoint = context.getProperty(REGION_NAME)
                    .evaluateAttributeExpressions().getValue().trim();

            final EndpointConfiguration endpointConfiguration = buildEndpointConfiguration(regionOrEndpoint);
            getLogger().info("Configuring S3 client with endpoint: {}, signing region: {}",
                    endpointConfiguration.getServiceEndpoint(), endpointConfiguration.getSigningRegion());

            final AmazonS3ClientBuilder s3Builder = AmazonS3ClientBuilder.standard()
                    .withCredentials(credentialsProvider)
                    .withClientConfiguration(config)
                    .withEndpointConfiguration(endpointConfiguration);

            // Apply S3 client options on the builder (clients built via builder are immutable)
            final Boolean useChunkedEncoding = context.getProperty(USE_CHUNKED_ENCODING).asBoolean();
            if (useChunkedEncoding != null && !useChunkedEncoding) {
                s3Builder.withChunkedEncodingDisabled(true);
            }

            final Boolean usePathStyleAccess = context.getProperty(USE_PATH_STYLE_ACCESS).asBoolean();
            final boolean endpointOverrideSet = !StringUtils.trimToEmpty(
                    context.getProperty(ENDPOINT_OVERRIDE).evaluateAttributeExpressions().getValue()).isEmpty();
            if ((usePathStyleAccess != null && usePathStyleAccess) || endpointOverrideSet) {
                s3Builder.withPathStyleAccessEnabled(true);
            }

            s3 = (AmazonS3Client) s3Builder.build();
        }

        return s3;
    }

    /**
     * Builds an EndpointConfiguration from a region code or a full endpoint URL.
     *
     * <ul>
     *   <li>Region code (e.g. {@code ap-south-2}) → endpoint {@code https://s3.ap-south-2.amazonaws.com},
     *       signing region {@code ap-south-2}.</li>
     *   <li>Full URL (contains {@code ://}) → used as-is for the endpoint; signing region is
     *       extracted from the URL via pattern matching, falling back to {@code us-east-1}.</li>
     * </ul>
     */
    private EndpointConfiguration buildEndpointConfiguration(final String regionOrEndpoint) {
        if (regionOrEndpoint.contains("://")) {
            final String signingRegion = extractSigningRegion(regionOrEndpoint);
            return new EndpointConfiguration(regionOrEndpoint, signingRegion);
        } else {
            final String endpoint = "https://s3." + regionOrEndpoint + ".amazonaws.com";
            return new EndpointConfiguration(endpoint, regionOrEndpoint);
        }
    }

    /**
     * Attempts to extract a region code from a URL using the pattern
     * {@code [./-](<2-3 letters>-(<word->)*<digits>)[./-]}.
     * Examples:
     * <ul>
     *   <li>{@code https://s3.us-east-1.amazonaws.com} → {@code us-east-1}</li>
     *   <li>{@code https://s3.us-gov-west-1.amazonaws.com} → {@code us-gov-west-1}</li>
     *   <li>{@code https://ns.compat.objectstorage.sa-santiago-1.oraclecloud.com} → {@code sa-santiago-1}</li>
     *   <li>{@code https://ns.compat.objectstorage.eu-frankfurt-1.oraclecloud.com} → {@code eu-frankfurt-1}</li>
     * </ul>
     * Returns {@code us-east-1} as default when no match is found.
     */
    private String extractSigningRegion(final String url) {
        final Matcher matcher = REGION_FROM_URL_PATTERN.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        getLogger().debug("Could not extract signing region from endpoint URL '{}'; defaulting to us-east-1", url);
        return "us-east-1";
    }

    /**
     * Applies S3ClientOptions to a client that was NOT created via the builder
     * (e.g. encryption clients). Builder-created clients are immutable and must
     * use withPathStyleAccessEnabled / withChunkedEncodingDisabled on the builder instead.
     */
    private void configureClientOptionsLegacy(final ProcessContext context, final AmazonS3Client s3) {
        S3ClientOptions.Builder builder = S3ClientOptions.builder();

        Boolean useChunkedEncoding = context.getProperty(USE_CHUNKED_ENCODING).asBoolean();
        if (useChunkedEncoding != null && !useChunkedEncoding) {
            builder.disableChunkedEncoding();
        }

        Boolean usePathStyleAccess = context.getProperty(USE_PATH_STYLE_ACCESS).asBoolean();
        if (usePathStyleAccess != null && usePathStyleAccess) {
            builder.setPathStyleAccess(true);
        }

        if (!StringUtils.trimToEmpty(context.getProperty(ENDPOINT_OVERRIDE).evaluateAttributeExpressions().getValue()).isEmpty()) {
            builder.setPathStyleAccess(true);
        }

        s3.setS3ClientOptions(builder.build());
    }

    private void initializeSignerOverride(final ProcessContext context, final ClientConfiguration config) {
        final String signer = context.getProperty(SIGNER_OVERRIDE).getValue();
        final AwsSignerType signerType = AwsSignerType.forValue(signer);

        if (signerType == CUSTOM_SIGNER) {
            final String signerClassName = context.getProperty(S3_CUSTOM_SIGNER_CLASS_NAME).evaluateAttributeExpressions().getValue();

            config.setSignerOverride(AwsCustomSignerUtil.registerCustomSigner(signerClassName));
        } else if (signerType != DEFAULT_SIGNER) {
            config.setSignerOverride(signer);
        }
    }

    @Override
    protected boolean isCustomSignerConfigured(final ProcessContext context) {
        final String signer = context.getProperty(SIGNER_OVERRIDE).getValue();
        final AwsSignerType signerType = AwsSignerType.forValue(signer);
        return signerType == CUSTOM_SIGNER;
    }

    /**
     * Create client using AWSCredentials
     *
     * @deprecated use {@link #createClient(ProcessContext, AWSCredentialsProvider, ClientConfiguration)} instead
     */
    @Override
    protected AmazonS3Client createClient(final ProcessContext context, final AWSCredentials credentials, final ClientConfiguration config) {
        getLogger().info("Creating client with AWS credentials");
        return createClient(context, new AWSStaticCredentialsProvider(credentials), config);
    }

    protected Grantee createGrantee(final String value) {
        if (StringUtils.isEmpty(value)) {
            return null;
        }

        if (value.contains("@")) {
            return new EmailAddressGrantee(value);
        } else {
            return new CanonicalGrantee(value);
        }
    }

    protected final List<Grantee> createGrantees(final String value) {
        if (StringUtils.isEmpty(value)) {
            return Collections.emptyList();
        }

        final List<Grantee> grantees = new ArrayList<>();
        final String[] vals = value.split(",");
        for (final String val : vals) {
            final String identifier = val.trim();
            final Grantee grantee = createGrantee(identifier);
            if (grantee != null) {
                grantees.add(grantee);
            }
        }
        return grantees;
    }

    protected String getUrlForObject(final String bucket, final String key) {
        Region region = getRegion();

        if (region == null) {
            return  DEFAULT_PROTOCOL.toString() + "://s3.amazonaws.com/" + bucket + "/" + key;
        } else {
            final String endpoint = region.getServiceEndpoint("s3");
            return DEFAULT_PROTOCOL.toString() + "://" + endpoint + "/" + bucket + "/" + key;
        }
    }

    /**
     * Create AccessControlList if appropriate properties are configured.
     *
     * @param context ProcessContext
     * @param flowFile FlowFile
     * @return AccessControlList or null if no ACL properties were specified
     */
    protected final AccessControlList createACL(final ProcessContext context, final FlowFile flowFile) {
        // lazy-initialize ACL, as it should not be used if no properties were specified
        AccessControlList acl = null;

        final String ownerId = context.getProperty(OWNER).evaluateAttributeExpressions(flowFile).getValue();
        if (!StringUtils.isEmpty(ownerId)) {
            final Owner owner = new Owner();
            owner.setId(ownerId);
            if (acl == null) {
                acl = new AccessControlList();
            }
            acl.setOwner(owner);
        }

        for (final Grantee grantee : createGrantees(context.getProperty(FULL_CONTROL_USER_LIST).evaluateAttributeExpressions(flowFile).getValue())) {
            if (acl == null) {
                acl = new AccessControlList();
            }
            acl.grantPermission(grantee, Permission.FullControl);
        }

        for (final Grantee grantee : createGrantees(context.getProperty(READ_USER_LIST).evaluateAttributeExpressions(flowFile).getValue())) {
            if (acl == null) {
                acl = new AccessControlList();
            }
            acl.grantPermission(grantee, Permission.Read);
        }

        for (final Grantee grantee : createGrantees(context.getProperty(WRITE_USER_LIST).evaluateAttributeExpressions(flowFile).getValue())) {
            if (acl == null) {
                acl = new AccessControlList();
            }
            acl.grantPermission(grantee, Permission.Write);
        }

        for (final Grantee grantee : createGrantees(context.getProperty(READ_ACL_LIST).evaluateAttributeExpressions(flowFile).getValue())) {
            if (acl == null) {
                acl = new AccessControlList();
            }
            acl.grantPermission(grantee, Permission.ReadAcp);
        }

        for (final Grantee grantee : createGrantees(context.getProperty(WRITE_ACL_LIST).evaluateAttributeExpressions(flowFile).getValue())) {
            if (acl == null) {
                acl = new AccessControlList();
            }
            acl.grantPermission(grantee, Permission.WriteAcp);
        }

        return acl;
    }

    protected FlowFile extractExceptionDetails(final Exception e, final ProcessSession session, FlowFile flowFile) {
        flowFile = session.putAttribute(flowFile, "s3.exception", e.getClass().getName());
        if (e instanceof AmazonS3Exception) {
            flowFile = putAttribute(session, flowFile, "s3.additionalDetails", ((AmazonS3Exception) e).getAdditionalDetails());
        }
        if (e instanceof AmazonServiceException) {
            final AmazonServiceException ase = (AmazonServiceException) e;
            flowFile = putAttribute(session, flowFile, "s3.statusCode", ase.getStatusCode());
            flowFile = putAttribute(session, flowFile, "s3.errorCode", ase.getErrorCode());
            flowFile = putAttribute(session, flowFile, "s3.errorMessage", ase.getErrorMessage());
        }
        return flowFile;
    }

    private FlowFile putAttribute(final ProcessSession session, final FlowFile flowFile, final String key, final Object value) {
        return (value == null) ? flowFile : session.putAttribute(flowFile, key, value.toString());
    }

    /**
     * Create CannedAccessControlList if {@link #CANNED_ACL} property specified.
     *
     * @param context ProcessContext
     * @param flowFile FlowFile
     * @return CannedAccessControlList or null if not specified
     */
    protected final CannedAccessControlList createCannedACL(final ProcessContext context, final FlowFile flowFile) {
        CannedAccessControlList cannedAcl = null;

        final String cannedAclString = context.getProperty(CANNED_ACL).evaluateAttributeExpressions(flowFile).getValue();
        if (!StringUtils.isEmpty(cannedAclString)) {
            cannedAcl = CannedAccessControlList.valueOf(cannedAclString);
        }

        return cannedAcl;
    }
}
