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
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.util.StringInputStream;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestFetchS3ObjectUniversal extends AbstractS3UniversalTest {

    private AmazonS3Client mockS3Client;
    private FetchS3ObjectUniversal processor;
    private TestRunner runner;

    @BeforeEach
    public void setUp() throws IOException {
        mockS3Client = mockS3Client();
        processor = new FetchS3ObjectUniversal() {
            @Override
            protected AmazonS3Client getClient() {
                return mockS3Client;
            }
        };
        runner = TestRunners.newTestRunner(processor);

        // Configurar respuesta por defecto del mock
        final S3Object s3Object = new S3Object();
        final ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentDisposition("test-file.txt");
        s3Object.setObjectMetadata(metadata);
        s3Object.setObjectContent(new StringInputStream("content"));
        Mockito.when(mockS3Client.getObject(Mockito.any(GetObjectRequest.class))).thenReturn(s3Object);
    }

    @Override
    protected TestRunner getRunner() {
        return runner;
    }

    @Override
    protected AbstractS3Processor getProcessor() {
        return processor;
    }

    @Test
    public void testFetchObjectStandardRegion() {
        runner.setProperty(FetchS3ObjectUniversal.REGION_NAME, "us-east-1");
        runner.setProperty(FetchS3ObjectUniversal.BUCKET, "test-bucket");
        final Map<String, String> attrs = new HashMap<>();
        attrs.put("filename", "test-key");
        runner.enqueue(new byte[0], attrs);

        runner.run(1);

        runner.assertAllFlowFilesTransferred(FetchS3ObjectUniversal.REL_SUCCESS, 1);
        ArgumentCaptor<GetObjectRequest> captor = ArgumentCaptor.forClass(GetObjectRequest.class);
        Mockito.verify(mockS3Client, Mockito.times(1)).getObject(captor.capture());
        assertEquals("test-bucket", captor.getValue().getBucketName());
        assertEquals("test-key", captor.getValue().getKey());
    }

    /** Región nueva no enumerada en SDK v1. */
    @Test
    public void testFetchObjectNewAwsRegion() {
        runner.setProperty(FetchS3ObjectUniversal.REGION_NAME, "me-central-1");
        runner.setProperty(FetchS3ObjectUniversal.BUCKET, "test-bucket");
        final Map<String, String> attrs = new HashMap<>();
        attrs.put("filename", "test-key");
        runner.enqueue(new byte[0], attrs);

        runner.run(1);

        runner.assertAllFlowFilesTransferred(FetchS3ObjectUniversal.REL_SUCCESS, 1);
        Mockito.verify(mockS3Client, Mockito.times(1)).getObject(Mockito.any(GetObjectRequest.class));
    }

    /** Endpoint OCI completo. */
    @Test
    public void testFetchObjectOciEndpoint() {
        runner.setProperty(FetchS3ObjectUniversal.REGION_NAME,
                "https://namespace.compat.objectstorage.us-ashburn-1.oraclecloud.com");
        runner.setProperty(FetchS3ObjectUniversal.BUCKET, "test-bucket");
        final Map<String, String> attrs = new HashMap<>();
        attrs.put("filename", "test-key");
        runner.enqueue(new byte[0], attrs);

        runner.run(1);

        runner.assertAllFlowFilesTransferred(FetchS3ObjectUniversal.REL_SUCCESS, 1);
        Mockito.verify(mockS3Client, Mockito.times(1)).getObject(Mockito.any(GetObjectRequest.class));
    }

    @Test
    public void testFetchObjectS3Exception() {
        runner.setProperty(FetchS3ObjectUniversal.REGION_NAME, "us-east-1");
        runner.setProperty(FetchS3ObjectUniversal.BUCKET, "test-bucket");
        final Map<String, String> attrs = new HashMap<>();
        attrs.put("filename", "test-key");
        runner.enqueue(new byte[0], attrs);
        Mockito.when(mockS3Client.getObject(Mockito.any(GetObjectRequest.class)))
               .thenThrow(new AmazonS3Exception("NoSuchKey"));

        runner.run(1);

        runner.assertAllFlowFilesTransferred(FetchS3ObjectUniversal.REL_FAILURE, 1);
    }

    @Test
    public void testFetchObjectAttributesWritten() throws Exception {
        // S3Object must have bucketName set, otherwise s3.bucket attribute will be null
        final S3Object s3ObjectWithBucket = new S3Object();
        s3ObjectWithBucket.setBucketName("my-bucket");
        final ObjectMetadata meta = new ObjectMetadata();
        meta.setContentDisposition("my-key.txt");
        s3ObjectWithBucket.setObjectMetadata(meta);
        s3ObjectWithBucket.setObjectContent(new StringInputStream("content"));
        Mockito.when(mockS3Client.getObject(Mockito.any(GetObjectRequest.class))).thenReturn(s3ObjectWithBucket);

        runner.setProperty(FetchS3ObjectUniversal.REGION_NAME, "eu-west-1");
        runner.setProperty(FetchS3ObjectUniversal.BUCKET, "my-bucket");
        final Map<String, String> attrs = new HashMap<>();
        attrs.put("filename", "my-key");
        runner.enqueue(new byte[0], attrs);

        runner.run(1);

        runner.assertAllFlowFilesTransferred(FetchS3ObjectUniversal.REL_SUCCESS, 1);
        final MockFlowFile flowFile = runner.getFlowFilesForRelationship(FetchS3ObjectUniversal.REL_SUCCESS).get(0);
        flowFile.assertAttributeEquals("s3.bucket", "my-bucket");
    }
}
