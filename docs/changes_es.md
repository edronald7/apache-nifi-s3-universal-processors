# Plan de Cambios — NiFi AWS S3 Processors Universal

## Contexto

Los procesadores oficiales de Apache NiFi 1.20 para AWS S3 usan `AWS SDK for Java v1`
(`com.amazonaws` versión `1.12.371`). La propiedad `Region` está restringida a un enum
`Regions` que no incluye regiones recientes de AWS ni endpoints S3-compatibles de OCI.

Este documento detalla todas las modificaciones realizadas para producir los procesadores
**Universal**, que aceptan cualquier región o endpoint de forma dinámica.

---

## 1. Limpieza del módulo (Paso 4)

### Módulos eliminados del bundle `nifi-aws-bundle`
- `nifi-aws-parameter-providers`
- `nifi-aws-parameter-value-providers`

### Paquetes Java eliminados de `nifi-aws-processors`
- `org.apache.nifi.processors.aws.cloudwatch`
- `org.apache.nifi.processors.aws.dynamodb`
- `org.apache.nifi.processors.aws.kinesis`
- `org.apache.nifi.processors.aws.lambda`
- `org.apache.nifi.processors.aws.ml`
- `org.apache.nifi.processors.aws.sns`
- `org.apache.nifi.processors.aws.sqs`
- `org.apache.nifi.processors.aws.wag`

### Paquetes Java eliminados de `nifi-aws-abstract-processors`
- `org.apache.nifi.processors.aws.dynamodb`
- `org.apache.nifi.processors.aws.kinesis`
- `org.apache.nifi.processors.aws.lambda`
- `org.apache.nifi.processors.aws.sns`
- `org.apache.nifi.processors.aws.sqs`
- `org.apache.nifi.processors.aws.wag`

### Dependencias Maven eliminadas
| pom.xml | Dependencia eliminada |
|---|---|
| `nifi-aws-abstract-processors` | `aws-java-sdk-dynamodb`, `aws-java-sdk-kinesis`, `amazon-kinesis-client`, `aws-java-sdk-lambda`, `aws-java-sdk-sns`, `aws-java-sdk-sqs` |
| `nifi-aws-processors` | `aws-java-sdk-translate`, `aws-java-sdk-polly`, `aws-java-sdk-transcribe`, `aws-java-sdk-textract` |

---

## 2. Renombrado de procesadores (Paso 5)

Cada procesador S3 recibe el sufijo `Universal` en nombre de clase y archivo:

| Clase original | Clase Universal |
|---|---|
| `DeleteS3Object` | `DeleteS3ObjectUniversal` |
| `FetchS3Object`  | `FetchS3ObjectUniversal`  |
| `ListS3`         | `ListS3Universal`         |
| `PutS3Object`    | `PutS3ObjectUniversal`    |
| `TagS3Object`    | `TagS3ObjectUniversal`    |

Cambios aplicados en cada clase:
- Nombre del archivo `.java` renombrado
- Declaración `public class XxxUniversal extends AbstractS3Processor`
- Anotación `@Tags` actualizada — se añade `"Universal"`
- `@CapabilityDescription` actualizada — menciona soporte dinámico de región y OCI
- `@SeeAlso` actualizado — referencias a las nuevas clases `*Universal`

---

## 3. Nueva propiedad REGION_NAME (Paso 5 / Paso 6)

### Archivo afectado
`nifi-aws-abstract-processors/.../AbstractS3Processor.java`

### PropertyDescriptor agregado

```java
public static final PropertyDescriptor REGION_NAME = new PropertyDescriptor.Builder()
    .name("region-name")
    .displayName("Region Name")
    .description(
        "Nombre o código de la región del servicio S3 (ej. us-east-1, ap-south-2, me-central-1). " +
        "Para servicios S3-compatibles como OCI, ingresar la URL de endpoint completa " +
        "(ej. https://namespace.compat.objectstorage.us-ashburn-1.oraclecloud.com). " +
        "Si el valor contiene '://', se trata como URL de endpoint; " +
        "en caso contrario se construye el endpoint como s3.<region>.amazonaws.com.")
    .required(true)
    .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
    .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
    .build();
```

