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

package com.android.server.biometrics.sensors.fingerprint.aidl;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.biometrics.ITestSession;
import android.hardware.biometrics.ITestSessionCallback;
import android.hardware.biometrics.fingerprint.virtualhal.AcquiredInfoAndVendorCode;
import android.hardware.biometrics.fingerprint.virtualhal.EnrollmentProgressStep;
import android.hardware.biometrics.fingerprint.virtualhal.NextEnrollment;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintEnrollOptions;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.IFingerprintServiceReceiver;
import android.os.Binder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.HardwareAuthTokenUtils;
import com.android.server.biometrics.sensors.BaseClientMonitor;
import com.android.server.biometrics.sensors.BiometricStateCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.fingerprint.FingerprintUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * A test session implementation for {@link FingerprintProvider}. See
 * {@link android.hardware.biometrics.BiometricTestSession}.
 */
class BiometricTestSessionImpl extends ITestSession.Stub {

    private static final String TAG = "fp/aidl/BiometricTestSessionImpl";
    private static final int VHAL_ENROLLMENT_ID = 9999;

    @NonNull private final Context mContext;
    private final int mSensorId;
    @NonNull private final ITestSessionCallback mCallback;
    @NonNull private final BiometricStateCallback mBiometricStateCallback;
    @NonNull private final FingerprintProvider mProvider;
    @NonNull private final Sensor mSensor;
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

