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

package com.android.systemui.fingerprint;

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

public class FingerprintDialogImpl extends SystemUI implements CommandQueue.Callbacks {
    private static final String TAG = "FingerprintDialogImpl";
    private static final boolean DEBUG = true;

    protected static final int MSG_SHOW_DIALOG = 1;
    protected static final int MSG_FINGERPRINT_AUTHENTICATED = 2;
    protected static final int MSG_FINGERPRINT_HELP = 3;
    protected static final int MSG_FINGERPRINT_ERROR = 4;
    protected static final int MSG_HIDE_DIALOG = 5;
    protected static final int MSG_BUTTON_NEGATIVE = 6;
    protected static final int MSG_USER_CANCELED = 7;
    protected static final int MSG_BUTTON_POSITIVE = 8;
    protected static final int MSG_CLEAR_MESSAGE = 9;


    private FingerprintDialogView mDialogView;
    private WindowManager mWindowManager;
    private IBiometricPromptReceiver mReceiver;
    private boolean mDialogShowing;

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case MSG_SHOW_DIALOG:
                    handleShowDialog((SomeArgs) msg.obj);
                    break;
                case MSG_FINGERPRINT_AUTHENTICATED:
                    handleFingerprintAuthenticated();
                    break;
                case MSG_FINGERPRINT_HELP:
                    handleFingerprintHelp((String) msg.obj);
                    break;
                case MSG_FINGERPRINT_ERROR:
                    handleFingerprintError((String) msg.obj);
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
                case MSG_CLEAR_MESSAGE:
                    handleClearMessage();
                    break;
            }
        }
    };

    @Override
    public void start() {
        if (!mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) {
            return;
        }
        getComponent(CommandQueue.class).addCallbacks(this);
        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        mDialogView = new FingerprintDialogView(mContext, mHandler);
    }

    @Override
    public void showFingerprintDialog(Bundle bundle, IBiometricPromptReceiver receiver) {
        if (DEBUG) Log.d(TAG, "showFingerprintDialog");
        // Remove these messages as they are part of the previous client
        mHandler.removeMessages(MSG_FINGERPRINT_ERROR);
        mHandler.removeMessages(MSG_FINGERPRINT_HELP);
        mHandler.removeMessages(MSG_FINGERPRINT_AUTHENTICATED);
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = bundle;
        args.arg2 = receiver;
        mHandler.obtainMessage(MSG_SHOW_DIALOG, args).sendToTarget();
    }

    @Override
    public void onFingerprintAuthenticated() {
        if (DEBUG) Log.d(TAG, "onFingerprintAuthenticated");
        mHandler.obtainMessage(MSG_FINGERPRINT_AUTHENTICATED).sendToTarget();
    }

    @Override
    public void onFingerprintHelp(String message) {
        if (DEBUG) Log.d(TAG, "onFingerprintHelp: " + message);
        mHandler.obtainMessage(MSG_FINGERPRINT_HELP, message).sendToTarget();
    }

    @Override
    public void onFingerprintError(String error) {
        if (DEBUG) Log.d(TAG, "onFingerprintError: " + error);
        mHandler.obtainMessage(MSG_FINGERPRINT_ERROR, error).sendToTarget();
    }

    @Override
    public void hideFingerprintDialog() {
        if (DEBUG) Log.d(TAG, "hideFingerprintDialog");
        mHandler.obtainMessage(MSG_HIDE_DIALOG, false /* userCanceled */).sendToTarget();
    }

    private void handleShowDialog(SomeArgs args) {
        if (DEBUG) Log.d(TAG, "handleShowDialog");
        if (mDialogShowing) {
            Log.w(TAG, "Dialog already showing");
            return;
        }
        mReceiver = (IBiometricPromptReceiver) args.arg2;
        mDialogView.setBundle((Bundle)args.arg1);
        mWindowManager.addView(mDialogView, mDialogView.getLayoutParams());
        mDialogShowing = true;
    }

    private void handleFingerprintAuthenticated() {
        if (DEBUG) Log.d(TAG, "handleFingerprintAuthenticated");
        mDialogView.announceForAccessibility(
                mContext.getResources().getText(
                        com.android.internal.R.string.fingerprint_authenticated));
        handleHideDialog(false /* userCanceled */);
    }

    private void handleFingerprintHelp(String message) {
        if (DEBUG) Log.d(TAG, "handleFingerprintHelp: " + message);
        mDialogView.showHelpMessage(message);
    }

    private void handleFingerprintError(String error) {
        if (DEBUG) Log.d(TAG, "handleFingerprintError: " + error);
        if (!mDialogShowing) {
            if (DEBUG) Log.d(TAG, "Dialog already dismissed");
            return;
        }
        mDialogView.showErrorMessage(error);
    }

    private void handleHideDialog(boolean userCanceled) {
        if (DEBUG) Log.d(TAG, "handleHideDialog");
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

    private void handleClearMessage() {
        mDialogView.resetMessage();
    }

    private void handleUserCanceled() {
        handleHideDialog(true /* userCanceled */);
    }
}
