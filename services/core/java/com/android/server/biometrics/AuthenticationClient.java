/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.biometrics;

import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.IBiometricPromptReceiver;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.statusbar.IStatusBarService;

/**
 * A class to keep track of the authentication state for a given client.
 */
public abstract class AuthenticationClient extends ClientMonitor {
    private long mOpId;
    private Handler mHandler;

    public abstract int handleFailedAttempt();
    public abstract void resetFailedAttempts();
    public abstract String getErrorString(int error, int vendorCode);
    public abstract String getAcquiredString(int acquireInfo, int vendorCode);
    /**
      * @return one of {@link #TYPE_FINGERPRINT} {@link #TYPE_IRIS} or {@link #TYPE_FACE}
      */
    public abstract int getBiometricType();

    public static final int LOCKOUT_NONE = 0;
    public static final int LOCKOUT_TIMED = 1;
    public static final int LOCKOUT_PERMANENT = 2;

    // Callback mechanism received from the client
    // (BiometricPrompt -> BiometricPromptService -> <Biometric>Service -> AuthenticationClient)
    private IBiometricPromptReceiver mDialogReceiverFromClient;
    private Bundle mBundle;
    private IStatusBarService mStatusBarService;
    private boolean mInLockout;
    protected boolean mDialogDismissed;

    // Receives events from SystemUI and handles them before forwarding them to BiometricDialog
    protected IBiometricPromptReceiver mDialogReceiver = new IBiometricPromptReceiver.Stub() {
        @Override // binder call
        public void onDialogDismissed(int reason) {
            if (mBundle != null && mDialogReceiverFromClient != null) {
                try {
                    mDialogReceiverFromClient.onDialogDismissed(reason);
                    if (reason == BiometricPrompt.DISMISSED_REASON_USER_CANCEL) {
                        onError(getHalDeviceId(), BiometricConstants.BIOMETRIC_ERROR_USER_CANCELED,
                                0 /* vendorCode */);
                    }
                    mDialogDismissed = true;
                } catch (RemoteException e) {
                    Slog.e(getLogTag(), "Unable to notify dialog dismissed", e);
                }
                stop(true /* initiatedByClient */);
            }
        }
    };

    /**
     * This method is called when authentication starts.
     */
    public abstract void onStart();

    /**
     * This method is called when a biometric is authenticated or authentication is stopped
     * (cancelled by the user, or an error such as lockout has occurred).
     */
    public abstract void onStop();

