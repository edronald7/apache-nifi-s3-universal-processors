# Changelog — NiFi AWS S3 Universal Processors

> 📄 También disponible en español: [changes_es.md](changes_es.md)

## Context

The official Apache NiFi 1.20 AWS S3 processors use `AWS SDK for Java v1`
(`com.amazonaws` version `1.12.371`). The `Region` property is restricted to a
`Regions` enum that does not include recent AWS regions or OCI S3-compatible endpoints.

This document details all modifications made to produce the **Universal** processors,
which accept any region or endpoint dynamically.

---

## 1. Module cleanup (Step 4)

### Modules removed from `nifi-aws-bundle`
- `nifi-aws-parameter-providers`
- `nifi-aws-parameter-value-providers`

### Java packages removed from `nifi-aws-processors`
- `org.apache.nifi.processors.aws.cloudwatch`
- `org.apache.nifi.processors.aws.dynamodb`
- `org.apache.nifi.processors.aws.kinesis`
- `org.apache.nifi.processors.aws.lambda`
- `org.apache.nifi.processors.aws.ml`
- `org.apache.nifi.processors.aws.sns`
- `org.apache.nifi.processors.aws.sqs`
- `org.apache.nifi.processors.aws.wag`

### Java packages removed from `nifi-aws-abstract-processors`
- `org.apache.nifi.processors.aws.dynamodb`
- `org.apache.nifi.processors.aws.kinesis`
- `org.apache.nifi.processors.aws.lambda`
- `org.apache.nifi.processors.aws.sns`
- `org.apache.nifi.processors.aws.sqs`
- `org.apache.nifi.processors.aws.wag`

### Maven dependencies removed

| pom.xml | Removed dependency |
|---|---|
| `nifi-aws-abstract-processors` | `aws-java-sdk-dynamodb`, `aws-java-sdk-kinesis`, `amazon-kinesis-client`, `aws-java-sdk-lambda`, `aws-java-sdk-sns`, `aws-java-sdk-sqs` |
| `nifi-aws-processors` | `aws-java-sdk-translate`, `aws-java-sdk-polly`, `aws-java-sdk-transcribe`, `aws-java-sdk-textract` |

---

## 2. Processor renaming (Step 5)

Each S3 processor receives the `Universal` suffix in class name and file name:

| Original class | Universal class |
|---|---|
| `DeleteS3Object` | `DeleteS3ObjectUniversal` |
| `FetchS3Object`  | `FetchS3ObjectUniversal`  |
| `ListS3`         | `ListS3Universal`         |
| `PutS3Object`    | `PutS3ObjectUniversal`    |
| `TagS3Object`    | `TagS3ObjectUniversal`    |

Changes applied in each class:
- `.java` file renamed
- `public class XxxUniversal extends AbstractS3Processor` declaration
- `@Tags` annotation updated — `"Universal"` added
- `@CapabilityDescription` updated — mentions dynamic region support and OCI
- `@SeeAlso` updated — references to the new `*Universal` classes

---

## 3. New REGION_NAME property (Step 5 / Step 6)

### Affected file
`nifi-aws-abstract-processors/.../AbstractS3Processor.java`

### PropertyDescriptor added

```java
public static final PropertyDescriptor REGION_NAME = new PropertyDescriptor.Builder()
    .name("region-name")
    .displayName("Region Name")
    .description(
        "Region name or code for the S3 service (e.g. us-east-1, ap-south-2, me-central-1). " +
        "For S3-compatible services such as OCI, enter the full endpoint URL " +
        "(e.g. https://namespace.compat.objectstorage.us-ashburn-1.oraclecloud.com). " +
        "If the value contains '://', it is treated as an endpoint URL; " +
        "otherwise the endpoint is built as s3.<region>.amazonaws.com.")
    .required(true)
    .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
    .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
    .build();
```

### Auto-detection logic (in `createClient`)

