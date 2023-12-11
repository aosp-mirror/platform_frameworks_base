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

import static com.android.server.biometrics.sensors.BiometricSchedulerOperation.STATE_STARTED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.biometrics.IBiometricService;
import android.os.Handler;
import android.os.Looper;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.biometrics.sensors.fingerprint.GestureAvailabilityDispatcher;

/**
 * A user-aware scheduler that requests user-switches based on scheduled operation's targetUserId.
 * TODO (b/304604965): Remove class when Flags.FLAG_DE_HIDL is removed.
 *
 * @param <T> Hal instance for starting the user.
 * @param <U> Session associated with the current user id.
 */
public class UserAwareBiometricScheduler<T, U> extends BiometricScheduler<T, U> {

    private static final String TAG = "UaBiometricScheduler";

    /**
     * Interface to retrieve the owner's notion of the current userId. Note that even though
     * the scheduler can determine this based on its history of processed clients, we should still
     * query the owner since it may be cleared due to things like HAL death, etc.
     */
    public interface CurrentUserRetriever {
        int getCurrentUserId();
    }

    public interface UserSwitchCallback {
        @NonNull StopUserClient<?> getStopUserClient(int userId);
        @NonNull StartUserClient<?, ?> getStartUserClient(int newUserId);
    }

    @NonNull private final CurrentUserRetriever mCurrentUserRetriever;
    @NonNull private final UserSwitchCallback mUserSwitchCallback;
    @Nullable private StopUserClient<?> mStopUserClient;

    private class ClientFinishedCallback implements ClientMonitorCallback {
        @NonNull private final BaseClientMonitor mOwner;

        ClientFinishedCallback(@NonNull BaseClientMonitor owner) {
            mOwner = owner;
        }

        @Override
        public void onClientFinished(@NonNull BaseClientMonitor clientMonitor, boolean success) {
            mHandler.post(() -> {
                Slog.d(TAG, "[Client finished] " + clientMonitor + ", success: " + success);

                // Set mStopUserClient to null when StopUserClient fails. Otherwise it's possible
                // for that the queue will wait indefinitely until the field is cleared.
                if (clientMonitor instanceof StopUserClient<?>) {
                    if (!success) {
                        Slog.w(TAG, "StopUserClient failed(), is the HAL stuck? "
                                + "Clearing mStopUserClient");
                    }
                    mStopUserClient = null;
                }
                if (mCurrentOperation != null && mCurrentOperation.isFor(mOwner)) {
                    mCurrentOperation = null;
                } else {
                    // can happen if the hal dies and is usually okay
                    // do not unset the current operation that may be newer
                    Slog.w(TAG, "operation is already null or different (reset?): "
                            + mCurrentOperation);
                }
                startNextOperationIfIdle();
            });
        }
    }

    @VisibleForTesting
    public UserAwareBiometricScheduler(@NonNull String tag,
            @NonNull Handler handler,
            @SensorType int sensorType,
            @Nullable GestureAvailabilityDispatcher gestureAvailabilityDispatcher,
            @NonNull IBiometricService biometricService,
            @NonNull CurrentUserRetriever currentUserRetriever,
            @NonNull UserSwitchCallback userSwitchCallback) {
        super(handler, sensorType, gestureAvailabilityDispatcher, biometricService,
                LOG_NUM_RECENT_OPERATIONS);

        mCurrentUserRetriever = currentUserRetriever;
        mUserSwitchCallback = userSwitchCallback;
    }

    public UserAwareBiometricScheduler(@NonNull String tag,
            @SensorType int sensorType,
            @Nullable GestureAvailabilityDispatcher gestureAvailabilityDispatcher,
            @NonNull CurrentUserRetriever currentUserRetriever,
            @NonNull UserSwitchCallback userSwitchCallback) {
        this(tag, new Handler(Looper.getMainLooper()), sensorType, gestureAvailabilityDispatcher,
                IBiometricService.Stub.asInterface(
                        ServiceManager.getService(Context.BIOMETRIC_SERVICE)),
                currentUserRetriever, userSwitchCallback);
    }

    @Override
    protected void startNextOperationIfIdle() {
        if (mCurrentOperation != null) {
            Slog.v(TAG, "Not idle, current operation: " + mCurrentOperation);
            return;
        }
        if (mPendingOperations.isEmpty()) {
            Slog.d(TAG, "No operations, returning to idle");
            return;
        }

        final int currentUserId = mCurrentUserRetriever.getCurrentUserId();
        final int nextUserId = mPendingOperations.getFirst().getTargetUserId();

        if (nextUserId == currentUserId || mPendingOperations.getFirst().isStartUserOperation()) {
            super.startNextOperationIfIdle();
        } else if (currentUserId == UserHandle.USER_NULL) {
            final BaseClientMonitor startClient =
                    mUserSwitchCallback.getStartUserClient(nextUserId);
            final ClientFinishedCallback finishedCallback =
                    new ClientFinishedCallback(startClient);

            Slog.d(TAG, "[Starting User] " + startClient);
            mCurrentOperation = new BiometricSchedulerOperation(
                    startClient, finishedCallback, STATE_STARTED);
            startClient.start(finishedCallback);
        } else {
            if (mStopUserClient != null) {
                Slog.d(TAG, "[Waiting for StopUser] " + mStopUserClient);
            } else {
                mStopUserClient = mUserSwitchCallback
                        .getStopUserClient(currentUserId);
                final ClientFinishedCallback finishedCallback =
                        new ClientFinishedCallback(mStopUserClient);

                Slog.d(TAG, "[Stopping User] current: " + currentUserId
                        + ", next: " + nextUserId + ". " + mStopUserClient);
                mCurrentOperation = new BiometricSchedulerOperation(
                        mStopUserClient, finishedCallback, STATE_STARTED);
                mStopUserClient.start(finishedCallback);
            }
        }
    }

    @Override
    public void onUserStopped() {
        if (mStopUserClient == null) {
            Slog.e(TAG, "Unexpected onUserStopped");
            return;
        }

        Slog.d(TAG, "[OnUserStopped]: " + mStopUserClient);
        mStopUserClient.onUserStopped();
        mStopUserClient = null;
    }

    @VisibleForTesting
    @Nullable public StopUserClient<?> getStopUserClient() {
        return mStopUserClient;
    }
}
