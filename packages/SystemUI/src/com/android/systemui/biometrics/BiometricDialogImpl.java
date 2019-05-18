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

package com.android.systemui.biometrics;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.IBiometricServiceReceiverInternal;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.view.WindowManager;

import com.android.internal.os.SomeArgs;
import com.android.systemui.Dependency;
import com.android.systemui.SystemUI;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.statusbar.CommandQueue;

/**
 * Receives messages sent from AuthenticationClient and shows the appropriate biometric UI (e.g.
 * BiometricDialogView).
 */
public class BiometricDialogImpl extends SystemUI implements CommandQueue.Callbacks {
    private static final String TAG = "BiometricDialogImpl";
    private static final boolean DEBUG = true;

    private static final int MSG_SHOW_DIALOG = 1;
    private static final int MSG_BIOMETRIC_AUTHENTICATED = 2;
    private static final int MSG_BIOMETRIC_HELP = 3;
    private static final int MSG_BIOMETRIC_ERROR = 4;
    private static final int MSG_HIDE_DIALOG = 5;
    private static final int MSG_BUTTON_NEGATIVE = 6;
    private static final int MSG_USER_CANCELED = 7;
    private static final int MSG_BUTTON_POSITIVE = 8;
    private static final int MSG_TRY_AGAIN_PRESSED = 9;

    private SomeArgs mCurrentDialogArgs;
    private BiometricDialogView mCurrentDialog;
    private WindowManager mWindowManager;
    private IBiometricServiceReceiverInternal mReceiver;
    private boolean mDialogShowing;
    private Callback mCallback = new Callback();
    private WakefulnessLifecycle mWakefulnessLifecycle;