```java
String regionOrEndpoint = context.getProperty(REGION_NAME).evaluateAttributeExpressions().getValue();
AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard()
    .withCredentials(credentialsProvider)
    .withClientConfiguration(config);

if (regionOrEndpoint.contains("://")) {
    // Full endpoint URL — for OCI or other S3-compatible services
    builder.withEndpointConfiguration(
        new AwsClientBuilder.EndpointConfiguration(regionOrEndpoint, signingRegion));
} else {
    // Region code — builds standard AWS endpoint
    String endpoint = "https://s3." + regionOrEndpoint + ".amazonaws.com";
    builder.withEndpointConfiguration(
        new AwsClientBuilder.EndpointConfiguration(endpoint, regionOrEndpoint));
}
```

> `withEndpointConfiguration()` is used instead of `withRegion(Regions.fromName())` to
> avoid the `Regions` enum dependency and support regions not listed in SDK v1.

---

## 4. Changes in `AbstractAWSProcessor` (Step 6)

### File
`nifi-aws-abstract-processors/.../AbstractAWSProcessor.java`

### Modifications
- `REGION` removed from `supportedPropertyDescriptors` list in `AbstractS3Processor`
- `REGION_NAME` added to the supported descriptors list
- `getRegionAndInitializeEndpoint()` is no longer called from `AbstractS3Processor`;
  region/endpoint configuration is handled directly in `createClient()`
- Helper methods `getAvailableRegions()` and `createAllowableValue(Regions)` are kept
  in `AbstractAWSProcessor` to avoid breaking compilation (other modules may reference them),
  but they are not used by the Universal processors

---

## 5. Changes in `AbstractS3Processor` (Step 6)

### File
`nifi-aws-abstract-processors/.../s3/AbstractS3Processor.java`

### Modifications
- `createClient(ProcessContext, AWSCredentialsProvider, ClientConfiguration)` overridden
  to inject the region/endpoint from `REGION_NAME`
- `REGION_NAME` added to `PROPERTIES` (descriptor list)
- `REGION` removed from `PROPERTIES` (no longer applicable)

---

## 6. Unit tests (Step 7)

### Test inventory — 166 tests, 0 failures

Tests are scoped exclusively to the processors and classes that were modified:

#### Tests for the 5 Universal processors

| Class | Tests | What it verifies |
|---|---|---|
| `AbstractS3UniversalTest` | — (base class) | Defines 8 common tests; `setAdditionalRequiredProperties()` hook allows subclasses to add extra required properties |
| `TestDeleteS3ObjectUniversal` | 13 | Deletion with standard AWS region, new region (ap-south-2), OCI endpoint, S3 exception, versioned deletion |
| `TestFetchS3ObjectUniversal` | 13 | Download with standard AWS region, new region, OCI endpoint, S3 exception, FlowFile attributes |
| `TestListS3Universal` | 12 | Listing with standard AWS region, new region, OCI endpoint, S3 exception |
| `TestPutS3ObjectUniversal` | 13 | Upload with standard AWS region, new region, OCI endpoint, S3 exception, FlowFile attributes |
| `TestTagS3ObjectUniversal` | 13 | Tagging with standard AWS region, new region, OCI endpoint, S3 exception, APPEND mode |

The 8 inherited tests from `AbstractS3UniversalTest` verify per processor:
- `REGION_NAME` is present in the properties list
- `REGION` (fixed enum) is **not** in the properties list
- Without `REGION_NAME` the processor is invalid
- With a standard AWS region (`us-east-1`) it is valid
- With a new region (`ap-south-2`) it is valid
- With an OCI endpoint URL it is valid
- With an empty string it is invalid
- With Expression Language (`${s3.region}`) it is valid

#### Credentials and proxy tests

