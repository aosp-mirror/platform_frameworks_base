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

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.IActivityTaskManager;
import android.app.TaskStackListener;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.IBiometricServiceReceiverInternal;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.SomeArgs;
import com.android.systemui.SystemUI;
import com.android.systemui.statusbar.CommandQueue;

import java.util.List;

/**
 * Receives messages sent from {@link com.android.server.biometrics.BiometricService} and shows the
 * appropriate biometric UI (e.g. BiometricDialogView).
 */
public class AuthController extends SystemUI implements CommandQueue.Callbacks,
        AuthDialogCallback {
    private static final String USE_NEW_DIALOG =
            "com.android.systemui.biometrics.AuthController.USE_NEW_DIALOG";

    private static final String TAG = "BiometricPrompt/AuthController";
    private static final boolean DEBUG = true;

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
                        mReceiver.onDialogDismissed(BiometricPrompt.DISMISSED_REASON_USER_CANCEL);
                        mReceiver = null;
                    }
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Remote exception", e);
            }
        }
    };

    @Override
    public void onTryAgainPressed() {
        try {
            mReceiver.onTryAgainPressed();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException when handling try again", e);
        }
    }

    @Override
    public void onDismissed(@DismissedReason int reason) {
        switch (reason) {
            case AuthDialogCallback.DISMISSED_USER_CANCELED:
                sendResultAndCleanUp(BiometricPrompt.DISMISSED_REASON_USER_CANCEL);
                break;

            case AuthDialogCallback.DISMISSED_BUTTON_NEGATIVE:
                sendResultAndCleanUp(BiometricPrompt.DISMISSED_REASON_NEGATIVE);
                break;

            case AuthDialogCallback.DISMISSED_BUTTON_POSITIVE:
                sendResultAndCleanUp(BiometricPrompt.DISMISSED_REASON_CONFIRMED);
                break;

            case AuthDialogCallback.DISMISSED_AUTHENTICATED:
                sendResultAndCleanUp(BiometricPrompt.DISMISSED_REASON_CONFIRM_NOT_REQUIRED);
                break;

            case AuthDialogCallback.DISMISSED_ERROR:
                sendResultAndCleanUp(BiometricPrompt.DISMISSED_REASON_ERROR);
                break;

            case AuthDialogCallback.DISMISSED_BY_SYSTEM_SERVER:
                sendResultAndCleanUp(BiometricPrompt.DISMISSED_REASON_SERVER_REQUESTED);
                break;

            default:
                Log.e(TAG, "Unhandled reason: " + reason);
                break;
        }
    }

    private void sendResultAndCleanUp(@DismissedReason int reason) {
        if (mReceiver == null) {
            Log.e(TAG, "Receiver is null");
            return;
        }
        try {
            mReceiver.onDialogDismissed(reason);
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

    public AuthController() {
        this(new Injector());
    }

    @VisibleForTesting
    AuthController(Injector injector) {
        mInjector = injector;
    }

    @Override
    public void start() {
        final PackageManager pm = mContext.getPackageManager();
        if (pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
                || pm.hasSystemFeature(PackageManager.FEATURE_FACE)
                || pm.hasSystemFeature(PackageManager.FEATURE_IRIS)) {
            getComponent(CommandQueue.class).addCallback(this);
            mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
            mActivityTaskManager = mInjector.getActivityTaskManager();

            try {
                mTaskStackListener = new BiometricTaskStackListener();
                mActivityTaskManager.registerTaskStackListener(mTaskStackListener);
            } catch (RemoteException e) {
                Log.w(TAG, "Unable to register task stack listener", e);
            }
        }
    }

    @Override
    public void showBiometricDialog(Bundle bundle, IBiometricServiceReceiverInternal receiver,
            int type, boolean requireConfirmation, int userId, String opPackageName) {
        if (DEBUG) {
            Log.d(TAG, "showBiometricDialog, type: " + type
                    + ", requireConfirmation: " + requireConfirmation);
        }
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = bundle;
        args.arg2 = receiver;
        args.argi1 = type;
        args.arg3 = requireConfirmation;
        args.argi2 = userId;
        args.arg4 = opPackageName;

        boolean skipAnimation = false;
        if (mCurrentDialog != null) {
            Log.w(TAG, "mCurrentDialog: " + mCurrentDialog);
            skipAnimation = true;
        }
        showDialog(args, skipAnimation, null /* savedState */);
    }

    @Override
    public void onBiometricAuthenticated(boolean authenticated, String failureReason) {
        if (DEBUG) Log.d(TAG, "onBiometricAuthenticated: " + authenticated
                + " reason: " + failureReason);

        if (authenticated) {
            mCurrentDialog.onAuthenticationSucceeded();
        } else {
            mCurrentDialog.onAuthenticationFailed(failureReason);
        }
    }

    @Override
    public void onBiometricHelp(String message) {
        if (DEBUG) Log.d(TAG, "onBiometricHelp: " + message);

        mCurrentDialog.onHelp(message);
    }

    @Override
    public void onBiometricError(String error) {
        if (DEBUG) Log.d(TAG, "onBiometricError: " + error);
        mCurrentDialog.onError(error);
    }

    @Override
    public void hideBiometricDialog() {
        if (DEBUG) Log.d(TAG, "hideBiometricDialog");

        mCurrentDialog.dismissFromSystemServer();
    }

    private void showDialog(SomeArgs args, boolean skipAnimation, Bundle savedState) {
        mCurrentDialogArgs = args;
        final int type = args.argi1;
        final Bundle biometricPromptBundle = (Bundle) args.arg1;
        final boolean requireConfirmation = (boolean) args.arg3;
        final int userId = args.argi2;
        final String opPackageName = (String) args.arg4;

        // Create a new dialog but do not replace the current one yet.
        final AuthDialog newDialog = buildDialog(
                biometricPromptBundle,
                requireConfirmation,
                userId,
                type,
                opPackageName,
                skipAnimation);

        if (newDialog == null) {
            Log.e(TAG, "Unsupported type: " + type);
            return;
        }

        if (DEBUG) {
            Log.d(TAG, "showDialog, "
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
                showDialog(mCurrentDialogArgs, true /* skipAnimation */, savedState);
            }
        }
    }

    protected AuthDialog buildDialog(Bundle biometricPromptBundle, boolean requireConfirmation,
            int userId, int type, String opPackageName, boolean skipIntro) {
        if (Settings.Secure.getIntForUser(
                mContext.getContentResolver(), USE_NEW_DIALOG, userId, 0) != 0) {
            return new AuthContainerView.Builder(mContext)
                    .setCallback(this)
                    .setBiometricPromptBundle(biometricPromptBundle)
                    .setRequireConfirmation(requireConfirmation)
                    .setUserId(userId)
                    .setOpPackageName(opPackageName)
                    .setSkipIntro(skipIntro)
                    .build(type);
        } else {
            return new BiometricDialogView.Builder(mContext)
                    .setCallback(this)
                    .setBiometricPromptBundle(biometricPromptBundle)
                    .setRequireConfirmation(requireConfirmation)
                    .setUserId(userId)
                    .setOpPackageName(opPackageName)
                    .setSkipIntro(skipIntro)
                    .build(type);
        }
    }
}
