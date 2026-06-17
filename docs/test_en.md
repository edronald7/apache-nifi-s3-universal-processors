# Test Case — Migration from AWS S3 (us-east-1) to OCI Object Storage (Santiago de Chile)

> 📄 También disponible en español: [test_es.md](test_es.md)

## Objective

Verify that the **Universal** processors can move files from an AWS S3 bucket in `us-east-1`
to an OCI Object Storage bucket in region `sa-santiago-1` (Santiago de Chile),
using OCI's S3-compatible endpoint.

This test case specifically validates that `REGION_NAME` accepts:
- A standard AWS region code (`us-east-1`)
- A full OCI endpoint URL (`https://<namespace>.compat.objectstorage.sa-santiago-1.oraclecloud.com`)

---

## Environment details

### Source — AWS S3

| Field | Value |
|---|---|
| Region | `us-east-1` |
| Source bucket | `<aws-bucket-name>` |
| Prefix (optional) | `input/` |
| Access Key | `<AWS_ACCESS_KEY_ID>` |
| Secret Key | `<AWS_SECRET_ACCESS_KEY>` |

### Destination — OCI Object Storage (Santiago)

| Field | Value |
|---|---|
| OCI Region | `sa-santiago-1` |
| S3-compatible endpoint | `https://<namespace>.compat.objectstorage.sa-santiago-1.oraclecloud.com` |
| Destination bucket | `<oci-bucket-name>` |
| Access Key (Customer Secret Key) | `<OCI_ACCESS_KEY>` |
| Secret Key (Customer Secret Key) | `<OCI_SECRET_KEY>` |

> **How to get the OCI namespace**: OCI Console → Profile → Tenancy → Object Storage Namespace.
> **How to get Customer Secret Keys**: OCI Console → Profile → User Settings → Customer Secret Keys → Generate Secret Key.

---

## NiFi flow

```
ListS3Universal ──► FetchS3ObjectUniversal ──► PutS3ObjectUniversal
  (AWS us-east-1)       (AWS us-east-1)          (OCI Santiago)
```

---

## Processor configuration

### 1. ListS3Universal

| Property | Value |
|---|---|
| **Bucket** | `<aws-bucket-name>` |
| **Region Name** | `us-east-1` |
| **Prefix** | `input/` *(or empty to list everything)* |
| **Access Key** | `<AWS_ACCESS_KEY_ID>` |
| **Secret Key** | `<AWS_SECRET_ACCESS_KEY>` |
| **Use Path Style Access** | `false` *(AWS standard)* |
| **Write Object Tags** | `false` |
| **Requester Pays** | `false` |

### 2. FetchS3ObjectUniversal

| Property | Value |
|---|---|
| **Bucket** | `${s3.bucket}` |
| **Object Key** | `${filename}` |
| **Region Name** | `us-east-1` |
| **Access Key** | `<AWS_ACCESS_KEY_ID>` |
| **Secret Key** | `<AWS_SECRET_ACCESS_KEY>` |
| **Use Path Style Access** | `false` |
| **Requester Pays** | `false` |

> `${s3.bucket}` and `${filename}` are attributes that `ListS3Universal` writes automatically to each FlowFile.

### 3. PutS3ObjectUniversal

| Property | Value |
|---|---|
| **Bucket** | `<oci-bucket-name>` |
| **Object Key** | `${filename}` |
| **Region Name** | `https://<namespace>.compat.objectstorage.sa-santiago-1.oraclecloud.com` |
| **Access Key** | `<OCI_ACCESS_KEY>` |
| **Secret Key** | `<OCI_SECRET_KEY>` |
| **Use Path Style Access** | `true` *(required for OCI)* |
| **Use Chunked Encoding** | `false` |
| **Storage Class** | `Standard` |
| **Server Side Encryption** | `None` |

> ⚠️ **`Use Path Style Access = true` is mandatory for OCI.**
> OCI does not support virtual-hosted style (`bucket.endpoint/key`); it requires path style (`endpoint/bucket/key`).

