/*
* Copyright (C) 2022 The Pixel Experience Project
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package com.android.server.biometrics.sensors.face.custom;

import android.content.Context;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.util.custom.faceunlock.IFaceService;
import com.android.server.biometrics.sensors.BaseClientMonitor;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.HalClientMonitor;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;

import java.util.Map;
import java.util.function.Supplier;

class FaceUpdateActiveUserClient extends HalClientMonitor<IFaceService> {
    private static final String FACE_DATA_DIR = "facedata";
    private static final String TAG = "FaceUpdateActiveUserClient";
    private final Map<Integer, Long> mAuthenticatorIds;
    private final int mCurrentUserId;
    private final boolean mHasEnrolledBiometrics;

    FaceUpdateActiveUserClient(Context context, Supplier<IFaceService> lazyDaemon, int userId, String owner, int sensorId, BiometricLogger biometricLogger, BiometricContext biometricContext, int currentUserId, boolean hasEnrolledBIometrics, Map<Integer, Long> authenticatorIds) {
        super(context, lazyDaemon, null, null, userId, owner, 0, sensorId, biometricLogger, biometricContext);
        mCurrentUserId = currentUserId;
        mHasEnrolledBiometrics = hasEnrolledBIometrics;
        mAuthenticatorIds = authenticatorIds;
    }

    @Override
    public void start(ClientMonitorCallback callback) {
        super.start(callback);
        if (mCurrentUserId == getTargetUserId()) {
            Slog.d(TAG, "Already user: " + mCurrentUserId + ", refreshing authenticatorId");
            try {
                mAuthenticatorIds.put(getTargetUserId(), mHasEnrolledBiometrics ? (long) getFreshDaemon().getAuthenticatorId() : 0);
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to refresh authenticatorId", e);
            }
            callback.onClientFinished(this, true);
            return;
        }
        startHalOperation();
    }

    @Override
    public void unableToStart() {
    }

    @Override
    protected void startHalOperation() {
        mCallback.onClientFinished(this, false);
    }

    @Override
    public int getProtoEnum() {
        return 1;
    }
}