    public AuthenticationClient(Context context, Metrics metrics,
            BiometricService.DaemonWrapper daemon, long halDeviceId, IBinder token,
            BiometricService.ServiceListener listener, int targetUserId, int groupId, long opId,
            boolean restricted, String owner, Bundle bundle,
            IBiometricPromptReceiver dialogReceiver, IStatusBarService statusBarService) {
        super(context, metrics, daemon, halDeviceId, token, listener, targetUserId, groupId,
                restricted, owner);
        mOpId = opId;
        mBundle = bundle;
        mDialogReceiverFromClient = dialogReceiver;
        mStatusBarService = statusBarService;
        mHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public void binderDied() {
        super.binderDied();
        // When the binder dies, we should stop the client. This probably belongs in
        // ClientMonitor's binderDied(), but testing all the cases would be tricky.
        // AuthenticationClient is the most user-visible case.
        stop(false /* initiatedByClient */);
    }

    @Override
    public boolean onAcquired(int acquiredInfo, int vendorCode) {
        // If the dialog is showing, the client doesn't need to receive onAcquired messages.
        if (mBundle != null) {
            try {
                if (acquiredInfo != BiometricConstants.BIOMETRIC_ACQUIRED_GOOD) {
                    mStatusBarService.onBiometricHelp(getAcquiredString(acquiredInfo, vendorCode));
                }
                return false; // acquisition continues
            } catch (RemoteException e) {
                Slog.e(getLogTag(), "Remote exception when sending acquired message", e);
                return true; // client failed
            } finally {
                // Good scans will keep the device awake
                if (acquiredInfo == BiometricConstants.BIOMETRIC_ACQUIRED_GOOD) {
                    notifyUserActivity();
                }
            }
        } else {
            return super.onAcquired(acquiredInfo, vendorCode);
        }
    }

    @Override
    public boolean onError(long deviceId, int error, int vendorCode) {
        if (mDialogDismissed) {
            // If user cancels authentication, the application has already received the
            // ERROR_USER_CANCELED message from onDialogDismissed()
            // and stopped the biometric hardware, so there is no need to send a
            // ERROR_CANCELED message.
            return true;
        }
        if (mBundle != null) {
            try {
                mStatusBarService.onBiometricError(getErrorString(error, vendorCode));
            } catch (RemoteException e) {
                Slog.e(getLogTag(), "Remote exception when sending error", e);
            }
        }
        return super.onError(deviceId, error, vendorCode);
    }

    @Override
    public boolean onAuthenticated(BiometricAuthenticator.Identifier identifier,
            boolean authenticated) {
        boolean result = false;

        // If the biometric dialog is showing, notify authentication succeeded
        if (mBundle != null) {
            try {
                if (authenticated) {
                    mStatusBarService.onBiometricAuthenticated();
                } else {
                    mStatusBarService.onBiometricHelp(getContext().getResources().getString(
                            com.android.internal.R.string.biometric_not_recognized));
                }
            } catch (RemoteException e) {
                Slog.e(getLogTag(), "Failed to notify Authenticated:", e);
            }
        }

        final BiometricService.ServiceListener listener = getListener();
        if (listener != null) {
            try {
                mMetricsLogger.action(mMetrics.actionBiometricAuth(), authenticated);
                if (!authenticated) {
                    listener.onAuthenticationFailed(getHalDeviceId());
                } else {
                    if (DEBUG) {
                        Slog.v(getLogTag(), "onAuthenticated(owner=" + getOwnerString()
                                + ", id=" + identifier.getBiometricId());
                    }

                    // Explicitly have if/else here to make it super obvious in case the code is
                    // touched in the future.
                    if (!getIsRestricted()) {
                        listener.onAuthenticationSucceeded(
                                getHalDeviceId(), identifier, getTargetUserId());
                    } else {
                        listener.onAuthenticationSucceeded(
                                getHalDeviceId(), null, getTargetUserId());
                    }
                }
            } catch (RemoteException e) {
                Slog.w(getLogTag(), "Failed to notify Authenticated:", e);
                result = true; // client failed
            }
        } else {
            result = true; // client not listening
        }
        if (!authenticated) {
            if (listener != null) {
                vibrateError();
            }
            // allow system-defined limit of number of attempts before giving up
            int lockoutMode =  handleFailedAttempt();
            if (lockoutMode != LOCKOUT_NONE) {
                try {
                    mInLockout = true;
                    Slog.w(getLogTag(), "Forcing lockout (fp driver code should do this!), mode(" +
                            lockoutMode + ")");
                    stop(false);
                    int errorCode = lockoutMode == LOCKOUT_TIMED ?
                            BiometricConstants.BIOMETRIC_ERROR_LOCKOUT :
                            BiometricConstants.BIOMETRIC_ERROR_LOCKOUT_PERMANENT;

                    // Send the lockout message to the system dialog
                    if (mBundle != null) {
                        mStatusBarService.onBiometricError(
                                getErrorString(errorCode, 0 /* vendorCode */));
                        mHandler.postDelayed(() -> {
                            try {
                                listener.onError(getHalDeviceId(), errorCode, 0 /* vendorCode */);
                            } catch (RemoteException e) {
                                Slog.w(getLogTag(), "RemoteException while sending error");
                            }
                        }, BiometricPrompt.HIDE_DIALOG_DELAY);
                    } else {
                        listener.onError(getHalDeviceId(), errorCode, 0 /* vendorCode */);
                    }
                } catch (RemoteException e) {
                    Slog.w(getLogTag(), "Failed to notify lockout:", e);
                }
            }
            result |= lockoutMode != LOCKOUT_NONE; // in a lockout mode
        } else {
            if (listener != null) {
                vibrateSuccess();
            }
            result |= true; // we have a valid biometric, done
            resetFailedAttempts();
            onStop();
        }
        return result;
    }

    /**
     * Start authentication
     */
    @Override
    public int start() {
        onStart();
        try {
            final int result = getDaemonWrapper().authenticate(mOpId, getGroupId());
            if (result != 0) {
                Slog.w(getLogTag(), "startAuthentication failed, result=" + result);
                mMetricsLogger.histogram(mMetrics.tagAuthStartError(), result);
                onError(getHalDeviceId(), BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE,
                        0 /* vendorCode */);
                return result;
            }
            if (DEBUG) Slog.w(getLogTag(), "client " + getOwnerString() + " is authenticating...");

            // If authenticating with system dialog, show the dialog
            if (mBundle != null) {
                try {
                    mStatusBarService.showBiometricDialog(mBundle, mDialogReceiver,
                            getBiometricType());
                } catch (RemoteException e) {
                    Slog.e(getLogTag(), "Unable to show biometric dialog", e);
                }
            }
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
        } finally {
            // If the user already cancelled authentication (via some interaction with the
            // dialog, we do not need to hide it since it's already hidden.
            // If the device is in lockout, don't hide the dialog - it will automatically hide
            // after BiometricPrompt.HIDE_DIALOG_DELAY
            if (mBundle != null && !mDialogDismissed && !mInLockout) {
                try {
                    mStatusBarService.hideBiometricDialog();
                } catch (RemoteException e) {
                    Slog.e(getLogTag(), "Unable to hide biometric dialog", e);
                }
            }
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