### Lógica de detección automática (en `createClient`)

```java
String regionOrEndpoint = context.getProperty(REGION_NAME).evaluateAttributeExpressions().getValue();
AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard()
    .withCredentials(credentialsProvider)
    .withClientConfiguration(config);

if (regionOrEndpoint.contains("://")) {
    // Endpoint completo — para OCI u otros servicios S3-compatibles
    builder.withEndpointConfiguration(
        new AwsClientBuilder.EndpointConfiguration(regionOrEndpoint, "us-east-1"));
} else {
    // Código de región — construye endpoint estándar de AWS
    String endpoint = "https://s3." + regionOrEndpoint + ".amazonaws.com";
    builder.withEndpointConfiguration(
        new AwsClientBuilder.EndpointConfiguration(endpoint, regionOrEndpoint));
}
```

> Se usa `withEndpointConfiguration()` en lugar de `withRegion(Regions.fromName())` para
> evitar la dependencia del enum `Regions` y soportar regiones no enumeradas en SDK v1.

---

## 4. Cambios en `AbstractAWSProcessor` (Paso 6)

### Archivo
`nifi-aws-abstract-processors/.../AbstractAWSProcessor.java`

### Modificaciones
- Se elimina `REGION` de la lista de `supportedPropertyDescriptors` en `AbstractS3Processor`
- Se añade `REGION_NAME` a la lista de descriptores soportados
- El método `getRegionAndInitializeEndpoint()` ya no se invoca desde `AbstractS3Processor`;
  la configuración de región/endpoint se hace directamente en `createClient()`
- Los métodos auxiliares `getAvailableRegions()` y `createAllowableValue(Regions)` se conservan
  en `AbstractAWSProcessor` para no romper la compilación (otros módulos podrían referenciarlos),
  pero no se usan en los procesadores Universal

---

## 5. Cambios en `AbstractS3Processor` (Paso 6)

### Archivo
`nifi-aws-abstract-processors/.../s3/AbstractS3Processor.java`

### Modificaciones
- Se sobrescribe `createClient(ProcessContext, AWSCredentialsProvider, ClientConfiguration)`
  para inyectar la región/endpoint desde `REGION_NAME`
- Se añade `REGION_NAME` a `PROPERTIES` (lista de descriptores)
- Se elimina `REGION` de `PROPERTIES` (ya no aplica)

---

## 6. Tests unitarios (Paso 7)

### Inventario de tests — 166 tests, 0 fallos

Los tests están acotados exclusivamente a los procesadores y clases que modificamos:

#### Tests de los 5 procesadores Universal

| Clase | Tests | Qué verifica |
|---|---|---|
| `AbstractS3UniversalTest` | — (clase base) | Define los 8 tests comunes; `setAdditionalRequiredProperties()` hook permite que subclases añadan propiedades requeridas adicionales |
| `TestDeleteS3ObjectUniversal` | 13 | Borrado con región AWS estándar, región nueva (ap-south-2), endpoint OCI, excepción S3, borrado de versión |
| `TestFetchS3ObjectUniversal` | 13 | Descarga con región AWS estándar, región nueva, endpoint OCI, excepción S3, atributos escritos en FlowFile |
| `TestListS3Universal` | 12 | Listado con región AWS estándar, región nueva, endpoint OCI, excepción S3 |
| `TestPutS3ObjectUniversal` | 13 | Upload con región AWS estándar, región nueva, endpoint OCI, excepción S3, atributos escritos en FlowFile |
| `TestTagS3ObjectUniversal` | 13 | Tagging con región AWS estándar, región nueva, endpoint OCI, excepción S3, modo APPEND |