| Class | Tests | What it verifies |
|---|---|---|
| `TestCredentialsProviderFactory` | 17 | Credentials provider factory (uses `FetchS3ObjectUniversal`) |
| `AWSProcessorProxyTest` | 8 | HTTP/HTTPS proxy configuration (uses `FetchS3ObjectUniversal`) |
| `AWSCredentialsProviderControllerServiceTest` | 19 | Credentials as a Controller Service |
| `TestAWSCredentials` | 4 | `AbstractAWSProcessor` with anonymous, key/secret, and file credentials |

#### Encryption strategy tests

| Class | Tests | What it verifies |
|---|---|---|
| `TestClientSideCEncryptionStrategyKeyValidation` | 7 | AES key validation (128/192/256-bit, empty, non-Base64) |
| `TestServerSideCEncryptionStrategyKeyValidation` | 5 | SSE-C key validation |
| `TestS3EncryptionStrategies` | 6 | CSE-C, CSE-KMS, SSE-C, SSE-KMS, SSE-S3, NoOp strategies |
| `TestStandardS3EncryptionServiceValidation` | 32 | Combination validation of `StandardS3EncryptionService` |
| `TestStandardS3EncryptionService` | 4 | `StandardS3EncryptionService` as a Controller Service |

### Bugs found and fixed during test analysis

#### 1. `AbstractS3Processor.createClient` — immutable client

`AmazonS3ClientBuilder` produces an **immutable** client. Calling `setS3ClientOptions()` after
`.build()` throws `UnsupportedOperationException: Client is immutable when created with the builder`.

**Fix**: all S3 options (path-style access, chunked encoding) are configured on the builder
before calling `.build()`, using `withPathStyleAccessEnabled()` and `withChunkedEncodingDisabled()`.
For encryption clients (created without builder) a `configureClientOptionsLegacy()` method
is maintained that calls `setS3ClientOptions()`.

#### 2. `AWSProcessorProxyTest` — `REGION_NAME` required

`FetchS3ObjectUniversal` now requires `REGION_NAME`. The test `@BeforeEach` was only setting
`BUCKET` and calling `assertValid()`, failing with
`'Region Name' is invalid because Region Name is required`.

**Fix**: add `runner.setProperty(AbstractS3Processor.REGION_NAME, "us-east-1")` to the setup.

#### 3. `TestPutS3ObjectUniversal` — unconfigured `listMultipartUploads` mock

`PutS3ObjectUniversal.onTrigger()` calls `ageoffS3Uploads()` which internally calls
`s3.listMultipartUploads(...)`. The mock returned `null` (Mockito default), causing
NPE when iterating `listing.getMultipartUploads()`.

**Fix**: add to `@BeforeEach`:
```java
final MultipartUploadListing emptyListing = new MultipartUploadListing();
emptyListing.setMultipartUploads(new ArrayList<>());
Mockito.when(mockS3Client.listMultipartUploads(any(...))).thenReturn(emptyListing);
```

#### 4. `TestPutS3ObjectUniversal` — `PutObjectResult.getMetadata()` null

`PutS3ObjectUniversal.onTrigger()` calls `result.getMetadata().getStorageClass()` to write
the `s3.storageClass` attribute. `new PutObjectResult()` does not initialize the `metadata`
field, returning `null` → NPE.

**Fix**: add `mockResult.setMetadata(new ObjectMetadata())` when creating the result mock.

#### 5. `TestFetchS3ObjectUniversal.testFetchObjectAttributesWritten` — `s3.bucket` null

`FetchS3ObjectUniversal` sets the `s3.bucket` attribute from `s3Object.getBucketName()`.
`new S3Object()` in the mock does not initialize the field, returning `null`.

**Fix**: in `testFetchObjectAttributesWritten`, configure a specific `S3Object` with
`s3ObjectWithBucket.setBucketName("my-bucket")`.

#### 6. `AbstractS3IT.java` — orphaned class removed

Base class for integration tests that referenced `ITDeleteS3Object`, `ITFetchS3Object`,
`ITPutS3Object`, and `ITListS3` (all deleted). Also used `Regions.fromName()` (replaced API).
Removed because no concrete subclass extends it.

