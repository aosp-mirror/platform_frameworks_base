/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.biometrics.IBiometricService;
import android.os.Binder;
import android.os.IBinder;
import android.os.UserHandle;
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
public class UserAwareBiometricSchedulerTest {

    private static final String TAG = "BiometricSchedulerTest";
    private static final int TEST_SENSOR_ID = 0;

    private UserAwareBiometricScheduler mScheduler;
    private IBinder mToken;

    @Mock
    private Context mContext;
    @Mock
    private IBiometricService mBiometricService;

    private TestUserStartedCallback mUserStartedCallback;
    private TestUserStoppedCallback mUserStoppedCallback;
    private int mCurrentUserId = UserHandle.USER_NULL;
    private boolean mStartOperationsFinish;
    private int mStartUserClientCount;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mToken = new Binder();
        mStartOperationsFinish = true;
        mStartUserClientCount = 0;
        mUserStartedCallback = new TestUserStartedCallback();
        mUserStoppedCallback = new TestUserStoppedCallback();

        mScheduler = new UserAwareBiometricScheduler(TAG,
                null /* gestureAvailabilityDispatcher */,
                mBiometricService,
                () -> mCurrentUserId,
                new UserAwareBiometricScheduler.UserSwitchCallback() {
                    @NonNull
                    @Override
                    public StopUserClient<?> getStopUserClient(int userId) {
                        return new TestStopUserClient(mContext, Object::new, mToken, userId,
                                TEST_SENSOR_ID, mUserStoppedCallback);
                    }

                    @NonNull
                    @Override
                    public StartUserClient<?, ?> getStartUserClient(int newUserId) {
                        mStartUserClientCount++;
                        return new TestStartUserClient(mContext, Object::new, mToken, newUserId,
                                TEST_SENSOR_ID, mUserStartedCallback, mStartOperationsFinish);
                    }
                });
    }

    @Test
    public void testScheduleOperation_whenNoUser() {
        mCurrentUserId = UserHandle.USER_NULL;

        final BaseClientMonitor nextClient = mock(BaseClientMonitor.class);
        when(nextClient.getTargetUserId()).thenReturn(0);

        mScheduler.scheduleClientMonitor(nextClient);
        waitForIdle();

        assertEquals(0, mUserStoppedCallback.numInvocations);
        assertEquals(1, mUserStartedCallback.numInvocations);
        verify(nextClient).start(any());
    }

    @Test
    public void testScheduleOperation_whenNoUser_notStarted() {
        mCurrentUserId = UserHandle.USER_NULL;
        mStartOperationsFinish = false;

        final BaseClientMonitor[] nextClients = new BaseClientMonitor[] {
                mock(BaseClientMonitor.class),
                mock(BaseClientMonitor.class),
                mock(BaseClientMonitor.class)
        };
        for (BaseClientMonitor client : nextClients) {
            when(client.getTargetUserId()).thenReturn(5);
            mScheduler.scheduleClientMonitor(client);
            waitForIdle();
        }

        assertEquals(0, mUserStoppedCallback.numInvocations);
        assertEquals(0, mUserStartedCallback.numInvocations);
        assertEquals(1, mStartUserClientCount);
        for (BaseClientMonitor client : nextClients) {
            verify(client, never()).start(any());
        }
    }

    @Test
    public void testScheduleOperation_whenNoUser_notStarted_andReset() {
        mCurrentUserId = UserHandle.USER_NULL;
        mStartOperationsFinish = false;

        final BaseClientMonitor client = mock(BaseClientMonitor.class);
        when(client.getTargetUserId()).thenReturn(5);
        mScheduler.scheduleClientMonitor(client);
        waitForIdle();

        final TestStartUserClient startUserClient =
                (TestStartUserClient) mScheduler.mCurrentOperation.mClientMonitor;
        mScheduler.reset();
        assertNull(mScheduler.mCurrentOperation);

        final BiometricScheduler.Operation fakeOperation = new BiometricScheduler.Operation(
                mock(BaseClientMonitor.class), new BaseClientMonitor.Callback() {});
        mScheduler.mCurrentOperation = fakeOperation;
        startUserClient.mCallback.onClientFinished(startUserClient, true);
        assertSame(fakeOperation, mScheduler.mCurrentOperation);
    }

    @Test
    public void testScheduleOperation_whenSameUser() {
        mCurrentUserId = 10;

        BaseClientMonitor nextClient = mock(BaseClientMonitor.class);
        when(nextClient.getTargetUserId()).thenReturn(mCurrentUserId);

        mScheduler.scheduleClientMonitor(nextClient);

        waitForIdle();

        verify(nextClient).start(any());
        assertEquals(0, mUserStoppedCallback.numInvocations);
        assertEquals(0, mUserStartedCallback.numInvocations);
    }

    @Test
    public void testScheduleOperation_whenDifferentUser() {
        mCurrentUserId = 10;

        final int nextUserId = 11;
        BaseClientMonitor nextClient = mock(BaseClientMonitor.class);
        when(nextClient.getTargetUserId()).thenReturn(nextUserId);

        mScheduler.scheduleClientMonitor(nextClient);

        waitForIdle();
        assertEquals(1, mUserStoppedCallback.numInvocations);

        waitForIdle();
        assertEquals(1, mUserStartedCallback.numInvocations);

        waitForIdle();
        verify(nextClient).start(any());
    }

    private static void waitForIdle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private class TestUserStoppedCallback implements StopUserClient.UserStoppedCallback {
        int numInvocations;

        @Override
        public void onUserStopped() {
            numInvocations++;
            mCurrentUserId = UserHandle.USER_NULL;
        }
    }

    private class TestUserStartedCallback implements StartUserClient.UserStartedCallback<Object> {
        int numInvocations;

        @Override
        public void onUserStarted(int newUserId, Object newObject) {
            numInvocations++;
            mCurrentUserId = newUserId;
        }
    }

    private static class TestStopUserClient extends StopUserClient<Object> {
        public TestStopUserClient(@NonNull Context context,
                @NonNull LazyDaemon<Object> lazyDaemon, @Nullable IBinder token, int userId,
                int sensorId, @NonNull UserStoppedCallback callback) {
            super(context, lazyDaemon, token, userId, sensorId, callback);
        }

        @Override
        protected void startHalOperation() {

        }

        @Override
        public void start(@NonNull Callback callback) {
            super.start(callback);
            onUserStopped();
        }

        @Override
        public void unableToStart() {

        }
    }

    private static class TestStartUserClient extends StartUserClient<Object, Object> {
        private final boolean mShouldFinish;

        Callback mCallback;

        public TestStartUserClient(@NonNull Context context,
                @NonNull LazyDaemon<Object> lazyDaemon, @Nullable IBinder token, int userId,
                int sensorId, @NonNull UserStartedCallback<Object> callback, boolean shouldFinish) {
            super(context, lazyDaemon, token, userId, sensorId, callback);
            mShouldFinish = shouldFinish;
        }

        @Override
        protected void startHalOperation() {

        }

        @Override
        public void start(@NonNull Callback callback) {
            super.start(callback);

            mCallback = callback;
            if (mShouldFinish) {
                mUserStartedCallback.onUserStarted(getTargetUserId(), new Object());
                callback.onClientFinished(this, true /* success */);
            }
        }

        @Override
        public void unableToStart() {

        }
    }
}