Los 8 tests heredados de `AbstractS3UniversalTest` por cada procesador verifican:
- `REGION_NAME` está presente en la lista de propiedades
- `REGION` (enum fijo) **no** está en la lista de propiedades
- Sin `REGION_NAME` el procesador es inválido
- Con región AWS estándar (`us-east-1`) es válido
- Con región nueva (`ap-south-2`) es válido
- Con URL de endpoint OCI es válida
- Con cadena vacía es inválido
- Con Expression Language (`${s3.region}`) es válido

#### Tests de credenciales y proxy

| Clase | Tests | Qué verifica |
|---|---|---|
| `TestCredentialsProviderFactory` | 17 | Fábrica de proveedores de credenciales (usa `FetchS3ObjectUniversal`) |
| `AWSProcessorProxyTest` | 8 | Configuración de proxy HTTP/HTTPS (usa `FetchS3ObjectUniversal`) |
| `AWSCredentialsProviderControllerServiceTest` | 19 | Servicio de credenciales como Controller Service |
| `TestAWSCredentials` | 4 | `AbstractAWSProcessor` con credenciales anónimas, clave/secreto y archivo |

#### Tests de estrategias de cifrado

| Clase | Tests | Qué verifica |
|---|---|---|
| `TestClientSideCEncryptionStrategyKeyValidation` | 7 | Validación de clave AES (128/192/256 bits, vacía, no Base64) |
| `TestServerSideCEncryptionStrategyKeyValidation` | 5 | Validación de clave SSE-C |
| `TestS3EncryptionStrategies` | 6 | Estrategias CSE-C, CSE-KMS, SSE-C, SSE-KMS, SSE-S3, NoOp |
| `TestStandardS3EncryptionServiceValidation` | 32 | Validación de combinaciones del `StandardS3EncryptionService` |
| `TestStandardS3EncryptionService` | 4 | `StandardS3EncryptionService` como Controller Service |

### Correcciones aplicadas a los tests

#### 1. `AbstractS3Processor.createClient` — cliente inmutable

`AmazonS3ClientBuilder` produce un cliente **inmutable**. Llamar `setS3ClientOptions()` después del
`.build()` lanza `UnsupportedOperationException: Client is immutable when created with the builder`.

**Fix**: todas las opciones S3 (path-style access, chunked encoding) se configuran en el
builder antes de invocar `.build()`, usando `withPathStyleAccessEnabled()` y
`withChunkedEncodingDisabled()`. Para el caso de clientes de encriptación (creados sin builder) se
mantiene una función `configureClientOptionsLegacy()` que llama `setS3ClientOptions()`.

#### 2. `AWSProcessorProxyTest` — `REGION_NAME` requerido

`FetchS3ObjectUniversal` ahora requiere `REGION_NAME`. El `@BeforeEach` del test seteaba solo `BUCKET`
y llamaba `assertValid()`, lo que fallaba con `'Region Name' is invalid because Region Name is required`.

**Fix**: añadir `runner.setProperty(AbstractS3Processor.REGION_NAME, "us-east-1")` al setup.

#### 3. `TestPutS3ObjectUniversal` — mock `listMultipartUploads` sin configurar

`PutS3ObjectUniversal.onTrigger()` llama `ageoffS3Uploads()` que internamente llama
`s3.listMultipartUploads(...)`. El mock devolvía `null` (valor por defecto Mockito), causando
NPE al iterar `listing.getMultipartUploads()`.

**Fix**: añadir al `@BeforeEach`:
```java
final MultipartUploadListing emptyListing = new MultipartUploadListing();
emptyListing.setMultipartUploads(new ArrayList<>());
Mockito.when(mockS3Client.listMultipartUploads(any(...))).thenReturn(emptyListing);
```

#### 4. `TestPutS3ObjectUniversal` — mock `PutObjectResult.getMetadata()` null

`PutS3ObjectUniversal.onTrigger()` llama `result.getMetadata().getStorageClass()` para escribir
el atributo `s3.storageClass`. `new PutObjectResult()` no inicializa el campo `metadata`, devolviendo
`null` → NPE.

