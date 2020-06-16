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

import android.app.IActivityTaskManager;
import android.app.TaskStackListener;
import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.security.KeyStore;
import android.util.Slog;
import android.view.Surface;

import java.util.ArrayList;

/**
 * A class to keep track of the authentication state for a given client.
 */
public abstract class AuthenticationClient extends ClientMonitor {

    public abstract int handleFailedAttempt(int userId);
    public void resetFailedAttempts(int userId) {}

    public static final int LOCKOUT_NONE = 0;
    public static final int LOCKOUT_TIMED = 1;
    public static final int LOCKOUT_PERMANENT = 2;

    private final boolean mIsStrongBiometric;
    private final long mOpId;
    private final boolean mShouldFrameworkHandleLockout;
    private final boolean mRequireConfirmation;
    private final IActivityTaskManager mActivityTaskManager;
    private final TaskStackListener mTaskStackListener;
    private final PowerManager mPowerManager;
    private final Surface mSurface;

    // We need to track this state since it's possible for applications to request for
    // authentication while the device is already locked out. In that case, the client is created
    // but not started yet. The user shouldn't receive the error haptics in this case.
    private boolean mStarted;
    private long mStartTimeMs;

    /**
     * This method is called when authentication starts.
     */
    private void onStart() {
        try {
            mActivityTaskManager.registerTaskStackListener(mTaskStackListener);
        } catch (RemoteException e) {
            Slog.e(getLogTag(), "Could not register task stack listener", e);
        }
    }

    /**
     * This method is called when a biometric is authenticated or authentication is stopped
     * (cancelled by the user, or an error such as lockout has occurred).
     */
    private void onStop() {
        try {
            mActivityTaskManager.unregisterTaskStackListener(mTaskStackListener);
        } catch (RemoteException e) {
            Slog.e(getLogTag(), "Could not unregister task stack listener", e);
        }
    }

    public abstract boolean wasUserDetected();

    public AuthenticationClient(Context context, Constants constants,
            BiometricServiceBase.DaemonWrapper daemon, IBinder token,
            ClientMonitorCallbackConverter listener, int targetUserId, int groupId, long opId,
            boolean shouldFrameworkHandleLockout, boolean restricted, String owner, int cookie,
            boolean requireConfirmation, int sensorId, boolean isStrongBiometric, int statsModality,
            int statsClient, IActivityTaskManager activityTaskManager,
            TaskStackListener taskStackListener, PowerManager powerManager, Surface surface) {
        super(context, constants, daemon, token, listener, targetUserId, groupId,
                restricted, owner, cookie, sensorId, statsModality,
                BiometricsProtoEnums.ACTION_AUTHENTICATE, statsClient);
        mIsStrongBiometric = isStrongBiometric;
        mOpId = opId;
        mShouldFrameworkHandleLockout = shouldFrameworkHandleLockout;
        mRequireConfirmation = requireConfirmation;
        mActivityTaskManager = activityTaskManager;
        mTaskStackListener = taskStackListener;
        mPowerManager = powerManager;
        mSurface = surface;
    }

    protected long getStartTimeMs() {
        return mStartTimeMs;
    }

    @Override
    public void notifyUserActivity() {
        long now = SystemClock.uptimeMillis();
        mPowerManager.userActivity(now, PowerManager.USER_ACTIVITY_EVENT_TOUCH, 0);
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
        return mOpId != 0;
    }

    @Override
    public boolean onError(int error, int vendorCode) {
        if (!mShouldFrameworkHandleLockout) {
            switch (error) {
                case BiometricConstants.BIOMETRIC_ERROR_TIMEOUT:
                    if (!wasUserDetected() && !isBiometricPrompt()) {
                        // No vibration if user was not detected on keyguard
                        break;
                    }
                case BiometricConstants.BIOMETRIC_ERROR_LOCKOUT:
                case BiometricConstants.BIOMETRIC_ERROR_LOCKOUT_PERMANENT:
                    if (mStarted) {
                        vibrateError();
                    }
                    break;
                default:
                    break;
            }
        }
        return super.onError(error, vendorCode);
    }

