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

package com.android.server.biometrics.sensors.face;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.face.V1_0.IBiometricsFace;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.sensors.BiometricUtils;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.RemovalClient;

/**
 * Face-specific removal client supporting the {@link android.hardware.biometrics.face.V1_0}
 * and {@link android.hardware.biometrics.face.V1_1} HIDL interfaces.
 */
class FaceRemovalClient extends RemovalClient<IBiometricsFace> {
    private static final String TAG = "FaceRemovalClient";

    FaceRemovalClient(@NonNull Context context, @NonNull LazyDaemon<IBiometricsFace> lazyDaemon,
            @NonNull IBinder token, @NonNull ClientMonitorCallbackConverter listener,
            int biometricId, int userId, @NonNull String owner, @NonNull BiometricUtils utils,
            int sensorId) {
        super(context, lazyDaemon, token, listener, biometricId, userId, owner, utils, sensorId,
                BiometricsProtoEnums.MODALITY_FACE);
    }

    @Override
    protected void startHalOperation() {
        try {
            getFreshDaemon().remove(mBiometricId);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when requesting remove", e);
            mFinishCallback.onClientFinished(this, false /* success */);
        }
    }
}
