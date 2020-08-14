/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.biometrics.sensors;

import android.content.Context;
import android.platform.test.annotations.Presubmit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@Presubmit
@SmallTest
public class BiometricSchedulerTest {

    private static final String TAG = "BiometricSchedulerTest";

    private BiometricScheduler mScheduler;

    @Mock
    private Context mContext;
    @Mock
    private ClientMonitor.LazyDaemon<Object> mLazyDaemon;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mScheduler = new BiometricScheduler(TAG, null /* gestureAvailabilityTracker */);
    }

    @Test
    public void testClientDuplicateFinish_ignoredBySchedulerAndDoesNotCrash() {
        final ClientMonitor<Object> client1 = new TestClientMonitor(mContext, mLazyDaemon);
        final ClientMonitor<Object> client2 = new TestClientMonitor(mContext, mLazyDaemon);
        mScheduler.scheduleClientMonitor(client1);
        mScheduler.scheduleClientMonitor(client2);

        client1.mCallback.onClientFinished(client1, true /* success */);
        client1.mCallback.onClientFinished(client1, true /* success */);
    }

    private static class TestClientMonitor extends ClientMonitor<Object> {

        public TestClientMonitor(@NonNull Context context, @NonNull LazyDaemon<Object> lazyDaemon) {
            super(context, lazyDaemon, null /* token */, null /* listener */, 0 /* userId */,
                    TAG, 0 /* cookie */, 0 /* sensorId */, 0 /* statsModality */,
                    0 /* statsAction */, 0 /* statsClient */);
        }

        @Override
        public void unableToStart() {

        }

        @Override
        protected void startHalOperation() {

        }
    }
}