**Fix**: añadir `mockResult.setMetadata(new ObjectMetadata())` al crear el mock de resultado.

#### 5. `TestFetchS3ObjectUniversal.testFetchObjectAttributesWritten` — `s3.bucket` null

`FetchS3ObjectUniversal` establece el atributo `s3.bucket` a partir de `s3Object.getBucketName()`.
El `new S3Object()` del mock no inicializa el campo, devolviendo `null`.

**Fix**: en el test `testFetchObjectAttributesWritten` se configura un `S3Object` específico con
`s3ObjectWithBucket.setBucketName("my-bucket")`.

#### 6. `AbstractS3IT.java` — clase huérfana eliminada

Clase base para integration tests que referenciaba `ITDeleteS3Object`, `ITFetchS3Object`,
`ITPutS3Object` e `ITListS3` (eliminados). También usaba `Regions.fromName()` (API reemplazada).
Eliminado porque no existe ninguna subclase concreta que la extienda.

---

## 7. Compilación (Paso 8)

### Prerequisitos

```bash
# Java 1.8
java -version          # debe mostrar 1.8.x
# Con SDKMAN (si hay varias versiones instaladas):
sdk use java 8.0.x-...

# Maven 3.x
mvn -version
```

### Comando de compilación

```bash
cd nifi-source/nifi-nar-bundles/nifi-aws-bundle
mvn clean package -DskipTests
```

Artefacto resultante:
```
nifi-aws-nar-universal/target/nifi-aws-nar-universal-1.20.0.nar
```

### Compilación en entorno limpio (`~/.m2` vacío o máquina nueva)

No se requiere ningún paso previo. Maven descarga todo automáticamente de Maven Central
durante el primer build (~200 MB de dependencias). El comando es idéntico:

```bash
cd nifi-source/nifi-nar-bundles/nifi-aws-bundle
mvn clean package -DskipTests
```

Lo que Maven resuelve automáticamente:

| Origen | Qué descarga |
|---|---|
| Archivos locales (relativePath) | `nifi-nar-bundles/pom.xml` y `nifi-source/pom.xml` — ya en el repo |
| Maven Central | `org.apache:apache:29` (bisabuelo POM) |
| Maven Central | `nifi-api`, `nifi-framework-api`, `nifi-mock`, `aws-java-sdk-s3`, `aws-java-sdk-sts`, `aws-java-sdk-core`, `commons-io`, `commons-lang3` y ~150 dependencias transitivas |

### Nota sobre el plugin NAR

`nifi-nar-maven-plugin:1.4.0` intenta generar documentación de extensiones durante el
empaquetado. Al compilar fuera del árbol completo de NiFi no puede resolver todas las
dependencias de la cadena NAR (`nifi-jetty-bundle`, etc.). Para evitar que el build
falle, el módulo `nifi-aws-nar-universal/pom.xml` tiene configurado:

```xml
<plugin>
    <artifactId>nifi-nar-maven-plugin</artifactId>
    <configuration>
        <enforceDocGeneration>false</enforceDocGeneration>
    </configuration>
</plugin>
```

Esto permite que el build continúe con una advertencia en lugar de un error.

> **Nota histórica:** durante el desarrollo inicial se usó un proyecto Maven temporal
> (`prefetch-pom.xml`) para pre-descargar artefactos del framework NiFi. Ese paso quedó
> obsoleto en cuanto se añadió `<enforceDocGeneration>false</enforceDocGeneration>`.

El módulo NAR fue renombrado de `nifi-aws-nar` a `nifi-aws-nar-universal` (directorio y `artifactId`)
para evitar conflictos con el NAR oficial de NiFi al desplegarse en el mismo servidor.

### Identidad del NAR — namespace propio (`groupId`)

Al intentar arrancar NiFi con el NAR se detectó un conflicto de classloader: el NAR compartía
el namespace `org.apache.nifi` con el NAR oficial, lo que causaba colisiones cuando ambos
cargaban el mismo `nifi-aws-service-api-nar` como classloader padre.

