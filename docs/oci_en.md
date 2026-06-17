# OCI Object Storage — Configuration Guide for Universal Processors

This document describes step by step how to obtain Oracle Cloud Infrastructure (OCI) credentials
and configure the Universal NiFi processors to operate against OCI Object Storage buckets,
using **Santiago de Chile** (`sa-santiago-1`) as the reference region.

> 📄 También disponible en español: [oci_es.md](oci_es.md)

---

## Table of Contents

1. [Background concepts](#1-background-concepts)
2. [OCI prerequisites](#2-oci-prerequisites)
3. [Get the Object Storage Namespace](#3-get-the-object-storage-namespace)
4. [Create Customer Secret Keys (Access Key / Secret Key)](#4-create-customer-secret-keys)
5. [Build the endpoint URL](#5-build-the-endpoint-url)
6. [Available OCI regions](#6-available-oci-regions)
7. [Minimum IAM policies in OCI](#7-minimum-iam-policies-in-oci)
8. [Processor configuration in NiFi](#8-processor-configuration-in-nifi)
9. [Common errors and solutions](#9-common-errors-and-solutions)

---

## 1. Background concepts

OCI Object Storage exposes an Amazon S3-compatible API (S3 Compatibility API).
The Universal processors consume it without modification, provided three elements are
correctly configured:

| Element | AWS equivalent | OCI value |
|---|---|---|
| **Access Key** | AWS Access Key ID | Customer Secret Key → Access Key |
| **Secret Key** | AWS Secret Access Key | Customer Secret Key → Secret Key |
| **Region Name** | Region code (`us-east-1`) | Full OCI endpoint URL |

> OCI does not use short region codes for the S3-compatible API; the full endpoint URL must be used.

---

## 2. OCI prerequisites

Before starting, you need:

- An active OCI account with access to the console (`https://cloud.oracle.com`).
- A **Compartment** in which to create buckets.
- A **Bucket** created in the target region (e.g. Santiago).
- Permission to create **Customer Secret Keys** for the user that NiFi will authenticate as.

---

## 3. Get the Object Storage Namespace

The namespace is a unique identifier for the tenancy; it is part of the endpoint URL.

### From the OCI Console

1. Log in to `https://cloud.oracle.com`.
2. Click the profile icon (top-right corner) → **Tenancy: `<name>`**.
3. On the Tenancy page, find the **Object Storage Settings** section.
4. Copy the **Object Storage Namespace** value.

   Example: `axbcd1efghi2`

### From OCI CLI

```bash
oci os ns get
```

Response:
```json
{
  "data": "axbcd1efghi2"
}
```

### From AWS CLI (connectivity check)

```bash
aws s3 ls \
  --endpoint-url https://axbcd1efghi2.compat.objectstorage.sa-santiago-1.oraclecloud.com \
  --region sa-santiago-1
```

---

## 4. Create Customer Secret Keys

Customer Secret Keys are the credentials equivalent to AWS Access Key / Secret Key.
They are generated per user, not per account.

### Steps

1. Log in to `https://cloud.oracle.com`.
2. Click the profile icon (top-right corner) → **My profile**
   *(or "User Settings" if you are an administrator managing another user)*.
3. In the left-side menu, under **Resources**, click **Customer secret keys**.
4. Click **Generate secret key**.
5. In the **Friendly name** field, enter a descriptive name (e.g. `nifi-santiago-prod`).
6. Click **Generate secret key**.
7. **Copy the Secret Key value immediately** — OCI will not display it again.

   ```
   Secret Key: wXyZ1234abcdEFGH5678ijklMNOP9012qrstUVWX  ← copy now
   ```

8. Close the dialog. The table will show the entry with the **Access Key** (column "Access Key").

   ```
   Access Key: 12ab34cd56ef78gh90ij12kl34mn56op78qr90st
   ```

> **Important**: the Secret Key is only shown once at the time of creation.
> If lost, you must delete the key and generate a new one.

---

## 5. Build the endpoint URL

The OCI S3-compatible endpoint URL follows this pattern:

```
https://<namespace>.compat.objectstorage.<region>.oraclecloud.com
```

For Santiago de Chile (`sa-santiago-1`) with namespace `axbcd1efghi2`:

```
https://axbcd1efghi2.compat.objectstorage.sa-santiago-1.oraclecloud.com
```

This full value is what goes into the **Region Name** property of the Universal processor.

---

## 6. Available OCI regions

OCI regions with their code and S3-compatible endpoint:

| Region | Code | S3-compatible endpoint |
|---|---|---|
| Santiago de Chile | `sa-santiago-1` | `https://<ns>.compat.objectstorage.sa-santiago-1.oraclecloud.com` |
| Ashburn (Virginia) | `us-ashburn-1` | `https://<ns>.compat.objectstorage.us-ashburn-1.oraclecloud.com` |
| Phoenix (Arizona) | `us-phoenix-1` | `https://<ns>.compat.objectstorage.us-phoenix-1.oraclecloud.com` |
| São Paulo | `sa-saopaulo-1` | `https://<ns>.compat.objectstorage.sa-saopaulo-1.oraclecloud.com` |
| Vinhedo (Brazil) | `sa-vinhedo-1` | `https://<ns>.compat.objectstorage.sa-vinhedo-1.oraclecloud.com` |
| Frankfurt | `eu-frankfurt-1` | `https://<ns>.compat.objectstorage.eu-frankfurt-1.oraclecloud.com` |
| London | `uk-london-1` | `https://<ns>.compat.objectstorage.uk-london-1.oraclecloud.com` |
| Tokyo | `ap-tokyo-1` | `https://<ns>.compat.objectstorage.ap-tokyo-1.oraclecloud.com` |
| Sydney | `ap-sydney-1` | `https://<ns>.compat.objectstorage.ap-sydney-1.oraclecloud.com` |
| Mumbai | `ap-mumbai-1` | `https://<ns>.compat.objectstorage.ap-mumbai-1.oraclecloud.com` |

> Replace `<ns>` with the tenancy namespace obtained in step 3.
> Full list: https://docs.oracle.com/en-us/iaas/Content/General/Concepts/regions.htm

---

## 7. Minimum IAM policies in OCI

The user whose Customer Secret Keys NiFi will use needs an OCI IAM policy
that allows operating on the target bucket.

### Minimum policy to read and write to a bucket

In the OCI Console: **Identity & Security → Policies → Create Policy**

```
Allow group <nifi-group> to manage objects in compartment <compartment-name>
  where target.bucket.name = '<bucket-name>'
```

To list buckets (required for `ListS3Universal`):

```
Allow group <nifi-group> to read buckets in compartment <compartment-name>
```

Full recommended policy for all Universal processors:

```
Allow group nifi-group to manage objects in compartment production
  where target.bucket.name = 'my-bucket-santiago'
Allow group nifi-group to read buckets in compartment production
```

> If the Customer Secret Keys user is a local (non-federated) tenancy administrator,
> the policy may be omitted. For production environments, a dedicated user with minimum
> permissions is recommended.

> **Note on `NoSuchBucket` (404)**: OCI returns 404 instead of 403 when the user lacks
> IAM permissions — this is intentional OCI behavior to avoid revealing bucket existence.
> If the bucket is visible in the OCI Console but the S3 API returns 404, the issue is
> almost certainly a missing IAM policy.

---

## 8. Processor configuration in NiFi

### Common values for OCI Santiago

| Field | Value |
|---|---|
| **Access Key** | `12ab34cd56ef78gh90ij12kl34mn56op78qr90st` |
| **Secret Key** | `wXyZ1234abcdEFGH5678ijklMNOP9012qrstUVWX` |
| **Region Name** | `https://axbcd1efghi2.compat.objectstorage.sa-santiago-1.oraclecloud.com` |
| **Use Path Style Access** | `true` ← **required** for OCI |
| **Use Chunked Encoding** | `false` ← recommended for OCI |

> **Why `Use Path Style Access = true`?**
> OCI does not support virtual-hosted style (`bucket.endpoint/key`). It only supports
> path style (`endpoint/bucket/key`). Without this option the AWS SDK generates URLs like
> `bucket.namespace.compat.objectstorage.region.oraclecloud.com`, but OCI's SSL certificate
> only covers the base host, causing:
> `Certificate doesn't match any of the subject alternative names`.
>
> **Note**: these properties are available on all five Universal processors from the
> Jun 2026 fix onwards. If they do not appear in the NiFi UI, the deployed NAR predates the fix.

---

### ListS3Universal — list objects in OCI bucket

| Property | Value |
|---|---|
| **Bucket** | `my-bucket-santiago` |
| **Region Name** | `https://axbcd1efghi2.compat.objectstorage.sa-santiago-1.oraclecloud.com` |
| **Access Key** | `<access-key>` |
| **Secret Key** | `<secret-key>` |
| **Use Path Style Access** | `true` |
| **Use Chunked Encoding** | `false` |
| **Prefix** | *(empty or desired prefix, e.g. `data/`)* |
| **Listing Strategy** | `Tracking Timestamps` *(or `Tracking Entities`)* |

---

### FetchS3ObjectUniversal — download objects from OCI bucket

| Property | Value |
|---|---|
| **Bucket** | `${s3.bucket}` |
| **Object Key** | `${filename}` |
| **Region Name** | `https://axbcd1efghi2.compat.objectstorage.sa-santiago-1.oraclecloud.com` |
| **Access Key** | `<access-key>` |
| **Secret Key** | `<secret-key>` |
| **Use Path Style Access** | `true` |
| **Use Chunked Encoding** | `false` |

> `${s3.bucket}` and `${filename}` are attributes that `ListS3Universal` writes automatically.

---

### PutS3ObjectUniversal — upload objects to OCI bucket

| Property | Value |
|---|---|
| **Bucket** | `my-bucket-santiago` |
| **Object Key** | `${filename}` |
| **Region Name** | `https://axbcd1efghi2.compat.objectstorage.sa-santiago-1.oraclecloud.com` |
| **Access Key** | `<access-key>` |
| **Secret Key** | `<secret-key>` |
| **Use Path Style Access** | `true` |
| **Use Chunked Encoding** | `false` |
| **Storage Class** | `Standard` |
| **Server Side Encryption** | `None` |

---

### DeleteS3ObjectUniversal — delete objects in OCI bucket

| Property | Value |
|---|---|
| **Bucket** | `${s3.bucket}` |
| **Object Key** | `${filename}` |
| **Region Name** | `https://axbcd1efghi2.compat.objectstorage.sa-santiago-1.oraclecloud.com` |
| **Access Key** | `<access-key>` |
| **Secret Key** | `<secret-key>` |
| **Use Path Style Access** | `true` |
| **Use Chunked Encoding** | `false` |

---

### TagS3ObjectUniversal — tag objects in OCI bucket

| Property | Value |
|---|---|
| **Bucket** | `${s3.bucket}` |
| **Object Key** | `${filename}` |
| **Region Name** | `https://axbcd1efghi2.compat.objectstorage.sa-santiago-1.oraclecloud.com` |
| **Access Key** | `<access-key>` |
| **Secret Key** | `<secret-key>` |
| **Use Path Style Access** | `true` |
| **Use Chunked Encoding** | `false` |
| **Tag Key** | `<tag-key>` |
| **Tag Value** | `<tag-value>` |

> OCI Object Storage supports object tagging via the S3-compatible API.

---

## 9. Common errors and solutions

| Error | Likely cause | Solution |
|---|---|---|
| `403 Forbidden` | Wrong credentials or `Use Path Style Access = false` | Verify Access Key / Secret Key and enable `Use Path Style Access = true` |
| `404 Not Found` on verify | NiFi evaluates `${filename}` without FlowFile during config verification | Normal behaviour; ignore if the flow works during actual execution |
| `SignatureDoesNotMatch` | Wrong signing region or malformed endpoint URL | Verify the URL includes the correct OCI namespace and region code |
| `Connection refused` / timeout | Incorrect namespace in endpoint URL | Verify namespace with `oci os ns get` or from OCI Console |
| `InvalidArgument: Chunked upload is not supported` | `Use Chunked Encoding = true` on OCI endpoint | Set `Use Chunked Encoding = false` |
| `Use Path Style Access` missing from UI | NAR predates Jun 2026 fix | Recompile and redeploy the NAR with the fix |
| `NoSuchBucket` | Bucket does not exist in that region/namespace, or missing IAM policy | Verify bucket name, namespace, and IAM policy in OCI Console |
| `Client is immutable when created with the builder` | Outdated NAR (pre-fix) in production | Replace NAR with the version compiled after May 29 2026 |
| `Access Denied` on list | IAM policy missing `read buckets` statement | Add `Allow group ... to read buckets in compartment ...` |

---

## Quick reference

```
OCI Namespace     : ____________________________
OCI Region        : sa-santiago-1
Full endpoint     : https://____________.compat.objectstorage.sa-santiago-1.oraclecloud.com
Access Key        : ____________________________
Secret Key        : ____________________________   (save immediately at creation time)
OCI Bucket        : ____________________________
Compartment       : ____________________________
```