---

## 7. Build (Step 8)

### Prerequisites

```bash
# Java 1.8
java -version          # must show 1.8.x
# With SDKMAN (if multiple versions are installed):
sdk use java 8.0.x-...

# Maven 3.x
mvn -version
```

### Build command

```bash
cd nifi-source/nifi-nar-bundles/nifi-aws-bundle
mvn clean package -DskipTests
```

Output artifact:
```
nifi-aws-nar-universal/target/nifi-aws-nar-universal-1.20.0.nar
```

### Clean environment build (empty `~/.m2` or new machine)

No prior steps are required. Maven downloads everything automatically from Maven Central
during the first build (~200 MB of dependencies). The command is identical:

```bash
cd nifi-source/nifi-nar-bundles/nifi-aws-bundle
mvn clean package -DskipTests
```

What Maven resolves automatically:

| Source | What it downloads |
|---|---|
| Local files (relativePath) | `nifi-nar-bundles/pom.xml` and `nifi-source/pom.xml` — already in the repo |
| Maven Central | `org.apache:apache:29` (great-grandparent POM) |
| Maven Central | `nifi-api`, `nifi-framework-api`, `nifi-mock`, `aws-java-sdk-s3`, `aws-java-sdk-sts`, `aws-java-sdk-core`, `commons-io`, `commons-lang3` and ~150 transitive dependencies |

### Note on the NAR plugin

`nifi-nar-maven-plugin:1.4.0` attempts to generate extension documentation during packaging.
When building outside the full NiFi source tree it cannot resolve all NAR chain dependencies
(`nifi-jetty-bundle`, etc.). To prevent build failure, `nifi-aws-nar-universal/pom.xml`
is configured with:

```xml
<plugin>
    <artifactId>nifi-nar-maven-plugin</artifactId>
    <configuration>
        <enforceDocGeneration>false</enforceDocGeneration>
    </configuration>
</plugin>
```

This allows the build to continue with a warning rather than an error.

### NAR identity — custom namespace (`groupId`)

When deploying NiFi with the NAR, a classloader conflict was detected: the NAR shared the
`org.apache.nifi` namespace with the official NAR, causing collisions when both loaded
`nifi-aws-service-api-nar` as their parent classloader.

**Solution**: add an explicit `groupId` of `com.custom.nifi` to the `nifi-aws-nar-universal` module.
Only that module is modified — the internal JARs do not affect NiFi's classloading system.

Resulting MANIFEST.MF:
```
Nar-Group: com.custom.nifi              ← custom namespace, no conflict
Nar-Id: nifi-aws-nar-universal
Nar-Dependency-Group: org.apache.nifi
Nar-Dependency-Id: nifi-aws-service-api-nar   ← uses the official NAR already in lib/
Nar-Dependency-Version: 1.20.0
```

Result:
- Official NAR `org.apache.nifi:nifi-aws-nar:1.20.0` remains intact (Kinesis, SQS, SNS, etc.)
- Our NAR `com.custom.nifi:nifi-aws-nar-universal:1.20.0` coexists without conflict
- Both use `org.apache.nifi:nifi-aws-service-api-nar:1.20.0` as parent classloader

### Deployment

```bash
# Copy ONLY our NAR — do NOT remove the official nifi-aws-nar-1.20.0.nar
cp nifi-aws-nar-universal/target/nifi-aws-nar-universal-1.20.0.nar \
   /path/to/nifi-1.20/extensions/
# Restart NiFi to load the new NAR
```

---

## 8. Extension registry fix (`services` file)

### Symptom
NiFi failed to start with errors of the form:
```
RuntimeException: Could not create Class for ExtensionDefinition[
  type=Processor,
  implementation=org.apache.nifi.processors.aws.kinesis.stream.ConsumeKinesisStream,
  bundle=com.custom.nifi:nifi-aws-nar-universal:1.20.0]
Caused by: java.lang.ClassNotFoundException: ...ConsumeKinesisStream
```

