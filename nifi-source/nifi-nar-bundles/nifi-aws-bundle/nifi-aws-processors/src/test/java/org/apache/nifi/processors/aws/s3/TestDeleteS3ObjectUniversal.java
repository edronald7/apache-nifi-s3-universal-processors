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
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.DeleteObjectRequest;
import com.amazonaws.services.s3.model.DeleteVersionRequest;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestDeleteS3ObjectUniversal extends AbstractS3UniversalTest {

    private AmazonS3Client mockS3Client;
    private DeleteS3ObjectUniversal processor;
    private TestRunner runner;

    @BeforeEach
    public void setUp() {
        mockS3Client = mockS3Client();
        processor = new DeleteS3ObjectUniversal() {
            @Override
            protected AmazonS3Client getClient() {
                return mockS3Client;
            }
        };
        runner = TestRunners.newTestRunner(processor);
    }

    @Override
    protected TestRunner getRunner() {
        return runner;
    }

    @Override
    protected AbstractS3Processor getProcessor() {
        return processor;
    }

    // -----------------------------------------------------------------------
    // Tests de comportamiento con región AWS estándar
    // -----------------------------------------------------------------------

    @Test
    public void testDeleteObjectStandardRegion() {
        runner.setProperty(DeleteS3ObjectUniversal.REGION_NAME, "us-east-1");
        runner.setProperty(DeleteS3ObjectUniversal.BUCKET, "test-bucket");
        final Map<String, String> attrs = new HashMap<>();
        attrs.put("filename", "delete-key");
        runner.enqueue(new byte[0], attrs);

        runner.run(1);

        runner.assertAllFlowFilesTransferred(DeleteS3ObjectUniversal.REL_SUCCESS, 1);
        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        Mockito.verify(mockS3Client, Mockito.times(1)).deleteObject(captor.capture());
        assertEquals("test-bucket", captor.getValue().getBucketName());
        assertEquals("delete-key", captor.getValue().getKey());
    }

    /** Región nueva no enumerada en SDK v1 — debe funcionar igual. */
    @Test
    public void testDeleteObjectNewAwsRegion() {
        runner.setProperty(DeleteS3ObjectUniversal.REGION_NAME, "ap-south-2");
        runner.setProperty(DeleteS3ObjectUniversal.BUCKET, "test-bucket");
        final Map<String, String> attrs = new HashMap<>();
        attrs.put("filename", "delete-key");
        runner.enqueue(new byte[0], attrs);

        runner.run(1);

        runner.assertAllFlowFilesTransferred(DeleteS3ObjectUniversal.REL_SUCCESS, 1);
        Mockito.verify(mockS3Client, Mockito.times(1)).deleteObject(Mockito.any(DeleteObjectRequest.class));
    }

    /** URL de endpoint OCI — debe funcionar igual. */
    @Test
    public void testDeleteObjectOciEndpoint() {
        runner.setProperty(DeleteS3ObjectUniversal.REGION_NAME,
                "https://namespace.compat.objectstorage.us-ashburn-1.oraclecloud.com");
        runner.setProperty(DeleteS3ObjectUniversal.BUCKET, "test-bucket");
        final Map<String, String> attrs = new HashMap<>();
        attrs.put("filename", "delete-key");
        runner.enqueue(new byte[0], attrs);

        runner.run(1);

        runner.assertAllFlowFilesTransferred(DeleteS3ObjectUniversal.REL_SUCCESS, 1);
        Mockito.verify(mockS3Client, Mockito.times(1)).deleteObject(Mockito.any(DeleteObjectRequest.class));
    }

    @Test
    public void testDeleteObjectS3Exception() {
        runner.setProperty(DeleteS3ObjectUniversal.REGION_NAME, "us-east-1");
        runner.setProperty(DeleteS3ObjectUniversal.BUCKET, "test-bucket");
        final Map<String, String> attrs = new HashMap<>();
        attrs.put("filename", "delete-key");
        runner.enqueue(new byte[0], attrs);
        Mockito.doThrow(new AmazonS3Exception("NoSuchBucket"))
               .when(mockS3Client).deleteObject(Mockito.any());

        runner.run(1);

        runner.assertAllFlowFilesTransferred(DeleteS3ObjectUniversal.REL_FAILURE, 1);
    }

    @Test
    public void testDeleteVersionSimple() {
        runner.setProperty(DeleteS3ObjectUniversal.REGION_NAME, "us-east-1");
        runner.setProperty(DeleteS3ObjectUniversal.BUCKET, "test-bucket");
        runner.setProperty(DeleteS3ObjectUniversal.VERSION_ID, "test-version");
        final Map<String, String> attrs = new HashMap<>();
        attrs.put("filename", "test-key");
        runner.enqueue(new byte[0], attrs);

        runner.run(1);

        runner.assertAllFlowFilesTransferred(DeleteS3ObjectUniversal.REL_SUCCESS, 1);
        ArgumentCaptor<DeleteVersionRequest> captor = ArgumentCaptor.forClass(DeleteVersionRequest.class);
        Mockito.verify(mockS3Client, Mockito.times(1)).deleteVersion(captor.capture());
        assertEquals("test-bucket", captor.getValue().getBucketName());
        assertEquals("test-key", captor.getValue().getKey());
        assertEquals("test-version", captor.getValue().getVersionId());
        Mockito.verify(mockS3Client, Mockito.never()).deleteObject(Mockito.any(DeleteObjectRequest.class));
    }
}
