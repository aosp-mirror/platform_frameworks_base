/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.internal.os;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
@Presubmit
public class BinderLatencyBucketsTest {
    @Test
    public void testBucketThresholds() {
        BinderLatencyBuckets latencyBuckets = new BinderLatencyBuckets(10, 2, 1.45f);
        assertThat(latencyBuckets.getBuckets())
            .asList()
            .containsExactly(2, 3, 4, 6, 8, 12, 18, 26, 39)
            .inOrder();
    }

    @Test
    public void testSampleAssignment() {
        BinderLatencyBuckets latencyBuckets = new BinderLatencyBuckets(10, 2, 1.45f);
        assertEquals(0, latencyBuckets.sampleToBucket(0));
        assertEquals(0, latencyBuckets.sampleToBucket(1));
        assertEquals(1, latencyBuckets.sampleToBucket(2));
        assertEquals(2, latencyBuckets.sampleToBucket(3));
        assertEquals(3, latencyBuckets.sampleToBucket(4));
        assertEquals(5, latencyBuckets.sampleToBucket(9));
        assertEquals(6, latencyBuckets.sampleToBucket(13));
        assertEquals(7, latencyBuckets.sampleToBucket(25));
        assertEquals(9, latencyBuckets.sampleToBucket(100));
    }

    @Test
    public void testMaxIntBuckets() {
        BinderLatencyBuckets latencyBuckets = new BinderLatencyBuckets(5, Integer.MAX_VALUE / 2, 2);
        assertThat(latencyBuckets.getBuckets())
            .asList()
            .containsExactly(Integer.MAX_VALUE / 2, Integer.MAX_VALUE - 1)
            .inOrder();

        assertEquals(0, latencyBuckets.sampleToBucket(0));
        assertEquals(0, latencyBuckets.sampleToBucket(Integer.MAX_VALUE / 2 - 1));
        assertEquals(1, latencyBuckets.sampleToBucket(Integer.MAX_VALUE - 2));
        assertEquals(2, latencyBuckets.sampleToBucket(Integer.MAX_VALUE));
    }
}