### Root cause
NiFi uses the file
`META-INF/services/org.apache.nifi.processor.Processor`
inside the JAR to discover all processor classes at startup
(`StandardExtensionDiscoveringManager`). The original file listed the **28 classes**
from the official NAR (Kinesis, DynamoDB, Lambda, ML, SNS, SQS, CloudWatch, etc.). After
deleting those classes from the source code the JAR no longer contained them, but the
services file still referenced them → fatal `ClassNotFoundException`.

### Modified file

```
nifi-source/nifi-nar-bundles/nifi-aws-bundle/
  nifi-aws-processors/src/main/resources/
    META-INF/services/org.apache.nifi.processor.Processor
```

### Change applied

**Before** (28 entries — all original NAR processors):
```
org.apache.nifi.processors.aws.s3.FetchS3Object
org.apache.nifi.processors.aws.s3.PutS3Object
... (23 more: SQS, Kinesis, DynamoDB, Lambda, ML, CloudWatch, WAG)
```

**After** (5 entries — only the implemented Universal processors):
```
org.apache.nifi.processors.aws.s3.DeleteS3ObjectUniversal
org.apache.nifi.processors.aws.s3.FetchS3ObjectUniversal
org.apache.nifi.processors.aws.s3.ListS3Universal
org.apache.nifi.processors.aws.s3.PutS3ObjectUniversal
org.apache.nifi.processors.aws.s3.TagS3ObjectUniversal
```

### Result
NiFi starts correctly. The 5 Universal processors are available in the UI.
The official NAR `org.apache.nifi:nifi-aws-nar:1.20.0` continues loading its own processors
(Kinesis, SQS, etc.) without any interference.

---

## 9. `nifi-source/` cleanup

### Context

Cloning the official NiFi 1.20 repository downloads the full project tree
(186 MB, 22,000+ files). Only the `nifi-aws-bundle` subtree is relevant to this project.
The rest are orphaned modules: other NiFi bundles, minifi, registry, toolkit, docs,
system tests, etc.

All external artifacts needed by `nifi-aws-bundle` (NiFi APIs, utilities, test dependencies)
are downloaded to `~/.m2` after the first successful build, so the source code of those
modules is not needed.

### Removed modules / files

| Category | Removed |
|---|---|
| Other NiFi bundles | `nifi-nar-bundles/nifi-*` (99 bundles: standard, kafka, hadoop, gcp…) |
| Core top-level modules | `nifi-api/`, `nifi-commons/`, `nifi-framework-api/`, `nifi-server-api/`, `nifi-bootstrap/` |
| Packaging and distribution | `nifi-assembly/`, `nifi-docker/`, `nifi-docs/`, `nifi-stateless/` |
| Sibling projects | `minifi/`, `c2/`, `nifi-registry/` |
| Tools and utilities | `nifi-toolkit/`, `nifi-h2/`, `nifi-manifest/`, `nifi-external/`, `nifi-system-tests/` |
| Build / plugins | `nifi-maven-archetypes/`, `nifi-dependency-check-maven/`, `nifi-mock/` |
| Apache repo metadata | `.asf.yaml`, `.github/`, `.gitignore`, `checkstyle.xml`, `KEYS`, `LICENSE`, `NOTICE`, `README.md`, `SECURITY.md` |

### Cleanup results

| | Before | After |
|---|---|---|
| Files in `nifi-source/` | 22,385 | **175** |
| Disk size | 186 MB | **64 MB** |
| Build time | ~32 s | **~7 s** |

### Final `nifi-source/` structure

