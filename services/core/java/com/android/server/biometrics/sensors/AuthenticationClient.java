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
public abstract class AuthenticationClient extends AcquisitionClient {

    private static final String TAG = "Biometrics/AuthenticationClient";

    private final boolean mIsStrongBiometric;
    private final boolean mRequireConfirmation;
    private final IActivityTaskManager mActivityTaskManager;
    private final TaskStackListener mTaskStackListener;
    private final LockoutTracker mLockoutTracker;

    protected final long mOperationId;

    private long mStartTimeMs;

    public AuthenticationClient(@NonNull Context context, @NonNull IBinder token,
            @NonNull ClientMonitorCallbackConverter listener, int targetUserId, int groupId,
            long operationId, boolean restricted, @NonNull String owner, int cookie,
            boolean requireConfirmation, int sensorId, boolean isStrongBiometric, int statsModality,
            int statsClient, @NonNull TaskStackListener taskStackListener,
            @NonNull LockoutTracker lockoutTracker) {
        super(context, token, listener, targetUserId, groupId, restricted, owner, cookie, sensorId,
                statsModality, BiometricsProtoEnums.ACTION_AUTHENTICATE, statsClient);
        mIsStrongBiometric = isStrongBiometric;
        mOperationId = operationId;
        mRequireConfirmation = requireConfirmation;
        mActivityTaskManager = ActivityTaskManager.getService();
        mTaskStackListener = taskStackListener;
        mLockoutTracker = lockoutTracker;
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

    public boolean onAuthenticated(BiometricAuthenticator.Identifier identifier,
            boolean authenticated, ArrayList<Byte> token) {
        super.logOnAuthenticated(getContext(), authenticated, mRequireConfirmation,
                getTargetUserId(), isBiometricPrompt());

        final ClientMonitorCallbackConverter listener = getListener();

        boolean result = false;

        try {
            if (DEBUG) Slog.v(TAG, "onAuthenticated(" + authenticated + ")"
                    + ", ID:" + identifier.getBiometricId()
                    + ", Owner: " + getOwnerString()
                    + ", isBP: " + isBiometricPrompt()
                    + ", listener: " + listener
                    + ", requireConfirmation: " + mRequireConfirmation
                    + ", user: " + getTargetUserId());

            if (authenticated) {
                mAlreadyDone = true;

                if (listener != null) {
                    vibrateSuccess();
                }
                result = true;

                try {
                    mActivityTaskManager.unregisterTaskStackListener(mTaskStackListener);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Could not unregister task stack listener", e);
                }

                final byte[] byteToken = new byte[token.size()];
                for (int i = 0; i < token.size(); i++) {
                    byteToken[i] = token.get(i);
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

                    try {
                        // Explicitly have if/else here to make it super obvious in case the code is
                        // touched in the future.
                        if (!getIsRestricted()) {
                            listener.onAuthenticationSucceeded(getSensorId(), identifier, byteToken,
                                    getTargetUserId(), mIsStrongBiometric);
                        } else {
                            listener.onAuthenticationSucceeded(getSensorId(), null /* identifier */,
                                    byteToken, getTargetUserId(), mIsStrongBiometric);
                        }
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Remote exception", e);
                    }
                } else {
                    // Client not listening
                    Slog.w(TAG, "Client not listening");
                    result = true;
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
                result = lockoutMode != LockoutTracker.LOCKOUT_NONE; // in a lockout mode
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
            result = true;
        }
        return result;
    }

    /**
     * Start authentication
     */
    @Override
    public int start() {
        try {
            mActivityTaskManager.registerTaskStackListener(mTaskStackListener);
        } catch (RemoteException e) {
            Slog.e(TAG, "Could not register task stack listener", e);
        }

        try {
            mStartTimeMs = System.currentTimeMillis();
            final int result = startHalOperation();
            if (result != 0) {
                Slog.w(TAG, "startAuthentication failed, result=" + result);
                onError(BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE, 0 /* vendorCode */);
                return result;
            }
            if (DEBUG) Slog.w(TAG, "client " + getOwnerString() + " is authenticating...");
        } catch (RemoteException e) {
            Slog.e(TAG, "startAuthentication failed", e);
            return BiometricConstants.BIOMETRIC_ERROR_UNABLE_TO_PROCESS;
        }
        return 0; // success
    }

    @Override
    public int stop(boolean initiatedByClient) {
        if (mAlreadyCancelled) {
            Slog.w(TAG, "stopAuthentication: already cancelled!");
            return 0;
        }

        try {
            mActivityTaskManager.unregisterTaskStackListener(mTaskStackListener);
        } catch (RemoteException e) {
            Slog.e(TAG, "Could not unregister task stack listener", e);
        }

        try {
            final int result = stopHalOperation();
            if (result != 0) {
                Slog.w(TAG, "stopAuthentication failed, result=" + result);
                return result;
            }
            if (DEBUG) Slog.w(TAG, "client " + getOwnerString() +
                    " is no longer authenticating");
        } catch (RemoteException e) {
            Slog.e(TAG, "stopAuthentication failed", e);
            return BiometricConstants.BIOMETRIC_ERROR_UNABLE_TO_PROCESS;
        }

        mAlreadyCancelled = true;
        return 0; // success
    }
}
