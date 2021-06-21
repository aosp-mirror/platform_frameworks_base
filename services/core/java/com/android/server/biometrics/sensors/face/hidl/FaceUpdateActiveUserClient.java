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

package com.android.server.biometrics.sensors.face.hidl;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.face.V1_0.IBiometricsFace;
import android.os.Environment;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.BiometricsProto;
import com.android.server.biometrics.sensors.HalClientMonitor;

import java.io.File;
import java.util.Map;

public class FaceUpdateActiveUserClient extends HalClientMonitor<IBiometricsFace> {
    private static final String TAG = "FaceUpdateActiveUserClient";
    private static final String FACE_DATA_DIR = "facedata";

    private final int mCurrentUserId;
    private final boolean mHasEnrolledBiometrics;
    @NonNull private final Map<Integer, Long> mAuthenticatorIds;

    FaceUpdateActiveUserClient(@NonNull Context context,
            @NonNull LazyDaemon<IBiometricsFace> lazyDaemon,  int userId, @NonNull String owner,
            int sensorId, int currentUserId, boolean hasEnrolledBIometrics,
            @NonNull Map<Integer, Long> authenticatorIds) {
        super(context, lazyDaemon, null /* token */, null /* listener */, userId, owner,
                0 /* cookie */, sensorId, BiometricsProtoEnums.MODALITY_UNKNOWN,
                BiometricsProtoEnums.ACTION_UNKNOWN, BiometricsProtoEnums.CLIENT_UNKNOWN);
        mCurrentUserId = currentUserId;
        mHasEnrolledBiometrics = hasEnrolledBIometrics;
        mAuthenticatorIds = authenticatorIds;
    }

    @Override
    public void start(@NonNull Callback callback) {
        super.start(callback);

        if (mCurrentUserId == getTargetUserId()) {
            Slog.d(TAG, "Already user: " + mCurrentUserId + ", refreshing authenticatorId");
            try {
                mAuthenticatorIds.put(getTargetUserId(), mHasEnrolledBiometrics
                        ? getFreshDaemon().getAuthenticatorId().value : 0L);
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to refresh authenticatorId", e);
            }
            callback.onClientFinished(this, true /* success */);
            return;
        }

        startHalOperation();
    }

    @Override
    public void unableToStart() {
        // Nothing to do here
    }

    @Override
    protected void startHalOperation() {
        final File storePath = new File(Environment.getDataVendorDeDirectory(getTargetUserId()),
                FACE_DATA_DIR);
        if (!storePath.exists()) {
            Slog.e(TAG, "vold has not created the directory?");
            mCallback.onClientFinished(this, false /* success */);
            return;
        }

        try {
            getFreshDaemon().setActiveUser(getTargetUserId(), storePath.getAbsolutePath());
            mCallback.onClientFinished(this, true /* success */);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to setActiveUser: " + e);
            mCallback.onClientFinished(this, false /* success */);
        }
    }

    @Override
    public int getProtoEnum() {
        return BiometricsProto.CM_UPDATE_ACTIVE_USER;
    }
}
