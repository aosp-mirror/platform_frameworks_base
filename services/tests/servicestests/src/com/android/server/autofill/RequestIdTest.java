/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.autofill;

import static com.google.common.truth.Truth.assertThat;

import android.util.Slog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class RequestIdTest {

    private static final int TEST_DATASET_SIZE = 300;
    private static final int TEST_WRAP_SIZE = 50; // Number of request ids before wrap happens
    private static final String TAG = "RequestIdTest";

    List<Integer> datasetPrimaryNoWrap = new ArrayList<>();
    List<Integer> datasetPrimaryWrap = new ArrayList<>();
    List<Integer> datasetSecondaryNoWrap = new ArrayList<>();
    List<Integer> datasetSecondaryWrap = new ArrayList<>();
    List<Integer> datasetMixedNoWrap = new ArrayList<>();
    List<Integer> datasetMixedWrap = new ArrayList<>();

    List<Integer> manualWrapRequestIdList = Arrays.asList(3, 9, 15,
                                                            RequestId.MAX_SECONDARY_REQUEST_ID - 5,
                                                            RequestId.MAX_SECONDARY_REQUEST_ID - 3);
    List<Integer> manualNoWrapRequestIdList =Arrays.asList(2, 6, 10, 14, 18, 22, 26, 30);

    List<Integer> manualOneElementRequestIdList = Arrays.asList(1);

    @Before
    public void setup() throws IllegalArgumentException {
        Slog.d(TAG, "setup()");
        { // Generate primary only ids that do not wrap
            RequestId requestId = new RequestId(RequestId.MIN_PRIMARY_REQUEST_ID);
            for (int i = 0; i < TEST_DATASET_SIZE; i++) {
                datasetPrimaryNoWrap.add(requestId.nextId(false));
            }
            Collections.sort(datasetPrimaryNoWrap);
        }

        { // Generate primary only ids that wrap
            RequestId requestId = new RequestId(RequestId.MAX_PRIMARY_REQUEST_ID -
                                                    TEST_WRAP_SIZE * 2);
            for (int i = 0; i < TEST_DATASET_SIZE; i++) {
                datasetPrimaryWrap.add(requestId.nextId(false));
            }
            Collections.sort(datasetPrimaryWrap);
        }

        { // Generate SECONDARY only ids that do not wrap
            RequestId requestId = new RequestId(RequestId.MIN_SECONDARY_REQUEST_ID);
            for (int i = 0; i < TEST_DATASET_SIZE; i++) {
                datasetSecondaryNoWrap.add(requestId.nextId(true));
            }
            Collections.sort(datasetSecondaryNoWrap);
        }

        { // Generate SECONDARY only ids that wrap
            RequestId requestId = new RequestId(RequestId.MAX_SECONDARY_REQUEST_ID -
                                                    TEST_WRAP_SIZE * 2);
            for (int i = 0; i < TEST_DATASET_SIZE; i++) {
                datasetSecondaryWrap.add(requestId.nextId(true));
            }
            Collections.sort(datasetSecondaryWrap);
        }

        { // Generate MIXED only ids that do not wrap
            RequestId requestId = new RequestId(RequestId.MIN_REQUEST_ID);
            for (int i = 0; i < TEST_DATASET_SIZE; i++) {
                datasetMixedNoWrap.add(requestId.nextId(i % 2 != 0));
            }
            Collections.sort(datasetMixedNoWrap);
        }

        { // Generate MIXED only ids that wrap
            RequestId requestId = new RequestId(RequestId.MAX_REQUEST_ID -
                                                    TEST_WRAP_SIZE);
            for (int i = 0; i < TEST_DATASET_SIZE; i++) {
                datasetMixedWrap.add(requestId.nextId(i % 2 != 0));
            }
            Collections.sort(datasetMixedWrap);
        }
        Slog.d(TAG, "finishing setup()");
    }

    @Test
    public void testRequestIdLists() {
        Slog.d(TAG, "testRequestIdLists()");
        for (int id : datasetPrimaryNoWrap) {
            assertThat(RequestId.isSecondaryProvider(id)).isFalse();
            assertThat(id).isAtLeast(RequestId.MIN_PRIMARY_REQUEST_ID);
            assertThat(id).isAtMost(RequestId.MAX_PRIMARY_REQUEST_ID);
        }

        for (int id : datasetPrimaryWrap) {
            assertThat(RequestId.isSecondaryProvider(id)).isFalse();
            assertThat(id).isAtLeast(RequestId.MIN_PRIMARY_REQUEST_ID);
            assertThat(id).isAtMost(RequestId.MAX_PRIMARY_REQUEST_ID);
        }

        for (int id : datasetSecondaryNoWrap) {
            assertThat(RequestId.isSecondaryProvider(id)).isTrue();
            assertThat(id).isAtLeast(RequestId.MIN_SECONDARY_REQUEST_ID);
            assertThat(id).isAtMost(RequestId.MAX_SECONDARY_REQUEST_ID);
        }

        for (int id : datasetSecondaryWrap) {
            assertThat(RequestId.isSecondaryProvider(id)).isTrue();
            assertThat(id).isAtLeast(RequestId.MIN_SECONDARY_REQUEST_ID);
            assertThat(id).isAtMost(RequestId.MAX_SECONDARY_REQUEST_ID);
        }
    }

    @Test
    public void testCreateNewRequestId() {
        Slog.d(TAG, "testCreateNewRequestId()");
        for (int i = 0; i < 100000; i++) {
            RequestId requestId = new RequestId();
            assertThat(requestId.getRequestId()).isAtLeast(RequestId.MIN_REQUEST_ID);
            assertThat(requestId.getRequestId()).isAtMost(RequestId.MAX_START_ID);
        }
    }

    @Test
    public void testGetNextRequestId() throws IllegalArgumentException{
        Slog.d(TAG, "testGetNextRequestId()");
        RequestId requestId = new RequestId();
        // Large Primary
        for (int i = 0; i < 100000; i++) {
            int y = requestId.nextId(false);
            assertThat(RequestId.isSecondaryProvider(y)).isFalse();
            assertThat(y).isAtLeast(RequestId.MIN_PRIMARY_REQUEST_ID);
            assertThat(y).isAtMost(RequestId.MAX_PRIMARY_REQUEST_ID);
        }

        // Large Secondary
        requestId = new RequestId();
        for (int i = 0; i < 100000; i++) {
            int y = requestId.nextId(true);
            assertThat(RequestId.isSecondaryProvider(y)).isTrue();
            assertThat(y).isAtLeast(RequestId.MIN_SECONDARY_REQUEST_ID);
            assertThat(y).isAtMost(RequestId.MAX_SECONDARY_REQUEST_ID);
        }

        // Large Mixed
        requestId = new RequestId();
        for (int i = 0; i < 50000; i++) {
            int y = requestId.nextId(i % 2 != 0);
            assertThat(y).isAtLeast(RequestId.MIN_REQUEST_ID);
            assertThat(y).isAtMost(RequestId.MAX_REQUEST_ID);
        }
    }

    @Test
    public void testGetLastRequestId() {
        Slog.d(TAG, "testGetLastRequestId()");

        {   // Primary no wrap
            int lastIdIndex = datasetPrimaryNoWrap.size() - 1;
            int lastComputedIdIndex = RequestId.getLastRequestIdIndex(datasetPrimaryNoWrap);
            assertThat(lastIdIndex).isEqualTo(lastComputedIdIndex);
        }

        {   // Primary wrap
            // The last index would be the # of request ids left after wrap
            // minus 1 (index starts at 0)
            int lastIdIndex = TEST_DATASET_SIZE - TEST_WRAP_SIZE - 1;
            int lastComputedIdIndex = RequestId.getLastRequestIdIndex(datasetPrimaryWrap);
            assertThat(lastComputedIdIndex).isEqualTo(lastIdIndex);
        }

        {   // Secondary no wrap
            int lastIdIndex = datasetSecondaryNoWrap.size() - 1;
            int lastComputedIdIndex = RequestId.getLastRequestIdIndex(datasetSecondaryNoWrap);
            assertThat(lastIdIndex).isEqualTo(lastComputedIdIndex);
        }

        {   // Secondary wrap
            int lastIdIndex = TEST_DATASET_SIZE - TEST_WRAP_SIZE - 1;
            int lastComputedIdIndex = RequestId.getLastRequestIdIndex(datasetSecondaryWrap);
            assertThat(lastIdIndex).isEqualTo(lastComputedIdIndex);
        }

        {   // Mixed no wrap
            int lastIdIndex = datasetMixedNoWrap.size() - 1;
            int lastComputedIdIndex = RequestId.getLastRequestIdIndex(datasetMixedNoWrap);
            assertThat(lastIdIndex).isEqualTo(lastComputedIdIndex);
        }

        {   // Mixed wrap
            int lastIdIndex = TEST_DATASET_SIZE - TEST_WRAP_SIZE - 1;
            int lastComputedIdIndex = RequestId.getLastRequestIdIndex(datasetMixedWrap);
            assertThat(lastIdIndex).isEqualTo(lastComputedIdIndex);
        }

        {   // Manual wrap
            int lastIdIndex = 2; // [3, 9, 15,
                                 // MAX_SECONDARY_REQUEST_ID - 5, MAX_SECONDARY_REQUEST_ID - 3]
            int lastComputedIdIndex = RequestId.getLastRequestIdIndex(manualWrapRequestIdList);
            assertThat(lastIdIndex).isEqualTo(lastComputedIdIndex);
        }

        {   // Manual no wrap
            int lastIdIndex = manualNoWrapRequestIdList.size() - 1; // [2, 6, 10, 14,
                                                                    // 18, 22, 26, 30]
            int lastComputedIdIndex = RequestId.getLastRequestIdIndex(manualNoWrapRequestIdList);
            assertThat(lastIdIndex).isEqualTo(lastComputedIdIndex);

        }

        {   // Manual one element
            int lastIdIndex = 0; // [1]
            int lastComputedIdIndex = RequestId.getLastRequestIdIndex(
                manualOneElementRequestIdList);
            assertThat(lastIdIndex).isEqualTo(lastComputedIdIndex);

        }
    }
}
