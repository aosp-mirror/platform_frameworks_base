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

package com.android.server.biometrics.sensors.fingerprint.hidl;

import static android.Manifest.permission.TEST_BIOMETRIC;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.biometrics.ITestSession;
import android.hardware.biometrics.ITestSessionCallback;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.IFingerprintServiceReceiver;
import android.os.Binder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.Utils;
import com.android.server.biometrics.sensors.BaseClientMonitor;
import com.android.server.biometrics.sensors.BiometricStateCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.fingerprint.FingerprintUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * A test session implementation for the {@link Fingerprint21} provider. See
 * {@link android.hardware.biometrics.BiometricTestSession}.
 */
public class BiometricTestSessionImpl extends ITestSession.Stub {

    private static final String TAG = "BiometricTestSessionImpl";

    @NonNull private final Context mContext;
    private final int mSensorId;
    @NonNull private final ITestSessionCallback mCallback;
    @NonNull private final BiometricStateCallback mBiometricStateCallback;
    @NonNull private final Fingerprint21 mFingerprint21;
    @NonNull private final Fingerprint21.HalResultController mHalResultController;
    @NonNull private final Set<Integer> mEnrollmentIds;
    @NonNull private final Random mRandom;

    /**
     * Internal receiver currently only used for enroll. Results do not need to be forwarded to the
     * test, since enrollment is a platform-only API. The authentication path is tested through
     * the public FingerprintManager APIs and does not use this receiver.
     */
    private final IFingerprintServiceReceiver mReceiver = new IFingerprintServiceReceiver.Stub() {
        @Override
        public void onEnrollResult(Fingerprint fp, int remaining) {

        }

        @Override
        public void onAcquired(int acquiredInfo, int vendorCode) {

        }

        @Override
        public void onAuthenticationSucceeded(Fingerprint fp, int userId,
                boolean isStrongBiometric) {

        }

        @Override
        public void onFingerprintDetected(int sensorId, int userId, boolean isStrongBiometric) {

        }

        @Override
        public void onAuthenticationFailed() {

        }

        @Override
        public void onError(int error, int vendorCode) {

        }

        @Override
        public void onRemoved(Fingerprint fp, int remaining) {

        }

        @Override
        public void onChallengeGenerated(int sensorId, int userId, long challenge) {

        }

        @Override
        public void onUdfpsPointerDown(int sensorId) {

        }

        @Override
        public void onUdfpsPointerUp(int sensorId) {

        }
    };

    BiometricTestSessionImpl(@NonNull Context context, int sensorId,
            @NonNull ITestSessionCallback callback,
            @NonNull BiometricStateCallback biometricStateCallback,
            @NonNull Fingerprint21 fingerprint21,
            @NonNull Fingerprint21.HalResultController halResultController) {
        mContext = context;
        mSensorId = sensorId;
        mCallback = callback;
        mFingerprint21 = fingerprint21;
        mBiometricStateCallback = biometricStateCallback;
        mHalResultController = halResultController;
        mEnrollmentIds = new HashSet<>();
        mRandom = new Random();
    }

    @Override
    public void setTestHalEnabled(boolean enabled) {
        Utils.checkPermission(mContext, TEST_BIOMETRIC);

        mFingerprint21.setTestHalEnabled(enabled);
    }

    @Override
    public void startEnroll(int userId) {
        Utils.checkPermission(mContext, TEST_BIOMETRIC);

        mFingerprint21.scheduleEnroll(mSensorId, new Binder(), new byte[69], userId, mReceiver,
                mContext.getOpPackageName(), FingerprintManager.ENROLL_ENROLL);
    }

    @Override
    public void finishEnroll(int userId) {
        Utils.checkPermission(mContext, TEST_BIOMETRIC);

        int nextRandomId = mRandom.nextInt();
        while (mEnrollmentIds.contains(nextRandomId)) {
            nextRandomId = mRandom.nextInt();
        }

        mEnrollmentIds.add(nextRandomId);
        mHalResultController.onEnrollResult(0 /* deviceId */,
                nextRandomId /* fingerId */, userId, 0);
    }

    @Override
    public void acceptAuthentication(int userId)  {
        Utils.checkPermission(mContext, TEST_BIOMETRIC);

        // Fake authentication with any of the existing fingers
        List<Fingerprint> fingerprints = FingerprintUtils.getLegacyInstance(mSensorId)
                .getBiometricsForUser(mContext, userId);
        if (fingerprints.isEmpty()) {
            Slog.w(TAG, "No fingerprints, returning");
            return;
        }
        final int fid = fingerprints.get(0).getBiometricId();
        final ArrayList<Byte> hat = new ArrayList<>(Collections.nCopies(69, (byte) 0));
        mHalResultController.onAuthenticated(0 /* deviceId */, fid, userId, hat);
    }

    @Override
    public void rejectAuthentication(int userId)  {
        Utils.checkPermission(mContext, TEST_BIOMETRIC);

        mHalResultController.onAuthenticated(0 /* deviceId */, 0 /* fingerId */, userId, null);
    }

    @Override
    public void notifyAcquired(int userId, int acquireInfo)  {
        Utils.checkPermission(mContext, TEST_BIOMETRIC);

        mHalResultController.onAcquired(0 /* deviceId */, acquireInfo, 0 /* vendorCode */);
    }

    @Override
    public void notifyError(int userId, int errorCode)  {
        Utils.checkPermission(mContext, TEST_BIOMETRIC);

        mHalResultController.onError(0 /* deviceId */, errorCode, 0 /* vendorCode */);
    }

    @Override
    public void cleanupInternalState(int userId)  {
        Utils.checkPermission(mContext, TEST_BIOMETRIC);

        mFingerprint21.scheduleInternalCleanup(mSensorId, userId, new ClientMonitorCallback() {
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
