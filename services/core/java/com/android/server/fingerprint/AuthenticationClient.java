/**
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.fingerprint;

import static android.Manifest.permission.USE_BIOMETRIC;
import static android.Manifest.permission.USE_FINGERPRINT;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprint;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.IBiometricPromptReceiver;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.IFingerprintServiceReceiver;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.EventLog;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.statusbar.IStatusBarService;

import java.util.List;

/**
 * A class to keep track of the authentication state for a given client.
 */
public abstract class AuthenticationClient extends ClientMonitor {
    private long mOpId;

    public abstract int handleFailedAttempt();
    public abstract void resetFailedAttempts();

    public static final int LOCKOUT_NONE = 0;
    public static final int LOCKOUT_TIMED = 1;
    public static final int LOCKOUT_PERMANENT = 2;

    // Callback mechanism received from the client
    // (BiometricPrompt -> FingerprintManager -> FingerprintService -> AuthenticationClient)
    private IBiometricPromptReceiver mDialogReceiverFromClient;
    private Bundle mBundle;
    private IStatusBarService mStatusBarService;
    private boolean mInLockout;
    private final FingerprintManager mFingerprintManager;
    protected boolean mDialogDismissed;

    // Receives events from SystemUI and handles them before forwarding them to FingerprintDialog
    protected IBiometricPromptReceiver mDialogReceiver = new IBiometricPromptReceiver.Stub() {
        @Override // binder call
        public void onDialogDismissed(int reason) {
            if (mBundle != null && mDialogReceiverFromClient != null) {
                try {
                    mDialogReceiverFromClient.onDialogDismissed(reason);
                    if (reason == BiometricPrompt.DISMISSED_REASON_USER_CANCEL) {
                        onError(FingerprintManager.FINGERPRINT_ERROR_USER_CANCELED,
                                0 /* vendorCode */);
                    }
                    mDialogDismissed = true;
                } catch (RemoteException e) {
                    Slog.e(TAG, "Unable to notify dialog dismissed", e);
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
     * This method is called when a fingerprint is authenticated or authentication is stopped
     * (cancelled by the user, or an error such as lockout has occurred).
     */
    public abstract void onStop();

    public AuthenticationClient(Context context, long halDeviceId, IBinder token,
            IFingerprintServiceReceiver receiver, int targetUserId, int groupId, long opId,
            boolean restricted, String owner, Bundle bundle,
            IBiometricPromptReceiver dialogReceiver, IStatusBarService statusBarService) {
        super(context, halDeviceId, token, receiver, targetUserId, groupId, restricted, owner);
        mOpId = opId;
        mBundle = bundle;
        mDialogReceiverFromClient = dialogReceiver;
        mStatusBarService = statusBarService;
        mFingerprintManager = (FingerprintManager) getContext()
                .getSystemService(Context.FINGERPRINT_SERVICE);
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
                if (acquiredInfo != FingerprintManager.FINGERPRINT_ACQUIRED_GOOD) {
                    mStatusBarService.onFingerprintHelp(
                            mFingerprintManager.getAcquiredString(acquiredInfo, vendorCode));
                }
                return false; // acquisition continues
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception when sending acquired message", e);
                return true; // client failed
            } finally {
                // Good scans will keep the device awake
                if (acquiredInfo == FingerprintManager.FINGERPRINT_ACQUIRED_GOOD) {
                    notifyUserActivity();
                }
            }
        } else {
            return super.onAcquired(acquiredInfo, vendorCode);
        }
    }

    @Override
    public boolean onError(int error, int vendorCode) {
        if (mDialogDismissed) {
            // If user cancels authentication, the application has already received the
            // FingerprintManager.FINGERPRINT_ERROR_USER_CANCELED message from onDialogDismissed()
            // and stopped the fingerprint hardware, so there is no need to send a
            // FingerprintManager.FINGERPRINT_ERROR_CANCELED message.
            return true;
        }
        if (mBundle != null) {
            try {
                mStatusBarService.onFingerprintError(
                        mFingerprintManager.getErrorString(error, vendorCode));
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception when sending error", e);
            }
        }
        return super.onError(error, vendorCode);
    }

    @Override
    public boolean onAuthenticated(int fingerId, int groupId) {
        boolean result = false;
        boolean authenticated = fingerId != 0;

        // Ensure authentication only succeeds if the client activity is on top or is keyguard.
        boolean isBackgroundAuth = false;
        if (authenticated && !isKeyguard(getContext(), getOwnerString())) {
            final ActivityManager activityManager =
                    (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
            final IActivityManager activityManagerService = activityManager != null
                    ? activityManager.getService()
                    : null;
            if (activityManagerService == null) {
                Slog.e(TAG, "Unable to get activity manager service");
                isBackgroundAuth = true;
            } else {
                try {
                    final List<ActivityManager.RunningTaskInfo> tasks =
                            activityManagerService.getTasks(1);
                    if (tasks == null || tasks.isEmpty()) {
                        Slog.e(TAG, "No running tasks reported");
                        isBackgroundAuth = true;
                    } else {
                        final ComponentName topActivity = tasks.get(0).topActivity;
                        if (topActivity == null) {
                            Slog.e(TAG, "Unable to get top activity");
                            isBackgroundAuth = true;
                        } else {
                            final String topPackage = topActivity.getPackageName();
                            if (!topPackage.contentEquals(getOwnerString())) {
                                Slog.e(TAG, "Background authentication detected, top: " + topPackage
                                        + ", client: " + this);
                                isBackgroundAuth = true;
                            }
                        }
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Unable to get running tasks", e);
                    isBackgroundAuth = true;
                }
            }
        }

        // Fail authentication if we can't confirm the client activity is on top.
        if (isBackgroundAuth) {
            Slog.e(TAG, "Failing possible background authentication");
            authenticated = false;

            // SafetyNet logging for exploitation attempts of b/159249069.
            final ApplicationInfo appInfo = getContext().getApplicationInfo();
            EventLog.writeEvent(0x534e4554, "159249069", appInfo != null ? appInfo.uid : -1,
                    "Attempted background authentication");
        }

        // If the fingerprint dialog is showing, notify authentication succeeded
        if (mBundle != null) {
            try {
                if (authenticated) {
                    // SafetyNet logging for b/159249069 if constraint is violated.
                    if (isBackgroundAuth) {
                        final ApplicationInfo appInfo = getContext().getApplicationInfo();
                        EventLog.writeEvent(0x534e4554, "159249069",
                                appInfo != null ? appInfo.uid : -1,
                                "Successful background authentication! Dialog notified");
                    }

                    mStatusBarService.onFingerprintAuthenticated();
                } else {
                    mStatusBarService.onFingerprintHelp(getContext().getResources().getString(
                            com.android.internal.R.string.fingerprint_not_recognized));
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to notify Authenticated:", e);
            }
        }

        IFingerprintServiceReceiver receiver = getReceiver();
        if (receiver != null) {
            try {
                MetricsLogger.action(getContext(), MetricsEvent.ACTION_FINGERPRINT_AUTH,
                        authenticated);
                if (!authenticated) {
                    receiver.onAuthenticationFailed(getHalDeviceId());
                } else {
                    // SafetyNet logging for b/159249069 if constraint is violated.
                    if (isBackgroundAuth) {
                        final ApplicationInfo appInfo = getContext().getApplicationInfo();
                        EventLog.writeEvent(0x534e4554, "159249069",
                                appInfo != null ? appInfo.uid : -1,
                                "Successful background authentication! Receiver notified");
                    }

                    if (DEBUG) {
                        Slog.v(TAG, "onAuthenticated(owner=" + getOwnerString()
                                + ", id=" + fingerId + ", gp=" + groupId + ")");
                    }
                    Fingerprint fp = !getIsRestricted()
                            ? new Fingerprint("" /* TODO */, groupId, fingerId, getHalDeviceId())
                            : null;
                    receiver.onAuthenticationSucceeded(getHalDeviceId(), fp, getTargetUserId());
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to notify Authenticated:", e);
                result = true; // client failed
            }
        } else {
            result = true; // client not listening
        }
        if (!authenticated) {
            if (receiver != null) {
                vibrateError();
            }
            // allow system-defined limit of number of attempts before giving up
            int lockoutMode =  handleFailedAttempt();
            if (lockoutMode != LOCKOUT_NONE) {
                try {
                    mInLockout = true;
                    Slog.w(TAG, "Forcing lockout (fp driver code should do this!), mode(" +
                            lockoutMode + ")");
                    stop(false);
                    int errorCode = lockoutMode == LOCKOUT_TIMED ?
                            FingerprintManager.FINGERPRINT_ERROR_LOCKOUT :
                            FingerprintManager.FINGERPRINT_ERROR_LOCKOUT_PERMANENT;

                    // TODO: if the dialog is showing, this error should be delayed. On a similar
                    // note, AuthenticationClient should override onError and delay all other errors
                    // as well, if the dialog is showing
                    receiver.onError(getHalDeviceId(), errorCode, 0 /* vendorCode */);

                    // Send the lockout message to the system dialog
                    if (mBundle != null) {
                        mStatusBarService.onFingerprintError(
                                mFingerprintManager.getErrorString(errorCode, 0 /* vendorCode */));
                    }
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to notify lockout:", e);
                }
            }
            result |= lockoutMode != LOCKOUT_NONE; // in a lockout mode
        } else {
            // SafetyNet logging for b/159249069 if constraint is violated.
            if (isBackgroundAuth) {
                final ApplicationInfo appInfo = getContext().getApplicationInfo();
                EventLog.writeEvent(0x534e4554, "159249069", appInfo != null ? appInfo.uid : -1,
                        "Successful background authentication! Lockout reset");
            }

            if (receiver != null) {
                vibrateSuccess();
            }
            result |= true; // we have a valid fingerprint, done
            resetFailedAttempts();
            onStop();
        }
        return result;
    }

    private static boolean isKeyguard(Context context, String clientPackage) {
        final boolean hasFingerPermission = context.checkCallingOrSelfPermission(USE_FINGERPRINT)
                    == PackageManager.PERMISSION_GRANTED;
        final boolean hasBiometricPermission = context.checkCallingOrSelfPermission(USE_BIOMETRIC)
                    == PackageManager.PERMISSION_GRANTED;
        final boolean hasPermission = hasFingerPermission || hasBiometricPermission;

        final ComponentName keyguardComponent = ComponentName.unflattenFromString(
                context.getResources().getString(R.string.config_keyguardComponent));
        final String keyguardPackage = keyguardComponent != null
                ? keyguardComponent.getPackageName() : null;
        return hasPermission && keyguardPackage != null && keyguardPackage.equals(clientPackage);
    }

    /**
     * Start authentication
     */
    @Override
    public int start() {
        IBiometricsFingerprint daemon = getFingerprintDaemon();
        if (daemon == null) {
            Slog.w(TAG, "start authentication: no fingerprint HAL!");
            return ERROR_ESRCH;
        }
        onStart();
        try {
            final int result = daemon.authenticate(mOpId, getGroupId());
            if (result != 0) {
                Slog.w(TAG, "startAuthentication failed, result=" + result);
                MetricsLogger.histogram(getContext(), "fingeprintd_auth_start_error", result);
                onError(FingerprintManager.FINGERPRINT_ERROR_HW_UNAVAILABLE, 0 /* vendorCode */);
                return result;
            }
            if (DEBUG) Slog.w(TAG, "client " + getOwnerString() + " is authenticating...");

            // If authenticating with system dialog, show the dialog
            if (mBundle != null) {
                try {
                    mStatusBarService.showFingerprintDialog(mBundle, mDialogReceiver);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Unable to show fingerprint dialog", e);
                }
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "startAuthentication failed", e);
            return ERROR_ESRCH;
        }
        return 0; // success
    }

    @Override
    public int stop(boolean initiatedByClient) {
        if (mAlreadyCancelled) {
            Slog.w(TAG, "stopAuthentication: already cancelled!");
            return 0;
        }

        onStop();
        IBiometricsFingerprint daemon = getFingerprintDaemon();
        if (daemon == null) {
            Slog.w(TAG, "stopAuthentication: no fingerprint HAL!");
            return ERROR_ESRCH;
        }
        try {
            final int result = daemon.cancel();
            if (result != 0) {
                Slog.w(TAG, "stopAuthentication failed, result=" + result);
                return result;
            }
            if (DEBUG) Slog.w(TAG, "client " + getOwnerString() + " is no longer authenticating");
        } catch (RemoteException e) {
            Slog.e(TAG, "stopAuthentication failed", e);
            return ERROR_ESRCH;
        } finally {
            // If the user already cancelled authentication (via some interaction with the
            // dialog, we do not need to hide it since it's already hidden.
            // If the device is in lockout, don't hide the dialog - it will automatically hide
            // after BiometricPrompt.HIDE_DIALOG_DELAY
            if (mBundle != null && !mDialogDismissed && !mInLockout) {
                try {
                    mStatusBarService.hideFingerprintDialog();
                } catch (RemoteException e) {
                    Slog.e(TAG, "Unable to hide fingerprint dialog", e);
                }
            }
        }
        mAlreadyCancelled = true;
        return 0; // success
    }

    @Override
    public boolean onEnrollResult(int fingerId, int groupId, int remaining) {
        if (DEBUG) Slog.w(TAG, "onEnrollResult() called for authenticate!");
        return true; // Invalid for Authenticate
    }

    @Override
    public boolean onRemoved(int fingerId, int groupId, int remaining) {
        if (DEBUG) Slog.w(TAG, "onRemoved() called for authenticate!");
        return true; // Invalid for Authenticate
    }

    @Override
    public boolean onEnumerationResult(int fingerId, int groupId, int remaining) {
        if (DEBUG) Slog.w(TAG, "onEnumerationResult() called for authenticate!");
        return true; // Invalid for Authenticate
    }
}