    private Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case MSG_SHOW_DIALOG:
                    handleShowDialog((SomeArgs) msg.obj, false /* skipAnimation */,
                            null /* savedState */);
                    break;
                case MSG_BIOMETRIC_AUTHENTICATED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    handleBiometricAuthenticated((boolean) args.arg1 /* authenticated */,
                            (String) args.arg2 /* failureReason */);
                    args.recycle();
                    break;
                }
                case MSG_BIOMETRIC_HELP: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    handleBiometricHelp((String) args.arg1 /* message */);
                    args.recycle();
                    break;
                }
                case MSG_BIOMETRIC_ERROR:
                    handleBiometricError((String) msg.obj);
                    break;
                case MSG_HIDE_DIALOG:
                    handleHideDialog((Boolean) msg.obj);
                    break;
                case MSG_BUTTON_NEGATIVE:
                    handleButtonNegative();
                    break;
                case MSG_USER_CANCELED:
                    handleUserCanceled();
                    break;
                case MSG_BUTTON_POSITIVE:
                    handleButtonPositive();
                    break;
                case MSG_TRY_AGAIN_PRESSED:
                    handleTryAgainPressed();
                    break;
                default:
                    Log.w(TAG, "Unknown message: " + msg.what);
                    break;
            }
        }
    };

    private class Callback implements DialogViewCallback {
        @Override
        public void onUserCanceled() {
            mHandler.obtainMessage(MSG_USER_CANCELED).sendToTarget();
        }

        @Override
        public void onErrorShown() {
            mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_HIDE_DIALOG,
                    false /* userCanceled */), BiometricPrompt.HIDE_DIALOG_DELAY);
        }

        @Override
        public void onNegativePressed() {
            mHandler.obtainMessage(MSG_BUTTON_NEGATIVE).sendToTarget();
        }

        @Override
        public void onPositivePressed() {
            mHandler.obtainMessage(MSG_BUTTON_POSITIVE).sendToTarget();
        }

        @Override
        public void onTryAgainPressed() {
            mHandler.obtainMessage(MSG_TRY_AGAIN_PRESSED).sendToTarget();
        }
    }

    final WakefulnessLifecycle.Observer mWakefulnessObserver = new WakefulnessLifecycle.Observer() {
        @Override
        public void onStartedGoingToSleep() {
            if (mDialogShowing) {
                if (DEBUG) Log.d(TAG, "User canceled due to screen off");
                mHandler.obtainMessage(MSG_USER_CANCELED).sendToTarget();
            }
        }
    };

    @Override
    public void start() {
        final PackageManager pm = mContext.getPackageManager();
        if (pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
                || pm.hasSystemFeature(PackageManager.FEATURE_FACE)
                || pm.hasSystemFeature(PackageManager.FEATURE_IRIS)) {
            getComponent(CommandQueue.class).addCallback(this);
            mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
            mWakefulnessLifecycle = Dependency.get(WakefulnessLifecycle.class);
            mWakefulnessLifecycle.addObserver(mWakefulnessObserver);
        }
    }

    @Override
    public void showBiometricDialog(Bundle bundle, IBiometricServiceReceiverInternal receiver,
            int type, boolean requireConfirmation, int userId) {
        if (DEBUG) {
            Log.d(TAG, "showBiometricDialog, type: " + type
                    + ", requireConfirmation: " + requireConfirmation);
        }
        // Remove these messages as they are part of the previous client
        mHandler.removeMessages(MSG_BIOMETRIC_ERROR);
        mHandler.removeMessages(MSG_BIOMETRIC_HELP);
        mHandler.removeMessages(MSG_BIOMETRIC_AUTHENTICATED);
        mHandler.removeMessages(MSG_HIDE_DIALOG);
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = bundle;
        args.arg2 = receiver;
        args.argi1 = type;
        args.arg3 = requireConfirmation;
        args.argi2 = userId;
        mHandler.obtainMessage(MSG_SHOW_DIALOG, args).sendToTarget();
    }

    @Override
    public void onBiometricAuthenticated(boolean authenticated, String failureReason) {
        if (DEBUG) Log.d(TAG, "onBiometricAuthenticated: " + authenticated
                + " reason: " + failureReason);

        SomeArgs args = SomeArgs.obtain();
        args.arg1 = authenticated;
        args.arg2 = failureReason;
        mHandler.obtainMessage(MSG_BIOMETRIC_AUTHENTICATED, args).sendToTarget();
    }

    @Override
    public void onBiometricHelp(String message) {
        if (DEBUG) Log.d(TAG, "onBiometricHelp: " + message);
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = message;
        mHandler.obtainMessage(MSG_BIOMETRIC_HELP, args).sendToTarget();
    }

    @Override
    public void onBiometricError(String error) {
        if (DEBUG) Log.d(TAG, "onBiometricError: " + error);
        mHandler.obtainMessage(MSG_BIOMETRIC_ERROR, error).sendToTarget();
    }

    @Override
    public void hideBiometricDialog() {
        if (DEBUG) Log.d(TAG, "hideBiometricDialog");
        mHandler.obtainMessage(MSG_HIDE_DIALOG, false /* userCanceled */).sendToTarget();
    }

    private void handleShowDialog(SomeArgs args, boolean skipAnimation, Bundle savedState) {
        mCurrentDialogArgs = args;
        final int type = args.argi1;

        // Create a new dialog but do not replace the current one yet.
        BiometricDialogView newDialog;
        if (type == BiometricAuthenticator.TYPE_FINGERPRINT) {
            newDialog = new FingerprintDialogView(mContext, mCallback);
        } else if (type == BiometricAuthenticator.TYPE_FACE) {
            newDialog = new FaceDialogView(mContext, mCallback);
        } else {
            Log.e(TAG, "Unsupported type: " + type);
            return;
        }

        if (DEBUG) Log.d(TAG, "handleShowDialog, "
                + " savedState: " + savedState
                + " mCurrentDialog: " + mCurrentDialog
                + " newDialog: " + newDialog
                + " type: " + type);

        if (savedState != null) {
            // SavedState is only non-null if it's from onConfigurationChanged. Restore the state
            // even though it may be removed / re-created again
            newDialog.restoreState(savedState);
        } else if (mCurrentDialog != null && mDialogShowing) {
            // If somehow we're asked to show a dialog, the old one doesn't need to be animated
            // away. This can happen if the app cancels and re-starts auth during configuration
            // change. This is ugly because we also have to do things on onConfigurationChanged
            // here.
            mCurrentDialog.forceRemove();
        }

        mReceiver = (IBiometricServiceReceiverInternal) args.arg2;
        newDialog.setBundle((Bundle) args.arg1);
        newDialog.setRequireConfirmation((boolean) args.arg3);
        newDialog.setUserId(args.argi2);
        newDialog.setSkipIntro(skipAnimation);
        mCurrentDialog = newDialog;
        mWindowManager.addView(mCurrentDialog, mCurrentDialog.getLayoutParams());
        mDialogShowing = true;
    }

    private void handleBiometricAuthenticated(boolean authenticated, String failureReason) {
        if (DEBUG) Log.d(TAG, "handleBiometricAuthenticated: " + authenticated);

        if (authenticated) {
            mCurrentDialog.announceForAccessibility(
                    mContext.getResources()
                            .getText(mCurrentDialog.getAuthenticatedAccessibilityResourceId()));
            if (mCurrentDialog.requiresConfirmation()) {
                mCurrentDialog.updateState(BiometricDialogView.STATE_PENDING_CONFIRMATION);
            } else {
                mCurrentDialog.updateState(BiometricDialogView.STATE_AUTHENTICATED);
                mHandler.postDelayed(() -> {
                    handleHideDialog(false /* userCanceled */);
                }, mCurrentDialog.getDelayAfterAuthenticatedDurationMs());
            }
        } else {
            mCurrentDialog.onAuthenticationFailed(failureReason);
        }
    }

    private void handleBiometricHelp(String message) {
        if (DEBUG) Log.d(TAG, "handleBiometricHelp: " + message);
        mCurrentDialog.onHelpReceived(message);
    }

    private void handleBiometricError(String error) {
        if (DEBUG) Log.d(TAG, "handleBiometricError: " + error);
        if (!mDialogShowing) {
            if (DEBUG) Log.d(TAG, "Dialog already dismissed");
            return;
        }
        mCurrentDialog.onErrorReceived(error);
    }

    private void handleHideDialog(boolean userCanceled) {
        if (DEBUG) Log.d(TAG, "handleHideDialog, userCanceled: " + userCanceled);
        if (!mDialogShowing) {
            // This can happen if there's a race and we get called from both
            // onAuthenticated and onError, etc.
            Log.w(TAG, "Dialog already dismissed, userCanceled: " + userCanceled);
            return;
        }
        if (userCanceled) {
            try {
                mReceiver.onDialogDismissed(BiometricPrompt.DISMISSED_REASON_USER_CANCEL);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException when hiding dialog", e);
            }
        }
        mReceiver = null;
        mDialogShowing = false;
        mCurrentDialog.startDismiss();
    }

    private void handleButtonNegative() {
        if (mReceiver == null) {
            Log.e(TAG, "Receiver is null");
            return;
        }
        try {
            mReceiver.onDialogDismissed(BiometricPrompt.DISMISSED_REASON_NEGATIVE);
        } catch (RemoteException e) {
            Log.e(TAG, "Remote exception when handling negative button", e);
        }
        handleHideDialog(false /* userCanceled */);
    }

    private void handleButtonPositive() {
        if (mReceiver == null) {
            Log.e(TAG, "Receiver is null");
            return;
        }
        try {
            mReceiver.onDialogDismissed(BiometricPrompt.DISMISSED_REASON_POSITIVE);
        } catch (RemoteException e) {
            Log.e(TAG, "Remote exception when handling positive button", e);
        }
        handleHideDialog(false /* userCanceled */);
    }

    private void handleUserCanceled() {
        handleHideDialog(true /* userCanceled */);
    }

    private void handleTryAgainPressed() {
        try {
            mReceiver.onTryAgainPressed();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException when handling try again", e);
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        final boolean wasShowing = mDialogShowing;

        // Save the state of the current dialog (buttons showing, etc)
        final Bundle savedState = new Bundle();
        if (mCurrentDialog != null) {
            mCurrentDialog.onSaveState(savedState);
        }

        if (mDialogShowing) {
            mCurrentDialog.forceRemove();
            mDialogShowing = false;
        }

        if (wasShowing) {
            handleShowDialog(mCurrentDialogArgs, true /* skipAnimation */, savedState);
        }
    }
}
