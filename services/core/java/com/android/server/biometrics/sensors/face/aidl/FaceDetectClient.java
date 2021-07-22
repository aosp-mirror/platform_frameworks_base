/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.biometrics.sensors.face.aidl;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.common.ICancellationSignal;
import android.hardware.biometrics.face.ISession;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.BiometricsProto;
import com.android.server.biometrics.sensors.AcquisitionClient;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.DetectionConsumer;

/**
 * Performs face detection without exposing any matching information (e.g. accept/reject have the
 * same haptic, lockout counter is not increased).
 */
public class FaceDetectClient extends AcquisitionClient<ISession> implements DetectionConsumer {

    private static final String TAG = "FaceDetectClient";

    private final boolean mIsStrongBiometric;
    @Nullable private ICancellationSignal mCancellationSignal;

    public FaceDetectClient(@NonNull Context context, @NonNull LazyDaemon<ISession> lazyDaemon,
            @NonNull IBinder token, @NonNull ClientMonitorCallbackConverter listener, int userId,
            @NonNull String owner, int sensorId, boolean isStrongBiometric, int statsClient) {
        super(context, lazyDaemon, token, listener, userId, owner, 0 /* cookie */, sensorId,
                true /* shouldVibrate */, BiometricsProtoEnums.MODALITY_FACE,
                BiometricsProtoEnums.ACTION_AUTHENTICATE, statsClient);
        mIsStrongBiometric = isStrongBiometric;
    }

    @Override
    public void start(@NonNull Callback callback) {
        super.start(callback);
        startHalOperation();
    }

    @Override
    protected void stopHalOperation() {
        if (mCancellationSignal != null) {
            try {
                mCancellationSignal.cancel();
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception", e);
                mCallback.onClientFinished(this, false /* success */);
            }
        }
    }

    @Override
    protected void startHalOperation() {
        try {
            mCancellationSignal = getFreshDaemon().detectInteraction();
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when requesting face detect", e);
            mCallback.onClientFinished(this, false /* success */);
        }
    }

    @Override
    public void onInteractionDetected() {
        vibrateSuccess();

        try {
            getListener().onDetected(getSensorId(), getTargetUserId(), mIsStrongBiometric);
            mCallback.onClientFinished(this, true /* success */);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when sending onDetected", e);
            mCallback.onClientFinished(this, false /* success */);
        }
    }

    @Override
    public int getProtoEnum() {
        return BiometricsProto.CM_DETECT_INTERACTION;
    }

    @Override
    public boolean interruptsPrecedingClients() {
        return true;
    }
}
