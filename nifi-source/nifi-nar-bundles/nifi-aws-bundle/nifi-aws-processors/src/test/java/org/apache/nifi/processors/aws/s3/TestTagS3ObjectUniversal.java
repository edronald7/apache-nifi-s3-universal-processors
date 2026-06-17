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
import com.amazonaws.services.s3.model.GetObjectTaggingRequest;
import com.amazonaws.services.s3.model.GetObjectTaggingResult;
import com.amazonaws.services.s3.model.SetObjectTaggingRequest;
import com.amazonaws.services.s3.model.Tag;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestTagS3ObjectUniversal extends AbstractS3UniversalTest {

    private AmazonS3Client mockS3Client;
    private TagS3ObjectUniversal processor;
    private TestRunner runner;

    @BeforeEach
    public void setUp() {
        mockS3Client = mockS3Client();
        processor = new TagS3ObjectUniversal() {
            @Override
            protected AmazonS3Client getClient() {
                return mockS3Client;
            }
        };
        runner = TestRunners.newTestRunner(processor);

        // Mock de tagging existente vacío
        final GetObjectTaggingResult emptyTagResult = new GetObjectTaggingResult(new ArrayList<>());
        Mockito.when(mockS3Client.getObjectTagging(Mockito.any(GetObjectTaggingRequest.class)))
               .thenReturn(emptyTagResult);
    }

    @Override
    protected TestRunner getRunner() {
        return runner;
    }

    @Override
    protected AbstractS3Processor getProcessor() {
        return processor;
    }

    @Override
    protected void setAdditionalRequiredProperties(TestRunner runner) {
        runner.setProperty(TagS3ObjectUniversal.TAG_KEY, "env");
        runner.setProperty(TagS3ObjectUniversal.TAG_VALUE, "test");
    }

    @Test
    public void testTagObjectStandardRegion() {
        runner.setProperty(TagS3ObjectUniversal.REGION_NAME, "us-east-1");
        runner.setProperty(TagS3ObjectUniversal.BUCKET, "test-bucket");
        runner.setProperty(TagS3ObjectUniversal.TAG_KEY, "env");
        runner.setProperty(TagS3ObjectUniversal.TAG_VALUE, "prod");
        final Map<String, String> attrs = new HashMap<>();
        attrs.put("filename", "test-key");
        runner.enqueue(new byte[0], attrs);

        runner.run(1);

        runner.assertAllFlowFilesTransferred(TagS3ObjectUniversal.REL_SUCCESS, 1);
        ArgumentCaptor<SetObjectTaggingRequest> captor = ArgumentCaptor.forClass(SetObjectTaggingRequest.class);
        Mockito.verify(mockS3Client, Mockito.times(1)).setObjectTagging(captor.capture());
        final SetObjectTaggingRequest req = captor.getValue();
        assertEquals("test-bucket", req.getBucketName());
        assertEquals("test-key", req.getKey());
        final List<Tag> tags = req.getTagging().getTagSet();
        assertEquals(1, tags.size());
        assertEquals("env", tags.get(0).getKey());
        assertEquals("prod", tags.get(0).getValue());
    }

    /** Región nueva (ap-south-2) no enumerada en SDK v1. */
    @Test
    public void testTagObjectNewAwsRegion() {
        runner.setProperty(TagS3ObjectUniversal.REGION_NAME, "ap-south-2");
        runner.setProperty(TagS3ObjectUniversal.BUCKET, "test-bucket");
        runner.setProperty(TagS3ObjectUniversal.TAG_KEY, "project");
        runner.setProperty(TagS3ObjectUniversal.TAG_VALUE, "nifi");
        final Map<String, String> attrs = new HashMap<>();
        attrs.put("filename", "test-key");
        runner.enqueue(new byte[0], attrs);

        runner.run(1);

        runner.assertAllFlowFilesTransferred(TagS3ObjectUniversal.REL_SUCCESS, 1);
        Mockito.verify(mockS3Client, Mockito.times(1)).setObjectTagging(Mockito.any(SetObjectTaggingRequest.class));
    }

    /** Endpoint OCI completo. */
    @Test
    public void testTagObjectOciEndpoint() {
        runner.setProperty(TagS3ObjectUniversal.REGION_NAME,
                "https://namespace.compat.objectstorage.sa-saopaulo-1.oraclecloud.com");
        runner.setProperty(TagS3ObjectUniversal.BUCKET, "test-bucket");
        runner.setProperty(TagS3ObjectUniversal.TAG_KEY, "team");
        runner.setProperty(TagS3ObjectUniversal.TAG_VALUE, "data");
        final Map<String, String> attrs = new HashMap<>();
        attrs.put("filename", "test-key");
        runner.enqueue(new byte[0], attrs);

        runner.run(1);

        runner.assertAllFlowFilesTransferred(TagS3ObjectUniversal.REL_SUCCESS, 1);
        Mockito.verify(mockS3Client, Mockito.times(1)).setObjectTagging(Mockito.any(SetObjectTaggingRequest.class));
    }

    @Test
    public void testTagObjectS3Exception() {
        runner.setProperty(TagS3ObjectUniversal.REGION_NAME, "us-east-1");
        runner.setProperty(TagS3ObjectUniversal.BUCKET, "test-bucket");
        runner.setProperty(TagS3ObjectUniversal.TAG_KEY, "env");
        runner.setProperty(TagS3ObjectUniversal.TAG_VALUE, "prod");
        final Map<String, String> attrs = new HashMap<>();
        attrs.put("filename", "test-key");
        runner.enqueue(new byte[0], attrs);
        Mockito.doThrow(new AmazonS3Exception("NoSuchKey"))
               .when(mockS3Client).setObjectTagging(Mockito.any(SetObjectTaggingRequest.class));

        runner.run(1);

        runner.assertAllFlowFilesTransferred(TagS3ObjectUniversal.REL_FAILURE, 1);
    }

    /** Tags existentes se conservan al usar APPEND_TAG=true. */
    @Test
    public void testTagObjectAppendExistingTags() {
        runner.setProperty(TagS3ObjectUniversal.REGION_NAME, "us-east-1");
        runner.setProperty(TagS3ObjectUniversal.BUCKET, "test-bucket");
        runner.setProperty(TagS3ObjectUniversal.TAG_KEY, "new-tag");
        runner.setProperty(TagS3ObjectUniversal.TAG_VALUE, "new-value");
        runner.setProperty(TagS3ObjectUniversal.APPEND_TAG, "true");

        final List<Tag> existingTags = new ArrayList<>();
        existingTags.add(new Tag("existing-tag", "existing-value"));
        Mockito.when(mockS3Client.getObjectTagging(Mockito.any(GetObjectTaggingRequest.class)))
               .thenReturn(new GetObjectTaggingResult(existingTags));

        final Map<String, String> attrs = new HashMap<>();
        attrs.put("filename", "test-key");
        runner.enqueue(new byte[0], attrs);

        runner.run(1);

        runner.assertAllFlowFilesTransferred(TagS3ObjectUniversal.REL_SUCCESS, 1);
        ArgumentCaptor<SetObjectTaggingRequest> captor = ArgumentCaptor.forClass(SetObjectTaggingRequest.class);
        Mockito.verify(mockS3Client, Mockito.times(1)).setObjectTagging(captor.capture());
        final List<Tag> writtenTags = captor.getValue().getTagging().getTagSet();
        assertEquals(2, writtenTags.size());
        assertTrue(writtenTags.stream().anyMatch(t -> "existing-tag".equals(t.getKey())));
        assertTrue(writtenTags.stream().anyMatch(t -> "new-tag".equals(t.getKey())));
    }
}
