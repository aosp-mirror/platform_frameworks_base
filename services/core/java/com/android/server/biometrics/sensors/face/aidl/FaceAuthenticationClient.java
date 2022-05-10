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
import android.content.Context;
import android.content.res.Resources;
import android.hardware.SensorPrivacyManager;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricFaceConstants;
import android.hardware.biometrics.common.ICancellationSignal;
import android.hardware.biometrics.face.IFace;
import android.hardware.face.FaceAuthenticationFrame;
import android.hardware.face.FaceManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.biometrics.Utils;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.sensors.AuthenticationClient;
import com.android.server.biometrics.sensors.BiometricNotificationUtils;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.ClientMonitorCompositeCallback;
import com.android.server.biometrics.sensors.LockoutCache;
import com.android.server.biometrics.sensors.LockoutConsumer;
import com.android.server.biometrics.sensors.LockoutTracker;
import com.android.server.biometrics.sensors.face.UsageStats;

import java.util.ArrayList;
import java.util.function.Supplier;

/**
 * Face-specific authentication client for the {@link IFace} AIDL HAL interface.
 */
class FaceAuthenticationClient extends AuthenticationClient<AidlSession>
        implements LockoutConsumer {
    private static final String TAG = "FaceAuthenticationClient";

    @NonNull private final UsageStats mUsageStats;
    @NonNull private final LockoutCache mLockoutCache;
    @Nullable private final NotificationManager mNotificationManager;
    @Nullable private ICancellationSignal mCancellationSignal;
    @Nullable private SensorPrivacyManager mSensorPrivacyManager;

    private final int[] mBiometricPromptIgnoreList;
    private final int[] mBiometricPromptIgnoreListVendor;
    private final int[] mKeyguardIgnoreList;
    private final int[] mKeyguardIgnoreListVendor;

    @FaceManager.FaceAcquired private int mLastAcquire = FaceManager.FACE_ACQUIRED_UNKNOWN;

    FaceAuthenticationClient(@NonNull Context context,
            @NonNull Supplier<AidlSession> lazyDaemon,
            @NonNull IBinder token, long requestId,
            @NonNull ClientMonitorCallbackConverter listener, int targetUserId, long operationId,
            boolean restricted, String owner, int cookie, boolean requireConfirmation, int sensorId,
            @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext,
            boolean isStrongBiometric, @NonNull UsageStats usageStats,
            @NonNull LockoutCache lockoutCache, boolean allowBackgroundAuthentication,
            boolean isKeyguardBypassEnabled) {
        this(context, lazyDaemon, token, requestId, listener, targetUserId, operationId,
                restricted, owner, cookie, requireConfirmation, sensorId, logger, biometricContext,
                isStrongBiometric, usageStats, lockoutCache, allowBackgroundAuthentication,
                isKeyguardBypassEnabled, context.getSystemService(SensorPrivacyManager.class));
    }

    @VisibleForTesting
    FaceAuthenticationClient(@NonNull Context context,
            @NonNull Supplier<AidlSession> lazyDaemon,
            @NonNull IBinder token, long requestId,
            @NonNull ClientMonitorCallbackConverter listener, int targetUserId, long operationId,
            boolean restricted, String owner, int cookie, boolean requireConfirmation, int sensorId,
            @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext,
            boolean isStrongBiometric, @NonNull UsageStats usageStats,
            @NonNull LockoutCache lockoutCache, boolean allowBackgroundAuthentication,
            boolean isKeyguardBypassEnabled, SensorPrivacyManager sensorPrivacyManager) {
        super(context, lazyDaemon, token, listener, targetUserId, operationId, restricted,
                owner, cookie, requireConfirmation, sensorId, logger, biometricContext,
                isStrongBiometric, null /* taskStackListener */, lockoutCache,
                allowBackgroundAuthentication, true /* shouldVibrate */,
                isKeyguardBypassEnabled);
        setRequestId(requestId);
        mUsageStats = usageStats;
        mLockoutCache = lockoutCache;
        mNotificationManager = context.getSystemService(NotificationManager.class);
        mSensorPrivacyManager = sensorPrivacyManager;

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
                getLogger().createALSCallback(true /* startWithClient */), callback);
    }

    @Override
    protected void startHalOperation() {
        try {
            if (mSensorPrivacyManager != null
                    && mSensorPrivacyManager
                    .isSensorPrivacyEnabled(SensorPrivacyManager.TOGGLE_TYPE_SOFTWARE,
                    SensorPrivacyManager.Sensors.CAMERA)) {
                onError(BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE,
                        0 /* vendorCode */);
                mCallback.onClientFinished(this, false /* success */);
            } else {
                mCancellationSignal = doAuthenticate();
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when requesting auth", e);
            onError(BiometricFaceConstants.FACE_ERROR_HW_UNAVAILABLE, 0 /* vendorCode */);
            mCallback.onClientFinished(this, false /* success */);
        }
    }

    private ICancellationSignal doAuthenticate() throws RemoteException {
        final AidlSession session = getFreshDaemon();

        if (session.hasContextMethods()) {
            return session.getSession().authenticateWithContext(
                    mOperationId, getOperationContext());
        } else {
            return session.getSession().authenticate(mOperationId);
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

    @Override
    public boolean wasUserDetected() {
        // Do not provide haptic feedback if the user was not detected, and an error (usually
        // ERROR_TIMEOUT) is received.
        return mLastAcquire != FaceManager.FACE_ACQUIRED_NOT_DETECTED
                && mLastAcquire != FaceManager.FACE_ACQUIRED_SENSOR_DIRTY
                && mLastAcquire != FaceManager.FACE_ACQUIRED_UNKNOWN;
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

        if (error == BiometricConstants.BIOMETRIC_ERROR_RE_ENROLL) {
            BiometricNotificationUtils.showReEnrollmentNotification(getContext());
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

    @Override
    public void onLockoutTimed(long durationMillis) {
        super.onLockoutTimed(durationMillis);
        mLockoutCache.setLockoutModeForUser(getTargetUserId(), LockoutTracker.LOCKOUT_TIMED);
        // Lockout metrics are logged as an error code.
        final int error = BiometricFaceConstants.FACE_ERROR_LOCKOUT;
        getLogger().logOnError(getContext(), getOperationContext(),
                error, 0 /* vendorCode */, getTargetUserId());

        try {
            getListener().onError(getSensorId(), getCookie(), error, 0 /* vendorCode */);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }
    }

    @Override
    public void onLockoutPermanent() {
        super.onLockoutPermanent();
        mLockoutCache.setLockoutModeForUser(getTargetUserId(), LockoutTracker.LOCKOUT_PERMANENT);
        // Lockout metrics are logged as an error code.
        final int error = BiometricFaceConstants.FACE_ERROR_LOCKOUT_PERMANENT;
        getLogger().logOnError(getContext(), getOperationContext(),
                error, 0 /* vendorCode */, getTargetUserId());

        try {
            getListener().onError(getSensorId(), getCookie(), error, 0 /* vendorCode */);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }
    }
}