**Solución**: añadir un `groupId` explícito `com.custom.nifi` al módulo `nifi-aws-nar-universal`.
Solo se modifica ese módulo — los JARs internos no afectan al sistema de classloading de NiFi.

MANIFEST.MF resultante:
```
Nar-Group: com.custom.nifi              ← namespace propio, sin conflicto
Nar-Id: nifi-aws-nar-universal
Nar-Dependency-Group: org.apache.nifi
Nar-Dependency-Id: nifi-aws-service-api-nar   ← usa el NAR oficial ya en lib/
Nar-Dependency-Version: 1.20.0
```

De esta forma:
- El NAR oficial `org.apache.nifi:nifi-aws-nar:1.20.0` permanece intacto (Kinesis, SQS, SNS, etc.)
- Nuestro NAR `com.custom.nifi:nifi-aws-nar-universal:1.20.0` coexiste sin conflicto
- Ambos usan `org.apache.nifi:nifi-aws-service-api-nar:1.20.0` como classloader padre

### Despliegue

```bash
# Copiar SOLO nuestro NAR — NO eliminar el nifi-aws-nar-1.20.0.nar oficial
cp nifi-aws-nar-universal/target/nifi-aws-nar-universal-1.20.0.nar \
   /ruta/a/nifi-1.20/extensions/
# Reiniciar NiFi para que cargue el nuevo NAR
```

---

## 8. Corrección del registro de extensiones (`services`)

### Síntoma
NiFi fallaba al arrancar con errores del tipo:
```
RuntimeException: Could not create Class for ExtensionDefinition[
  type=Processor,
  implementation=org.apache.nifi.processors.aws.kinesis.stream.ConsumeKinesisStream,
  bundle=com.custom.nifi:nifi-aws-nar-universal:1.20.0]
Caused by: java.lang.ClassNotFoundException: ...ConsumeKinesisStream
```

### Causa raíz
NiFi utiliza el archivo
`META-INF/services/org.apache.nifi.processor.Processor`
dentro del JAR para descubrir todas las clases de procesador al iniciar
(`StandardExtensionDiscoveringManager`). El archivo original listaba las **28 clases**
del NAR oficial (Kinesis, DynamoDB, Lambda, ML, SNS, SQS, CloudWatch, etc.). Al haber
eliminado esas clases del código fuente, el JAR ya no las contenía, pero el archivo
de servicios seguía referenciándolas → `ClassNotFoundException` fatal.

### Archivo modificado

```
nifi-source/nifi-nar-bundles/nifi-aws-bundle/
  nifi-aws-processors/src/main/resources/
    META-INF/services/org.apache.nifi.processor.Processor
```

### Cambio aplicado

**Antes** (28 entradas — todas las del NAR original):
```
org.apache.nifi.processors.aws.s3.FetchS3Object
org.apache.nifi.processors.aws.s3.PutS3Object
org.apache.nifi.processors.aws.s3.DeleteS3Object
org.apache.nifi.processors.aws.s3.TagS3Object
org.apache.nifi.processors.aws.s3.ListS3
org.apache.nifi.processors.aws.sns.PutSNS
org.apache.nifi.processors.aws.sqs.GetSQS
... (23 entradas más: SQS, Kinesis, DynamoDB, Lambda, ML, CloudWatch, WAG)
```

**Después** (5 entradas — solo los procesadores Universal implementados):
```
org.apache.nifi.processors.aws.s3.DeleteS3ObjectUniversal
org.apache.nifi.processors.aws.s3.FetchS3ObjectUniversal
org.apache.nifi.processors.aws.s3.ListS3Universal
org.apache.nifi.processors.aws.s3.PutS3ObjectUniversal
org.apache.nifi.processors.aws.s3.TagS3ObjectUniversal
```

### Resultado
NiFi arranca correctamente. Los 5 procesadores Universal quedan disponibles en la UI.
El NAR oficial `org.apache.nifi:nifi-aws-nar:1.20.0` sigue cargando sus propios procesadores
(Kinesis, SQS, etc.) sin ninguna interferencia.