```
nifi-source/
├── pom.xml                              ← grandparent POM (global BOM)
└── nifi-nar-bundles/
    ├── pom.xml                          ← direct parent POM of nifi-aws-bundle
    └── nifi-aws-bundle/
        ├── pom.xml
        ├── nifi-aws-service-api/        ← AWS service interfaces
        ├── nifi-aws-service-api-nar/    ← service interfaces NAR
        ├── nifi-aws-abstract-processors/← AbstractAWSProcessor, AbstractS3Processor + REGION_NAME
        ├── nifi-aws-processors/         ← the 5 *Universal processors
        └── nifi-aws-nar-universal/      ← final package → nifi-aws-nar-universal-1.20.0.nar
```

---

## 10. Maven POM chain and build resolution

### POM inheritance chain

When running `mvn clean package` from `nifi-aws-bundle/`, Maven builds the inheritance tree
by reading each `<parent>` recursively:

```
nifi-aws-bundle/pom.xml
│  <parent> nifi-nar-bundles:1.20.0
│  Maven looks first at ../pom.xml  →  nifi-nar-bundles/pom.xml  (found locally)
│
└── nifi-nar-bundles/pom.xml
    │  <parent> nifi:1.20.0
    │  Maven looks first at ../pom.xml  →  nifi-source/pom.xml  (found locally)
    │
    └── nifi-source/pom.xml
        │  <parent> org.apache:apache:29
        │  <relativePath /> (empty → no local lookup)
        │  Maven downloads from Maven Central
        │
        └── org.apache:apache:29  (Maven Central)
```

### `<parent>` resolution rule

Maven follows this order for each level:

1. If the child `pom.xml` has an explicit `<relativePath>` and the file exists → reads it locally.
2. If no explicit `<relativePath>` → tries `../pom.xml` by default; if it exists, reads it locally.
3. If no local file matches → looks in `~/.m2` (local cache).
4. If also not in cache → downloads from remote repositories (Maven Central / NiFi repo).

This is why the two local POMs (`nifi-source/pom.xml` and `nifi-nar-bundles/pom.xml`) are kept:
both exist in `~/.m2` after the first download, but Maven gives preference to them when found
via `relativePath`. Removing them does not break the build on this machine (they are cached),
but it could on a clean environment where they have not yet been downloaded.

### External dependencies — resolved from `~/.m2`

All dependencies that `nifi-aws-bundle` needs from other NiFi modules are already downloaded
as binary artifacts in `~/.m2`; their source code is not needed:

| Artifact | Usage |
|---|---|
| `nifi-api-1.20.0.jar` | NiFi public API (Processor, PropertyDescriptor…) |
| `nifi-framework-api-1.20.0.jar` | Framework API (ControllerService…) |
| `nifi-mock-1.20.0.jar` | Unit test framework |
| `nifi-standard-services-api-nar-1.20.0.nar` | Parent NAR for service API NAR |
| `nifi-distributed-cache-client-service-api-1.20.0.jar` | Distributed cache interface |
| `nifi-listed-entity-1.20.0.jar` | Entity listing utility (ListS3) |
| `nifi-standard-record-utils-1.20.0.jar` | Record utilities |
| `nifi-record-serialization-services-1.20.0.jar` | Serialization services |

---

## Fix A — Missing `Use Path Style Access` and `Use Chunked Encoding` properties (Jun 2026)

### Production issue

When using `ListS3Universal` against an OCI Object Storage bucket (Santiago region `sa-santiago-1`)
the following error was obtained:

```
Failed to list contents of bucket '...': Unable to execute HTTP request:
Certificate for <bucket.namespace.compat.objectstorage.sa-santiago-1.oraclecloud.com>
doesn't match any of the subject alternative names:
[swiftobjectstorage.sa-santiago-1.oraclecloud.com]
```

Additionally, the properties `Use Path Style Access` and `Use Chunked Encoding` did not appear
in any processor except `PutS3ObjectUniversal`.

### Root cause

`USE_PATH_STYLE_ACCESS` and `USE_CHUNKED_ENCODING` are **defined** in `AbstractS3Processor`
(base class), but were **never registered** in the `properties` list of 4 of the 5 Universal
processors. Only `PutS3ObjectUniversal` included them (inherited from the original `PutS3Object`).

