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

package com.android.server.biometrics.sensors.fingerprint.hidl;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.biometrics.fingerprint.ISession;
import android.os.Build;
import android.os.Environment;
import android.os.RemoteException;
import android.os.SELinux;
import android.util.Slog;

import com.android.server.biometrics.BiometricsProto;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.StartUserClient;
import com.android.server.biometrics.sensors.fingerprint.aidl.AidlSession;

import java.io.File;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sets the HAL's current active user, and updates the framework's authenticatorId cache.
 */
public class FingerprintUpdateActiveUserClient extends StartUserClient<ISession,
        AidlSession> {

    private static final String TAG = "FingerprintUpdateActiveUserClient";
    private static final String FP_DATA_DIR = "fpdata";

    private final Supplier<Integer> mCurrentUserId;
    private final boolean mForceUpdateAuthenticatorId;
    private final boolean mHasEnrolledBiometrics;
    private final Map<Integer, Long> mAuthenticatorIds;
    private File mDirectory;

    FingerprintUpdateActiveUserClient(@NonNull Context context,
            @NonNull Supplier<ISession> lazyDaemon, int userId,
            @NonNull String owner, int sensorId,
            @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext,
            @NonNull Supplier<Integer> currentUserId,
            boolean hasEnrolledBiometrics, @NonNull Map<Integer, Long> authenticatorIds,
            boolean forceUpdateAuthenticatorId,
            @NonNull UserStartedCallback<AidlSession> userStartedCallback) {
        super(context, lazyDaemon, null /* token */, userId, sensorId, logger, biometricContext,
                userStartedCallback);
        mCurrentUserId = currentUserId;
        mForceUpdateAuthenticatorId = forceUpdateAuthenticatorId;
        mHasEnrolledBiometrics = hasEnrolledBiometrics;
        mAuthenticatorIds = authenticatorIds;
    }

    @Override
    public void start(@NonNull ClientMonitorCallback callback) {
        super.start(callback);

        if (mCurrentUserId.get() == getTargetUserId() && !mForceUpdateAuthenticatorId) {
            Slog.d(TAG, "Already user: " + mCurrentUserId + ", returning");
            mUserStartedCallback.onUserStarted(getTargetUserId(), null, 0);
            callback.onClientFinished(this, true /* success */);
            return;
        }

        int firstSdkInt = Build.VERSION.DEVICE_INITIAL_SDK_INT;
        if (firstSdkInt < Build.VERSION_CODES.BASE) {
            Slog.e(TAG, "First SDK version " + firstSdkInt + " is invalid; must be " +
                    "at least VERSION_CODES.BASE");
        }
        File baseDir;
        if (firstSdkInt <= Build.VERSION_CODES.O_MR1) {
            baseDir = Environment.getUserSystemDirectory(getTargetUserId());
        } else {
            baseDir = Environment.getDataVendorDeDirectory(getTargetUserId());
        }

        mDirectory = new File(baseDir, FP_DATA_DIR);
        if (!mDirectory.exists()) {
            if (!mDirectory.mkdir()) {
                Slog.e(TAG, "Cannot make directory: " + mDirectory.getAbsolutePath());
                callback.onClientFinished(this, false /* success */);
                return;
            }
            // Calling mkdir() from this process will create a directory with our
            // permissions (inherited from the containing dir). This command fixes
            // the label.
            if (!SELinux.restorecon(mDirectory)) {
                Slog.e(TAG, "Restorecons failed. Directory will have wrong label.");
                callback.onClientFinished(this, false /* success */);
                return;
            }
        }

        startHalOperation();
    }

    @Override
    public void unableToStart() {
        // Nothing to do here
    }

    @Override
    protected void startHalOperation() {
        try {
            final int targetId = getTargetUserId();
            Slog.d(TAG, "Setting active user: " + targetId);
            HidlToAidlSessionAdapter sessionAdapter = (HidlToAidlSessionAdapter) getFreshDaemon();
            if (sessionAdapter.getIBiometricsFingerprint() == null) {
                Slog.e(TAG, "Failed to setActiveGroup: HIDL daemon is null.");
                mCallback.onClientFinished(this, false /* success */);
                return;
            }
            sessionAdapter.setActiveGroup(targetId, mDirectory.getAbsolutePath());
            mAuthenticatorIds.put(targetId, mHasEnrolledBiometrics
                    ? sessionAdapter.getAuthenticatorIdForUpdateClient() : 0L);
            mUserStartedCallback.onUserStarted(targetId, null, 0);
            mCallback.onClientFinished(this, true /* success */);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to setActiveGroup: " + e);
            mCallback.onClientFinished(this, false /* success */);
        }
    }

    @Override
    public int getProtoEnum() {
        return BiometricsProto.CM_UPDATE_ACTIVE_USER;
    }
}
