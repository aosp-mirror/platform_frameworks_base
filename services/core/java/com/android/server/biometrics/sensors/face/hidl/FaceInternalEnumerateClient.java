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
import android.hardware.face.Face;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.sensors.BiometricUtils;
import com.android.server.biometrics.sensors.InternalEnumerateClient;

import java.util.List;
import java.util.function.Supplier;

/**
 * Face-specific internal enumerate client supporting the
 * {@link android.hardware.biometrics.face.V1_0} HIDL interface.
 */
class FaceInternalEnumerateClient extends InternalEnumerateClient<IBiometricsFace> {
    private static final String TAG = "FaceInternalEnumerateClient";

    FaceInternalEnumerateClient(@NonNull Context context,
            @NonNull Supplier<IBiometricsFace> lazyDaemon, @NonNull IBinder token, int userId,
            @NonNull String owner, @NonNull List<Face> enrolledList,
            @NonNull BiometricUtils<Face> utils, int sensorId,
            @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext) {
        super(context, lazyDaemon, token, userId, owner, enrolledList, utils, sensorId,
                logger, biometricContext);
    }

    @Override
    protected void startHalOperation() {
        try {
            getFreshDaemon().enumerate();
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when requesting enumerate", e);
            mCallback.onClientFinished(this, false /* success */);
        }
    }

    @Override
    protected int getModality() {
        return BiometricsProtoEnums.MODALITY_FACE;
    }
}