---

## 9. Limpieza de `nifi-source/`

### Contexto

Al clonar el repositorio oficial de NiFi 1.20 se descarga el árbol completo del proyecto
(186 MB, 22.000+ archivos). Solo el sub-árbol `nifi-aws-bundle` es relevante para este
proyecto. El resto son módulos huérfanos: otros bundles de NiFi, minifi, registry, toolkit,
docs, tests de sistema, etc.

Todos los artefactos externos que necesita `nifi-aws-bundle` (APIs, utilidades NiFi,
dependencias de test) ya quedan descargados en `~/.m2` tras la primera compilación
exitosa, por lo que el código fuente de esos módulos no es necesario.

### Módulos / archivos eliminados

| Categoría | Eliminado |
|---|---|
| Otros bundles NiFi | `nifi-nar-bundles/nifi-*` (99 bundles: standard, kafka, hadoop, gcp…) |
| Módulos top-level del core | `nifi-api/`, `nifi-commons/`, `nifi-framework-api/`, `nifi-server-api/`, `nifi-bootstrap/` |
| Empaquetado y distribución | `nifi-assembly/`, `nifi-docker/`, `nifi-docs/`, `nifi-stateless/` |
| Proyectos hermanos | `minifi/`, `c2/`, `nifi-registry/` |
| Herramientas y utilidades | `nifi-toolkit/`, `nifi-h2/`, `nifi-manifest/`, `nifi-external/`, `nifi-system-tests/` |
| Build / plugins | `nifi-maven-archetypes/`, `nifi-dependency-check-maven/`, `nifi-mock/` |
| Metadatos del repo Apache | `.asf.yaml`, `.github/`, `.gitignore`, `checkstyle.xml`, `KEYS`, `LICENSE`, `NOTICE`, `README.md`, `SECURITY.md` |

### Resultado de la limpieza

| | Antes | Después |
|---|---|---|
| Archivos en `nifi-source/` | 22.385 | **175** |
| Peso en disco | 186 MB | **64 MB** |
| Tiempo de compilación | ~32 s | **~7 s** |

### Estructura final de `nifi-source/`

```
nifi-source/
├── pom.xml                              ← grandparent POM (BOM global de versiones)
└── nifi-nar-bundles/
    ├── pom.xml                          ← parent POM directo de nifi-aws-bundle
    └── nifi-aws-bundle/                 ← nuestro código
        ├── pom.xml
        ├── nifi-aws-service-api/        ← interfaces de servicios AWS (AWSCredentialsProviderService, etc.)
        ├── nifi-aws-service-api-nar/    ← NAR de las interfaces de servicio
        ├── nifi-aws-abstract-processors/← AbstractAWSProcessor, AbstractS3Processor + REGION_NAME
        ├── nifi-aws-processors/         ← los 5 procesadores *Universal
        └── nifi-aws-nar-universal/      ← empaquetado final → nifi-aws-nar-universal-1.20.0.nar
```

---

## 10. Cadena de POMs Maven y cómo se resuelve la compilación

### Cadena de herencia POM

Al ejecutar `mvn clean package` desde `nifi-aws-bundle/`, Maven construye el árbol de
herencia leyendo cada `<parent>` de forma recursiva:

```
nifi-aws-bundle/pom.xml
│  <parent> nifi-nar-bundles:1.20.0
│  Maven busca primero ../pom.xml  →  nifi-nar-bundles/pom.xml  (existe localmente)
│
└── nifi-nar-bundles/pom.xml
    │  <parent> nifi:1.20.0
    │  Maven busca primero ../pom.xml  →  nifi-source/pom.xml  (existe localmente)
    │
    └── nifi-source/pom.xml
        │  <parent> org.apache:apache:29
        │  <relativePath /> (vacío → no busca localmente)
        │  Maven descarga de Maven Central
        │
        └── org.apache:apache:29  (Maven Central)
```

