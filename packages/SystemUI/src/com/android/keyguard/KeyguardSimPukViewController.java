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

package com.android.keyguard;

import android.annotation.NonNull;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.telephony.PinResult;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.WindowManager;
import android.widget.ImageView;

import com.android.internal.util.LatencyTracker;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.keyguard.domain.interactor.KeyguardKeyboardInteractor;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.res.R;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;

public class KeyguardSimPukViewController
        extends KeyguardPinBasedInputViewController<KeyguardSimPukView> {
    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    public static final String TAG = "KeyguardSimPukView";

    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final TelephonyManager mTelephonyManager;

    private String mPukText;
    private String mPinText;
    private int mRemainingAttempts;
    // Below flag is set to true during power-up or when a new SIM card inserted on device.
    // When this is true and when SIM card is PUK locked state, on PIN lock screen, message would
    // be displayed to inform user about the number of remaining PUK attempts left.
    private boolean mShowDefaultMessage;
    private StateMachine mStateMachine = new StateMachine();
    private int mSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private CheckSimPuk mCheckSimPukThread;
    private ProgressDialog mSimUnlockProgressDialog;

    KeyguardUpdateMonitorCallback mUpdateMonitorCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onSimStateChanged(int subId, int slotId, int simState) {
            if (DEBUG) Log.v(TAG, "onSimStateChanged(subId=" + subId + ",state=" + simState + ")");
            // If the SIM is unlocked via a key sequence through the emergency dialer, it will
            // move into the READY state and the PUK lock keyguard should be removed.
            if (simState == TelephonyManager.SIM_STATE_READY) {
                mRemainingAttempts = -1;
                mShowDefaultMessage = true;
                getKeyguardSecurityCallback().dismiss(
                        true, mSelectedUserInteractor.getSelectedUserId(),
                        SecurityMode.SimPuk);
            } else {
                resetState();
            }
        }
    };
    private ImageView mSimImageView;
    private AlertDialog mRemainingAttemptsDialog;

    protected KeyguardSimPukViewController(KeyguardSimPukView view,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            SecurityMode securityMode, LockPatternUtils lockPatternUtils,
            KeyguardSecurityCallback keyguardSecurityCallback,
            KeyguardMessageAreaController.Factory messageAreaControllerFactory,
            LatencyTracker latencyTracker, LiftToActivateListener liftToActivateListener,
            TelephonyManager telephonyManager, FalsingCollector falsingCollector,
            EmergencyButtonController emergencyButtonController, FeatureFlags featureFlags,
            SelectedUserInteractor selectedUserInteractor,
            KeyguardKeyboardInteractor keyguardKeyboardInteractor) {
        super(view, keyguardUpdateMonitor, securityMode, lockPatternUtils, keyguardSecurityCallback,
                messageAreaControllerFactory, latencyTracker, liftToActivateListener,
                emergencyButtonController, falsingCollector, featureFlags, selectedUserInteractor,
                keyguardKeyboardInteractor);
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mTelephonyManager = telephonyManager;
        mSimImageView = mView.findViewById(R.id.keyguard_sim);
    }

    @Override
    protected void onViewAttached() {
        super.onViewAttached();
        mKeyguardUpdateMonitor.registerCallback(mUpdateMonitorCallback);
    }

    @Override
    protected void onViewDetached() {
        super.onViewDetached();
        mKeyguardUpdateMonitor.removeCallback(mUpdateMonitorCallback);
    }

    @Override
    public void updateMessageAreaVisibility() {
        if (mMessageAreaController == null) return;
        mMessageAreaController.setIsVisible(true);
    }

    @Override
    public void onResume(int reason) {
        super.onResume(reason);
        if (mShowDefaultMessage) {
            showDefaultMessage();
        }
    }

    @Override
    void resetState() {
        super.resetState();
        mStateMachine.reset();
    }

    @Override
    protected void verifyPasswordAndUnlock() {
        mStateMachine.next();
    }

    private class StateMachine {
        static final int ENTER_PUK = 0;
        static final int ENTER_PIN = 1;
        static final int CONFIRM_PIN = 2;
        static final int DONE = 3;

        private int mState = ENTER_PUK;

        public void next() {
            int msg = 0;
            if (mState == ENTER_PUK) {
                if (checkPuk()) {
                    mState = ENTER_PIN;
                    msg = com.android.systemui.res.R.string.kg_puk_enter_pin_hint;
                } else {
                    msg = com.android.systemui.res.R.string.kg_invalid_sim_puk_hint;
                }
            } else if (mState == ENTER_PIN) {
                if (checkPin()) {
                    mState = CONFIRM_PIN;
                    msg = com.android.systemui.res.R.string.kg_enter_confirm_pin_hint;
                } else {
                    msg = com.android.systemui.res.R.string.kg_invalid_sim_pin_hint;
                }
            } else if (mState == CONFIRM_PIN) {
                if (confirmPin()) {
                    mState = DONE;
                    msg = com.android.systemui.res.R.string.keyguard_sim_unlock_progress_dialog_message;
                    updateSim();
                } else {
                    mState = ENTER_PIN; // try again?
                    msg = com.android.systemui.res.R.string.kg_invalid_confirm_pin_hint;
                }
            }
            mView.resetPasswordText(true /* animate */, true /* announce */);
            if (msg != 0) {
                mMessageAreaController.setMessage(msg);
            }
        }


        void reset() {
            mPinText = "";
            mPukText = "";
            mState = ENTER_PUK;
            handleSubInfoChangeIfNeeded();
            if (mShowDefaultMessage) {
                showDefaultMessage();
            }

            mView.setESimLocked(KeyguardEsimArea.isEsimLocked(mView.getContext(), mSubId), mSubId);

            mPasswordEntry.requestFocus();
        }
    }

    private void showDefaultMessage() {
        if (mRemainingAttempts >= 0) {
            mMessageAreaController.setMessage(mView.getPukPasswordErrorMessage(
                    mRemainingAttempts, true,
                    KeyguardEsimArea.isEsimLocked(mView.getContext(), mSubId)));
            return;
        }

        boolean isEsimLocked = KeyguardEsimArea.isEsimLocked(mView.getContext(), mSubId);
        int count = 1;
        if (mTelephonyManager != null) {
            count = mTelephonyManager.getActiveModemCount();
        }
        Resources rez = mView.getResources();
        String msg;
        TypedArray array = mView.getContext().obtainStyledAttributes(
                new int[] { android.R.attr.textColor });
        int color = array.getColor(0, Color.WHITE);
        array.recycle();
        if (count < 2) {
            msg = rez.getString(R.string.kg_puk_enter_puk_hint);
        } else {
            SubscriptionInfo info = mKeyguardUpdateMonitor.getSubscriptionInfoForSubId(mSubId);
            CharSequence displayName = info != null ? info.getDisplayName() : "";
            if (!TextUtils.isEmpty(displayName)) {
                msg = rez.getString(R.string.kg_puk_enter_puk_hint_multi, displayName);
            } else {
                msg = rez.getString(R.string.kg_puk_enter_puk_hint);
            }
            if (info != null) {
                color = info.getIconTint();
            }
        }
        if (isEsimLocked) {
            msg = rez.getString(R.string.kg_sim_lock_esim_instructions, msg);
        }
        mMessageAreaController.setMessage(msg);
        mSimImageView.setImageTintList(ColorStateList.valueOf(color));

        // Sending empty PUK here to query the number of remaining PIN attempts
        new CheckSimPuk("", "", mSubId) {
            void onSimLockChangedResponse(final PinResult result) {
                if (result == null) Log.e(TAG, "onSimCheckResponse, pin result is NULL");
                else {
                    Log.d(TAG, "onSimCheckResponse " + " empty One result "
                            + result.toString());
                    if (result.getAttemptsRemaining() >= 0) {
                        mRemainingAttempts = result.getAttemptsRemaining();
                        mMessageAreaController.setMessage(
                                mView.getPukPasswordErrorMessage(
                                        result.getAttemptsRemaining(), true,
                                        KeyguardEsimArea.isEsimLocked(mView.getContext(), mSubId)));
                    }
                }
            }
        }.start();
    }

    private boolean checkPuk() {
        // make sure the puk is at least 8 digits long.
        if (mPasswordEntry.getText().length() >= 8) {
            mPukText = mPasswordEntry.getText();
            return true;
        }
        return false;
    }

    private boolean checkPin() {
        // make sure the PIN is between 4 and 8 digits
        int length = mPasswordEntry.getText().length();
        if (length >= 4 && length <= 8) {
            mPinText = mPasswordEntry.getText();
            return true;
        }
        return false;
    }

    public boolean confirmPin() {
        return mPinText.equals(mPasswordEntry.getText());
    }

    private void updateSim() {
        getSimUnlockProgressDialog().show();

        if (mCheckSimPukThread == null) {
            mCheckSimPukThread = new CheckSimPuk(mPukText, mPinText, mSubId) {
                @Override
                void onSimLockChangedResponse(final PinResult result) {
                    mView.post(() -> {
                        if (mSimUnlockProgressDialog != null) {
                            mSimUnlockProgressDialog.hide();
                        }
                        mView.resetPasswordText(true /* animate */,
                                /* announce */
                                result.getResult() != PinResult.PIN_RESULT_TYPE_SUCCESS);
                        if (result.getResult() == PinResult.PIN_RESULT_TYPE_SUCCESS) {
                            mKeyguardUpdateMonitor.reportSimUnlocked(mSubId);
                            mRemainingAttempts = -1;
                            mShowDefaultMessage = true;

                            getKeyguardSecurityCallback().dismiss(
                                    true, mSelectedUserInteractor.getSelectedUserId(),
                                    SecurityMode.SimPuk);
                        } else {
                            mShowDefaultMessage = false;
                            if (result.getResult() == PinResult.PIN_RESULT_TYPE_INCORRECT) {
                                // show message
                                mMessageAreaController.setMessage(mView.getPukPasswordErrorMessage(
                                        result.getAttemptsRemaining(), false,
                                        KeyguardEsimArea.isEsimLocked(mView.getContext(), mSubId)));
                                if (result.getAttemptsRemaining() <= 2) {
                                    // this is getting critical - show dialog
                                    getPukRemainingAttemptsDialog(
                                            result.getAttemptsRemaining()).show();
                                } else {
                                    // show message
                                    mMessageAreaController.setMessage(
                                            mView.getPukPasswordErrorMessage(
                                                    result.getAttemptsRemaining(), false,
                                                    KeyguardEsimArea.isEsimLocked(
                                                            mView.getContext(), mSubId)));
                                }
                            } else {
                                mMessageAreaController.setMessage(mView.getResources().getString(
                                        R.string.kg_password_puk_failed));
                            }
                            if (DEBUG) {
                                Log.d(TAG, "verifyPasswordAndUnlock "
                                        + " UpdateSim.onSimCheckResponse: "
                                        + " attemptsRemaining=" + result.getAttemptsRemaining());
                            }
                        }
                        mStateMachine.reset();
                        mCheckSimPukThread = null;
                    });
                }
            };
            mCheckSimPukThread.start();
        }
    }

    @Override
    protected boolean shouldLockout(long deadline) {
        // SIM PUK doesn't have a timed lockout
        return false;
    }

    private Dialog getSimUnlockProgressDialog() {
        if (mSimUnlockProgressDialog == null) {
            mSimUnlockProgressDialog = new ProgressDialog(mView.getContext());
            mSimUnlockProgressDialog.setMessage(
                    mView.getResources().getString(R.string.kg_sim_unlock_progress_dialog_message));
            mSimUnlockProgressDialog.setIndeterminate(true);
            mSimUnlockProgressDialog.setCancelable(false);
            if (!(mView.getContext() instanceof Activity)) {
                mSimUnlockProgressDialog.getWindow().setType(
                        WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
            }
        }
        return mSimUnlockProgressDialog;
    }

    private void handleSubInfoChangeIfNeeded() {
        int subId = mKeyguardUpdateMonitor.getNextSubIdForState(
                TelephonyManager.SIM_STATE_PUK_REQUIRED);
        if (subId != mSubId && SubscriptionManager.isValidSubscriptionId(subId)) {
            mSubId = subId;
            mShowDefaultMessage = true;
            mRemainingAttempts = -1;
        }
    }


    private Dialog getPukRemainingAttemptsDialog(int remaining) {
        String msg = mView.getPukPasswordErrorMessage(remaining, false,
                KeyguardEsimArea.isEsimLocked(mView.getContext(), mSubId));
        if (mRemainingAttemptsDialog == null) {
            AlertDialog.Builder builder = new AlertDialog.Builder(mView.getContext());
            builder.setMessage(msg);
            builder.setCancelable(false);
            builder.setNeutralButton(R.string.ok, null);
            mRemainingAttemptsDialog = builder.create();
            mRemainingAttemptsDialog.getWindow().setType(
                    WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        } else {
            mRemainingAttemptsDialog.setMessage(msg);
        }
        return mRemainingAttemptsDialog;
    }

    @Override
    public void onPause() {
        // dismiss the dialog.
        if (mSimUnlockProgressDialog != null) {
            mSimUnlockProgressDialog.dismiss();
            mSimUnlockProgressDialog = null;
        }
    }

    /**
     * Since the IPC can block, we want to run the request in a separate thread
     * with a callback.
     */
    private abstract class CheckSimPuk extends Thread {

        private final String mPin, mPuk;
        private final int mSubId;

        protected CheckSimPuk(String puk, String pin, int subId) {
            mPuk = puk;
            mPin = pin;
            mSubId = subId;
        }

        abstract void onSimLockChangedResponse(@NonNull PinResult result);

        @Override
        public void run() {
            if (DEBUG) {
                Log.v(TAG, "call supplyIccLockPuk(subid=" + mSubId + ")");
            }
            TelephonyManager telephonyManager = mTelephonyManager.createForSubscriptionId(mSubId);
            final PinResult result = telephonyManager.supplyIccLockPuk(mPuk, mPin);
            if (DEBUG) {
                Log.v(TAG, "supplyIccLockPuk returned: " + result.toString());
            }
            mView.post(() -> onSimLockChangedResponse(result));
        }
    }

}
