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
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.util.custom.faceunlock.IFaceService;
import com.android.server.biometrics.sensors.BaseClientMonitor;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.HalClientMonitor;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;

import java.util.function.Supplier;

public class FaceGetFeatureClient extends HalClientMonitor<IFaceService> {
    private static final String TAG = "FaceGetFeatureClient";
    private final int mFaceId;
    private final int mFeature;
    private boolean mValue;

    FaceGetFeatureClient(Context context, Supplier<IFaceService> lazyDaemon, IBinder token, ClientMonitorCallbackConverter listener, int userId, String owner, int sensorId, BiometricLogger biometricLogger, BiometricContext biometricContext, int feature, int faceId) {
        super(context, lazyDaemon, token, listener, userId, owner, 0, sensorId, biometricLogger, biometricContext);
        mFeature = feature;
        mFaceId = faceId;
    }

    @Override
    public void unableToStart() {
        try {
            if (getListener() != null) {
                getListener().onFeatureGet(false, new int[0], new boolean[0]);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to send error", e);
        }
    }

    @Override
    public void start(ClientMonitorCallback callback) {
        super.start(callback);
        startHalOperation();
    }

    @Override
    protected void startHalOperation() {
        try {
            boolean result = getFreshDaemon().getFeature(mFeature, mFaceId);
            int[] features = {mFeature};
            boolean[] featureState = {result};
            mValue = result;
            if (getListener() != null) {
                getListener().onFeatureGet(result, features, featureState);
            }
            mCallback.onClientFinished(this, true);
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to getFeature", e);
            mCallback.onClientFinished(this, false);
        }
    }

    public boolean getValue() {
        return mValue;
    }

    @Override
    public int getProtoEnum() {
        return 9;
    }
}
