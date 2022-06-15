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
import android.hardware.biometrics.BiometricFaceConstants;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.face.IFace;
import android.hardware.biometrics.face.ISession;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Slog;

import com.android.server.biometrics.BiometricsProto;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.ErrorConsumer;
import com.android.server.biometrics.sensors.HalClientMonitor;

import java.util.HashMap;
import java.util.Map;

/**
 * Face-specific get feature client for the {@link IFace} AIDL HAL interface.
 */
public class FaceGetFeatureClient extends HalClientMonitor<ISession> implements ErrorConsumer {

    private static final String TAG = "FaceGetFeatureClient";

    private final int mUserId;

    FaceGetFeatureClient(@NonNull Context context, @NonNull LazyDaemon<ISession> lazyDaemon,
            @NonNull IBinder token, @Nullable ClientMonitorCallbackConverter listener, int userId,
            @NonNull String owner, int sensorId) {
        super(context, lazyDaemon, token, listener, userId, owner, 0 /* cookie */, sensorId,
                BiometricsProtoEnums.MODALITY_UNKNOWN, BiometricsProtoEnums.ACTION_UNKNOWN,
                BiometricsProtoEnums.CLIENT_UNKNOWN);
        mUserId = userId;
    }

    @Override
    public void unableToStart() {
        mCallback.onClientFinished(this, false /* success */);
    }

    @Override
    public void start(@NonNull Callback callback) {
        super.start(callback);
        startHalOperation();
    }

    @Override
    protected void startHalOperation() {
        try {
            getFreshDaemon().getFeatures();
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to getFeature", e);
            mCallback.onClientFinished(this, false /* success */);
        }
    }

    @Override
    public int getProtoEnum() {
        return BiometricsProto.CM_GET_FEATURE;
    }

    public void onFeatureGet(boolean success, byte[] features) {
        try {
            HashMap<Integer, Boolean> featureMap = getFeatureMap();
            int[] featuresToSend = new int[featureMap.size()];
            boolean[] featureState = new boolean[featureMap.size()];

            // The AIDL get feature api states that the presence of a feature means
            // it is enabled, while the lack thereof means its disabled.
            for (int i = 0; i < features.length; i++) {
                featureMap.put(AidlConversionUtils.convertAidlToFrameworkFeature(features[i]),
                        true);
            }

            int i = 0;
            for (Map.Entry<Integer, Boolean> entry : featureMap.entrySet()) {
                featuresToSend[i] = entry.getKey();
                featureState[i] = entry.getValue();
                i++;
            }

            boolean attentionEnabled =
                    featureMap.get(BiometricFaceConstants.FEATURE_REQUIRE_ATTENTION);
            Slog.d(TAG, "Updating attention value for user: " + mUserId
                    + " to value: " + attentionEnabled);
            Settings.Secure.putIntForUser(getContext().getContentResolver(),
                    Settings.Secure.FACE_UNLOCK_ATTENTION_REQUIRED,
                    attentionEnabled ? 1 : 0, mUserId);

            getListener().onFeatureGet(success, featuresToSend, featureState);
        } catch (RemoteException | IllegalArgumentException e) {
            Slog.e(TAG, "exception", e);
            mCallback.onClientFinished(this, false /* success */);
            return;
        }

        mCallback.onClientFinished(this, true /* success */);
    }

    private @NonNull HashMap<Integer, Boolean> getFeatureMap() {
        HashMap<Integer, Boolean> featureMap = new HashMap<>();
        featureMap.put(BiometricFaceConstants.FEATURE_REQUIRE_ATTENTION, false);
        return featureMap;
    }

    @Override
    public void onError(int errorCode, int vendorCode) {
        try {
            getListener().onFeatureGet(false /* success */, new int[0], new boolean[0]);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }

        mCallback.onClientFinished(this, false /* success */);
    }

}
