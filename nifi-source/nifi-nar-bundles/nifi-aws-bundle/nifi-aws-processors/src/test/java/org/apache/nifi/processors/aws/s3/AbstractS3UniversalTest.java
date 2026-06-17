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

import com.amazonaws.services.s3.AmazonS3Client;
import org.apache.nifi.components.ConfigurableComponent;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.util.TestRunner;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Clase base con tests comunes a todos los procesadores S3 Universal.
 * Verifica que REGION_NAME est? presente, que REGION (enum fijo) fue eliminado,
 * y la l?gica de validaci?n de la nueva propiedad.
 */
public abstract class AbstractS3UniversalTest {

    protected abstract TestRunner getRunner();
    protected abstract AbstractS3Processor getProcessor();

    /**
     * Hook for subclasses to set additional required properties before assertValid() calls.
     * Override this if the processor has required properties beyond BUCKET + REGION_NAME.
     */
    protected void setAdditionalRequiredProperties(TestRunner runner) {
        // default: no additional properties
    }

    /** REGION_NAME debe estar en la lista de propiedades soportadas. */
    @Test
    public void testRegionNamePropertyPresent() {
        List<PropertyDescriptor> props = ((ConfigurableComponent) getProcessor()).getPropertyDescriptors();
        assertTrue(props.contains(AbstractS3Processor.REGION_NAME),
                "REGION_NAME debe estar en los descriptores soportados");
    }

    /** REGION (enum fijo del SDK) NO debe estar en la lista de propiedades. */
    @Test
    public void testOldRegionEnumNotPresent() {
        List<PropertyDescriptor> props = ((ConfigurableComponent) getProcessor()).getPropertyDescriptors();
        assertFalse(props.contains(AbstractS3Processor.REGION),
                "REGION (enum fijo) no debe estar en los descriptores; fue reemplazado por REGION_NAME");
    }

    /** Sin REGION_NAME el procesador no debe ser v?lido. */
    @Test
    public void testInvalidWithoutRegionName() {
        TestRunner runner = getRunner();
        runner.setProperty(AbstractS3Processor.BUCKET, "test-bucket");
        runner.assertNotValid();
    }

    /** Con un c?digo de regi?n est?ndar el procesador debe ser v?lido. */
    @Test
    public void testValidWithStandardRegionCode() {
        TestRunner runner = getRunner();
        runner.setProperty(AbstractS3Processor.BUCKET, "test-bucket");
        runner.setProperty(AbstractS3Processor.REGION_NAME, "us-east-1");
        setAdditionalRequiredProperties(runner);
        runner.assertValid();
    }

    /** Con una regi?n nueva (no en el enum Regions) el procesador debe ser v?lido. */
    @Test
    public void testValidWithNewRegionCode() {
        TestRunner runner = getRunner();
        runner.setProperty(AbstractS3Processor.BUCKET, "test-bucket");
        runner.setProperty(AbstractS3Processor.REGION_NAME, "ap-south-2");
        setAdditionalRequiredProperties(runner);
        runner.assertValid();
    }

    /** Con una URL de endpoint completa (OCI) el procesador debe ser v?lido. */
    @Test
    public void testValidWithOciEndpointUrl() {
        TestRunner runner = getRunner();
        runner.setProperty(AbstractS3Processor.BUCKET, "test-bucket");
        runner.setProperty(AbstractS3Processor.REGION_NAME,
                "https://namespace.compat.objectstorage.us-ashburn-1.oraclecloud.com");
        setAdditionalRequiredProperties(runner);
        runner.assertValid();
    }

    /** Con una cadena vac?a en REGION_NAME el procesador no debe ser v?lido. */
    @Test
    public void testInvalidWithEmptyRegionName() {
        TestRunner runner = getRunner();
        runner.setProperty(AbstractS3Processor.BUCKET, "test-bucket");
        runner.setProperty(AbstractS3Processor.REGION_NAME, "");
        runner.assertNotValid();
    }

    /** REGION_NAME soporta Expression Language (variable NiFi). */
    @Test
    public void testValidWithExpressionLanguage() {
        TestRunner runner = getRunner();
        runner.setProperty(AbstractS3Processor.BUCKET, "test-bucket");
        runner.setProperty(AbstractS3Processor.REGION_NAME, "${s3.region}");
        setAdditionalRequiredProperties(runner);
        runner.assertValid();
    }

    /** Mock helper: crea un AmazonS3Client simulado. */
    protected static AmazonS3Client mockS3Client() {
        return org.mockito.Mockito.mock(AmazonS3Client.class);
    }
}