| Processor | `USE_PATH_STYLE_ACCESS` before fix | `USE_CHUNKED_ENCODING` before fix |
|---|---|---|
| `ListS3Universal` | missing | missing |
| `FetchS3ObjectUniversal` | missing | missing |
| `PutS3ObjectUniversal` | present | present |
| `DeleteS3ObjectUniversal` | missing | missing |
| `TagS3ObjectUniversal` | missing | missing |

Without being able to set `Use Path Style Access = true`, the AWS SDK generated virtual-hosted
style URLs:
```
<bucket>.<namespace>.compat.objectstorage.sa-santiago-1.oraclecloud.com
```
OCI only issues SSL certificates for the base host without the bucket prefix → TLS handshake
fails with a SAN (*Subject Alternative Names*) error.

### Files modified

`USE_PATH_STYLE_ACCESS` and `USE_CHUNKED_ENCODING` were added after `ENDPOINT_OVERRIDE`
in the `properties` list of the four affected processors:

```java
// Before
            ENDPOINT_OVERRIDE,
            SIGNER_OVERRIDE,

// After
            ENDPOINT_OVERRIDE,
            USE_PATH_STYLE_ACCESS,
            USE_CHUNKED_ENCODING,
            SIGNER_OVERRIDE,
```

---

## Fix B — Wrong signing region for city-named OCI regions (Jun 2026)

### Production issue

When using `ListS3Universal` with the OCI Santiago endpoint
(`https://tdecloud.compat.objectstorage.sa-santiago-1.oraclecloud.com`):

```
SignatureDoesNotMatch: The secret key required to complete authentication could not be found.
The region must be specified if this is not the home region for the tenancy.
(Status Code: 403)
```

### Root cause

The `extractSigningRegion` method in `AbstractS3Processor` used a regex that only recognised
regions with **English directional names** (`east`, `west`, `north`, `south`, `central`, etc.):

```java
// Old pattern — does not match city names
Pattern.compile("[./-]([a-z]{2}-(?:gov-)?(?:east|west|north|south|central|...)-\\d)[./-]");
```

For `sa-santiago-1`, the segment `santiago` is not in that list, so the method failed to
extract the region and returned the fallback `us-east-1`. The request was signed for `us-east-1`
but OCI required the signature to use `sa-santiago-1` → `SignatureDoesNotMatch`.

The same problem affected other OCI regions with city names:
`eu-frankfurt-1`, `uk-london-1`, `sa-saopaulo-1`, `sa-vinhedo-1`, `ap-tokyo-1`, `ap-sydney-1`, etc.

### File modified

`AbstractS3Processor.java` — `REGION_FROM_URL_PATTERN`:

```java
// Before
Pattern.compile("[./-]([a-z]{2}-(?:gov-)?(?:east|west|...)-\\d)[./-]");

// After — accepts any lowercase word, including city names
Pattern.compile("[./-]([a-z]{2,3}-(?:[a-z]+-)*\\d+)[./-]");
```

The new pattern `[a-z]{2,3}-(?:[a-z]+-)*\d+` covers all known formats:

| Region | Old pattern | New pattern |
|---|---|---|
| `us-east-1` (AWS) | ✓ | ✓ |
| `ap-southeast-2` (AWS) | ✓ | ✓ |
| `us-gov-west-1` (AWS GovCloud) | ✓ | ✓ |
| `sa-santiago-1` (OCI) | ✗ → fallback `us-east-1` | ✓ |
| `eu-frankfurt-1` (OCI) | ✗ → fallback `us-east-1` | ✓ |
| `uk-london-1` (OCI) | ✗ → fallback `us-east-1` | ✓ |
| `sa-saopaulo-1` (OCI) | ✗ → fallback `us-east-1` | ✓ |
| `ap-tokyo-1` (OCI) | ✗ → fallback `us-east-1` | ✓ |