---

## Execution steps

### Step 1 — Prepare the source bucket in AWS

```bash
# Create test file
echo "migration test file AWS to OCI" > test-migration.txt

# Upload to AWS bucket (us-east-1)
aws s3 cp test-migration.txt s3://<aws-bucket-name>/input/test-migration.txt \
  --region us-east-1
```

Verify it exists:
```bash
aws s3 ls s3://<aws-bucket-name>/input/ --region us-east-1
```

### Step 2 — Verify connectivity to OCI bucket from AWS CLI (optional)

```bash
aws s3 ls s3://<oci-bucket-name>/ \
  --endpoint-url https://<namespace>.compat.objectstorage.sa-santiago-1.oraclecloud.com \
  --region sa-santiago-1
```

### Step 3 — Configure the processors in NiFi

1. Create the flow `ListS3Universal → FetchS3ObjectUniversal → PutS3ObjectUniversal`
2. Connect `FetchS3ObjectUniversal.success → PutS3ObjectUniversal`
3. Connect `PutS3ObjectUniversal.success → funnel` (or auto-terminate)
4. Route `failure` from each processor to a `LogAttribute` for diagnostics

### Step 4 — Run the flow

1. Start `ListS3Universal` (runs once and generates FlowFiles)
2. Start `FetchS3ObjectUniversal`
3. Start `PutS3ObjectUniversal`
4. Monitor the counters in the NiFi UI

### Step 5 — Verify in OCI

```bash
aws s3 ls s3://<oci-bucket-name>/input/ \
  --endpoint-url https://<namespace>.compat.objectstorage.sa-santiago-1.oraclecloud.com \
  --region sa-santiago-1
```

Or from the OCI Console: **Storage → Object Storage → Buckets → `<oci-bucket-name>`**

---

## Expected results

| Processor | Relationship | Expected result |
|---|---|---|
| `ListS3Universal` | `success` | 1 FlowFile per object found in the AWS bucket |
| `FetchS3ObjectUniversal` | `success` | FlowFile containing the downloaded object content |
| `PutS3ObjectUniversal` | `success` | Object uploaded to the OCI bucket in Santiago |

FlowFile attributes on `PutS3ObjectUniversal.success` should include:

| Attribute | Expected value |
|---|---|
| `s3.bucket` | `<oci-bucket-name>` |
| `s3.key` | `input/test-migration.txt` |
| `s3.etag` | MD5 hash of the file |
| `s3.storeClass` | `Standard` |

---

## Expected error cases and diagnostics

| Error | Likely cause | Solution |
|---|---|---|
| `404 Not Found` on Fetch | `filename` does not match the actual key in S3 | Check the prefix configured in `ListS3Universal` |
| `403 Forbidden` on Put | Wrong OCI credentials or insufficient permissions | Regenerate Customer Secret Keys in OCI Console |
| `InvalidArgument` or `SignatureDoesNotMatch` on OCI | Path Style Access disabled | Enable `Use Path Style Access = true` in `PutS3ObjectUniversal` |
| `Connection refused` | Malformed OCI endpoint | Verify namespace and region: `https://<namespace>.compat.objectstorage.sa-santiago-1.oraclecloud.com` |
| `Client is immutable` | Old NAR (pre-fix) loaded | Verify that `nifi-aws-nar-universal-1.20.0.nar` is the May 29 2026 build or later |

---

## Notes on the Santiago region (sa-santiago-1)

- **OCI S3-compatible endpoint**: `https://<namespace>.compat.objectstorage.sa-santiago-1.oraclecloud.com`
- **Signing region** (extracted automatically by the processor): `sa-santiago-1`
- This region **does NOT exist** in the `Regions` enum of AWS SDK v1.12.371 — this is exactly the scenario the Universal processors solve with `REGION_NAME`.
- The full endpoint URL is the value that must be entered in the `Region Name` property of the OCI destination processor.