    @Override
    public boolean onAuthenticated(BiometricAuthenticator.Identifier identifier,
            boolean authenticated, ArrayList<Byte> token) {
        super.logOnAuthenticated(getContext(), authenticated, mRequireConfirmation,
                getTargetUserId(), isBiometricPrompt());

        final ClientMonitorCallbackConverter listener = getListener();

        mMetricsLogger.action(mConstants.actionBiometricAuth(), authenticated);
        boolean result = false;

        try {
            if (DEBUG) Slog.v(getLogTag(), "onAuthenticated(" + authenticated + ")"
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
                if (mShouldFrameworkHandleLockout) {
                    resetFailedAttempts(getTargetUserId());
                }
                onStop();

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
                        Slog.d(getLogTag(), "Skipping addAuthToken");
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
                        Slog.e(getLogTag(), "Remote exception", e);
                    }
                } else {
                    // Client not listening
                    Slog.w(getLogTag(), "Client not listening");
                    result = true;
                }
            } else {
                if (listener != null) {
                    vibrateError();
                }

                // Allow system-defined limit of number of attempts before giving up
                final int lockoutMode = handleFailedAttempt(getTargetUserId());
                if (lockoutMode != LOCKOUT_NONE && mShouldFrameworkHandleLockout) {
                    Slog.w(getLogTag(), "Forcing lockout (driver code should do this!), mode("
                            + lockoutMode + ")");
                    stop(false);
                    final int errorCode = lockoutMode == LOCKOUT_TIMED
                            ? BiometricConstants.BIOMETRIC_ERROR_LOCKOUT
                            : BiometricConstants.BIOMETRIC_ERROR_LOCKOUT_PERMANENT;
                    onError(errorCode, 0 /* vendorCode */);
                } else {
                    // Don't send onAuthenticationFailed if we're in lockout, it causes a
                    // janky UI on Keyguard/BiometricPrompt since "authentication failed"
                    // will show briefly and be replaced by "device locked out" message.
                    if (listener != null) {
                        listener.onAuthenticationFailed(getSensorId());
                    }
                }
                result = lockoutMode != LOCKOUT_NONE; // in a lockout mode
            }
        } catch (RemoteException e) {
            Slog.e(getLogTag(), "Remote exception", e);
            result = true;
        }
        return result;
    }

    /**
     * Start authentication
     */
    @Override
    public int start() {
        mStarted = true;
        onStart();
        try {
            mStartTimeMs = System.currentTimeMillis();
            final int result = getDaemonWrapper().authenticate(mOpId, getGroupId(), mSurface);
            if (result != 0) {
                Slog.w(getLogTag(), "startAuthentication failed, result=" + result);
                mMetricsLogger.histogram(mConstants.tagAuthStartError(), result);
                onError(BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE, 0 /* vendorCode */);
                return result;
            }
            if (DEBUG) Slog.w(getLogTag(), "client " + getOwnerString() + " is authenticating...");
        } catch (RemoteException e) {
            Slog.e(getLogTag(), "startAuthentication failed", e);
            return ERROR_ESRCH;
        }
        return 0; // success
    }

    @Override
    public int stop(boolean initiatedByClient) {
        if (mAlreadyCancelled) {
            Slog.w(getLogTag(), "stopAuthentication: already cancelled!");
            return 0;
        }

        mStarted = false;

        onStop();

        try {
            final int result = getDaemonWrapper().cancel();
            if (result != 0) {
                Slog.w(getLogTag(), "stopAuthentication failed, result=" + result);
                return result;
            }
            if (DEBUG) Slog.w(getLogTag(), "client " + getOwnerString() +
                    " is no longer authenticating");
        } catch (RemoteException e) {
            Slog.e(getLogTag(), "stopAuthentication failed", e);
            return ERROR_ESRCH;
        }

        mAlreadyCancelled = true;
        return 0; // success
    }

    @Override
    public boolean onEnrollResult(BiometricAuthenticator.Identifier identifier,
            int remaining) {
        if (DEBUG) Slog.w(getLogTag(), "onEnrollResult() called for authenticate!");
        return true; // Invalid for Authenticate
    }

    @Override
    public boolean onRemoved(BiometricAuthenticator.Identifier identifier, int remaining) {
        if (DEBUG) Slog.w(getLogTag(), "onRemoved() called for authenticate!");
        return true; // Invalid for Authenticate
    }

    @Override
    public boolean onEnumerationResult(BiometricAuthenticator.Identifier identifier,
            int remaining) {
        if (DEBUG) Slog.w(getLogTag(), "onEnumerationResult() called for authenticate!");
        return true; // Invalid for Authenticate
    }
}
