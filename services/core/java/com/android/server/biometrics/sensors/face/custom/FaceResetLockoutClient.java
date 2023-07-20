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

import java.util.function.Supplier;

class FaceResetLockoutClient extends HalClientMonitor<IFaceService> {
    private static final String TAG = "FaceResetLockoutClient";
    private final byte[] mHardwareAuthToken;

    FaceResetLockoutClient(Context context, Supplier<IFaceService> lazyDaemon, int userId, String owner, int sensorId, BiometricLogger biometricLogger, BiometricContext biometricContext, byte[] hardwareAuthToken) {
        super(context, lazyDaemon, null, null, userId, owner, 0, sensorId, biometricLogger, biometricContext);
        mHardwareAuthToken = hardwareAuthToken.clone();
    }

    @Override
    public void unableToStart() {
    }

    @Override
    public void start(ClientMonitorCallback callback) {
        super.start(callback);
        startHalOperation();
    }

    @Override
    protected void startHalOperation() {
        try {
            getFreshDaemon().resetLockout(mHardwareAuthToken);
            mCallback.onClientFinished(this, true);
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to reset lockout", e);
            mCallback.onClientFinished(this, false);
        }
    }

    @Override
    public int getProtoEnum() {
        return 12;
    }

    @Override
    public boolean interruptsPrecedingClients() {
        return true;
    }
}
