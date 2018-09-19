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
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.IBiometricPromptReceiver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.view.WindowManager;

import com.android.internal.os.SomeArgs;
import com.android.systemui.SystemUI;
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

    private BiometricDialogView mDialogView;
    private WindowManager mWindowManager;
    private IBiometricPromptReceiver mReceiver;
    private boolean mDialogShowing;
    private Callback mCallback = new Callback();

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case MSG_SHOW_DIALOG:
                    handleShowDialog((SomeArgs) msg.obj);
                    break;
                case MSG_BIOMETRIC_AUTHENTICATED:
                    handleBiometricAuthenticated();
                    break;
                case MSG_BIOMETRIC_HELP:
                    handleBiometricHelp((String) msg.obj);
                    break;
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
            }
        }
    };

    private class Callback implements DialogViewCallback {
        @Override
        public void onUserCanceled() {
            mHandler.obtainMessage(BiometricDialogImpl.MSG_USER_CANCELED).sendToTarget();
        }

        @Override
        public void onErrorShown() {
            mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_HIDE_DIALOG,
                    false /* userCanceled */), BiometricPrompt.HIDE_DIALOG_DELAY);
        }

        @Override
        public void onNegativePressed() {
            mHandler.obtainMessage(BiometricDialogImpl.MSG_BUTTON_NEGATIVE).sendToTarget();
        }

        @Override
        public void onPositivePressed() {
            mHandler.obtainMessage(BiometricDialogImpl.MSG_BUTTON_POSITIVE).sendToTarget();
        }
    }

    @Override
    public void start() {
        if (!mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) {
            return;
        }
        getComponent(CommandQueue.class).addCallbacks(this);
        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        mDialogView = new BiometricDialogView(mContext, mCallback);
    }

    @Override
    public void showBiometricDialog(Bundle bundle, IBiometricPromptReceiver receiver, int type) {
        if (DEBUG) Log.d(TAG, "showBiometricDialog, type: " + type);
        // Remove these messages as they are part of the previous client
        mHandler.removeMessages(MSG_BIOMETRIC_ERROR);
        mHandler.removeMessages(MSG_BIOMETRIC_HELP);
        mHandler.removeMessages(MSG_BIOMETRIC_AUTHENTICATED);
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = bundle;
        args.arg2 = receiver;
        mHandler.obtainMessage(MSG_SHOW_DIALOG, args).sendToTarget();
    }

    @Override
    public void onBiometricAuthenticated() {
        if (DEBUG) Log.d(TAG, "onBiometricAuthenticated");
        mHandler.obtainMessage(MSG_BIOMETRIC_AUTHENTICATED).sendToTarget();
    }

    @Override
    public void onBiometricHelp(String message) {
        if (DEBUG) Log.d(TAG, "onBiometricHelp: " + message);
        mHandler.obtainMessage(MSG_BIOMETRIC_HELP, message).sendToTarget();
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

    private void handleShowDialog(SomeArgs args) {
        if (DEBUG) Log.d(TAG, "handleShowDialog, isAnimatingAway: "
                + mDialogView.isAnimatingAway());
        if (mDialogView.isAnimatingAway()) {
            mDialogView.forceRemove();
        } else if (mDialogShowing) {
            Log.w(TAG, "Dialog already showing");
            return;
        }
        mReceiver = (IBiometricPromptReceiver) args.arg2;
        mDialogView.setBundle((Bundle)args.arg1);
        mWindowManager.addView(mDialogView, mDialogView.getLayoutParams());
        mDialogShowing = true;
    }

    private void handleBiometricAuthenticated() {
        if (DEBUG) Log.d(TAG, "handleBiometricAuthenticated");

        // TODO: announce correct string depending on modality
        mDialogView.announceForAccessibility(
                mContext.getResources().getText(
                        com.android.internal.R.string.fingerprint_authenticated));
        handleHideDialog(false /* userCanceled */);
    }

    private void handleBiometricHelp(String message) {
        if (DEBUG) Log.d(TAG, "handleBiometricHelp: " + message);
        mDialogView.showHelpMessage(message);
    }

    private void handleBiometricError(String error) {
        if (DEBUG) Log.d(TAG, "handleBiometricError: " + error);
        if (!mDialogShowing) {
            if (DEBUG) Log.d(TAG, "Dialog already dismissed");
            return;
        }
        mDialogView.showErrorMessage(error);
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
        mDialogView.startDismiss();
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
}
