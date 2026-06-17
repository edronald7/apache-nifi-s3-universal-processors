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
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestListS3Universal extends AbstractS3UniversalTest {

    private AmazonS3Client mockS3Client;
    private ListS3Universal processor;
    private TestRunner runner;

    @BeforeEach
    public void setUp() {
        mockS3Client = mockS3Client();
        processor = new ListS3Universal() {
            @Override
            protected AmazonS3Client getClient() {
                return mockS3Client;
            }
        };
        runner = TestRunners.newTestRunner(processor);

        // Configurar respuesta vacía por defecto
        final ObjectListing emptyListing = new ObjectListing();
        emptyListing.setTruncated(false);
        Mockito.when(mockS3Client.listObjects(Mockito.any(ListObjectsRequest.class)))
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
    public void testListBucketStandardRegion() {
        runner.setProperty(ListS3Universal.REGION_NAME, "us-east-1");
        runner.setProperty(ListS3Universal.BUCKET, "test-bucket");

        runner.run(1);

        Mockito.verify(mockS3Client, Mockito.atLeastOnce())
               .listObjects(Mockito.any(ListObjectsRequest.class));
    }

    /** Región nueva (ap-south-2) no enumerada en SDK v1. */
    @Test
    public void testListBucketNewAwsRegion() {
        runner.setProperty(ListS3Universal.REGION_NAME, "ap-south-2");
        runner.setProperty(ListS3Universal.BUCKET, "test-bucket");

        runner.run(1);

        Mockito.verify(mockS3Client, Mockito.atLeastOnce())
               .listObjects(Mockito.any(ListObjectsRequest.class));
    }

    /** Endpoint OCI completo. */
    @Test
    public void testListBucketOciEndpoint() {
        runner.setProperty(ListS3Universal.REGION_NAME,
                "https://namespace.compat.objectstorage.ap-sydney-1.oraclecloud.com");
        runner.setProperty(ListS3Universal.BUCKET, "test-bucket");

        runner.run(1);

        Mockito.verify(mockS3Client, Mockito.atLeastOnce())
               .listObjects(Mockito.any(ListObjectsRequest.class));
    }

    /** Con objetos en el bucket crea FlowFiles. */
    @Test
    public void testListBucketWithObjects() {
        runner.setProperty(ListS3Universal.REGION_NAME, "eu-central-2");
        runner.setProperty(ListS3Universal.BUCKET, "test-bucket");

        final ObjectListing listing = new ObjectListing();
        listing.setTruncated(false);
        final S3ObjectSummary summary = new S3ObjectSummary();
        summary.setBucketName("test-bucket");
        summary.setKey("object-1");
        summary.setLastModified(new Date(0));
        summary.setSize(1024L);
        summary.setETag("etag-1");
        listing.getObjectSummaries().add(summary);
        Mockito.when(mockS3Client.listObjects(Mockito.any(ListObjectsRequest.class)))
               .thenReturn(listing);

        runner.run(1);

        assertEquals(1, runner.getFlowFilesForRelationship(ListS3Universal.REL_SUCCESS).size());
    }
}
