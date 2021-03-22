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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.biometrics.IBiometricService;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.biometrics.sensors.fingerprint.GestureAvailabilityDispatcher;

/**
 * A user-aware scheduler that requests user-switches based on scheduled operation's targetUserId.
 */
public class UserAwareBiometricScheduler extends BiometricScheduler {

    private static final String BASE_TAG = "UaBiometricScheduler";

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
        @NonNull StartUserClient<?> getStartUserClient(int newUserId);
    }

    @NonNull private final CurrentUserRetriever mCurrentUserRetriever;
    @NonNull private final UserSwitchCallback mUserSwitchCallback;
    @NonNull @VisibleForTesting final ClientFinishedCallback mClientFinishedCallback;

    @VisibleForTesting
    class ClientFinishedCallback implements BaseClientMonitor.Callback {
        @Override
        public void onClientFinished(@NonNull BaseClientMonitor clientMonitor, boolean success) {
            mHandler.post(() -> {
                Slog.d(getTag(), "[Client finished] " + clientMonitor + ", success: " + success);

                startNextOperationIfIdle();
            });
        }
    }

    @VisibleForTesting
    UserAwareBiometricScheduler(@NonNull String tag,
            @Nullable GestureAvailabilityDispatcher gestureAvailabilityDispatcher,
            @NonNull IBiometricService biometricService,
            @NonNull CurrentUserRetriever currentUserRetriever,
            @NonNull UserSwitchCallback userSwitchCallback) {
        super(tag, gestureAvailabilityDispatcher, biometricService, LOG_NUM_RECENT_OPERATIONS);

        mCurrentUserRetriever = currentUserRetriever;
        mUserSwitchCallback = userSwitchCallback;
        mClientFinishedCallback = new ClientFinishedCallback();
    }

    public UserAwareBiometricScheduler(@NonNull String tag,
            @Nullable GestureAvailabilityDispatcher gestureAvailabilityDispatcher,
            @NonNull CurrentUserRetriever currentUserRetriever,
            @NonNull UserSwitchCallback userSwitchCallback) {
        this(tag, gestureAvailabilityDispatcher, IBiometricService.Stub.asInterface(
                ServiceManager.getService(Context.BIOMETRIC_SERVICE)), currentUserRetriever,
                userSwitchCallback);
    }

    @Override
    protected String getTag() {
        return BASE_TAG + "/" + mBiometricTag;
    }

    @Override
    protected void startNextOperationIfIdle() {
        if (mCurrentOperation != null) {
            Slog.v(getTag(), "Not idle, current operation: " + mCurrentOperation);
            return;
        }
        if (mPendingOperations.isEmpty()) {
            Slog.d(getTag(), "No operations, returning to idle");
            return;
        }

        final int currentUserId = mCurrentUserRetriever.getCurrentUserId();
        final int nextUserId = mPendingOperations.getFirst().mClientMonitor.getTargetUserId();

        if (nextUserId == currentUserId) {
            super.startNextOperationIfIdle();
        } else if (currentUserId == UserHandle.USER_NULL) {
            Slog.d(getTag(), "User switch required, current user null, next: " + nextUserId);
            final BaseClientMonitor startClient =
                    mUserSwitchCallback.getStartUserClient(nextUserId);
            startClient.start(mClientFinishedCallback);
        } else {
            final BaseClientMonitor stopClient = mUserSwitchCallback
                    .getStopUserClient(currentUserId);
            Slog.d(getTag(), "User switch required, current: " + currentUserId
                    + ", next: " + nextUserId + ". " + stopClient);
            stopClient.start(mClientFinishedCallback);
        }
    }
}
