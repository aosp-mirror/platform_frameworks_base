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

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.List;

@RunWith(JUnit4.class)
public class RequestIdTest {

    List<Integer> datasetPrimaryNoWrap = new ArrayList<>();
    List<Integer> datasetPrimaryWrap = new ArrayList<>();
    List<Integer> datasetSecondaryNoWrap = new ArrayList<>();
    List<Integer> datasetSecondaryWrap = new ArrayList<>();
    List<Integer> datasetMixedNoWrap = new ArrayList<>();
    List<Integer> datasetMixedWrap = new ArrayList<>();

    @Before
    public void setup() throws Exception {
      int datasetSize = 300;

        { // Generate primary only ids that do not wrap
            RequestId requestId = new RequestId(0);
            for (int i = 0; i < datasetSize; i++) {
                datasetPrimaryNoWrap.add(requestId.nextId(false));
            }
        }

        { // Generate primary only ids that wrap
            RequestId requestId = new RequestId(0xff00);
            for (int i = 0; i < datasetSize; i++) {
                datasetPrimaryWrap.add(requestId.nextId(false));
            }
        }

        { // Generate SECONDARY only ids that do not wrap
            RequestId requestId = new RequestId(0);
            for (int i = 0; i < datasetSize; i++) {
                datasetSecondaryNoWrap.add(requestId.nextId(true));
            }
        }

        { // Generate SECONDARY only ids that wrap
            RequestId requestId = new RequestId(0xff00);
            for (int i = 0; i < datasetSize; i++) {
                datasetSecondaryWrap.add(requestId.nextId(true));
            }
        }

        { // Generate MIXED only ids that do not wrap
            RequestId requestId = new RequestId(0);
            for (int i = 0; i < datasetSize; i++) {
                datasetMixedNoWrap.add(requestId.nextId(i % 2 != 0));
            }
        }

        { // Generate MIXED only ids that wrap
            RequestId requestId = new RequestId(0xff00);
            for (int i = 0; i < datasetSize; i++) {
                datasetMixedWrap.add(requestId.nextId(i % 2 != 0));
            }
        }
    }

    @Test
    public void testRequestIdLists() {
        for (int id : datasetPrimaryNoWrap) {
            assertThat(RequestId.isSecondaryProvider(id)).isFalse();
            assertThat(id >= 0).isTrue();
            assertThat(id < 0xffff).isTrue();
        }

        for (int id : datasetPrimaryWrap) {
            assertThat(RequestId.isSecondaryProvider(id)).isFalse();
            assertThat(id >= 0).isTrue();
            assertThat(id < 0xffff).isTrue();
        }

        for (int id : datasetSecondaryNoWrap) {
            assertThat(RequestId.isSecondaryProvider(id)).isTrue();
            assertThat(id >= 0).isTrue();
            assertThat(id < 0xffff).isTrue();
        }

        for (int id : datasetSecondaryWrap) {
            assertThat(RequestId.isSecondaryProvider(id)).isTrue();
            assertThat(id >= 0).isTrue();
            assertThat(id < 0xffff).isTrue();
        }
    }

    @Test
    public void testRequestIdGeneration() {
        RequestId requestId = new RequestId(0);

        // Large Primary
        for (int i = 0; i < 100000; i++) {
            int y = requestId.nextId(false);
            assertThat(RequestId.isSecondaryProvider(y)).isFalse();
            assertThat(y >= 0).isTrue();
            assertThat(y < 0xffff).isTrue();
        }

        // Large Secondary
        requestId = new RequestId(0);
        for (int i = 0; i < 100000; i++) {
            int y = requestId.nextId(true);
            assertThat(RequestId.isSecondaryProvider(y)).isTrue();
            assertThat(y >= 0).isTrue();
            assertThat(y < 0xffff).isTrue();
        }

        // Large Mixed
        requestId = new RequestId(0);
        for (int i = 0; i < 50000; i++) {
            int y = requestId.nextId(i % 2 != 0);
            assertThat(RequestId.isSecondaryProvider(y)).isEqualTo(i % 2 == 0);
            assertThat(y >= 0).isTrue();
            assertThat(y < 0xffff).isTrue();
        }
    }

    @Test
    public void testGetLastRequestId() {
        // In this test, request ids are generated FIFO, so the last entry is also the last
        // request

        { // Primary no wrap
          int lastIdIndex = datasetPrimaryNoWrap.size() - 1;
          int lastComputedIdIndex = RequestId.getLastRequestIdIndex(datasetPrimaryNoWrap);
          assertThat(lastIdIndex).isEqualTo(lastComputedIdIndex);
        }

        { // Primary wrap
            int lastIdIndex = datasetPrimaryWrap.size() - 1;
            int lastComputedIdIndex = RequestId.getLastRequestIdIndex(datasetPrimaryWrap);
            assertThat(lastIdIndex).isEqualTo(lastComputedIdIndex);
        }

        { // Secondary no wrap
            int lastIdIndex = datasetSecondaryNoWrap.size() - 1;
            int lastComputedIdIndex = RequestId.getLastRequestIdIndex(datasetSecondaryNoWrap);
            assertThat(lastIdIndex).isEqualTo(lastComputedIdIndex);
        }

        { // Secondary wrap
            int lastIdIndex = datasetSecondaryWrap.size() - 1;
            int lastComputedIdIndex = RequestId.getLastRequestIdIndex(datasetSecondaryWrap);
            assertThat(lastIdIndex).isEqualTo(lastComputedIdIndex);
        }

        { // Mixed no wrap
            int lastIdIndex = datasetMixedNoWrap.size() - 1;
            int lastComputedIdIndex = RequestId.getLastRequestIdIndex(datasetMixedNoWrap);
            assertThat(lastIdIndex).isEqualTo(lastComputedIdIndex);
        }

        { // Mixed wrap
            int lastIdIndex = datasetMixedWrap.size() - 1;
            int lastComputedIdIndex = RequestId.getLastRequestIdIndex(datasetMixedWrap);
            assertThat(lastIdIndex).isEqualTo(lastComputedIdIndex);
        }

    }
}
