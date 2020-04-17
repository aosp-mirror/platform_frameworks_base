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
 * limitations under the License.
 */

package com.android.systemui.biometrics;

import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FACE;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FINGERPRINT;
import static android.hardware.biometrics.BiometricManager.Authenticators;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.IActivityTaskManager;
import android.app.TaskStackListener;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.IBiometricServiceReceiverInternal;
import android.hardware.face.FaceManager;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.WindowManager;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.SomeArgs;
import com.android.systemui.SystemUI;
import com.android.systemui.statusbar.CommandQueue;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Receives messages sent from {@link com.android.server.biometrics.BiometricService} and shows the
 * appropriate biometric UI (e.g. BiometricDialogView).
 */
@Singleton
public class AuthController extends SystemUI implements CommandQueue.Callbacks,
        AuthDialogCallback {

    private static final String TAG = "BiometricPrompt/AuthController";
    private static final boolean DEBUG = true;

    private final CommandQueue mCommandQueue;
    private final Injector mInjector;

    // TODO: These should just be saved from onSaveState
    private SomeArgs mCurrentDialogArgs;
    @VisibleForTesting
    AuthDialog mCurrentDialog;

    private Handler mHandler = new Handler(Looper.getMainLooper());
    private WindowManager mWindowManager;
    @VisibleForTesting
    IActivityTaskManager mActivityTaskManager;
    @VisibleForTesting
    BiometricTaskStackListener mTaskStackListener;
    @VisibleForTesting
    IBiometricServiceReceiverInternal mReceiver;

    public class BiometricTaskStackListener extends TaskStackListener {
        @Override
        public void onTaskStackChanged() {
            mHandler.post(mTaskStackChangedRunnable);
        }
    }

    @VisibleForTesting
    final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mCurrentDialog != null
                    && Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) {
                Log.w(TAG, "ACTION_CLOSE_SYSTEM_DIALOGS received");
                mCurrentDialog.dismissWithoutCallback(true /* animate */);
                mCurrentDialog = null;

                try {
                    if (mReceiver != null) {
                        mReceiver.onDialogDismissed(BiometricPrompt.DISMISSED_REASON_USER_CANCEL,
                                null /* credentialAttestation */);
                        mReceiver = null;
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "Remote exception", e);
                }
            }
        }
    };

    private final Runnable mTaskStackChangedRunnable = () -> {
        if (mCurrentDialog != null) {
            try {
                final String clientPackage = mCurrentDialog.getOpPackageName();
                Log.w(TAG, "Task stack changed, current client: " + clientPackage);
                final List<ActivityManager.RunningTaskInfo> runningTasks =
                        mActivityTaskManager.getTasks(1);
                if (!runningTasks.isEmpty()) {
                    final String topPackage = runningTasks.get(0).topActivity.getPackageName();
                    if (!topPackage.contentEquals(clientPackage)) {
                        Log.w(TAG, "Evicting client due to: " + topPackage);
                        mCurrentDialog.dismissWithoutCallback(true /* animate */);
                        mCurrentDialog = null;
                        if (mReceiver != null) {
                            mReceiver.onDialogDismissed(
                                    BiometricPrompt.DISMISSED_REASON_USER_CANCEL,
                                    null /* credentialAttestation */);
                            mReceiver = null;
                        }
                    }
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Remote exception", e);
            }
        }
    };

    @Override
    public void onTryAgainPressed() {
        if (mReceiver == null) {
            Log.e(TAG, "onTryAgainPressed: Receiver is null");
            return;
        }
        try {
            mReceiver.onTryAgainPressed();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException when handling try again", e);
        }
    }

    @Override
    public void onDeviceCredentialPressed() {
        if (mReceiver == null) {
            Log.e(TAG, "onDeviceCredentialPressed: Receiver is null");
            return;
        }
        try {
            mReceiver.onDeviceCredentialPressed();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException when handling credential button", e);
        }
    }

    @Override
    public void onSystemEvent(int event) {
        if (mReceiver == null) {
            Log.e(TAG, "onSystemEvent(" + event + "): Receiver is null");
            return;
        }
        try {
            mReceiver.onSystemEvent(event);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException when sending system event", e);
        }
    }

    @Override
    public void onDismissed(@DismissedReason int reason, @Nullable byte[] credentialAttestation) {
        switch (reason) {
            case AuthDialogCallback.DISMISSED_USER_CANCELED:
                sendResultAndCleanUp(BiometricPrompt.DISMISSED_REASON_USER_CANCEL,
                        credentialAttestation);
                break;

            case AuthDialogCallback.DISMISSED_BUTTON_NEGATIVE:
                sendResultAndCleanUp(BiometricPrompt.DISMISSED_REASON_NEGATIVE,
                        credentialAttestation);
                break;

            case AuthDialogCallback.DISMISSED_BUTTON_POSITIVE:
                sendResultAndCleanUp(BiometricPrompt.DISMISSED_REASON_BIOMETRIC_CONFIRMED,
                        credentialAttestation);
                break;

            case AuthDialogCallback.DISMISSED_BIOMETRIC_AUTHENTICATED:
                sendResultAndCleanUp(
                        BiometricPrompt.DISMISSED_REASON_BIOMETRIC_CONFIRM_NOT_REQUIRED,
                        credentialAttestation);
                break;

            case AuthDialogCallback.DISMISSED_ERROR:
                sendResultAndCleanUp(BiometricPrompt.DISMISSED_REASON_ERROR,
                        credentialAttestation);
                break;

            case AuthDialogCallback.DISMISSED_BY_SYSTEM_SERVER:
                sendResultAndCleanUp(BiometricPrompt.DISMISSED_REASON_SERVER_REQUESTED,
                        credentialAttestation);
                break;

            case AuthDialogCallback.DISMISSED_CREDENTIAL_AUTHENTICATED:
                sendResultAndCleanUp(BiometricPrompt.DISMISSED_REASON_CREDENTIAL_CONFIRMED,
                        credentialAttestation);
                break;

            default:
                Log.e(TAG, "Unhandled reason: " + reason);
                break;
        }
    }

    private void sendResultAndCleanUp(@DismissedReason int reason,
            @Nullable byte[] credentialAttestation) {
        if (mReceiver == null) {
            Log.e(TAG, "sendResultAndCleanUp: Receiver is null");
            return;
        }
        try {
            mReceiver.onDialogDismissed(reason, credentialAttestation);
        } catch (RemoteException e) {
            Log.w(TAG, "Remote exception", e);
        }
        onDialogDismissed(reason);
    }

    public static class Injector {
        IActivityTaskManager getActivityTaskManager() {
            return ActivityTaskManager.getService();
        }
    }

    @Inject
    public AuthController(Context context, CommandQueue commandQueue) {
        this(context, commandQueue, new Injector());
    }

    @VisibleForTesting
    AuthController(Context context, CommandQueue commandQueue, Injector injector) {
        super(context);
        mCommandQueue = commandQueue;
        mInjector = injector;

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);

        context.registerReceiver(mBroadcastReceiver, filter);
    }

    @Override
    public void start() {
        mCommandQueue.addCallback(this);
        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        mActivityTaskManager = mInjector.getActivityTaskManager();

        try {
            mTaskStackListener = new BiometricTaskStackListener();
            mActivityTaskManager.registerTaskStackListener(mTaskStackListener);
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to register task stack listener", e);
        }
    }

    @Override
    public void showAuthenticationDialog(Bundle bundle, IBiometricServiceReceiverInternal receiver,
            @BiometricAuthenticator.Modality int biometricModality, boolean requireConfirmation,
            int userId, String opPackageName, long operationId) {
        final int authenticators = Utils.getAuthenticators(bundle);

        if (DEBUG) {
            Log.d(TAG, "showAuthenticationDialog, authenticators: " + authenticators
                    + ", biometricModality: " + biometricModality
                    + ", requireConfirmation: " + requireConfirmation
                    + ", operationId: " + operationId);
        }
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = bundle;
        args.arg2 = receiver;
        args.argi1 = biometricModality;
        args.arg3 = requireConfirmation;
        args.argi2 = userId;
        args.arg4 = opPackageName;
        args.arg5 = operationId;

        boolean skipAnimation = false;
        if (mCurrentDialog != null) {
            Log.w(TAG, "mCurrentDialog: " + mCurrentDialog);
            skipAnimation = true;
        }

        showDialog(args, skipAnimation, null /* savedState */);
    }

    @Override
    public void onBiometricAuthenticated() {
        mCurrentDialog.onAuthenticationSucceeded();
    }

    @Override
    public void onBiometricHelp(String message) {
        if (DEBUG) Log.d(TAG, "onBiometricHelp: " + message);

        mCurrentDialog.onHelp(message);
    }

    private String getErrorString(int modality, int error, int vendorCode) {
        switch (modality) {
            case TYPE_FACE:
                return FaceManager.getErrorString(mContext, error, vendorCode);

            case TYPE_FINGERPRINT:
                return FingerprintManager.getErrorString(mContext, error, vendorCode);

            default:
                return "";
        }
    }

    @Override
    public void onBiometricError(int modality, int error, int vendorCode) {
        if (DEBUG) {
            Log.d(TAG, String.format("onBiometricError(%d, %d, %d)", modality, error, vendorCode));
        }

        final boolean isLockout = (error == BiometricConstants.BIOMETRIC_ERROR_LOCKOUT)
                || (error == BiometricConstants.BIOMETRIC_ERROR_LOCKOUT_PERMANENT);

        // TODO(b/141025588): Create separate methods for handling hard and soft errors.
        final boolean isSoftError = (error == BiometricConstants.BIOMETRIC_PAUSED_REJECTED
                || error == BiometricConstants.BIOMETRIC_ERROR_TIMEOUT);

        if (mCurrentDialog.isAllowDeviceCredentials() && isLockout) {
            if (DEBUG) Log.d(TAG, "onBiometricError, lockout");
            mCurrentDialog.animateToCredentialUI();
        } else if (isSoftError) {
            final String errorMessage = (error == BiometricConstants.BIOMETRIC_PAUSED_REJECTED)
                    ? mContext.getString(R.string.biometric_not_recognized)
                    : getErrorString(modality, error, vendorCode);
            if (DEBUG) Log.d(TAG, "onBiometricError, soft error: " + errorMessage);
            mCurrentDialog.onAuthenticationFailed(errorMessage);
        } else {
            final String errorMessage = getErrorString(modality, error, vendorCode);
            if (DEBUG) Log.d(TAG, "onBiometricError, hard error: " + errorMessage);
            mCurrentDialog.onError(errorMessage);
        }
    }

    @Override
    public void hideAuthenticationDialog() {
        if (DEBUG) Log.d(TAG, "hideAuthenticationDialog: " + mCurrentDialog);

        if (mCurrentDialog == null) {
            // Could be possible if the caller canceled authentication after credential success
            // but before the client was notified.
            return;
        }

        mCurrentDialog.dismissFromSystemServer();

        // BiometricService will have already sent the callback to the client in this case.
        // This avoids a round trip to SystemUI. So, just dismiss the dialog and we're done.
        mCurrentDialog = null;
    }

    private void showDialog(SomeArgs args, boolean skipAnimation, Bundle savedState) {
        mCurrentDialogArgs = args;
        final @BiometricAuthenticator.Modality int type = args.argi1;
        final Bundle biometricPromptBundle = (Bundle) args.arg1;
        final boolean requireConfirmation = (boolean) args.arg3;
        final int userId = args.argi2;
        final String opPackageName = (String) args.arg4;
        final long operationId = (long) args.arg5;

        // Create a new dialog but do not replace the current one yet.
        final AuthDialog newDialog = buildDialog(
                biometricPromptBundle,
                requireConfirmation,
                userId,
                type,
                opPackageName,
                skipAnimation,
                operationId);

        if (newDialog == null) {
            Log.e(TAG, "Unsupported type: " + type);
            return;
        }

        if (DEBUG) {
            Log.d(TAG, "userId: " + userId
                    + " savedState: " + savedState
                    + " mCurrentDialog: " + mCurrentDialog
                    + " newDialog: " + newDialog
                    + " type: " + type);
        }

        if (mCurrentDialog != null) {
            // If somehow we're asked to show a dialog, the old one doesn't need to be animated
            // away. This can happen if the app cancels and re-starts auth during configuration
            // change. This is ugly because we also have to do things on onConfigurationChanged
            // here.
            mCurrentDialog.dismissWithoutCallback(false /* animate */);
        }

        mReceiver = (IBiometricServiceReceiverInternal) args.arg2;
        mCurrentDialog = newDialog;
        mCurrentDialog.show(mWindowManager, savedState);
    }

    private void onDialogDismissed(@DismissedReason int reason) {
        if (DEBUG) Log.d(TAG, "onDialogDismissed: " + reason);
        if (mCurrentDialog == null) {
            Log.w(TAG, "Dialog already dismissed");
        }
        mReceiver = null;
        mCurrentDialog = null;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // Save the state of the current dialog (buttons showing, etc)
        if (mCurrentDialog != null) {
            final Bundle savedState = new Bundle();
            mCurrentDialog.onSaveState(savedState);
            mCurrentDialog.dismissWithoutCallback(false /* animate */);
            mCurrentDialog = null;

            // Only show the dialog if necessary. If it was animating out, the dialog is supposed
            // to send its pending callback immediately.
            if (savedState.getInt(AuthDialog.KEY_CONTAINER_STATE)
                    != AuthContainerView.STATE_ANIMATING_OUT) {
                final boolean credentialShowing =
                        savedState.getBoolean(AuthDialog.KEY_CREDENTIAL_SHOWING);
                if (credentialShowing) {
                    // TODO: Clean this up
                    Bundle bundle = (Bundle) mCurrentDialogArgs.arg1;
                    bundle.putInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED,
                            Authenticators.DEVICE_CREDENTIAL);
                }

                showDialog(mCurrentDialogArgs, true /* skipAnimation */, savedState);
            }
        }
    }

    protected AuthDialog buildDialog(Bundle biometricPromptBundle, boolean requireConfirmation,
            int userId, @BiometricAuthenticator.Modality int type, String opPackageName,
            boolean skipIntro, long operationId) {
        return new AuthContainerView.Builder(mContext)
                .setCallback(this)
                .setBiometricPromptBundle(biometricPromptBundle)
                .setRequireConfirmation(requireConfirmation)
                .setUserId(userId)
                .setOpPackageName(opPackageName)
                .setSkipIntro(skipIntro)
                .setOperationId(operationId)
                .build(type);
    }
}
