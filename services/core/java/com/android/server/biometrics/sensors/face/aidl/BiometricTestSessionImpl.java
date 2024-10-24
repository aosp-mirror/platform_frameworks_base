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

package com.android.server.biometrics.sensors.face.aidl;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.biometrics.ITestSession;
import android.hardware.biometrics.ITestSessionCallback;
import android.hardware.biometrics.face.AuthenticationFrame;
import android.hardware.biometrics.face.BaseFrame;
import android.hardware.biometrics.face.EnrollmentFrame;
import android.hardware.biometrics.face.virtualhal.AcquiredInfoAndVendorCode;
import android.hardware.biometrics.face.virtualhal.EnrollmentProgressStep;
import android.hardware.biometrics.face.virtualhal.NextEnrollment;
import android.hardware.face.Face;
import android.hardware.face.FaceAuthenticationFrame;
import android.hardware.face.FaceEnrollFrame;
import android.hardware.face.FaceEnrollOptions;
import android.hardware.face.IFaceServiceReceiver;
import android.os.Binder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.HardwareAuthTokenUtils;
import com.android.server.biometrics.sensors.BaseClientMonitor;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.EnrollClient;
import com.android.server.biometrics.sensors.face.FaceUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * A test session implementation for {@link FaceProvider}. See
 * {@link android.hardware.biometrics.BiometricTestSession}.
 */
public class BiometricTestSessionImpl extends ITestSession.Stub {

    private static final String TAG = "face/aidl/BiometricTestSessionImpl";
    private static final int VHAL_ENROLLMENT_ID = 9999;

    @NonNull private final Context mContext;
    private final int mSensorId;
    @NonNull private final ITestSessionCallback mCallback;
    @NonNull private final FaceProvider mProvider;
    @NonNull private final Sensor mSensor;
    @NonNull private final Set<Integer> mEnrollmentIds;
    @NonNull private final Random mRandom;

    /**
     * Internal receiver currently only used for enroll. Results do not need to be forwarded to the
     * test, since enrollment is a platform-only API. The authentication path is tested through
     * the public BiometricPrompt APIs and does not use this receiver.
     */
    private final IFaceServiceReceiver mReceiver = new IFaceServiceReceiver.Stub() {
        @Override
        public void onEnrollResult(Face face, int remaining) {

        }

        @Override
        public void onAcquired(int acquireInfo, int vendorCode) {

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
            @NonNull FaceProvider provider, @NonNull Sensor sensor) {
        mContext = context;
        mSensorId = sensorId;
        mCallback = callback;
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
    public void startEnroll(int userId) throws RemoteException {

        super.startEnroll_enforcePermission();

        Slog.i(TAG, "startEnroll(): isVhalForTesting=" + mProvider.isVhalForTesting());
        if (mProvider.isVhalForTesting()) {
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
            mProvider.getVhal().setOperationAuthenticateDuration(6000);
        }

        mProvider.scheduleEnroll(mSensorId, new Binder(), new byte[69], userId, mReceiver,
                mContext.getOpPackageName(), new int[0] /* disabledFeatures */,
                null /* previewSurface */, false /* debugConsent */,
                (new FaceEnrollOptions.Builder()).build());
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.TEST_BIOMETRIC)
    @Override
    public void finishEnroll(int userId) {

        super.finishEnroll_enforcePermission();

        if (mProvider.isVhalForTesting()) {
            return;
        }

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

        // Fake authentication with any of the existing faces
        super.acceptAuthentication_enforcePermission();

        if (mProvider.isVhalForTesting()) {
            mProvider.getVhal().setEnrollmentHit(VHAL_ENROLLMENT_ID);
            return;
        }

        List<Face> faces = FaceUtils.getInstance(mSensorId)
                .getBiometricsForUser(mContext, userId);
        if (faces.isEmpty()) {
            Slog.w(TAG, "No faces, returning");
            return;
        }
        final int fid = faces.get(0).getBiometricId();
        mSensor.getSessionForUser(userId).getHalSessionCallback().onAuthenticationSucceeded(fid,
                HardwareAuthTokenUtils.toHardwareAuthToken(new byte[69]));
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.TEST_BIOMETRIC)
    @Override
    public void rejectAuthentication(int userId) throws RemoteException {

        super.rejectAuthentication_enforcePermission();

        if (mProvider.isVhalForTesting()) {
            mProvider.getVhal().setEnrollmentHit(VHAL_ENROLLMENT_ID + 1);
            return;
        }

        mSensor.getSessionForUser(userId).getHalSessionCallback().onAuthenticationFailed();
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.TEST_BIOMETRIC)
    @Override
    public void notifyAcquired(int userId, int acquireInfo) {
        super.notifyAcquired_enforcePermission();

        BaseFrame data = new BaseFrame();
        data.acquiredInfo = (byte) acquireInfo;

        if (mSensor.getScheduler().getCurrentClient() instanceof EnrollClient) {
            final EnrollmentFrame frame = new EnrollmentFrame();
            frame.data = data;
            mSensor.getSessionForUser(userId).getHalSessionCallback()
                    .onEnrollmentFrame(frame);
        } else {
            final AuthenticationFrame frame = new AuthenticationFrame();
            frame.data = data;
            mSensor.getSessionForUser(userId).getHalSessionCallback()
                    .onAuthenticationFrame(frame);
        }
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

        if (mProvider.isVhalForTesting()) {
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
