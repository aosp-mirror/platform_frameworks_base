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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

@SmallTest
@RunWith(AndroidJUnit4.class)
@Presubmit
public class BinderLatencyObserverTest {
    @Test
    public void testLatencyCollectionWithMultipleClasses() {
        TestBinderLatencyObserver blo = new TestBinderLatencyObserver();

        Binder binder = new Binder();
        CallSession callSession = new CallSession();
        callSession.binderClass = binder.getClass();
        callSession.transactionCode = 1;
        blo.callEnded(callSession);
        blo.callEnded(callSession);
        callSession.transactionCode = 2;
        blo.callEnded(callSession);

        ArrayMap<LatencyDims, ArrayList<Long>> latencySamples = blo.getLatencySamples();
        assertEquals(2, latencySamples.keySet().size());
        assertThat(latencySamples.get(new LatencyDims(binder.getClass(), 1)))
            .containsExactlyElementsIn(Arrays.asList(1L, 2L));
        assertThat(latencySamples.get(new LatencyDims(binder.getClass(), 2))).containsExactly(3L);
    }

    @Test
    public void testSampling() {
        TestBinderLatencyObserver blo = new TestBinderLatencyObserver();
        blo.setSamplingInterval(2);

        Binder binder = new Binder();
        CallSession callSession = new CallSession();
        callSession.binderClass = binder.getClass();
        callSession.transactionCode = 1;
        blo.callEnded(callSession);
        callSession.transactionCode = 2;
        blo.callEnded(callSession);

        ArrayMap<LatencyDims, ArrayList<Long>> latencySamples = blo.getLatencySamples();
        assertEquals(1, latencySamples.size());
        LatencyDims dims = latencySamples.keySet().iterator().next();
        assertEquals(binder.getClass(), dims.getBinderClass());
        assertEquals(1, dims.getTransactionCode());
        ArrayList<Long> values = latencySamples.get(dims);
        assertThat(values).containsExactly(1L);
    }

    public static class TestBinderLatencyObserver extends BinderLatencyObserver {
        private long mElapsedTimeCallCount = 0;

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
            return ++mElapsedTimeCallCount;
        }
    }
}
