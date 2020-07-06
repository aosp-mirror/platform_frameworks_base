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
import android.hardware.biometrics.face.V1_0.OptionalBool;
import android.hardware.biometrics.face.V1_0.Status;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.sensors.ClientMonitor;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;

/**
 * Face-specific getFeature client supporting the {@link android.hardware.biometrics.face.V1_0}
 * and {@link android.hardware.biometrics.face.V1_1} HIDL interfaces.
 */
public class FaceGetFeatureClient extends ClientMonitor<IBiometricsFace> {

    private static final String TAG = "FaceGetFeatureClient";

    private final int mFeature;
    private final int mFaceId;

    FaceGetFeatureClient(@NonNull Context context, @NonNull LazyDaemon<IBiometricsFace> lazyDaemon,
            @NonNull IBinder token, @NonNull ClientMonitorCallbackConverter listener, int userId,
            @NonNull String owner, int sensorId, int feature, int faceId) {
        super(context, lazyDaemon, token, listener, userId, owner, 0 /* cookie */, sensorId,
                BiometricsProtoEnums.MODALITY_UNKNOWN, BiometricsProtoEnums.ACTION_UNKNOWN,
                BiometricsProtoEnums.CLIENT_UNKNOWN);
        mFeature = feature;
        mFaceId = faceId;

    }

    @Override
    public void unableToStart() {
        try {
            getListener().onFeatureGet(false /* success */, mFeature, false /* value */);
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to send error", e);
        }
    }

    @Override
    public void start(@NonNull FinishCallback finishCallback) {
        super.start(finishCallback);
        startHalOperation();
    }

    @Override
    protected void startHalOperation() {
        try {
            final OptionalBool result = getFreshDaemon().getFeature(mFeature, mFaceId);
            getListener().onFeatureGet(result.status == Status.OK, mFeature, result.value);
            mFinishCallback.onClientFinished(this, true /* success */);
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to getFeature", e);
            mFinishCallback.onClientFinished(this, false /* success */);
        }
    }
}
