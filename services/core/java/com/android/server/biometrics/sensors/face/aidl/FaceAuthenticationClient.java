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
import android.annotation.Nullable;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricFaceConstants;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.common.ICancellationSignal;
import android.hardware.biometrics.face.IFace;
import android.hardware.biometrics.face.ISession;
import android.hardware.face.FaceAuthenticationFrame;
import android.hardware.face.FaceManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.VibrationEffect;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.R;
import com.android.server.biometrics.Utils;
import com.android.server.biometrics.sensors.AuthenticationClient;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.LockoutCache;
import com.android.server.biometrics.sensors.LockoutConsumer;
import com.android.server.biometrics.sensors.LockoutTracker;
import com.android.server.biometrics.sensors.face.ReEnrollNotificationUtils;
import com.android.server.biometrics.sensors.face.UsageStats;

import java.util.ArrayList;

/**
 * Face-specific authentication client for the {@link IFace} AIDL HAL interface.
 */
class FaceAuthenticationClient extends AuthenticationClient<ISession> implements LockoutConsumer {
    private static final String TAG = "FaceAuthenticationClient";

    @NonNull private final UsageStats mUsageStats;
    @NonNull private final LockoutCache mLockoutCache;
    @Nullable private final NotificationManager mNotificationManager;
    @Nullable private ICancellationSignal mCancellationSignal;

    @NonNull private final ContentResolver mContentResolver;
    private final boolean mCustomHaptics;

    private final int[] mBiometricPromptIgnoreList;
    private final int[] mBiometricPromptIgnoreListVendor;
    private final int[] mKeyguardIgnoreList;
    private final int[] mKeyguardIgnoreListVendor;

    @FaceManager.FaceAcquired private int mLastAcquire = FaceManager.FACE_ACQUIRED_UNKNOWN;

    FaceAuthenticationClient(@NonNull Context context,
            @NonNull LazyDaemon<ISession> lazyDaemon, @NonNull IBinder token,
            @NonNull ClientMonitorCallbackConverter listener, int targetUserId, long operationId,
            boolean restricted, String owner, int cookie, boolean requireConfirmation, int sensorId,
            boolean isStrongBiometric, int statsClient, @NonNull UsageStats usageStats,
            @NonNull LockoutCache lockoutCache, boolean allowBackgroundAuthentication) {
        super(context, lazyDaemon, token, listener, targetUserId, operationId, restricted,
                owner, cookie, requireConfirmation, sensorId, isStrongBiometric,
                BiometricsProtoEnums.MODALITY_FACE, statsClient, null /* taskStackListener */,
                lockoutCache, allowBackgroundAuthentication);
        mUsageStats = usageStats;
        mLockoutCache = lockoutCache;
        mNotificationManager = context.getSystemService(NotificationManager.class);

        final Resources resources = getContext().getResources();
        mBiometricPromptIgnoreList = resources.getIntArray(
                R.array.config_face_acquire_biometricprompt_ignorelist);
        mBiometricPromptIgnoreListVendor = resources.getIntArray(
                R.array.config_face_acquire_vendor_biometricprompt_ignorelist);
        mKeyguardIgnoreList = resources.getIntArray(
                R.array.config_face_acquire_keyguard_ignorelist);
        mKeyguardIgnoreListVendor = resources.getIntArray(
                R.array.config_face_acquire_vendor_keyguard_ignorelist);

        mContentResolver = context.getContentResolver();
        mCustomHaptics = Settings.Global.getInt(mContentResolver,
                "face_custom_success_error", 0) == 1;
    }

    @Override
    protected void startHalOperation() {
        try {
            mCancellationSignal = getFreshDaemon().authenticate(mOperationId);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when requesting auth", e);
            onError(BiometricFaceConstants.FACE_ERROR_HW_UNAVAILABLE, 0 /* vendorCode */);
            mCallback.onClientFinished(this, false /* success */);
        }
    }

    @Override
    protected void stopHalOperation() {
        if (mCancellationSignal != null) {
            try {
                mCancellationSignal.cancel();
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception when requesting cancel", e);
                onError(BiometricFaceConstants.FACE_ERROR_HW_UNAVAILABLE, 0 /* vendorCode */);
                mCallback.onClientFinished(this, false /* success */);
            }
        }
    }

    private boolean wasUserDetected() {
        // Do not provide haptic feedback if the user was not detected, and an error (usually
        // ERROR_TIMEOUT) is received.
        return mLastAcquire != FaceManager.FACE_ACQUIRED_NOT_DETECTED
                && mLastAcquire != FaceManager.FACE_ACQUIRED_SENSOR_DIRTY
                && mLastAcquire != FaceManager.FACE_ACQUIRED_UNKNOWN;
    }

