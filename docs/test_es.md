# Caso de Prueba — Migración AWS S3 (us-east-1) → OCI Object Storage (Santiago de Chile)

## Objetivo

Verificar que los procesadores **Universal** pueden mover archivos desde un bucket AWS S3
en `us-east-1` hacia un bucket OCI Object Storage en la región `sa-santiago-1` (Santiago de Chile),
usando el endpoint S3-compatible de OCI.

Este caso valida específicamente que `REGION_NAME` acepta:
- Un código de región AWS estándar (`us-east-1`)
- Una URL de endpoint OCI completa (`https://<namespace>.compat.objectstorage.sa-santiago-1.oraclecloud.com`)

---

## Datos del entorno

### Origen — AWS S3

| Campo | Valor |
|---|---|
| Región | `us-east-1` |
| Bucket origen | `<nombre-bucket-aws>` |
| Prefijo (opcional) | `input/` |
| Access Key | `<AWS_ACCESS_KEY_ID>` |
| Secret Key | `<AWS_SECRET_ACCESS_KEY>` |

### Destino — OCI Object Storage (Santiago)

| Campo | Valor |
|---|---|
| Región OCI | `sa-santiago-1` |
| Endpoint S3-compatible | `https://<namespace>.compat.objectstorage.sa-santiago-1.oraclecloud.com` |
| Bucket destino | `<nombre-bucket-oci>` |
| Access Key (Customer Secret Key) | `<OCI_ACCESS_KEY>` |
| Secret Key (Customer Secret Key) | `<OCI_SECRET_KEY>` |

> **Cómo obtener el namespace OCI**: Consola OCI → Profile → Tenancy → Object Storage Namespace.
> **Cómo obtener las Customer Secret Keys**: Consola OCI → Profile → User Settings → Customer Secret Keys → Generate Secret Key.

---

## Flujo NiFi

```
ListS3Universal ──► FetchS3ObjectUniversal ──► PutS3ObjectUniversal
   (AWS us-east-1)       (AWS us-east-1)           (OCI Santiago)
```

---

## Configuración de cada procesador

### 1. ListS3Universal

| Propiedad | Valor |
|---|---|
| **Bucket** | `<nombre-bucket-aws>` |
| **Region Name** | `us-east-1` |
| **Prefix** | `input/` *(o vacío para listar todo)* |
| **Access Key** | `<AWS_ACCESS_KEY_ID>` |
| **Secret Key** | `<AWS_SECRET_ACCESS_KEY>` |
| **Use Path Style Access** | `false` *(AWS estándar)* |
| **Write Object Tags** | `false` |
| **Requester Pays** | `false` |

### 2. FetchS3ObjectUniversal

| Propiedad | Valor |
|---|---|
| **Bucket** | `${s3.bucket}` |
| **Object Key** | `${filename}` |
| **Region Name** | `us-east-1` |
| **Access Key** | `<AWS_ACCESS_KEY_ID>` |
| **Secret Key** | `<AWS_SECRET_ACCESS_KEY>` |
| **Use Path Style Access** | `false` |
| **Requester Pays** | `false` |

> `${s3.bucket}` y `${filename}` son atributos que `ListS3Universal` escribe automáticamente en cada FlowFile.

### 3. PutS3ObjectUniversal

| Propiedad | Valor |
|---|---|
| **Bucket** | `<nombre-bucket-oci>` |
| **Object Key** | `${filename}` |
| **Region Name** | `https://<namespace>.compat.objectstorage.sa-santiago-1.oraclecloud.com` |
| **Access Key** | `<OCI_ACCESS_KEY>` |
| **Secret Key** | `<OCI_SECRET_KEY>` |
| **Use Path Style Access** | `true` *(obligatorio para OCI)* |
| **Storage Class** | `Standard` |
| **Server Side Encryption** | `None` |

> ⚠️ **`Use Path Style Access = true` es obligatorio para OCI.**
> OCI no soporta virtual-hosted style (`bucket.endpoint/key`); requiere path style (`endpoint/bucket/key`).

---

## Pasos de ejecución

### Paso 1 — Preparar el bucket origen en AWS