### Regla de resolución del `<parent>`

Maven sigue este orden para cada nivel:

1. Si el `pom.xml` hijo tiene `<relativePath>` explícito y el archivo existe → lo lee localmente.
2. Si no hay `<relativePath>` explícito → intenta `../pom.xml` por defecto; si existe, lo lee localmente.
3. Si ningún archivo local coincide → busca en `~/.m2` (caché local).
4. Si tampoco está en caché → descarga desde los repositorios remotos (Maven Central / repo NiFi).

Por eso se conservan los dos POMs locales (`nifi-source/pom.xml` y
`nifi-nar-bundles/pom.xml`): ambos existen en `~/.m2` tras la primera descarga, pero
Maven les da preferencia al encontrarlos por `relativePath`. Eliminarlos no rompe la
compilación en esta máquina (están en caché), pero sí podría hacerlo en un entorno
limpio donde aún no se hayan descargado.

### Dependencias externas — resueltas desde `~/.m2`

Todas las dependencias que `nifi-aws-bundle` necesita de otros módulos NiFi ya están
descargadas como artefactos binarios en `~/.m2`; no se necesita su código fuente:

| Artefacto | Uso |
|---|---|
| `nifi-api-1.20.0.jar` | API pública de NiFi (Processor, PropertyDescriptor…) |
| `nifi-framework-api-1.20.0.jar` | API del framework (ControllerService…) |
| `nifi-mock-1.20.0.jar` | Framework de tests unitarios |
| `nifi-standard-services-api-nar-1.20.0.nar` | NAR padre del api-nar propio |
| `nifi-distributed-cache-client-service-api-1.20.0.jar` | Interfaz de caché distribuida |
| `nifi-listed-entity-1.20.0.jar` | Utilidad para listar entidades (ListS3) |
| `nifi-standard-record-utils-1.20.0.jar` | Utilidades de records |
| `nifi-record-serialization-services-1.20.0.jar` | Servicios de serialización |

---

## 7. Fix — Propiedades `Use Path Style Access` y `Use Chunked Encoding` faltantes (Jun 2026)

### Problema detectado en producción

Al intentar usar `ListS3Universal` contra un bucket OCI Object Storage (región Santiago `sa-santiago-1`)
se obtuvo el error:

```
Failed to list contents of bucket '...': Unable to execute HTTP request:
Certificate for <bucket.namespace.compat.objectstorage.sa-santiago-1.oraclecloud.com>
doesn't match any of the subject alternative names:
[swiftobjectstorage.sa-santiago-1.oraclecloud.com]
```

Adicionalmente, las propiedades `Use Path Style Access` y `Use Chunked Encoding` no aparecían
en ningún procesador salvo `PutS3ObjectUniversal`.

### Causa raíz

Las propiedades `USE_PATH_STYLE_ACCESS` y `USE_CHUNKED_ENCODING` están **definidas** en
`AbstractS3Processor` (clase base), pero **nunca fueron registradas** en la lista `properties`
de 4 de los 5 procesadores Universal. Solo `PutS3ObjectUniversal` las incluía (herencia del
`PutS3Object` original de NiFi).

| Procesador | `USE_PATH_STYLE_ACCESS` antes del fix | `USE_CHUNKED_ENCODING` antes del fix |
|---|---|---|
| `ListS3Universal` | ausente | ausente |
| `FetchS3ObjectUniversal` | ausente | ausente |
| `PutS3ObjectUniversal` | presente | presente |
| `DeleteS3ObjectUniversal` | ausente | ausente |
| `TagS3ObjectUniversal` | ausente | ausente |

Al no poder configurar `Use Path Style Access = true`, el AWS SDK generaba URLs con el bucket
prepuesto al hostname (*virtual-hosted style*):

```
<bucket>.<namespace>.compat.objectstorage.sa-santiago-1.oraclecloud.com
```

