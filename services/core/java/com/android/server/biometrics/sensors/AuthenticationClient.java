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

package com.android.server.biometrics.sensors;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityTaskManager;
import android.app.IActivityTaskManager;
import android.app.TaskStackListener;
import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.os.IBinder;
import android.os.RemoteException;
import android.security.KeyStore;
import android.util.Slog;

import java.util.ArrayList;

/**
 * A class to keep track of the authentication state for a given client.
 */
public abstract class AuthenticationClient<T> extends AcquisitionClient<T>
        implements AuthenticationConsumer  {

    private static final String TAG = "Biometrics/AuthenticationClient";

    private final boolean mIsStrongBiometric;
    private final boolean mRequireConfirmation;
    private final IActivityTaskManager mActivityTaskManager;
    @Nullable private final TaskStackListener mTaskStackListener;
    private final LockoutTracker mLockoutTracker;
    private final boolean mIsRestricted;

    protected final long mOperationId;

    private long mStartTimeMs;

    protected boolean mAuthAttempted;

    public AuthenticationClient(@NonNull Context context, @NonNull LazyDaemon<T> lazyDaemon,
            @NonNull IBinder token, @NonNull ClientMonitorCallbackConverter listener,
            int targetUserId, long operationId, boolean restricted, @NonNull String owner,
            int cookie, boolean requireConfirmation, int sensorId, boolean isStrongBiometric,
            int statsModality, int statsClient, @Nullable TaskStackListener taskStackListener,
            @NonNull LockoutTracker lockoutTracker) {
        super(context, lazyDaemon, token, listener, targetUserId, owner, cookie, sensorId,
                statsModality, BiometricsProtoEnums.ACTION_AUTHENTICATE, statsClient);
        mIsStrongBiometric = isStrongBiometric;
        mOperationId = operationId;
        mRequireConfirmation = requireConfirmation;
        mActivityTaskManager = ActivityTaskManager.getService();
        mTaskStackListener = taskStackListener;
        mLockoutTracker = lockoutTracker;
        mIsRestricted = restricted;
    }

    public @LockoutTracker.LockoutMode int handleFailedAttempt(int userId) {
        final @LockoutTracker.LockoutMode int lockoutMode =
                mLockoutTracker.getLockoutModeForUser(userId);
        final PerformanceTracker performanceTracker =
                PerformanceTracker.getInstanceForSensorId(getSensorId());

        if (lockoutMode == LockoutTracker.LOCKOUT_PERMANENT) {
            performanceTracker.incrementPermanentLockoutForUser(userId);
        } else if (lockoutMode == LockoutTracker.LOCKOUT_TIMED) {
            performanceTracker.incrementTimedLockoutForUser(userId);
        }

        return lockoutMode;
    }

    protected long getStartTimeMs() {
        return mStartTimeMs;
    }

    @Override
    public void binderDied() {
        final boolean clearListener = !isBiometricPrompt();
        binderDiedInternal(clearListener);
    }

    public boolean isBiometricPrompt() {
        return getCookie() != 0;
    }

    @Override
    protected boolean isCryptoOperation() {
        return mOperationId != 0;
    }

    @Override
    public void onAuthenticated(BiometricAuthenticator.Identifier identifier,
            boolean authenticated, ArrayList<Byte> hardwareAuthToken) {
        super.logOnAuthenticated(getContext(), authenticated, mRequireConfirmation,
                getTargetUserId(), isBiometricPrompt());

        final ClientMonitorCallbackConverter listener = getListener();

        try {
            if (DEBUG) Slog.v(TAG, "onAuthenticated(" + authenticated + ")"
                    + ", ID:" + identifier.getBiometricId()
                    + ", Owner: " + getOwnerString()
                    + ", isBP: " + isBiometricPrompt()
                    + ", listener: " + listener
                    + ", requireConfirmation: " + mRequireConfirmation
                    + ", user: " + getTargetUserId());

            final PerformanceTracker pm = PerformanceTracker.getInstanceForSensorId(getSensorId());
            if (isCryptoOperation()) {
                pm.incrementCryptoAuthForUser(getTargetUserId(), authenticated);
            } else {
                pm.incrementAuthForUser(getTargetUserId(), authenticated);
            }

            if (authenticated) {
                mAlreadyDone = true;

                if (listener != null) {
                    vibrateSuccess();
                }

                if (mTaskStackListener != null) {
                    try {
                        mActivityTaskManager.unregisterTaskStackListener(mTaskStackListener);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Could not unregister task stack listener", e);
                    }
                }

                final byte[] byteToken = new byte[hardwareAuthToken.size()];
                for (int i = 0; i < hardwareAuthToken.size(); i++) {
                    byteToken[i] = hardwareAuthToken.get(i);
                }
                if (isBiometricPrompt() && listener != null) {
                    // BiometricService will add the token to keystore
                    listener.onAuthenticationSucceeded(getSensorId(), identifier, byteToken,
                            getTargetUserId(), mIsStrongBiometric);
                } else if (!isBiometricPrompt() && listener != null) {
                    if (mIsStrongBiometric) {
                        KeyStore.getInstance().addAuthToken(byteToken);
                    } else {
                        Slog.d(TAG, "Skipping addAuthToken");
                    }

                    // Explicitly have if/else here to make it super obvious in case the code is
                    // touched in the future.
                    if (!mIsRestricted) {
                        listener.onAuthenticationSucceeded(getSensorId(), identifier, byteToken,
                                getTargetUserId(), mIsStrongBiometric);
                    } else {
                        listener.onAuthenticationSucceeded(getSensorId(), null /* identifier */,
                                byteToken, getTargetUserId(), mIsStrongBiometric);
                    }

                } else {
                    // Client not listening
                    Slog.w(TAG, "Client not listening");
                }
            } else {
                if (listener != null) {
                    vibrateError();
                }

                // Allow system-defined limit of number of attempts before giving up
                final @LockoutTracker.LockoutMode int lockoutMode =
                        handleFailedAttempt(getTargetUserId());
                if (lockoutMode == LockoutTracker.LOCKOUT_NONE) {
                    // Don't send onAuthenticationFailed if we're in lockout, it causes a
                    // janky UI on Keyguard/BiometricPrompt since "authentication failed"
                    // will show briefly and be replaced by "device locked out" message.
                    if (listener != null) {
                        listener.onAuthenticationFailed(getSensorId());
                    }
                }
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to notify listener, finishing", e);
            mFinishCallback.onClientFinished(this, false /* success */);
        }
    }

    @Override
    public void onAcquired(int acquiredInfo, int vendorCode) {
        super.onAcquired(acquiredInfo, vendorCode);

        final @LockoutTracker.LockoutMode int lockoutMode =
                mLockoutTracker.getLockoutModeForUser(getTargetUserId());
        if (lockoutMode == LockoutTracker.LOCKOUT_NONE) {
            PerformanceTracker pt = PerformanceTracker.getInstanceForSensorId(getSensorId());
            pt.incrementAcquireForUser(getTargetUserId(), isCryptoOperation());
        }
    }

    /**
     * Start authentication
     */
    @Override
    public void start(@NonNull FinishCallback finishCallback) {
        super.start(finishCallback);

        final @LockoutTracker.LockoutMode int lockoutMode =
                mLockoutTracker.getLockoutModeForUser(getTargetUserId());
        if (lockoutMode != LockoutTracker.LOCKOUT_NONE) {
            Slog.v(TAG, "In lockout mode(" + lockoutMode + ") ; disallowing authentication");
            int errorCode = lockoutMode == LockoutTracker.LOCKOUT_TIMED
                    ? BiometricConstants.BIOMETRIC_ERROR_LOCKOUT
                    : BiometricConstants.BIOMETRIC_ERROR_LOCKOUT_PERMANENT;
            onError(errorCode, 0 /* vendorCode */);
            return;
        }

        if (mTaskStackListener != null) {
            try {
                mActivityTaskManager.registerTaskStackListener(mTaskStackListener);
            } catch (RemoteException e) {
                Slog.e(TAG, "Could not register task stack listener", e);
            }
        }

        if (DEBUG) Slog.w(TAG, "Requesting auth for " + getOwnerString());

        mStartTimeMs = System.currentTimeMillis();
        mAuthAttempted = true;
        startHalOperation();
    }

    @Override
    public void cancel() {
        super.cancel();

        if (mTaskStackListener != null) {
            try {
                mActivityTaskManager.unregisterTaskStackListener(mTaskStackListener);
            } catch (RemoteException e) {
                Slog.e(TAG, "Could not unregister task stack listener", e);
            }
        }
    }
}