```bash
# Crear archivo de prueba
echo "archivo de prueba migración AWS → OCI" > test-migration.txt

# Subir al bucket AWS (us-east-1)
aws s3 cp test-migration.txt s3://<nombre-bucket-aws>/input/test-migration.txt \
  --region us-east-1
```

Verificar que existe:
```bash
aws s3 ls s3://<nombre-bucket-aws>/input/ --region us-east-1
```

### Paso 2 — Verificar conectividad al bucket OCI desde AWS CLI (opcional)

```bash
aws s3 ls s3://<nombre-bucket-oci>/ \
  --endpoint-url https://<namespace>.compat.objectstorage.sa-santiago-1.oraclecloud.com \
  --region sa-santiago-1
```

### Paso 3 — Configurar los procesadores en NiFi

1. Crear el flujo `ListS3Universal → FetchS3ObjectUniversal → PutS3ObjectUniversal`
2. Conectar `FetchS3ObjectUniversal.success → PutS3ObjectUniversal`
3. Conectar `PutS3ObjectUniversal.success → funnel` (o auto-terminate)
4. Enrutar `failure` de cada procesador a un `LogAttribute` para diagnóstico

### Paso 4 — Ejecutar el flujo

1. Arrancar `ListS3Universal` (se ejecuta una vez y genera FlowFiles)
2. Arrancar `FetchS3ObjectUniversal`
3. Arrancar `PutS3ObjectUniversal`
4. Observar los contadores en la UI de NiFi

### Paso 5 — Verificar en OCI

```bash
aws s3 ls s3://<nombre-bucket-oci>/input/ \
  --endpoint-url https://<namespace>.compat.objectstorage.sa-santiago-1.oraclecloud.com \
  --region sa-santiago-1
```

O desde la consola OCI: **Storage → Object Storage → Buckets → `<nombre-bucket-oci>`**

---

## Resultados esperados

| Procesador | Relación | Resultado esperado |
|---|---|---|
| `ListS3Universal` | `success` | 1 FlowFile por cada objeto encontrado en el bucket AWS |
| `FetchS3ObjectUniversal` | `success` | FlowFile con el contenido del objeto descargado |
| `PutS3ObjectUniversal` | `success` | Objeto subido al bucket OCI en Santiago |

Los atributos del FlowFile exitoso en `PutS3ObjectUniversal.success` deben incluir:

| Atributo | Valor esperado |
|---|---|
| `s3.bucket` | `<nombre-bucket-oci>` |
| `s3.key` | `input/test-migration.txt` |
| `s3.etag` | hash MD5 del archivo |
| `s3.storeClass` | `Standard` |

---

## Casos de error esperados y diagnóstico

| Error | Causa probable | Solución |
|---|---|---|
| `404 Not Found` en Fetch | El `filename` no coincide con la key real en S3 | Verificar el prefijo configurado en `ListS3Universal` |
| `403 Forbidden` en Put | Credenciales OCI incorrectas o permisos insuficientes | Regenerar Customer Secret Keys en OCI Console |
| `InvalidArgument` o `SignatureDoesNotMatch` en OCI | Path Style Access desactivado | Activar `Use Path Style Access = true` en `PutS3ObjectUniversal` |
| `Connection refused` | Endpoint OCI mal formado | Verificar namespace y región: `https://<namespace>.compat.objectstorage.sa-santiago-1.oraclecloud.com` |
| `Client is immutable` | NAR antiguo (pre-fix) cargado | Verificar que `lib/nifi-aws-nar-universal-1.20.0.nar` es el de Mayo 29 |

---

## Notas sobre la región Santiago (sa-santiago-1)

- **Endpoint OCI S3-compatible**: `https://<namespace>.compat.objectstorage.sa-santiago-1.oraclecloud.com`
- **Signing region** (extraída automáticamente por el procesador): `sa-santiago-1`
- Esta región **NO existe** en el enum `Regions` del AWS SDK v1.12.371 — es exactamente el escenario que los procesadores Universal resuelven con `REGION_NAME`.
- La URL completa del endpoint es el valor que debe ir en la propiedad `Region Name` del procesador de destino OCI.
