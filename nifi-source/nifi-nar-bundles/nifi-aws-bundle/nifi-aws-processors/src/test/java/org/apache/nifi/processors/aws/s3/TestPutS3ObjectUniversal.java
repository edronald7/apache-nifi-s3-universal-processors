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
import com.amazonaws.services.s3.model.ListMultipartUploadsRequest;
import com.amazonaws.services.s3.model.MultipartUploadListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.PutObjectResult;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestPutS3ObjectUniversal extends AbstractS3UniversalTest {

    private AmazonS3Client mockS3Client;
    private PutS3ObjectUniversal processor;
    private TestRunner runner;

    @BeforeEach
    public void setUp() {
        mockS3Client = mockS3Client();
        processor = new PutS3ObjectUniversal() {
            @Override
            protected AmazonS3Client getClient() {
                return mockS3Client;
            }
        };
        runner = TestRunners.newTestRunner(processor);

        final PutObjectResult mockResult = new PutObjectResult();
        mockResult.setVersionId("test-version-id");
        mockResult.setETag("test-etag");
        // getMetadata() must not return null; line 640 calls result.getMetadata().getStorageClass()
        mockResult.setMetadata(new ObjectMetadata());
        Mockito.when(mockS3Client.putObject(Mockito.any(PutObjectRequest.class))).thenReturn(mockResult);

        // listMultipartUploads is called during ageoff; mock it to return empty listing
        final MultipartUploadListing emptyListing = new MultipartUploadListing();
        emptyListing.setMultipartUploads(new ArrayList<>());
        Mockito.when(mockS3Client.listMultipartUploads(Mockito.any(ListMultipartUploadsRequest.class)))
               .thenReturn(emptyListing);
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
    public void testPutObjectStandardRegion() {
        runner.setProperty(PutS3ObjectUniversal.REGION_NAME, "us-east-1");
        runner.setProperty(PutS3ObjectUniversal.BUCKET, "test-bucket");
        final Map<String, String> attrs = new HashMap<>();
        attrs.put("filename", "test-object.txt");
        runner.enqueue("hello world".getBytes(), attrs);

        runner.run(1);

        runner.assertAllFlowFilesTransferred(PutS3ObjectUniversal.REL_SUCCESS, 1);
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        Mockito.verify(mockS3Client, Mockito.times(1)).putObject(captor.capture());
        assertEquals("test-bucket", captor.getValue().getBucketName());
        assertEquals("test-object.txt", captor.getValue().getKey());
    }

    /** Región nueva (eu-central-2) no enumerada en SDK v1. */
    @Test
    public void testPutObjectNewAwsRegion() {
        runner.setProperty(PutS3ObjectUniversal.REGION_NAME, "eu-central-2");
        runner.setProperty(PutS3ObjectUniversal.BUCKET, "test-bucket");
        final Map<String, String> attrs = new HashMap<>();
        attrs.put("filename", "test-object.txt");
        runner.enqueue("hello world".getBytes(), attrs);

        runner.run(1);

        runner.assertAllFlowFilesTransferred(PutS3ObjectUniversal.REL_SUCCESS, 1);
        Mockito.verify(mockS3Client, Mockito.times(1)).putObject(Mockito.any(PutObjectRequest.class));
    }

    /** Endpoint OCI completo. */
    @Test
    public void testPutObjectOciEndpoint() {
        runner.setProperty(PutS3ObjectUniversal.REGION_NAME,
                "https://namespace.compat.objectstorage.eu-frankfurt-1.oraclecloud.com");
        runner.setProperty(PutS3ObjectUniversal.BUCKET, "test-bucket");
        final Map<String, String> attrs = new HashMap<>();
        attrs.put("filename", "test-object.txt");
        runner.enqueue("hello world".getBytes(), attrs);

        runner.run(1);

        runner.assertAllFlowFilesTransferred(PutS3ObjectUniversal.REL_SUCCESS, 1);
        Mockito.verify(mockS3Client, Mockito.times(1)).putObject(Mockito.any(PutObjectRequest.class));
    }

    @Test
    public void testPutObjectS3Exception() {
        runner.setProperty(PutS3ObjectUniversal.REGION_NAME, "us-east-1");
        runner.setProperty(PutS3ObjectUniversal.BUCKET, "test-bucket");
        final Map<String, String> attrs = new HashMap<>();
        attrs.put("filename", "test-object.txt");
        runner.enqueue("hello world".getBytes(), attrs);
        Mockito.when(mockS3Client.putObject(Mockito.any(PutObjectRequest.class)))
               .thenThrow(new AmazonS3Exception("AccessDenied"));

        runner.run(1);

        runner.assertAllFlowFilesTransferred(PutS3ObjectUniversal.REL_FAILURE, 1);
    }

    @Test
    public void testPutObjectAttributesWritten() {
        runner.setProperty(PutS3ObjectUniversal.REGION_NAME, "us-west-2");
        runner.setProperty(PutS3ObjectUniversal.BUCKET, "my-bucket");
        final Map<String, String> attrs = new HashMap<>();
        attrs.put("filename", "my-key.txt");
        runner.enqueue("content".getBytes(), attrs);

        runner.run(1);

        runner.assertAllFlowFilesTransferred(PutS3ObjectUniversal.REL_SUCCESS, 1);
        final MockFlowFile flowFile = runner.getFlowFilesForRelationship(PutS3ObjectUniversal.REL_SUCCESS).get(0);
        flowFile.assertAttributeEquals("s3.bucket", "my-bucket");
        flowFile.assertAttributeEquals("s3.key", "my-key.txt");
        flowFile.assertAttributeEquals("s3.etag", "test-etag");
    }
}
