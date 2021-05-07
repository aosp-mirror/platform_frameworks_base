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

import static com.android.internal.os.BinderLatencyProto.Dims.SYSTEM_SERVER;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;

import android.os.Binder;
import android.platform.test.annotations.Presubmit;
import android.util.ArrayMap;
import android.util.proto.ProtoOutputStream;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.BinderInternal.CallSession;
import com.android.internal.os.BinderLatencyObserver.LatencyDims;
import com.android.internal.os.BinderLatencyProto.ApiStats;
import com.android.internal.os.BinderLatencyProto.Dims;
import com.android.internal.os.BinderLatencyProto.RepeatedApiStats;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
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

        blo.setElapsedTime(2);
        blo.callEnded(callSession);
        blo.setElapsedTime(4);
        blo.callEnded(callSession);
        blo.setElapsedTime(6);
        blo.callEnded(callSession);
        callSession.transactionCode = 2;
        blo.setElapsedTime(8);
        blo.callEnded(callSession);
        blo.setElapsedTime(10);
        blo.callEnded(callSession);

        ArrayMap<LatencyDims, int[]> latencyHistograms = blo.getLatencyHistograms();
        assertEquals(2, latencyHistograms.keySet().size());
        assertThat(latencyHistograms.get(LatencyDims.create(binder.getClass(), 1)))
            .asList().containsExactly(2, 0, 1, 0, 0).inOrder();
        assertThat(latencyHistograms.get(LatencyDims.create(binder.getClass(), 2)))
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
        blo.setElapsedTime(2);
        blo.callEnded(callSession);
        callSession.transactionCode = 2;
        blo.setElapsedTime(4);
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
        blo.setHistogramBucketsParams(5, 5, 1.125f);

        Binder binder = new Binder();
        CallSession callSession = new CallSession();
        callSession.binderClass = binder.getClass();
        callSession.transactionCode = 1;
        blo.setElapsedTime(2L + (long) Integer.MAX_VALUE);
        blo.callEnded(callSession);

        // The long call should be capped to maxint (to not overflow) and placed in the last bucket.
        assertThat(blo.getLatencyHistograms()
            .get(LatencyDims.create(binder.getClass(), 1)))
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
        blo.setElapsedTime(2);
        blo.callEnded(callSession);

        LatencyDims dims = LatencyDims.create(binder.getClass(), 1);
        // Fill the buckets with maxint.
        Arrays.fill(blo.getLatencyHistograms().get(dims), Integer.MAX_VALUE);
        assertThat(blo.getLatencyHistograms().get(dims))
            .asList().containsExactly(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        // Try to add another sample.
        blo.setElapsedTime(2);
        blo.callEnded(callSession);
        // Make sure the buckets don't overflow.
        assertThat(blo.getLatencyHistograms().get(dims))
            .asList().containsExactly(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    @Test
    public void testSingleAtomPush() {
        TestBinderLatencyObserver blo = new TestBinderLatencyObserver();

        Binder binder = new Binder();
        CallSession callSession = new CallSession();
        callSession.binderClass = binder.getClass();
        callSession.transactionCode = 1;
        blo.setElapsedTime(7);
        blo.callEnded(callSession);
        blo.callEnded(callSession);
        blo.setElapsedTime(8);
        blo.callEnded(callSession);

        // Trigger the statsd push.
        blo.getStatsdPushRunnable().run();

        ProtoOutputStream expectedProto = new ProtoOutputStream();
        long apiStatsToken = expectedProto.start(RepeatedApiStats.API_STATS);
        long dimsToken = expectedProto.start(ApiStats.DIMS);
        expectedProto.write(Dims.PROCESS_SOURCE, SYSTEM_SERVER);
        expectedProto.write(Dims.SERVICE_CLASS_NAME, binder.getClass().getName());
        expectedProto.write(Dims.SERVICE_METHOD_NAME, "1");
        expectedProto.end(dimsToken);
        expectedProto.write(ApiStats.FIRST_BUCKET_INDEX, 3);
        expectedProto.write(ApiStats.BUCKETS, 2);
        expectedProto.write(ApiStats.BUCKETS, 1);
        expectedProto.end(apiStatsToken);

        assertThat(blo.getWrittenAtoms())
                .containsExactly(Arrays.toString(expectedProto.getBytes()));
    }

    @Test
    public void testMultipleAtomPush() {
        TestBinderLatencyObserver blo = new TestBinderLatencyObserver();

        BinderTransactionNameResolver resolver = new BinderTransactionNameResolver();


        Binder binder = new Binder();
        CallSession callSession = new CallSession();
        callSession.binderClass = binder.getClass();
        callSession.transactionCode = 1;
        blo.setElapsedTime(1);
        blo.callEnded(callSession);
        callSession.transactionCode = 2;
        blo.setElapsedTime(5);
        blo.callEnded(callSession);
        callSession.transactionCode = 3;
        blo.callEnded(callSession);

        // Trigger the statsd push.
        blo.getStatsdPushRunnable().run();

        ProtoOutputStream expectedProto1 = new ProtoOutputStream();
        long apiStatsToken = expectedProto1.start(RepeatedApiStats.API_STATS);
        long dimsToken = expectedProto1.start(ApiStats.DIMS);
        expectedProto1.write(Dims.PROCESS_SOURCE, SYSTEM_SERVER);
        expectedProto1.write(Dims.SERVICE_CLASS_NAME, binder.getClass().getName());
        expectedProto1.write(Dims.SERVICE_METHOD_NAME, "1");
        expectedProto1.end(dimsToken);
        expectedProto1.write(ApiStats.FIRST_BUCKET_INDEX, 0);
        expectedProto1.write(ApiStats.BUCKETS, 1);
        expectedProto1.end(apiStatsToken);

        apiStatsToken = expectedProto1.start(RepeatedApiStats.API_STATS);
        dimsToken = expectedProto1.start(ApiStats.DIMS);
        expectedProto1.write(Dims.PROCESS_SOURCE, SYSTEM_SERVER);
        expectedProto1.write(Dims.SERVICE_CLASS_NAME, binder.getClass().getName());
        expectedProto1.write(Dims.SERVICE_METHOD_NAME, "2");
        expectedProto1.end(dimsToken);
        expectedProto1.write(ApiStats.FIRST_BUCKET_INDEX, 1);
        expectedProto1.write(ApiStats.BUCKETS, 1);
        expectedProto1.end(apiStatsToken);

        ProtoOutputStream expectedProto2 = new ProtoOutputStream();
        apiStatsToken = expectedProto2.start(RepeatedApiStats.API_STATS);
        dimsToken = expectedProto2.start(ApiStats.DIMS);
        expectedProto2.write(Dims.PROCESS_SOURCE, SYSTEM_SERVER);
        expectedProto2.write(Dims.SERVICE_CLASS_NAME, binder.getClass().getName());
        expectedProto2.write(Dims.SERVICE_METHOD_NAME, "3");
        expectedProto2.end(dimsToken);
        expectedProto2.write(ApiStats.FIRST_BUCKET_INDEX, 1);
        expectedProto2.write(ApiStats.BUCKETS, 1);
        expectedProto2.end(apiStatsToken);

        // Each ApiStats is around ~60 bytes so only two should fit in an atom.
        assertThat(blo.getWrittenAtoms())
                .containsExactly(
                        Arrays.toString(expectedProto1.getBytes()),
                        Arrays.toString(expectedProto2.getBytes()))
                .inOrder();
    }

    @Test
    public void testSharding() {
        TestBinderLatencyObserver blo = new TestBinderLatencyObserver();
        blo.setShardingModulo(2);
        blo.setHistogramBucketsParams(5, 5, 1.125f);

        Binder binder = new Binder();
        CallSession callSession = new CallSession();
        callSession.binderClass = binder.getClass();
        callSession.transactionCode = 1;
        blo.setElapsedTime(2);
        blo.callEnded(callSession);
        callSession.transactionCode = 2;
        blo.setElapsedTime(4);
        blo.callEnded(callSession);
        callSession.transactionCode = 3;
        blo.setElapsedTime(2);
        blo.callEnded(callSession);
        callSession.transactionCode = 4;
        blo.setElapsedTime(4);
        blo.callEnded(callSession);

        ArrayMap<LatencyDims, int[]> latencyHistograms = blo.getLatencyHistograms();
        Iterator<LatencyDims> iterator = latencyHistograms.keySet().iterator();
        LatencyDims dims;

        // Hash codes are not consistent per device and not mockable so the test needs to consider
        // whether the hashCode of LatencyDims is odd or even and test accordingly.
        if (LatencyDims.create(binder.getClass(), 0).hashCode() % 2 == 0) {
            assertEquals(2, latencyHistograms.size());
            dims = iterator.next();
            assertEquals(binder.getClass(), dims.getBinderClass());
            assertEquals(1, dims.getTransactionCode());
            assertThat(latencyHistograms.get(dims)).asList().containsExactly(1, 0, 0, 0, 0)
                .inOrder();
            dims = iterator.next();
            assertEquals(binder.getClass(), dims.getBinderClass());
            assertEquals(3, dims.getTransactionCode());
            assertThat(latencyHistograms.get(dims)).asList().containsExactly(1, 0, 0, 0, 0)
                .inOrder();
        } else {
            assertEquals(2, latencyHistograms.size());
            dims = iterator.next();
            assertEquals(binder.getClass(), dims.getBinderClass());
            assertEquals(2, dims.getTransactionCode());
            assertThat(latencyHistograms.get(dims)).asList().containsExactly(1, 0, 0, 0, 0)
                .inOrder();
            dims = iterator.next();
            assertEquals(binder.getClass(), dims.getBinderClass());
            assertEquals(4, dims.getTransactionCode());
            assertThat(latencyHistograms.get(dims)).asList().containsExactly(1, 0, 0, 0, 0)
                .inOrder();
        }
    }

    public static class TestBinderLatencyObserver extends BinderLatencyObserver {
        private long mElapsedTime = 0;
        private ArrayList<String> mWrittenAtoms;

        TestBinderLatencyObserver() {
            this(SYSTEM_SERVER);
        }

        TestBinderLatencyObserver(int processSource) {
            // Make random generator not random.
            super(
                    new Injector() {
                        public Random getRandomGenerator() {
                            return new Random() {
                                int mCallCount = 0;

                                public int nextInt() {
                                    return mCallCount++;
                                }

                                public int nextInt(int x) {
                                    return 1;
                                }
                            };
                        }
                    },
                    processSource);
            setSamplingInterval(1);
            mWrittenAtoms = new ArrayList<>();
        }

        @Override
        protected long getElapsedRealtimeMicro() {
            return mElapsedTime;
        }

        @Override
        protected int getMaxAtomSizeBytes() {
            return 1100;
        }

        @Override
        protected void writeAtomToStatsd(ProtoOutputStream atom) {
            mWrittenAtoms.add(Arrays.toString(atom.getBytes()));
        }

        public void setElapsedTime(long time) {
            mElapsedTime = time;
        }

        public ArrayList<String> getWrittenAtoms() {
            return mWrittenAtoms;
        }
    }
}
