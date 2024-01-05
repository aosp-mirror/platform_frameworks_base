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

import static android.adaptiveauth.Flags.reportBiometricAuthAttempts;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.NotificationManager;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.SensorPrivacyManager;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricFaceConstants;
import android.hardware.biometrics.BiometricManager.Authenticators;
import android.hardware.biometrics.common.ICancellationSignal;
import android.hardware.biometrics.face.IFace;
import android.hardware.face.FaceAuthenticateOptions;
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
import com.android.server.biometrics.log.OperationContextExt;
import com.android.server.biometrics.sensors.AuthSessionCoordinator;
import com.android.server.biometrics.sensors.AuthenticationClient;
import com.android.server.biometrics.sensors.AuthenticationStateListeners;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.ClientMonitorCompositeCallback;
import com.android.server.biometrics.sensors.LockoutConsumer;
import com.android.server.biometrics.sensors.LockoutTracker;
import com.android.server.biometrics.sensors.PerformanceTracker;
import com.android.server.biometrics.sensors.face.UsageStats;

import java.util.ArrayList;
import java.util.function.Supplier;

/**
 * Face-specific authentication client for the {@link IFace} AIDL HAL interface.
 */
public class FaceAuthenticationClient
        extends AuthenticationClient<AidlSession, FaceAuthenticateOptions>
        implements LockoutConsumer {
    private static final String TAG = "FaceAuthenticationClient";

    @NonNull
    private final UsageStats mUsageStats;
    @NonNull
    private final AuthSessionCoordinator mAuthSessionCoordinator;
    @Nullable
    private final NotificationManager mNotificationManager;
    private final int[] mBiometricPromptIgnoreList;
    private final int[] mBiometricPromptIgnoreListVendor;
    private final int[] mKeyguardIgnoreList;
    private final int[] mKeyguardIgnoreListVendor;
    @Nullable
    private ICancellationSignal mCancellationSignal;
    @Nullable
    private final SensorPrivacyManager mSensorPrivacyManager;
    @NonNull
    private final AuthenticationStateListeners mAuthenticationStateListeners;
    @FaceManager.FaceAcquired
    private int mLastAcquire = FaceManager.FACE_ACQUIRED_UNKNOWN;

    public FaceAuthenticationClient(@NonNull Context context,
            @NonNull Supplier<AidlSession> lazyDaemon,
            @NonNull IBinder token, long requestId,
            @NonNull ClientMonitorCallbackConverter listener, long operationId,
            boolean restricted, @NonNull FaceAuthenticateOptions options, int cookie,
            boolean requireConfirmation,
            @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext,
            boolean isStrongBiometric, @NonNull UsageStats usageStats,
            @NonNull LockoutTracker lockoutCache, boolean allowBackgroundAuthentication,
            @Authenticators.Types int sensorStrength,
            @NonNull AuthenticationStateListeners authenticationStateListeners) {
        this(context, lazyDaemon, token, requestId, listener, operationId,
                restricted, options, cookie, requireConfirmation, logger, biometricContext,
                isStrongBiometric, usageStats, lockoutCache, allowBackgroundAuthentication,
                context.getSystemService(SensorPrivacyManager.class), sensorStrength,
                authenticationStateListeners);
    }

    @VisibleForTesting
    FaceAuthenticationClient(@NonNull Context context,
            @NonNull Supplier<AidlSession> lazyDaemon,
            @NonNull IBinder token, long requestId,
            @NonNull ClientMonitorCallbackConverter listener, long operationId,
            boolean restricted, @NonNull FaceAuthenticateOptions options, int cookie,
            boolean requireConfirmation,
            @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext,
            boolean isStrongBiometric, @NonNull UsageStats usageStats,
            @NonNull LockoutTracker lockoutTracker, boolean allowBackgroundAuthentication,
            SensorPrivacyManager sensorPrivacyManager,
            @Authenticators.Types int biometricStrength,
            @NonNull AuthenticationStateListeners authenticationStateListeners) {
        super(context, lazyDaemon, token, listener, operationId, restricted,
                options, cookie, requireConfirmation, logger, biometricContext,
                isStrongBiometric, null /* taskStackListener */, lockoutTracker,
                allowBackgroundAuthentication, false /* shouldVibrate */,
                biometricStrength);
        setRequestId(requestId);
        mUsageStats = usageStats;
        mNotificationManager = context.getSystemService(NotificationManager.class);
        mSensorPrivacyManager = sensorPrivacyManager;
        mAuthSessionCoordinator = biometricContext.getAuthSessionCoordinator();
        mAuthenticationStateListeners = authenticationStateListeners;

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
            final OperationContextExt opContext = getOperationContext();
            final ICancellationSignal cancel = session.getSession().authenticateWithContext(
                    mOperationId, opContext.toAidlContext(getOptions()));
            getBiometricContext().subscribe(opContext, ctx -> {
                try {
                    session.getSession().onContextChanged(ctx);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Unable to notify context changed", e);
                }
            });
            return cancel;
        } else {
            return session.getSession().authenticate(mOperationId);
        }
    }

    @Override
    protected void stopHalOperation() {
        unsubscribeBiometricContext();

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
        // 4) onLockout
        // 5) onLockoutTimed
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

        if (reportBiometricAuthAttempts()) {
            if (authenticated) {
                mAuthenticationStateListeners.onAuthenticationSucceeded(getRequestReason(),
                        getTargetUserId());
            } else {
                mAuthenticationStateListeners.onAuthenticationFailed(getRequestReason(),
                        getTargetUserId());
            }
        }
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

        //Check if it is AIDL (lockoutTracker = null) or if it there is no lockout for HIDL
        if (getLockoutTracker() == null || getLockoutTracker().getLockoutModeForUser(
                getTargetUserId()) == LockoutTracker.LOCKOUT_NONE) {
            PerformanceTracker pt = PerformanceTracker.getInstanceForSensorId(getSensorId());
            pt.incrementAcquireForUser(getTargetUserId(), isCryptoOperation());
        }
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
        mAuthSessionCoordinator.lockOutTimed(getTargetUserId(), getSensorStrength(), getSensorId(),
                durationMillis, getRequestId());
        // Lockout metrics are logged as an error code.
        final int error = BiometricFaceConstants.FACE_ERROR_LOCKOUT;
        getLogger().logOnError(getContext(), getOperationContext(),
                error, 0 /* vendorCode */, getTargetUserId());

        PerformanceTracker.getInstanceForSensorId(getSensorId())
                .incrementTimedLockoutForUser(getTargetUserId());

        onError(error, 0 /* vendorCode */);
    }

    @Override
    public void onLockoutPermanent() {
        mAuthSessionCoordinator.lockedOutFor(getTargetUserId(), getSensorStrength(), getSensorId(),
                getRequestId());
        // Lockout metrics are logged as an error code.
        final int error = BiometricFaceConstants.FACE_ERROR_LOCKOUT_PERMANENT;
        getLogger().logOnError(getContext(), getOperationContext(),
                error, 0 /* vendorCode */, getTargetUserId());

        PerformanceTracker.getInstanceForSensorId(getSensorId())
                .incrementPermanentLockoutForUser(getTargetUserId());

        onError(error, 0 /* vendorCode */);
    }
}
