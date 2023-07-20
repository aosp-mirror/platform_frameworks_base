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
import android.hardware.biometrics.ITestSession;
import android.hardware.biometrics.ITestSessionCallback;
import android.hardware.face.Face;
import android.hardware.face.FaceAuthenticationFrame;
import android.hardware.face.FaceEnrollFrame;
import android.hardware.face.IFaceServiceReceiver;
import android.os.Binder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.Utils;
import com.android.server.biometrics.sensors.BaseClientMonitor;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.face.FaceUtils;

import java.util.List;
import java.util.Random;

public class BiometricTestSessionImpl extends ITestSession.Stub {
    private static final String TAG = "BiometricTestSessionImpl";
    private final ITestSessionCallback mCallback;
    private final Context mContext;
    private final CustomFaceProvider.HalResultController mHalResultController;
    private final CustomFaceProvider mCustomFaceProvider;
    private final int mSensorId;
    private final IFaceServiceReceiver mReceiver = new IFaceServiceReceiver.Stub() {
        @Override
        public void onEnrollResult(Face face, int remaining) {
        }

        @Override
        public void onAcquired(int acquiredInfo, int vendorCode) {
        }

        @Override
        public void onAuthenticationSucceeded(Face face, int userId, boolean isStrongBiometric) {
        }

        @Override
        public void onFaceDetected(int sensorId, int userId, boolean isStrongBiometric) {
        }

        @Override
        public void onAuthenticationFailed() {
        }

        @Override
        public void onError(int error, int vendorCode) {
        }

        @Override
        public void onRemoved(Face face, int remaining) {
        }

        @Override
        public void onFeatureSet(boolean success, int feature) {
        }

        @Override
        public void onFeatureGet(boolean success, int[] features, boolean[] featureState) {
        }

        public void onChallengeGenerated(int sensorId, int userId, long challenge) {
        }

        @Override
        public void onAuthenticationFrame(FaceAuthenticationFrame frame) {
        }

        @Override
        public void onEnrollmentFrame(FaceEnrollFrame frame) {
        }
    };
    private final Random mRandom = new Random();

    public BiometricTestSessionImpl(Context context, int sensorId, ITestSessionCallback callback, CustomFaceProvider customFaceProvider, CustomFaceProvider.HalResultController halResultController) {
        mContext = context;
        mSensorId = sensorId;
        mCallback = callback;
        mCustomFaceProvider = customFaceProvider;
        mHalResultController = halResultController;
    }

    public void setTestHalEnabled(boolean enabled) {
        Utils.checkPermission(mContext, "android.permission.TEST_BIOMETRIC");
        mCustomFaceProvider.setTestHalEnabled(enabled);
    }

    public void startEnroll(int userId) {
        Utils.checkPermission(mContext, "android.permission.TEST_BIOMETRIC");
        mCustomFaceProvider.scheduleEnroll(mSensorId, new Binder(), new byte[69], userId, mReceiver, mContext.getOpPackageName(), new int[0], null, false);
    }

    public void finishEnroll(int userId) {
        Utils.checkPermission(mContext, "android.permission.TEST_BIOMETRIC");
        mHalResultController.onEnrollResult(1, userId, 0);
    }

    public void acceptAuthentication(int userId) {
        Utils.checkPermission(mContext, "android.permission.TEST_BIOMETRIC");
        List<Face> faces = FaceUtils.getLegacyInstance(mSensorId).getBiometricsForUser(mContext, userId);
        if (faces.isEmpty()) {
            Slog.w(TAG, "No faces, returning");
        } else {
            mHalResultController.onAuthenticated(faces.get(0).getBiometricId(), userId, new byte[]{0});
        }
    }

    public void rejectAuthentication(int userId) {
        Utils.checkPermission(mContext, "android.permission.TEST_BIOMETRIC");
        mHalResultController.onAuthenticated(0, userId, null);
    }

    public void notifyAcquired(int userId, int acquireInfo) {
        Utils.checkPermission(mContext, "android.permission.TEST_BIOMETRIC");
        mHalResultController.onAcquired(userId, acquireInfo, 0);
    }

    public void notifyError(int userId, int errorCode) {
        Utils.checkPermission(mContext, "android.permission.TEST_BIOMETRIC");
        mHalResultController.onError(errorCode, 0);
    }

    public void cleanupInternalState(int userId) {
        Utils.checkPermission(mContext, "android.permission.TEST_BIOMETRIC");
        mCustomFaceProvider.scheduleInternalCleanup(mSensorId, userId, new ClientMonitorCallback() {
            @Override
            public void onClientStarted(BaseClientMonitor clientMonitor) {
                try {
                    mCallback.onCleanupStarted(clientMonitor.getTargetUserId());
                } catch (RemoteException e) {
                    Slog.e(BiometricTestSessionImpl.TAG, "Remote exception", e);
                }
            }

            @Override
            public void onClientFinished(BaseClientMonitor clientMonitor, boolean success) {
                try {
                    mCallback.onCleanupFinished(clientMonitor.getTargetUserId());
                } catch (RemoteException e) {
                    Slog.e(BiometricTestSessionImpl.TAG, "Remote exception", e);
                }
            }
        });
    }
}
