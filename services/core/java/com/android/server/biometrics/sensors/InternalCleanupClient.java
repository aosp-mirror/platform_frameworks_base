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

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.os.IBinder;
import android.util.Slog;

import com.android.internal.util.FrameworkStatsLog;

import java.util.ArrayList;
import java.util.List;

/**
 * Wraps {@link InternalEnumerateClient} and {@link RemovalClient}. Keeps track of all the
 * internal states when cleaning up mismatch between framework and HAL templates. This client
 * ends either when
 * 1) The HAL and Framework are in sync, and
 * {@link #onEnumerationResult(BiometricAuthenticator.Identifier, int)} returns true, or
 * 2) The HAL and Framework are not in sync, and
 * {@link #onRemoved(BiometricAuthenticator.Identifier, int)} returns true/
 */
public abstract class InternalCleanupClient<T> extends ClientMonitor<T>
        implements EnumerateConsumer, RemovalConsumer {

    private static final String TAG = "Biometrics/InternalCleanupClient";

    /**
     * Container for enumerated templates. Used to keep track when cleaning up unknown
     * templates.
     */
    private static final class UserTemplate {
        final BiometricAuthenticator.Identifier mIdentifier;
        final int mUserId;
        UserTemplate(BiometricAuthenticator.Identifier identifier, int userId) {
            this.mIdentifier = identifier;
            this.mUserId = userId;
        }
    }

    private final ArrayList<UserTemplate> mUnknownHALTemplates = new ArrayList<>();
    private final BiometricUtils mBiometricUtils;
    private final List<? extends BiometricAuthenticator.Identifier> mEnrolledList;
    private ClientMonitor<T> mCurrentTask;

    private final FinishCallback mEnumerateFinishCallback = clientMonitor -> {
        final List<BiometricAuthenticator.Identifier> unknownHALTemplates =
                ((InternalEnumerateClient<T>) mCurrentTask).getUnknownHALTemplates();

        if (!unknownHALTemplates.isEmpty()) {
            Slog.w(TAG, "Adding " + unknownHALTemplates.size() + " templates for deletion");
        }
        for (BiometricAuthenticator.Identifier unknownHALTemplate : unknownHALTemplates) {
            mUnknownHALTemplates.add(new UserTemplate(unknownHALTemplate,
                    mCurrentTask.getTargetUserId()));
        }

        if (mUnknownHALTemplates.isEmpty()) {
            // No unknown HAL templates. Unknown framework templates are already cleaned up in
            // InternalEnumerateClient. Finish this client.
            mFinishCallback.onClientFinished(this);
        } else {
            startCleanupUnknownHalTemplates();
        }
    };

    private final FinishCallback mRemoveFinishCallback = clientMonitor -> {
        mFinishCallback.onClientFinished(this);
    };

    protected abstract InternalEnumerateClient<T> getEnumerateClient(Context context, IBinder token,
            int userId, boolean restricted, String owner,
            List<? extends BiometricAuthenticator.Identifier> enrolledList, BiometricUtils utils,
            int sensorId, int statsModality);

    protected abstract RemovalClient<T> getRemovalClient(Context context, IBinder token,
            int biometricId, int userId, boolean restricted, String owner, BiometricUtils utils,
            int sensorId, int statsModality);

    protected InternalCleanupClient(@NonNull Context context, int userId, boolean restricted,
            @NonNull String owner, int sensorId, int statsModality,
            @NonNull List<? extends BiometricAuthenticator.Identifier> enrolledList,
            @NonNull BiometricUtils utils) {
        super(context, null /* token */, null /* ClientMonitorCallbackConverter */,
                userId, restricted, owner, 0 /* cookie */, sensorId, statsModality,
                BiometricsProtoEnums.ACTION_ENUMERATE, BiometricsProtoEnums.CLIENT_UNKNOWN);
        mBiometricUtils = utils;
        mEnrolledList = enrolledList;
    }

    private void startCleanupUnknownHalTemplates() {
        UserTemplate template = mUnknownHALTemplates.get(0);
        mUnknownHALTemplates.remove(template);
        mCurrentTask = getRemovalClient(getContext(), getToken(),
                template.mIdentifier.getBiometricId(), template.mUserId, getIsRestricted(),
                getContext().getPackageName(), mBiometricUtils, getSensorId(), mStatsModality);
        FrameworkStatsLog.write(FrameworkStatsLog.BIOMETRIC_SYSTEM_HEALTH_ISSUE_DETECTED,
                mStatsModality,
                BiometricsProtoEnums.ISSUE_UNKNOWN_TEMPLATE_ENROLLED_HAL);
        mCurrentTask.start(mDaemon, mRemoveFinishCallback);
    }

    @Override
    public void unableToStart() {
        // nothing to do here
    }

    @Override
    public void start(@NonNull T daemon, @NonNull FinishCallback finishCallback) {
        super.start(daemon, finishCallback);

        // Start enumeration. Removal will start if necessary, when enumeration is completed.
        mCurrentTask = getEnumerateClient(getContext(), getToken(), getTargetUserId(),
                getIsRestricted(), getOwnerString(), mEnrolledList, mBiometricUtils, getSensorId(),
                mStatsModality);
        mCurrentTask.start(daemon, mEnumerateFinishCallback);
    }

    @Override
    protected void startHalOperation() {
        // Internal cleanup's start method does not require a HAL operation, but rather
        // relies on its subtask's ClientMonitor to start the proper HAL operation.
    }

    @Override
    protected void stopHalOperation() {
        // Internal cleanup's cannot be stopped.
    }

    @Override
    public void onRemoved(BiometricAuthenticator.Identifier identifier, int remaining) {
        if (!(mCurrentTask instanceof RemovalClient)) {
            Slog.e(TAG, "onRemoved received during client: "
                    + mCurrentTask.getClass().getSimpleName());
            return;
        }
        ((RemovalClient) mCurrentTask).onRemoved(identifier, remaining);
    }

    @Override
    public void onEnumerationResult(BiometricAuthenticator.Identifier identifier,
            int remaining) {
        if (!(mCurrentTask instanceof InternalEnumerateClient)) {
            Slog.e(TAG, "onEnumerationResult received during client: "
                    + mCurrentTask.getClass().getSimpleName());
            return;
        }
        ((EnumerateConsumer) mCurrentTask).onEnumerationResult(identifier, remaining);
    }
}
