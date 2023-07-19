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
import android.hardware.face.Face;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.util.custom.faceunlock.IFaceService;
import com.android.server.biometrics.sensors.BiometricUtils;
import com.android.server.biometrics.sensors.InternalEnumerateClient;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;

import java.util.List;
import java.util.function.Supplier;

class FaceInternalEnumerateClient extends InternalEnumerateClient<IFaceService> {
    private static final String TAG = "FaceInternalEnumerateClient";

    FaceInternalEnumerateClient(Context context, Supplier<IFaceService> lazyDaemon, IBinder token, int userId, String owner, List<Face> enrolledList, BiometricUtils<Face> utils, int sensorId, BiometricLogger biometricLogger, BiometricContext biometricContext) {
        super(context, lazyDaemon, token, userId, owner, enrolledList, utils, sensorId, biometricLogger, biometricContext);
    }

    @Override
    protected void startHalOperation() {
        try {
            getFreshDaemon().enumerate();
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when requesting enumerate", e);
            mCallback.onClientFinished(this, false);
        }
    }
}
