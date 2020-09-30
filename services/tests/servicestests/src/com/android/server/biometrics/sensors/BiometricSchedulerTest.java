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

import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.hardware.biometrics.IBiometricService;
import android.platform.test.annotations.Presubmit;

import androidx.annotation.NonNull;
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
    private IBiometricService mBiometricService;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mScheduler = new BiometricScheduler(TAG, null /* gestureAvailabilityTracker */,
                mBiometricService);
    }

    @Test
    public void testClientDuplicateFinish_ignoredBySchedulerAndDoesNotCrash() {
        final ClientMonitor.LazyDaemon<Object> nonNullDaemon = () -> mock(Object.class);

        final ClientMonitor<Object> client1 = new TestClientMonitor(mContext, nonNullDaemon);
        final ClientMonitor<Object> client2 = new TestClientMonitor(mContext, nonNullDaemon);
        mScheduler.scheduleClientMonitor(client1);
        mScheduler.scheduleClientMonitor(client2);

        client1.mCallback.onClientFinished(client1, true /* success */);
        client1.mCallback.onClientFinished(client1, true /* success */);
    }

    @Test
    public void testRemovesPendingOperations_whenNullHal_andNotBiometricPrompt() {
        // Even if second client has a non-null daemon, it needs to be canceled.
        Object daemon2 = mock(Object.class);

        final ClientMonitor.LazyDaemon<Object> lazyDaemon1 = () -> null;
        final ClientMonitor.LazyDaemon<Object> lazyDaemon2 = () -> daemon2;

        final TestClientMonitor client1 = new TestClientMonitor(mContext, lazyDaemon1);
        final TestClientMonitor client2 = new TestClientMonitor(mContext, lazyDaemon2);

        final ClientMonitor.Callback callback1 = mock(ClientMonitor.Callback.class);
        final ClientMonitor.Callback callback2 = mock(ClientMonitor.Callback.class);

        // Pretend the scheduler is busy so the first operation doesn't start right away. We want
        // to pretend like there are two operations in the queue before kicking things off
        mScheduler.mCurrentOperation = new BiometricScheduler.Operation(
                mock(ClientMonitor.class), mock(ClientMonitor.Callback.class));

        mScheduler.scheduleClientMonitor(client1, callback1);
        assertEquals(1, mScheduler.mPendingOperations.size());
        // client1 is pending. Allow the scheduler to start once second client is added.
        mScheduler.mCurrentOperation = null;
        mScheduler.scheduleClientMonitor(client2, callback2);
        waitForIdle();

        assertTrue(client1.wasUnableToStart());
        verify(callback1).onClientFinished(eq(client1), eq(false) /* success */);
        verify(callback1, never()).onClientStarted(any());

        assertTrue(client2.wasUnableToStart());
        verify(callback2).onClientFinished(eq(client2), eq(false) /* success */);
        verify(callback2, never()).onClientStarted(any());

        assertTrue(mScheduler.mPendingOperations.isEmpty());
    }

    @Test
    public void testRemovesOnlyBiometricPromptOperation_whenNullHal() {
        // Second non-BiometricPrompt client has a valid daemon
        final Object daemon2 = mock(Object.class);

        final ClientMonitor.LazyDaemon<Object> lazyDaemon1 = () -> null;
        final ClientMonitor.LazyDaemon<Object> lazyDaemon2 = () -> daemon2;

        final TestClientMonitor client1 =
                new TestBiometricPromptClientMonitor(mContext, lazyDaemon1);
        final TestClientMonitor client2 = new TestClientMonitor(mContext, lazyDaemon2);

        final ClientMonitor.Callback callback1 = mock(ClientMonitor.Callback.class);
        final ClientMonitor.Callback callback2 = mock(ClientMonitor.Callback.class);

        // Pretend the scheduler is busy so the first operation doesn't start right away. We want
        // to pretend like there are two operations in the queue before kicking things off
        mScheduler.mCurrentOperation = new BiometricScheduler.Operation(
                mock(ClientMonitor.class), mock(ClientMonitor.Callback.class));

        mScheduler.scheduleClientMonitor(client1, callback1);
        assertEquals(1, mScheduler.mPendingOperations.size());
        // client1 is pending. Allow the scheduler to start once second client is added.
        mScheduler.mCurrentOperation = null;
        mScheduler.scheduleClientMonitor(client2, callback2);
        waitForIdle();

        // Simulate that the BiometricPrompt client's sensor is ready
        mScheduler.startPreparedClient(client1.getCookie());

        assertTrue(client1.wasUnableToStart());
        verify(callback1).onClientFinished(eq(client1), eq(false) /* success */);
        verify(callback1, never()).onClientStarted(any());

        // Client 2 was able to start
        assertFalse(client2.wasUnableToStart());
        assertTrue(client2.hasStarted());
        verify(callback2).onClientStarted(eq(client2));
    }

    private static class TestBiometricPromptClientMonitor extends TestClientMonitor {
        public TestBiometricPromptClientMonitor(@NonNull Context context,
                @NonNull LazyDaemon<Object> lazyDaemon) {
            super(context, lazyDaemon, 1 /* cookie */);
        }
    }

    private static class TestClientMonitor extends ClientMonitor<Object> {
        private boolean mUnableToStart;
        private boolean mStarted;

        public TestClientMonitor(@NonNull Context context, @NonNull LazyDaemon<Object> lazyDaemon) {
            super(context, lazyDaemon, null /* token */, null /* listener */, 0 /* userId */,
                    TAG, 0 /* cookie */, 0 /* sensorId */, 0 /* statsModality */,
                    0 /* statsAction */, 0 /* statsClient */);
        }

        public TestClientMonitor(@NonNull Context context, @NonNull LazyDaemon<Object> lazyDaemon,
                int cookie) {
            super(context, lazyDaemon, null /* token */, null /* listener */, 0 /* userId */,
                    TAG, cookie, 0 /* sensorId */, 0 /* statsModality */,
                    0 /* statsAction */, 0 /* statsClient */);
        }


        @Override
        public void unableToStart() {
            assertFalse(mUnableToStart);
            mUnableToStart = true;
        }

        @Override
        public void start(@NonNull Callback callback) {
            super.start(callback);
            assertFalse(mStarted);
            mStarted = true;
        }

        @Override
        protected void startHalOperation() {

        }

        public boolean wasUnableToStart() {
            return mUnableToStart;
        }

        public boolean hasStarted() {
            return mStarted;
        }
    }

    private static void waitForIdle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
}
