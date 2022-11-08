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
import android.content.res.Resources;
import android.hardware.SensorPrivacyManager;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricFaceConstants;
import android.hardware.biometrics.face.V1_0.IBiometricsFace;
import android.hardware.face.FaceManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.R;
import com.android.server.biometrics.Utils;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.sensors.AuthenticationClient;
import com.android.server.biometrics.sensors.BiometricNotificationUtils;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.ClientMonitorCompositeCallback;
import com.android.server.biometrics.sensors.LockoutTracker;
import com.android.server.biometrics.sensors.face.UsageStats;

import java.util.ArrayList;
import java.util.function.Supplier;

/**
 * Face-specific authentication client supporting the {@link android.hardware.biometrics.face.V1_0}
 * HIDL interface.
 */
class FaceAuthenticationClient extends AuthenticationClient<IBiometricsFace> {

    private static final String TAG = "FaceAuthenticationClient";

    private final UsageStats mUsageStats;

    private final int[] mBiometricPromptIgnoreList;
    private final int[] mBiometricPromptIgnoreListVendor;
    private final int[] mKeyguardIgnoreList;
    private final int[] mKeyguardIgnoreListVendor;

    private int mLastAcquire;
    private SensorPrivacyManager mSensorPrivacyManager;

    FaceAuthenticationClient(@NonNull Context context,
            @NonNull Supplier<IBiometricsFace> lazyDaemon,
            @NonNull IBinder token, long requestId,
            @NonNull ClientMonitorCallbackConverter listener, int targetUserId, long operationId,
            boolean restricted, String owner, int cookie, boolean requireConfirmation, int sensorId,
            @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext,
            boolean isStrongBiometric, @NonNull LockoutTracker lockoutTracker,
            @NonNull UsageStats usageStats, boolean allowBackgroundAuthentication,
            boolean isKeyguardBypassEnabled) {
        super(context, lazyDaemon, token, listener, targetUserId, operationId, restricted,
                owner, cookie, requireConfirmation, sensorId, logger, biometricContext,
                isStrongBiometric, null /* taskStackListener */,
                lockoutTracker, allowBackgroundAuthentication, false /* shouldVibrate */,
                isKeyguardBypassEnabled);
        setRequestId(requestId);
        mUsageStats = usageStats;
        mSensorPrivacyManager = context.getSystemService(SensorPrivacyManager.class);

        final Resources resources = getContext().getResources();
        mBiometricPromptIgnoreList = resources.getIntArray(
                R.array.config_face_acquire_biometricprompt_ignorelist);
        mBiometricPromptIgnoreListVendor = resources.getIntArray(
                R.array.config_face_acquire_vendor_biometricprompt_ignorelist);
        mKeyguardIgnoreList = resources.getIntArray(
                R.array.config_face_acquire_keyguard_ignorelist);
        mKeyguardIgnoreListVendor = resources.getIntArray(
                R.array.config_face_acquire_vendor_keyguard_ignorelist);
    }

    @Override
    public void start(@NonNull ClientMonitorCallback callback) {
        super.start(callback);
        mState = STATE_STARTED;
    }

    @NonNull
    @Override
    protected ClientMonitorCallback wrapCallbackForStart(@NonNull ClientMonitorCallback callback) {
        return new ClientMonitorCompositeCallback(
                getLogger().getAmbientLightProbe(true /* startWithClient */), callback);
    }

    @Override
    protected void startHalOperation() {

        if (mSensorPrivacyManager != null
                && mSensorPrivacyManager
                .isSensorPrivacyEnabled(SensorPrivacyManager.TOGGLE_TYPE_SOFTWARE,
                SensorPrivacyManager.Sensors.CAMERA)) {
            onError(BiometricFaceConstants.FACE_ERROR_HW_UNAVAILABLE, 0 /* vendorCode */);
            mCallback.onClientFinished(this, false /* success */);
            return;
        }

        try {
            getFreshDaemon().authenticate(mOperationId);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when requesting auth", e);
            onError(BiometricFaceConstants.FACE_ERROR_HW_UNAVAILABLE, 0 /* vendorCode */);
            mCallback.onClientFinished(this, false /* success */);
        }
    }

    @Override
    protected void stopHalOperation() {
        try {
            getFreshDaemon().cancel();
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when requesting cancel", e);
            onError(BiometricFaceConstants.FACE_ERROR_HW_UNAVAILABLE, 0 /* vendorCode */);
            mCallback.onClientFinished(this, false /* success */);
        }
    }

    @Override
    public boolean wasUserDetected() {
        // Do not provide haptic feedback if the user was not detected, and an error (usually
        // ERROR_TIMEOUT) is received.
        return mLastAcquire != FaceManager.FACE_ACQUIRED_NOT_DETECTED
                && mLastAcquire != FaceManager.FACE_ACQUIRED_SENSOR_DIRTY;
    }

    @Override
    protected void handleLifecycleAfterAuth(boolean authenticated) {
        // For face, the authentication lifecycle ends either when
        // 1) Authenticated == true
        // 2) Error occurred
        // 3) Authenticated == false
        mCallback.onClientFinished(this, true /* success */);
    }

    @Override
    public void onAuthenticated(BiometricAuthenticator.Identifier identifier,
            boolean authenticated, ArrayList<Byte> token) {
        super.onAuthenticated(identifier, authenticated, token);

        mState = STATE_STOPPED;
        mUsageStats.addEvent(new UsageStats.AuthenticationEvent(
                getStartTimeMs(),
                System.currentTimeMillis() - getStartTimeMs() /* latency */,
                authenticated,
                0 /* error */,
                0 /* vendorError */,
                getTargetUserId()));
    }

    @Override
    public void onError(@BiometricConstants.Errors int error, int vendorCode) {
        mUsageStats.addEvent(new UsageStats.AuthenticationEvent(
                getStartTimeMs(),
                System.currentTimeMillis() - getStartTimeMs() /* latency */,
                false /* authenticated */,
                error,
                vendorCode,
                getTargetUserId()));

        super.onError(error, vendorCode);
    }

    private int[] getAcquireIgnorelist() {
        return isBiometricPrompt() ? mBiometricPromptIgnoreList : mKeyguardIgnoreList;
    }

    private int[] getAcquireVendorIgnorelist() {
        return isBiometricPrompt() ? mBiometricPromptIgnoreListVendor : mKeyguardIgnoreListVendor;
    }

    private boolean shouldSend(int acquireInfo, int vendorCode) {
        if (acquireInfo == FaceManager.FACE_ACQUIRED_VENDOR) {
            return !Utils.listContains(getAcquireVendorIgnorelist(), vendorCode);
        } else {
            return !Utils.listContains(getAcquireIgnorelist(), acquireInfo);
        }
    }

    @Override
    public void onAcquired(int acquireInfo, int vendorCode) {
        mLastAcquire = acquireInfo;

        if (acquireInfo == FaceManager.FACE_ACQUIRED_RECALIBRATE) {
            BiometricNotificationUtils.showReEnrollmentNotification(getContext());
        }

        final boolean shouldSend = shouldSend(acquireInfo, vendorCode);
        onAcquiredInternal(acquireInfo, vendorCode, shouldSend);
    }
}
