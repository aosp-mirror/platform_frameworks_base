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

import static android.testing.TestableLooper.RunWithLooper;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.biometrics.IBiometricService;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.filters.SmallTest;

import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

@Presubmit
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class UserAwareBiometricSchedulerTest {

    private static final String TAG = "UserAwareBiometricSchedulerTest";
    private static final int TEST_SENSOR_ID = 0;

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    private Handler mHandler;
    private UserAwareBiometricScheduler mScheduler;
    private final IBinder mToken = new Binder();

    @Mock
    private Context mContext;
    @Mock
    private IBiometricService mBiometricService;
    @Mock
    private BiometricLogger mBiometricLogger;
    @Mock
    private BiometricContext mBiometricContext;

    private boolean mShouldFailStopUser = false;
    private final StopUserClientShouldFail mStopUserClientShouldFail =
            () -> {
                return mShouldFailStopUser;
            };
    private final TestUserStartedCallback mUserStartedCallback = new TestUserStartedCallback();
    private final TestUserStoppedCallback mUserStoppedCallback = new TestUserStoppedCallback();
    private int mCurrentUserId = UserHandle.USER_NULL;
    private boolean mStartOperationsFinish = true;
    private int mStartUserClientCount = 0;

    @Before
    public void setUp() {
        mShouldFailStopUser = false;
        mHandler = new Handler(TestableLooper.get(this).getLooper());
        mScheduler = new UserAwareBiometricScheduler(TAG,
                mHandler,
                BiometricScheduler.SENSOR_TYPE_UNKNOWN,
                null /* gestureAvailabilityDispatcher */,
                mBiometricService,
                () -> mCurrentUserId,
                new UserAwareBiometricScheduler.UserSwitchCallback() {
                    @NonNull
                    @Override
                    public StopUserClient<?> getStopUserClient(int userId) {
                        return new TestStopUserClient(mContext, Object::new, mToken, userId,
                                TEST_SENSOR_ID, mBiometricLogger, mBiometricContext,
                                mUserStoppedCallback, mStopUserClientShouldFail);
                    }

                    @NonNull
                    @Override
                    public StartUserClient<?, ?> getStartUserClient(int newUserId) {
                        mStartUserClientCount++;
                        return new TestStartUserClient(mContext, Object::new, mToken, newUserId,
                                TEST_SENSOR_ID,  mBiometricLogger, mBiometricContext,
                                mUserStartedCallback, mStartOperationsFinish);
                    }
                },
                CoexCoordinator.getInstance());
    }

    @Test
    public void testScheduleOperation_whenNoUser() {
        mCurrentUserId = UserHandle.USER_NULL;

        final BaseClientMonitor nextClient = mock(BaseClientMonitor.class);
        when(nextClient.getTargetUserId()).thenReturn(0);

        mScheduler.scheduleClientMonitor(nextClient);
        waitForIdle();

        assertThat(mUserStoppedCallback.mNumInvocations).isEqualTo(0);
        assertThat(mUserStartedCallback.mStartedUsers).containsExactly(0);
        verify(nextClient).start(any());
    }

    @Test
    public void testScheduleOperation_whenNoUser_notStarted() {
        mCurrentUserId = UserHandle.USER_NULL;
        mStartOperationsFinish = false;

        final BaseClientMonitor[] nextClients = new BaseClientMonitor[]{
                mock(BaseClientMonitor.class),
                mock(BaseClientMonitor.class),
                mock(BaseClientMonitor.class)
        };
        for (BaseClientMonitor client : nextClients) {
            when(client.getTargetUserId()).thenReturn(5);
            mScheduler.scheduleClientMonitor(client);
            waitForIdle();
        }

        assertThat(mUserStoppedCallback.mNumInvocations).isEqualTo(0);
        assertThat(mUserStartedCallback.mStartedUsers).isEmpty();
        assertThat(mStartUserClientCount).isEqualTo(1);
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
                (TestStartUserClient) mScheduler.mCurrentOperation.getClientMonitor();
        mScheduler.reset();
        assertThat(mScheduler.mCurrentOperation).isNull();

        final BiometricSchedulerOperation fakeOperation = new BiometricSchedulerOperation(
                mock(BaseClientMonitor.class), new ClientMonitorCallback() {});
        mScheduler.mCurrentOperation = fakeOperation;
        startUserClient.mCallback.onClientFinished(startUserClient, true);
        assertThat(fakeOperation).isSameInstanceAs(mScheduler.mCurrentOperation);
    }

    @Test
    public void testScheduleOperation_whenSameUser() {
        mCurrentUserId = 10;

        BaseClientMonitor nextClient = mock(BaseClientMonitor.class);
        when(nextClient.getTargetUserId()).thenReturn(mCurrentUserId);

        mScheduler.scheduleClientMonitor(nextClient);

        waitForIdle();

        verify(nextClient).start(any());
        assertThat(mUserStoppedCallback.mNumInvocations).isEqualTo(0);
        assertThat(mUserStartedCallback.mStartedUsers).isEmpty();
    }

    @Test
    public void testScheduleOperation_whenDifferentUser() {
        mCurrentUserId = 10;

        final int nextUserId = 11;
        BaseClientMonitor nextClient = mock(BaseClientMonitor.class);
        when(nextClient.getTargetUserId()).thenReturn(nextUserId);

        mScheduler.scheduleClientMonitor(nextClient);

        waitForIdle();
        assertThat(mUserStoppedCallback.mNumInvocations).isEqualTo(1);

        waitForIdle();
        assertThat(mUserStartedCallback.mStartedUsers).containsExactly(nextUserId);

        waitForIdle();
        verify(nextClient).start(any());
    }

    @Test
    public void testStartUser_alwaysStartsNextOperation() {
        BaseClientMonitor nextClient = mock(BaseClientMonitor.class);
        when(nextClient.getTargetUserId()).thenReturn(10);

        mScheduler.scheduleClientMonitor(nextClient);

        waitForIdle();
        verify(nextClient).start(any());

        // finish first operation
        mScheduler.getInternalCallback().onClientFinished(nextClient, true /* success */);
        waitForIdle();

        // schedule second operation but swap out the current operation
        // before it runs so that it's not current when it's completion callback runs
        nextClient = mock(BaseClientMonitor.class);
        when(nextClient.getTargetUserId()).thenReturn(11);
        mUserStartedCallback.mAfterStart = () -> mScheduler.mCurrentOperation = null;
        mScheduler.scheduleClientMonitor(nextClient);

        waitForIdle();
        verify(nextClient).start(any());
        assertThat(mUserStartedCallback.mStartedUsers).containsExactly(10, 11).inOrder();
        assertThat(mUserStoppedCallback.mNumInvocations).isEqualTo(1);
    }

    @Test
    public void testStartUser_failsClearsStopUserClient() {
        // When a stop user client fails, check that mStopUserClient
        // is set to null to prevent the scheduler from getting stuck.
        BaseClientMonitor nextClient = mock(BaseClientMonitor.class);
        when(nextClient.getTargetUserId()).thenReturn(10);

        mScheduler.scheduleClientMonitor(nextClient);

        waitForIdle();
        verify(nextClient).start(any());

        // finish first operation
        mScheduler.getInternalCallback().onClientFinished(nextClient, true /* success */);
        waitForIdle();

        // schedule second operation but swap out the current operation
        // before it runs so that it's not current when it's completion callback runs
        nextClient = mock(BaseClientMonitor.class);
        when(nextClient.getTargetUserId()).thenReturn(11);
        mUserStartedCallback.mAfterStart = () -> mScheduler.mCurrentOperation = null;
        mShouldFailStopUser = true;
        mScheduler.scheduleClientMonitor(nextClient);

        waitForIdle();
        assertThat(mUserStartedCallback.mStartedUsers).containsExactly(10, 11).inOrder();
        assertThat(mUserStoppedCallback.mNumInvocations).isEqualTo(0);
        assertThat(mScheduler.getStopUserClient()).isEqualTo(null);
    }

    private void waitForIdle() {
        TestableLooper.get(this).processAllMessages();
    }

    private class TestUserStoppedCallback implements StopUserClient.UserStoppedCallback {
        int mNumInvocations;

        @Override
        public void onUserStopped() {
            mNumInvocations++;
            mCurrentUserId = UserHandle.USER_NULL;
        }
    }

    private class TestUserStartedCallback implements StartUserClient.UserStartedCallback<Object> {
        final List<Integer> mStartedUsers = new ArrayList<>();
        Runnable mAfterStart = null;

        @Override
        public void onUserStarted(int newUserId, Object newObject, int halInterfaceVersion) {
            mStartedUsers.add(newUserId);
            mCurrentUserId = newUserId;
            if (mAfterStart != null) {
                mAfterStart.run();
            }
        }
    }

    private interface StopUserClientShouldFail {
        boolean shouldFail();
    }

    private class TestStopUserClient extends StopUserClient<Object> {
        private StopUserClientShouldFail mShouldFailClient;
        public TestStopUserClient(@NonNull Context context,
                @NonNull Supplier<Object> lazyDaemon, @Nullable IBinder token, int userId,
                int sensorId, @NonNull BiometricLogger logger,
                @NonNull BiometricContext biometricContext,
                @NonNull UserStoppedCallback callback, StopUserClientShouldFail shouldFail) {
            super(context, lazyDaemon, token, userId, sensorId, logger, biometricContext, callback);
            mShouldFailClient = shouldFail;
        }

        @Override
        protected void startHalOperation() {

        }

        @Override
        public void start(@NonNull ClientMonitorCallback callback) {
            super.start(callback);
            if (mShouldFailClient.shouldFail()) {
                getCallback().onClientFinished(this, false /* success */);
                // When the above fails, it means that the HAL has died, in this case we
                // need to ensure the UserSwitchCallback correctly returns the NULL user handle.
                mCurrentUserId = UserHandle.USER_NULL;
            } else {
                onUserStopped();
            }
        }

        @Override
        public void unableToStart() {

        }
    }

    private static class TestStartUserClient extends StartUserClient<Object, Object> {
        private final boolean mShouldFinish;

        ClientMonitorCallback mCallback;

        public TestStartUserClient(@NonNull Context context,
                @NonNull Supplier<Object> lazyDaemon, @Nullable IBinder token, int userId,
                int sensorId, @NonNull BiometricLogger logger,
                @NonNull BiometricContext biometricContext,
                @NonNull UserStartedCallback<Object> callback, boolean shouldFinish) {
            super(context, lazyDaemon, token, userId, sensorId, logger, biometricContext, callback);
            mShouldFinish = shouldFinish;
        }

        @Override
        protected void startHalOperation() {

        }

        @Override
        public void start(@NonNull ClientMonitorCallback callback) {
            super.start(callback);

            mCallback = callback;
            if (mShouldFinish) {
                mUserStartedCallback.onUserStarted(
                        getTargetUserId(), new Object(), 1 /* halInterfaceVersion */);
                callback.onClientFinished(this, true /* success */);
            }
        }

        @Override
        public void unableToStart() {

        }
    }
}