        @Override
        public void onUdfpsOverlayShown() {

        }
    };

    BiometricTestSessionImpl(@NonNull Context context, int sensorId,
            @NonNull ITestSessionCallback callback,
            @NonNull BiometricStateCallback biometricStateCallback,
            @NonNull FingerprintProvider provider,
            @NonNull Sensor sensor) {
        mContext = context;
        mSensorId = sensorId;
        mCallback = callback;
        mBiometricStateCallback = biometricStateCallback;
        mProvider = provider;
        mSensor = sensor;
        mEnrollmentIds = new HashSet<>();
        mRandom = new Random();
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.TEST_BIOMETRIC)
    @Override
    public void setTestHalEnabled(boolean enabled) {

        super.setTestHalEnabled_enforcePermission();

        mSensor.setTestHalEnabled(enabled);
        mProvider.setTestHalEnabled(enabled);
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.TEST_BIOMETRIC)
    @Override
    public void startEnroll(int userId) {

        super.startEnroll_enforcePermission();

        mProvider.scheduleEnroll(mSensorId, new Binder(), new byte[69], userId, mReceiver,
                mContext.getOpPackageName(), FingerprintManager.ENROLL_ENROLL,
                (new FingerprintEnrollOptions.Builder()).build());
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.TEST_BIOMETRIC)
    @Override
    public void finishEnroll(int userId) throws RemoteException {

        super.finishEnroll_enforcePermission();

        Slog.i(TAG, "finishEnroll(): useVhalForTesting=" + mProvider.useVhalForTesting());
        if (mProvider.useVhalForTesting()) {
            final AcquiredInfoAndVendorCode[] acquiredInfoAndVendorCodes =
                    {new AcquiredInfoAndVendorCode()};
            final EnrollmentProgressStep[] enrollmentProgressSteps =
                    {new EnrollmentProgressStep(), new EnrollmentProgressStep()};
            enrollmentProgressSteps[0].durationMs = 100;
            enrollmentProgressSteps[0].acquiredInfoAndVendorCodes = acquiredInfoAndVendorCodes;
            enrollmentProgressSteps[1].durationMs = 200;
            enrollmentProgressSteps[1].acquiredInfoAndVendorCodes = acquiredInfoAndVendorCodes;

            final NextEnrollment nextEnrollment = new NextEnrollment();
            nextEnrollment.id = VHAL_ENROLLMENT_ID;
            nextEnrollment.progressSteps = enrollmentProgressSteps;
            nextEnrollment.result = true;
            mProvider.getVhal().setNextEnrollment(nextEnrollment);
            mProvider.simulateVhalFingerDown(userId, mSensorId);
            return;
        }

        //TODO (b341889971): delete the following lines when b/341889971 is resolved
        int nextRandomId = mRandom.nextInt();
        while (mEnrollmentIds.contains(nextRandomId)) {
            nextRandomId = mRandom.nextInt();
        }

        mEnrollmentIds.add(nextRandomId);
        mSensor.getSessionForUser(userId).getHalSessionCallback()
                .onEnrollmentProgress(nextRandomId, 0 /* remaining */);
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.TEST_BIOMETRIC)
    @Override
    public void acceptAuthentication(int userId) throws RemoteException {

        // Fake authentication with any of the existing fingers
        super.acceptAuthentication_enforcePermission();

        if (mProvider.useVhalForTesting()) {
            mProvider.getVhal().setEnrollmentHit(VHAL_ENROLLMENT_ID);
            mProvider.simulateVhalFingerDown(userId, mSensorId);
            return;
        }

        //TODO (b341889971): delete the following lines when b/341889971 is resolved
        List<Fingerprint> fingerprints = FingerprintUtils.getInstance(mSensorId)
                .getBiometricsForUser(mContext, userId);
        if (fingerprints.isEmpty()) {
            Slog.w(TAG, "No fingerprints, returning");
            return;
        }
        final int fid = fingerprints.get(0).getBiometricId();
        mSensor.getSessionForUser(userId).getHalSessionCallback().onAuthenticationSucceeded(fid,
                HardwareAuthTokenUtils.toHardwareAuthToken(new byte[69]));
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.TEST_BIOMETRIC)
    @Override
    public void rejectAuthentication(int userId) throws RemoteException  {

        super.rejectAuthentication_enforcePermission();

        if (mProvider.useVhalForTesting()) {
            mProvider.getVhal().setEnrollmentHit(VHAL_ENROLLMENT_ID + 1);
            mProvider.simulateVhalFingerDown(userId, mSensorId);
            return;
        }

        //TODO (b341889971): delete the following lines when b/341889971 is resolved
        mSensor.getSessionForUser(userId).getHalSessionCallback().onAuthenticationFailed();
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.TEST_BIOMETRIC)
    @Override
    public void notifyAcquired(int userId, int acquireInfo)  {

        super.notifyAcquired_enforcePermission();

        mSensor.getSessionForUser(userId).getHalSessionCallback()
                .onAcquired((byte) acquireInfo, 0 /* vendorCode */);
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.TEST_BIOMETRIC)
    @Override
    public void notifyError(int userId, int errorCode)  {

        super.notifyError_enforcePermission();

        mSensor.getSessionForUser(userId).getHalSessionCallback().onError((byte) errorCode,
                0 /* vendorCode */);
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.TEST_BIOMETRIC)
    @Override
    public void cleanupInternalState(int userId) throws RemoteException {

        super.cleanupInternalState_enforcePermission();

        Slog.d(TAG, "cleanupInternalState: " + userId);

        if (mProvider.useVhalForTesting()) {
            Slog.i(TAG, "cleanup virtualhal configurations");
            mProvider.getVhal().resetConfigurations(); //setEnrollments(new int[]{});
        }

        mProvider.scheduleInternalCleanup(mSensorId, userId, new ClientMonitorCallback() {
            @Override
            public void onClientStarted(@NonNull BaseClientMonitor clientMonitor) {
                try {
                    Slog.d(TAG, "onClientStarted: " + clientMonitor);
                    mCallback.onCleanupStarted(clientMonitor.getTargetUserId());
                } catch (RemoteException e) {
                    Slog.e(TAG, "Remote exception", e);
                }
            }

            @Override
            public void onClientFinished(@NonNull BaseClientMonitor clientMonitor,
                    boolean success) {
                try {
                    Slog.d(TAG, "onClientFinished: " + clientMonitor);
                    mCallback.onCleanupFinished(clientMonitor.getTargetUserId());
                } catch (RemoteException e) {
                    Slog.e(TAG, "Remote exception", e);
                }
            }
        });
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.TEST_BIOMETRIC)
    @Override
    public int getSensorId() {
        super.getSensorId_enforcePermission();
        return mSensorId;
    }
}
