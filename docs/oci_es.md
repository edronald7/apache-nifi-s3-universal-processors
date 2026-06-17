# Configuración de OCI Object Storage para procesadores Universal

Este documento describe paso a paso cómo obtener las credenciales de Oracle Cloud Infrastructure (OCI)
y configurar los procesadores Universal en NiFi para operar sobre buckets OCI Object Storage,
usando como ejemplo la región **Santiago de Chile** (`sa-santiago-1`).

---

## Índice

1. [Conceptos previos](#1-conceptos-previos)
2. [Requisitos en OCI](#2-requisitos-en-oci)
3. [Obtener el Namespace de Object Storage](#3-obtener-el-namespace-de-object-storage)
4. [Crear Customer Secret Keys (Access Key / Secret Key)](#4-crear-customer-secret-keys)
5. [Construir la URL del endpoint](#5-construir-la-url-del-endpoint)
6. [Regiones OCI disponibles](#6-regiones-oci-disponibles)
7. [Políticas IAM mínimas en OCI](#7-políticas-iam-mínimas-en-oci)
8. [Configuración de cada procesador en NiFi](#8-configuración-de-cada-procesador-en-nifi)
9. [Errores comunes y soluciones](#9-errores-comunes-y-soluciones)

---

## 1. Conceptos previos

OCI Object Storage expone una API compatible con Amazon S3 (S3 Compatibility API).
Los procesadores Universal la consumen sin modificación, siempre que se configuren correctamente
tres elementos:

| Elemento | Equivalente AWS | Valor OCI |
|---|---|---|
| **Access Key** | AWS Access Key ID | Customer Secret Key → Access Key |
| **Secret Key** | AWS Secret Access Key | Customer Secret Key → Secret Key |
| **Region Name** | Código de región (`us-east-1`) | URL completa del endpoint OCI |

> OCI no usa códigos de región cortos para la API S3-compatible; se debe usar la URL completa.

---

## 2. Requisitos en OCI

Antes de comenzar se necesita:

- Una cuenta OCI activa con acceso a la consola (`https://cloud.oracle.com`).
- Un **Compartment** en el que crear los buckets.
- Un **Bucket** creado en la región destino (ej. Santiago).
- Permisos para crear **Customer Secret Keys** en el usuario con el que se autenticará NiFi.

---

## 3. Obtener el Namespace de Object Storage

El namespace es un identificador único del tenancy; forma parte de la URL del endpoint.

### Desde la consola OCI

1. Iniciar sesión en `https://cloud.oracle.com`.
2. Hacer clic en el ícono de perfil (esquina superior derecha) → **Tenancy: `<nombre>`**.
3. En la página del Tenancy, buscar la sección **Object Storage Settings**.
4. Copiar el valor de **Object Storage Namespace**.

   Ejemplo: `axbcd1efghi2`

### Desde OCI CLI

```bash
oci os ns get
```

Respuesta:
```json
{
  "data": "axbcd1efghi2"
}
```

### Desde AWS CLI (verificación de conectividad)

```bash
aws s3 ls \
  --endpoint-url https://axbcd1efghi2.compat.objectstorage.sa-santiago-1.oraclecloud.com \
  --region sa-santiago-1
```

---

## 4. Crear Customer Secret Keys

Las Customer Secret Keys son las credenciales equivalentes a AWS Access Key / Secret Key.
Se generan por usuario y no por cuenta.

### Pasos

1. Iniciar sesión en `https://cloud.oracle.com`.
2. Hacer clic en el ícono de perfil (esquina superior derecha) → **My profile**
   *(o "User Settings" si eres administrador administrando otro usuario)*.
3. En el menú lateral izquierdo, bajo **Resources**, hacer clic en **Customer secret keys**.
4. Hacer clic en **Generate secret key**.
5. En el campo **Friendly name**, ingresar un nombre descriptivo (ej. `nifi-santiago-prod`).
6. Hacer clic en **Generate secret key**.
7. **Copiar inmediatamente el valor del Secret Key** — OCI no lo mostrará de nuevo.

   ```
   Secret Key: wXyZ1234abcdEFGH5678ijklMNOP9012qrstUVWX  ← copiar ahora
   ```

8. Cerrar el diálogo. En la tabla aparecerá la entrada con el **Access Key** (columna "Access Key").

   ```
   Access Key: 12ab34cd56ef78gh90ij12kl34mn56op78qr90st
   ```

> **Importante**: el Secret Key solo se muestra una vez en el momento de la creación.
> Si se pierde, hay que eliminar la key y crear una nueva.

---

## 5. Construir la URL del endpoint

La URL del endpoint S3-compatible de OCI sigue este patrón:

```
https://<namespace>.compat.objectstorage.<region>.oraclecloud.com
```

Para Santiago de Chile (`sa-santiago-1`) con namespace `axbcd1efghi2`:

```
https://axbcd1efghi2.compat.objectstorage.sa-santiago-1.oraclecloud.com
```

Este valor completo es el que se ingresa en la propiedad **Region Name** del procesador Universal.

---

## 6. Regiones OCI disponibles

Listado de regiones OCI con su código y endpoint S3-compatible:

| Región | Código | Endpoint S3-compatible |
|---|---|---|
| Santiago de Chile | `sa-santiago-1` | `https://<ns>.compat.objectstorage.sa-santiago-1.oraclecloud.com` |
| Ashburn (Virginia) | `us-ashburn-1` | `https://<ns>.compat.objectstorage.us-ashburn-1.oraclecloud.com` |
| Phoenix (Arizona) | `us-phoenix-1` | `https://<ns>.compat.objectstorage.us-phoenix-1.oraclecloud.com` |
| São Paulo | `sa-saopaulo-1` | `https://<ns>.compat.objectstorage.sa-saopaulo-1.oraclecloud.com` |
| Vinhedo (Brasil) | `sa-vinhedo-1` | `https://<ns>.compat.objectstorage.sa-vinhedo-1.oraclecloud.com` |
| Frankfurt | `eu-frankfurt-1` | `https://<ns>.compat.objectstorage.eu-frankfurt-1.oraclecloud.com` |
| Londres | `uk-london-1` | `https://<ns>.compat.objectstorage.uk-london-1.oraclecloud.com` |
| Tokio | `ap-tokyo-1` | `https://<ns>.compat.objectstorage.ap-tokyo-1.oraclecloud.com` |
| Sídney | `ap-sydney-1` | `https://<ns>.compat.objectstorage.ap-sydney-1.oraclecloud.com` |
| Mumbai | `ap-mumbai-1` | `https://<ns>.compat.objectstorage.ap-mumbai-1.oraclecloud.com` |

> Reemplazar `<ns>` por el namespace del tenancy obtenido en el paso 3.
> Lista completa en: https://docs.oracle.com/en-us/iaas/Content/General/Concepts/regions.htm

---

## 7. Políticas IAM mínimas en OCI

El usuario cuyas Customer Secret Keys se usarán en NiFi necesita una política en OCI IAM
que le permita operar sobre el bucket objetivo.

### Política mínima para leer y escribir en un bucket

En la consola OCI: **Identity & Security → Policies → Create Policy**

```
Allow group <grupo-nifi> to manage objects in compartment <compartment-nombre>
  where target.bucket.name = '<nombre-bucket>'
```

Para listar buckets (necesario para `ListS3Universal`):

```
Allow group <grupo-nifi> to read buckets in compartment <compartment-nombre>
```

Política completa recomendada para todos los procesadores Universal:

```
Allow group nifi-group to manage objects in compartment produccion
  where target.bucket.name = 'mi-bucket-santiago'
Allow group nifi-group to read buckets in compartment produccion
```

> Si el usuario de las Customer Secret Keys es un usuario local (no federado) y es administrador
> del tenancy, puede omitir la política. Para entornos productivos se recomienda crear un usuario
> dedicado con permisos mínimos.

---

## 8. Configuración de cada procesador en NiFi

### Valores comunes para OCI Santiago

| Campo | Valor |
|---|---|
| **Access Key** | `12ab34cd56ef78gh90ij12kl34mn56op78qr90st` |
| **Secret Key** | `wXyZ1234abcdEFGH5678ijklMNOP9012qrstUVWX` |
| **Region Name** | `https://axbcd1efghi2.compat.objectstorage.sa-santiago-1.oraclecloud.com` |
| **Use Path Style Access** | `true` ← **obligatorio** para OCI |
| **Use Chunked Encoding** | `false` ← recomendado para OCI |

> **¿Por qué `Use Path Style Access = true`?**
> OCI no soporta virtual-hosted style (`bucket.endpoint/key`). Solo soporta path style
> (`endpoint/bucket/key`). Sin esta opción el AWS SDK genera URLs del tipo
> `bucket.namespace.compat.objectstorage.region.oraclecloud.com`, pero el certificado SSL
> de OCI solo cubre el host base, lo que provoca el error:
> `Certificate doesn't match any of the subject alternative names`.
>
> **Nota**: estas propiedades están disponibles en todos los procesadores Universal a partir
> del fix de Jun 2026. Si no aparecen en la UI de NiFi, el NAR desplegado es anterior al fix.

---

### ListS3Universal — listar objetos en bucket OCI

| Propiedad | Valor |
|---|---|
| **Bucket** | `mi-bucket-santiago` |
| **Region Name** | `https://axbcd1efghi2.compat.objectstorage.sa-santiago-1.oraclecloud.com` |
| **Access Key** | `<access-key>` |
| **Secret Key** | `<secret-key>` |
| **Use Path Style Access** | `true` |
| **Use Chunked Encoding** | `false` |
| **Prefix** | *(vacío o el prefijo deseado, ej. `data/`)* |
| **Listing Strategy** | `Tracking Timestamps` *(o `Tracking Entities`)* |

---

### FetchS3ObjectUniversal — descargar objetos desde bucket OCI

| Propiedad | Valor |
|---|---|
| **Bucket** | `${s3.bucket}` |
| **Object Key** | `${filename}` |
| **Region Name** | `https://axbcd1efghi2.compat.objectstorage.sa-santiago-1.oraclecloud.com` |
| **Access Key** | `<access-key>` |
| **Secret Key** | `<secret-key>` |
| **Use Path Style Access** | `true` |
| **Use Chunked Encoding** | `false` |

> `${s3.bucket}` y `${filename}` son atributos que `ListS3Universal` escribe automáticamente.

---

### PutS3ObjectUniversal — subir objetos a bucket OCI

| Propiedad | Valor |
|---|---|
| **Bucket** | `mi-bucket-santiago` |
| **Object Key** | `${filename}` |
| **Region Name** | `https://axbcd1efghi2.compat.objectstorage.sa-santiago-1.oraclecloud.com` |
| **Access Key** | `<access-key>` |
| **Secret Key** | `<secret-key>` |
| **Use Path Style Access** | `true` |
| **Use Chunked Encoding** | `false` |
| **Storage Class** | `Standard` |
| **Server Side Encryption** | `None` |

---

### DeleteS3ObjectUniversal — eliminar objetos en bucket OCI

| Propiedad | Valor |
|---|---|
| **Bucket** | `${s3.bucket}` |
| **Object Key** | `${filename}` |
| **Region Name** | `https://axbcd1efghi2.compat.objectstorage.sa-santiago-1.oraclecloud.com` |
| **Access Key** | `<access-key>` |
| **Secret Key** | `<secret-key>` |
| **Use Path Style Access** | `true` |
| **Use Chunked Encoding** | `false` |

---

### TagS3ObjectUniversal — etiquetar objetos en bucket OCI

| Propiedad | Valor |
|---|---|
| **Bucket** | `${s3.bucket}` |
| **Object Key** | `${filename}` |
| **Region Name** | `https://axbcd1efghi2.compat.objectstorage.sa-santiago-1.oraclecloud.com` |
| **Access Key** | `<access-key>` |
| **Secret Key** | `<secret-key>` |
| **Use Path Style Access** | `true` |
| **Use Chunked Encoding** | `false` |
| **Tag Key** | `<clave-etiqueta>` |
| **Tag Value** | `<valor-etiqueta>` |

> OCI Object Storage soporta etiquetas de objetos mediante la API S3-compatible.

---

## 9. Errores comunes y soluciones

| Error | Causa | Solución |
|---|---|---|
| `403 Forbidden` | Credenciales incorrectas o `Use Path Style Access = false` | Verificar Access Key / Secret Key y activar `Use Path Style Access = true` |
| `404 Not Found` en verify | NiFi evalúa `${filename}` sin FlowFile durante verificación de configuración | Es comportamiento normal; ignorar si el flujo funciona en ejecución real |
| `SignatureDoesNotMatch` | Región de firma incorrecta o URL de endpoint mal formada | Verificar que la URL incluye el namespace y el código de región OCI correcto |
| `Connection refused` o timeout | Namespace incorrecto en la URL del endpoint | Verificar el namespace con `oci os ns get` o desde la consola OCI |
| `InvalidArgument: Chunked upload is not supported` | `Use Chunked Encoding = true` en endpoint OCI | Configurar `Use Chunked Encoding = false` |
| `Use Path Style Access` no aparece en la UI | NAR anterior al fix de Jun 2026 | Recompilar y redesplegar el NAR con el fix |
| `NoSuchBucket` | El bucket no existe en la región configurada o el namespace es incorrecto | Verificar nombre de bucket y namespace en la consola OCI |
| `Client is immutable when created with the builder` | NAR desactualizado (pre-fix) en producción | Reemplazar el NAR por la versión compilada con el fix (posterior al 29 May 2026) |
| `Access Denied` en lista | La política IAM no incluye `read buckets` | Agregar `Allow group ... to read buckets in compartment ...` |

---

## Resumen de valores a completar

```
Namespace OCI     : ____________________________
Región OCI        : sa-santiago-1
Endpoint completo : https://____________.compat.objectstorage.sa-santiago-1.oraclecloud.com
Access Key        : ____________________________
Secret Key        : ____________________________   (guardar al momento de la creación)
Bucket OCI        : ____________________________
Compartment       : ____________________________
```
