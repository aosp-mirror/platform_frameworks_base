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
import android.hardware.biometrics.ITestSession;
import android.hardware.biometrics.ITestSessionCallback;
import android.hardware.face.Face;
import android.hardware.face.FaceAuthenticationFrame;
import android.hardware.face.FaceEnrollFrame;
import android.hardware.face.FaceEnrollOptions;
import android.hardware.face.IFaceServiceReceiver;
import android.os.Binder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.sensors.BaseClientMonitor;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.face.FaceUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class BiometricTestSessionImpl extends ITestSession.Stub {
    private static final String TAG = "BiometricTestSessionImpl";

    @NonNull private final Context mContext;
    private final int mSensorId;
    @NonNull private final ITestSessionCallback mCallback;
    @NonNull private final Face10 mFace10;
    @NonNull private final Face10.HalResultController mHalResultController;
    @NonNull private final Set<Integer> mEnrollmentIds;
    @NonNull private final Random mRandom;


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

        @Override
        public void onChallengeGenerated(int sensorId, int userId, long challenge) {

        }

        @Override
        public void onAuthenticationFrame(FaceAuthenticationFrame frame) {

        }

        @Override
        public void onEnrollmentFrame(FaceEnrollFrame frame) {

        }
    };

    BiometricTestSessionImpl(@NonNull Context context, int sensorId,
            @NonNull ITestSessionCallback callback,
            @NonNull Face10 face10,
            @NonNull Face10.HalResultController halResultController) {
        mContext = context;
        mSensorId = sensorId;
        mCallback = callback;
        mFace10 = face10;
        mHalResultController = halResultController;
        mEnrollmentIds = new HashSet<>();
        mRandom = new Random();
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.TEST_BIOMETRIC)
    @Override
    public void setTestHalEnabled(boolean enabled) {

        super.setTestHalEnabled_enforcePermission();

        mFace10.setTestHalEnabled(enabled);
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.TEST_BIOMETRIC)
    @Override
    public void startEnroll(int userId) {

        super.startEnroll_enforcePermission();

        mFace10.scheduleEnroll(mSensorId, new Binder(), new byte[69], userId, mReceiver,
                mContext.getOpPackageName(), new int[0] /* disabledFeatures */,
                null /* previewSurface */, false /* debugConsent */,
                (new FaceEnrollOptions.Builder()).build());
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.TEST_BIOMETRIC)
    @Override
    public void finishEnroll(int userId) {

        super.finishEnroll_enforcePermission();

        int nextRandomId = mRandom.nextInt();
        while (mEnrollmentIds.contains(nextRandomId)) {
            nextRandomId = mRandom.nextInt();
        }

        mEnrollmentIds.add(nextRandomId);
        mHalResultController.onEnrollResult(0 /* deviceId */,
                nextRandomId /* faceId */, userId, 0);
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.TEST_BIOMETRIC)
    @Override
    public void acceptAuthentication(int userId) {

        // Fake authentication with any of the existing fingers
        super.acceptAuthentication_enforcePermission();

        List<Face> faces = FaceUtils.getLegacyInstance(mSensorId)
                .getBiometricsForUser(mContext, userId);
        if (faces.isEmpty()) {
            Slog.w(TAG, "No faces, returning");
            return;
        }
        final int fid = faces.get(0).getBiometricId();
        final ArrayList<Byte> hat = new ArrayList<>(Collections.nCopies(69, (byte) 0));
        mHalResultController.onAuthenticated(0 /* deviceId */, fid, userId, hat);
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.TEST_BIOMETRIC)
    @Override
    public void rejectAuthentication(int userId) {

        super.rejectAuthentication_enforcePermission();

        mHalResultController.onAuthenticated(0 /* deviceId */, 0 /* faceId */, userId, null);
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.TEST_BIOMETRIC)
    @Override
    public void notifyAcquired(int userId, int acquireInfo) {

        super.notifyAcquired_enforcePermission();

        mHalResultController.onAcquired(0 /* deviceId */, userId, acquireInfo, 0 /* vendorCode */);
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.TEST_BIOMETRIC)
    @Override
    public void notifyError(int userId, int errorCode) {

        super.notifyError_enforcePermission();

        mHalResultController.onError(0 /* deviceId */, userId, errorCode, 0 /* vendorCode */);
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.TEST_BIOMETRIC)
    @Override
    public void cleanupInternalState(int userId) {

        super.cleanupInternalState_enforcePermission();

        mFace10.scheduleInternalCleanup(mSensorId, userId, new ClientMonitorCallback() {
            @Override
            public void onClientStarted(@NonNull BaseClientMonitor clientMonitor) {
                try {
                    mCallback.onCleanupStarted(clientMonitor.getTargetUserId());
                } catch (RemoteException e) {
                    Slog.e(TAG, "Remote exception", e);
                }
            }

            @Override
            public void onClientFinished(@NonNull BaseClientMonitor clientMonitor,
                    boolean success) {
                try {
                    mCallback.onCleanupFinished(clientMonitor.getTargetUserId());
                } catch (RemoteException e) {
                    Slog.e(TAG, "Remote exception", e);
                }
            }
        });
    }
}