    @Override
    public void onAuthenticated(BiometricAuthenticator.Identifier identifier,
            boolean authenticated, ArrayList<Byte> token) {
        super.onAuthenticated(identifier, authenticated, token);

        mUsageStats.addEvent(new UsageStats.AuthenticationEvent(
                getStartTimeMs(),
                System.currentTimeMillis() - getStartTimeMs() /* latency */,
                authenticated,
                0 /* error */,
                0 /* vendorError */,
                getTargetUserId()));

        // For face, the authentication lifecycle ends either when
        // 1) Authenticated == true
        // 2) Error occurred
        // 3) Authenticated == false
        mCallback.onClientFinished(this, true /* success */);
    }

    @Override
    public void onError(int error, int vendorCode) {
        mUsageStats.addEvent(new UsageStats.AuthenticationEvent(
                getStartTimeMs(),
                System.currentTimeMillis() - getStartTimeMs() /* latency */,
                false /* authenticated */,
                error,
                vendorCode,
                getTargetUserId()));

        switch (error) {
            case BiometricConstants.BIOMETRIC_ERROR_TIMEOUT:
                if (!wasUserDetected() && !isBiometricPrompt()) {
                    // No vibration if user was not detected on keyguard
                    break;
                }
            case BiometricConstants.BIOMETRIC_ERROR_LOCKOUT:
            case BiometricConstants.BIOMETRIC_ERROR_LOCKOUT_PERMANENT:
                if (mAuthAttempted) {
                    // Only vibrate if auth was attempted. If the user was already locked out prior
                    // to starting authentication, do not vibrate.
                    vibrateError();
                }
                break;
            case BiometricConstants.BIOMETRIC_ERROR_RE_ENROLL:
                ReEnrollNotificationUtils.showReEnrollmentNotification(getContext());
                break;
            default:
                break;
        }

        super.onError(error, vendorCode);
    }

    private int[] getAcquireIgnorelist() {
        return isBiometricPrompt() ? mBiometricPromptIgnoreList : mKeyguardIgnoreList;
    }

    private int[] getAcquireVendorIgnorelist() {
        return isBiometricPrompt() ? mBiometricPromptIgnoreListVendor : mKeyguardIgnoreListVendor;
    }

    private boolean shouldSendAcquiredMessage(int acquireInfo, int vendorCode) {
        return acquireInfo == FaceManager.FACE_ACQUIRED_VENDOR
                ? !Utils.listContains(getAcquireVendorIgnorelist(), vendorCode)
                : !Utils.listContains(getAcquireIgnorelist(), acquireInfo);
    }

    @Override
    public void onAcquired(int acquireInfo, int vendorCode) {
        mLastAcquire = acquireInfo;
        final boolean shouldSend = shouldSendAcquiredMessage(acquireInfo, vendorCode);
        onAcquiredInternal(acquireInfo, vendorCode, shouldSend);
    }

    /**
     * Called each time a new frame is received during face authentication.
     *
     * @param frame Information about the current frame.
     */
    public void onAuthenticationFrame(@NonNull FaceAuthenticationFrame frame) {
        // Log acquisition but don't send it to the client yet, since that's handled below.
        final int acquireInfo = frame.getData().getAcquiredInfo();
        final int vendorCode = frame.getData().getVendorCode();
        mLastAcquire = acquireInfo;
        onAcquiredInternal(acquireInfo, vendorCode, false /* shouldSend */);

        final boolean shouldSend = shouldSendAcquiredMessage(acquireInfo, vendorCode);
        if (shouldSend && getListener() != null) {
            try {
                getListener().onAuthenticationFrame(frame);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to send authentication frame", e);
                mCallback.onClientFinished(this, false /* success */);
            }
        }
    }

    @Override public void onLockoutTimed(long durationMillis) {
        mLockoutCache.setLockoutModeForUser(getTargetUserId(), LockoutTracker.LOCKOUT_TIMED);
        // Lockout metrics are logged as an error code.
        final int error = BiometricFaceConstants.FACE_ERROR_LOCKOUT;
        logOnError(getContext(), error, 0 /* vendorCode */, getTargetUserId());

        try {
            getListener().onError(getSensorId(), getCookie(), error, 0 /* vendorCode */);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }
    }

    @Override public void onLockoutPermanent() {
        mLockoutCache.setLockoutModeForUser(getTargetUserId(), LockoutTracker.LOCKOUT_PERMANENT);
        // Lockout metrics are logged as an error code.
        final int error = BiometricFaceConstants.FACE_ERROR_LOCKOUT_PERMANENT;
        logOnError(getContext(), error, 0 /* vendorCode */, getTargetUserId());

        try {
            getListener().onError(getSensorId(), getCookie(), error, 0 /* vendorCode */);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }
    }

    @Override
    protected @NonNull VibrationEffect getSuccessVibrationEffect() {
        if (!mCustomHaptics) {
            return super.getSuccessVibrationEffect();
        }

        return getVibration(Settings.Global.getString(mContentResolver,
                "face_success_type"), super.getSuccessVibrationEffect());
    }

    @Override
    protected @NonNull VibrationEffect getErrorVibrationEffect() {
        if (!mCustomHaptics) {
            return super.getErrorVibrationEffect();
        }

        return getVibration(Settings.Global.getString(mContentResolver,
                "face_error_type"), super.getErrorVibrationEffect());
    }
}
