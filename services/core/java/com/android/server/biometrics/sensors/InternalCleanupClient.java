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
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricsProtoEnums;
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
public class InternalCleanupClient extends ClientMonitor {
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
    private ClientMonitor mCurrentTask;

    public InternalCleanupClient(Context context, Constants constants,
            BiometricServiceBase.DaemonWrapper daemon,
            ClientMonitorCallbackConverter listener,
            int userId, int groupId, boolean restricted, String owner, int sensorId,
            int statsModality, List<? extends BiometricAuthenticator.Identifier> enrolledList,
            BiometricUtils utils) {
        super(context, constants, daemon, null /* token */, listener, userId, groupId, restricted,
                owner, 0 /* cookie */, sensorId, statsModality,
                BiometricsProtoEnums.ACTION_ENUMERATE, BiometricsProtoEnums.CLIENT_UNKNOWN);

        mBiometricUtils = utils;
        mCurrentTask = new InternalEnumerateClient(context, constants, daemon, getToken(),
                listener, userId, groupId, restricted, owner, enrolledList, utils, sensorId,
                statsModality);
    }

    private void startCleanupUnknownHalTemplates() {
        UserTemplate template = mUnknownHALTemplates.get(0);
        mUnknownHALTemplates.remove(template);
        mCurrentTask = new RemovalClient(getContext(),
                mConstants, getDaemonWrapper(), getToken(), null /* listener */,
                template.mIdentifier.getBiometricId(), 0 /* groupId */, template.mUserId,
                getIsRestricted(), getContext().getPackageName(), mBiometricUtils,
                getSensorId(), mStatsModality);
        FrameworkStatsLog.write(FrameworkStatsLog.BIOMETRIC_SYSTEM_HEALTH_ISSUE_DETECTED,
                mStatsModality,
                BiometricsProtoEnums.ISSUE_UNKNOWN_TEMPLATE_ENROLLED_HAL);
        mCurrentTask.start();
    }

    @Override
    public int start() {
        // Start enumeration. Removal will start if necessary, when enumeration is completed.
        return mCurrentTask.start();
    }

    @Override
    public int stop(boolean initiatedByClient) {
        return 0;
    }

    @Override
    public boolean onRemoved(BiometricAuthenticator.Identifier identifier, int remaining) {
        if (!(mCurrentTask instanceof RemovalClient)) {
            Slog.e(getLogTag(), "onRemoved received during client: "
                    + mCurrentTask.getClass().getSimpleName());
            return false;
        }
        return mCurrentTask.onRemoved(identifier, remaining);
    }

    @Override
    public boolean onEnumerationResult(BiometricAuthenticator.Identifier identifier,
            int remaining) {
        if (!(mCurrentTask instanceof InternalEnumerateClient)) {
            Slog.e(getLogTag(), "onEnumerationResult received during client: "
                    + mCurrentTask.getClass().getSimpleName());
            return false;
        }

        mCurrentTask.onEnumerationResult(identifier, remaining);

        if (remaining != 0) {
            return false;
        }

        final List<BiometricAuthenticator.Identifier> unknownHALTemplates =
                ((InternalEnumerateClient) mCurrentTask).getUnknownHALTemplates();

        if (!unknownHALTemplates.isEmpty()) {
            Slog.w(getLogTag(), "Adding " + unknownHALTemplates.size()
                    + " templates for deletion");
        }
        for (int i = 0; i < unknownHALTemplates.size(); i++) {
            mUnknownHALTemplates.add(new UserTemplate(unknownHALTemplates.get(i),
                    mCurrentTask.getTargetUserId()));
        }

        if (mUnknownHALTemplates.isEmpty()) {
            return true;
        } else {
            startCleanupUnknownHalTemplates();
            return false;
        }
    }

    @Override
    public boolean onEnrollResult(BiometricAuthenticator.Identifier identifier, int remaining) {
        return false;
    }

    @Override
    public boolean onAuthenticated(BiometricAuthenticator.Identifier identifier,
            boolean authenticated, ArrayList<Byte> token) {
        return false;
    }

    @Override
    public void notifyUserActivity() {

    }
}
