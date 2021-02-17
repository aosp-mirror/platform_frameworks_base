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

import android.os.Binder;
import android.platform.test.annotations.Presubmit;
import android.util.ArrayMap;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.BinderInternal.CallSession;
import com.android.internal.os.BinderLatencyObserver.LatencyDims;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Random;

@SmallTest
@RunWith(AndroidJUnit4.class)
@Presubmit
public class BinderLatencyObserverTest {
    @Test
    public void testLatencyCollectionWithMultipleClasses() {
        TestBinderLatencyObserver blo = new TestBinderLatencyObserver();
        blo.setHistogramBucketsParams(5, 5, 1.125f);

        Binder binder = new Binder();
        CallSession callSession = new CallSession();
        callSession.binderClass = binder.getClass();
        callSession.transactionCode = 1;
        blo.callEnded(callSession);
        blo.callEnded(callSession);
        blo.callEnded(callSession);
        callSession.transactionCode = 2;
        blo.callEnded(callSession);
        blo.callEnded(callSession);

        ArrayMap<LatencyDims, int[]> latencyHistograms = blo.getLatencyHistograms();
        assertEquals(2, latencyHistograms.keySet().size());
        assertThat(latencyHistograms.get(new LatencyDims(binder.getClass(), 1)))
            .asList().containsExactly(2, 0, 1, 0, 0).inOrder();
        assertThat(latencyHistograms.get(new LatencyDims(binder.getClass(), 2)))
            .asList().containsExactly(0, 0, 0, 0, 2).inOrder();
    }

    @Test
    public void testSampling() {
        TestBinderLatencyObserver blo = new TestBinderLatencyObserver();
        blo.setSamplingInterval(2);
        blo.setHistogramBucketsParams(5, 5, 1.125f);

        Binder binder = new Binder();
        CallSession callSession = new CallSession();
        callSession.binderClass = binder.getClass();
        callSession.transactionCode = 1;
        blo.callEnded(callSession);
        callSession.transactionCode = 2;
        blo.callEnded(callSession);

        ArrayMap<LatencyDims, int[]> latencyHistograms = blo.getLatencyHistograms();
        assertEquals(1, latencyHistograms.size());
        LatencyDims dims = latencyHistograms.keySet().iterator().next();
        assertEquals(binder.getClass(), dims.getBinderClass());
        assertEquals(1, dims.getTransactionCode());
        assertThat(latencyHistograms.get(dims)).asList().containsExactly(1, 0, 0, 0, 0).inOrder();
    }

    @Test
    public void testTooCallLengthOverflow() {
        TestBinderLatencyObserver blo = new TestBinderLatencyObserver();
        blo.setElapsedTime(2L + (long) Integer.MAX_VALUE);
        blo.setHistogramBucketsParams(5, 5, 1.125f);

        Binder binder = new Binder();
        CallSession callSession = new CallSession();
        callSession.binderClass = binder.getClass();
        callSession.transactionCode = 1;
        blo.callEnded(callSession);

        // The long call should be capped to maxint (to not overflow) and placed in the last bucket.
        assertThat(blo.getLatencyHistograms()
            .get(new LatencyDims(binder.getClass(), 1)))
            .asList().containsExactly(0, 0, 0, 0, 1)
            .inOrder();
    }

    @Test
    public void testHistogramBucketOverflow() {
        TestBinderLatencyObserver blo = new TestBinderLatencyObserver();
        blo.setHistogramBucketsParams(3, 5, 1.125f);

        Binder binder = new Binder();
        CallSession callSession = new CallSession();
        callSession.binderClass = binder.getClass();
        callSession.transactionCode = 1;
        blo.callEnded(callSession);

        LatencyDims dims = new LatencyDims(binder.getClass(), 1);
        // Fill the buckets with maxint.
        Arrays.fill(blo.getLatencyHistograms().get(dims), Integer.MAX_VALUE);
        assertThat(blo.getLatencyHistograms().get(dims))
            .asList().containsExactly(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        // Try to add another sample.
        blo.callEnded(callSession);
        // Make sure the buckets don't overflow.
        assertThat(blo.getLatencyHistograms().get(dims))
            .asList().containsExactly(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    public static class TestBinderLatencyObserver extends BinderLatencyObserver {
        private long mElapsedTime = 0;

        TestBinderLatencyObserver() {
            // Make random generator not random.
            super(new Injector() {
                public Random getRandomGenerator() {
                    return new Random() {
                        int mCallCount = 0;

                        public int nextInt() {
                            return mCallCount++;
                        }
                    };
                }
            });
            setSamplingInterval(1);
        }

        @Override
        protected long getElapsedRealtimeMicro() {
            mElapsedTime += 2;
            return mElapsedTime;
        }

        public void setElapsedTime(long time) {
            mElapsedTime = time;
        }
    }
}