OCI solo expide el certificado SSL para el host base sin el prefijo del bucket, por lo que
el handshake TLS falla con un error de SAN (*Subject Alternative Names*).

### Archivos modificados

Se agregaron `USE_PATH_STYLE_ACCESS` y `USE_CHUNKED_ENCODING` después de `ENDPOINT_OVERRIDE`
en la lista `properties` de los cuatro procesadores afectados:

- `ListS3Universal.java`
- `FetchS3ObjectUniversal.java`
- `DeleteS3ObjectUniversal.java`
- `TagS3ObjectUniversal.java`

Patrón aplicado en los 4 archivos:

```java
// Antes
            ENDPOINT_OVERRIDE,
            SIGNER_OVERRIDE,

// Después
            ENDPOINT_OVERRIDE,
            USE_PATH_STYLE_ACCESS,
            USE_CHUNKED_ENCODING,
            SIGNER_OVERRIDE,
```

### Configuración requerida para OCI tras el fix

| Propiedad | Valor requerido |
|---|---|
| `Use Path Style Access` | `true` |
| `Use Chunked Encoding` | `false` |

---

## 8. Fix — Región de firma incorrecta para regiones OCI con nombre de ciudad (Jun 2026)

### Problema detectado en producción

Al usar `ListS3Universal` con el endpoint OCI de Santiago
(`https://tdecloud.compat.objectstorage.sa-santiago-1.oraclecloud.com`) se obtuvo:

```
SignatureDoesNotMatch: The secret key required to complete authentication could not be found.
The region must be specified if this is not the home region for the tenancy.
(Status Code: 403)
```

### Causa raíz

El método `extractSigningRegion` en `AbstractS3Processor` usa una expresión regular que solo
reconoce regiones con **nombres de dirección en inglés** (`east`, `west`, `north`, `south`,
`central`, `northeast`, `northwest`, `southeast`, `southwest`):

```java
// Patrón anterior — no reconoce nombres de ciudad
Pattern.compile("[./-]([a-z]{2}-(?:gov-)?(?:east|west|north|south|central|...)-\\d)[./-]");
```

Para la región `sa-santiago-1`, el segmento `santiago` **no está** en esa lista, por lo que
el método no extrae la región y retorna el fallback `us-east-1`. El request se firma con
región `us-east-1`, pero OCI exige que la firma use `sa-santiago-1` → `SignatureDoesNotMatch`.

El mismo problema afecta a otras regiones OCI con nombres de ciudad:
`eu-frankfurt-1`, `uk-london-1`, `sa-saopaulo-1`, `sa-vinhedo-1`, `ap-tokyo-1`, `ap-sydney-1`, etc.

### Archivo modificado

`AbstractS3Processor.java` — patrón `REGION_FROM_URL_PATTERN`:

```java
// Antes
Pattern.compile("[./-]([a-z]{2}-(?:gov-)?(?:east|west|...)-\\d)[./-]");

// Después — acepta cualquier palabra en minúsculas, incluidos nombres de ciudad
Pattern.compile("[./-]([a-z]{2,3}-(?:[a-z]+-)*\\d+)[./-]");
```

El nuevo patrón `[a-z]{2,3}-(?:[a-z]+-)*\d+` cubre todos los formatos conocidos:

| Región | Patrón anterior | Patrón nuevo |
|---|---|---|
| `us-east-1` (AWS) | ✓ | ✓ |
| `ap-southeast-2` (AWS) | ✓ | ✓ |
| `us-gov-west-1` (AWS GovCloud) | ✓ | ✓ |
| `sa-santiago-1` (OCI) | ✗ → fallback `us-east-1` | ✓ |
| `eu-frankfurt-1` (OCI) | ✗ → fallback `us-east-1` | ✓ |
| `uk-london-1` (OCI) | ✗ → fallback `us-east-1` | ✓ |
| `sa-saopaulo-1` (OCI) | ✗ → fallback `us-east-1` | ✓ |
| `ap-tokyo-1` (OCI) | ✗ → fallback `us-east-1` | ✓ |
