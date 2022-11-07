/*
 * Copyright (C) 2012 The Android Open Source Project
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
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.telephony.PinResult;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.systemui.Dependency;
import com.android.systemui.R;


/**
 * Displays a PIN pad for entering a PUK (Pin Unlock Kode) provided by a carrier.
 */
public class KeyguardSimPukView extends KeyguardPinBasedInputView {
    private static final String LOG_TAG = "KeyguardSimPukView";
    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    public static final String TAG = "KeyguardSimPukView";

    private ProgressDialog mSimUnlockProgressDialog = null;
    private CheckSimPuk mCheckSimPukThread;

    // Below flag is set to true during power-up or when a new SIM card inserted on device.
    // When this is true and when SIM card is PUK locked state, on PIN lock screen, message would
    // be displayed to inform user about the number of remaining PUK attempts left.
    private boolean mShowDefaultMessage = true;
    private int mRemainingAttempts = -1;
    private String mPukText;
    private String mPinText;
    private StateMachine mStateMachine = new StateMachine();
    private AlertDialog mRemainingAttemptsDialog;
    private int mSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private ImageView mSimImageView;

    KeyguardUpdateMonitorCallback mUpdateMonitorCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onSimStateChanged(int subId, int slotId, int simState) {
            if (DEBUG) Log.v(TAG, "onSimStateChanged(subId=" + subId + ",state=" + simState + ")");
            switch(simState) {
                // If the SIM is unlocked via a key sequence through the emergency dialer, it will
                // move into the READY state and the PUK lock keyguard should be removed.
                case TelephonyManager.SIM_STATE_READY: {
                    mRemainingAttempts = -1;
                    mShowDefaultMessage = true;
                    // mCallback can be null if onSimStateChanged callback is called when keyguard
                    // isn't active.
                    if (mCallback != null) {
                        mCallback.dismiss(true, KeyguardUpdateMonitor.getCurrentUser(),
                            SecurityMode.SimPuk);
                    }
                    break;
                }
                default:
                    resetState();
            }
        }
    };

    public KeyguardSimPukView(Context context) {
        this(context, null);
    }

    public KeyguardSimPukView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    private class StateMachine {
        final int ENTER_PUK = 0;
        final int ENTER_PIN = 1;
        final int CONFIRM_PIN = 2;
        final int DONE = 3;
        private int state = ENTER_PUK;

        public void next() {
            int msg = 0;
            if (state == ENTER_PUK) {
                if (checkPuk()) {
                    state = ENTER_PIN;
                    msg = R.string.kg_puk_enter_pin_hint;
                } else {
                    msg = R.string.kg_invalid_sim_puk_hint;
                }
            } else if (state == ENTER_PIN) {
                if (checkPin()) {
                    state = CONFIRM_PIN;
                    msg = R.string.kg_enter_confirm_pin_hint;
                } else {
                    msg = R.string.kg_invalid_sim_pin_hint;
                }
            } else if (state == CONFIRM_PIN) {
                if (confirmPin()) {
                    state = DONE;
                    msg = R.string.keyguard_sim_unlock_progress_dialog_message;
                    updateSim();
                } else {
                    state = ENTER_PIN; // try again?
                    msg = R.string.kg_invalid_confirm_pin_hint;
                }
            }
            resetPasswordText(true /* animate */, true /* announce */);
            if (msg != 0) {
                mSecurityMessageDisplay.setMessage(msg);
            }
        }


        void reset() {
            mPinText="";
            mPukText="";
            state = ENTER_PUK;
            handleSubInfoChangeIfNeeded();
            if (mShowDefaultMessage) {
                showDefaultMessage();
            }
            boolean isEsimLocked = KeyguardEsimArea.isEsimLocked(mContext, mSubId);

            KeyguardEsimArea esimButton = findViewById(R.id.keyguard_esim_area);
            esimButton.setVisibility(isEsimLocked ? View.VISIBLE : View.GONE);
            mPasswordEntry.requestFocus();
        }


    }

    private void showDefaultMessage() {
        if (mRemainingAttempts >= 0) {
            mSecurityMessageDisplay.setMessage(getPukPasswordErrorMessage(
                    mRemainingAttempts, true));
            return;
        }

        boolean isEsimLocked = KeyguardEsimArea.isEsimLocked(mContext, mSubId);
        int count = 1;
        TelephonyManager telephonyManager =
            (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
        if (telephonyManager != null) {
            count = telephonyManager.getActiveModemCount();
        }
        Resources rez = getResources();
        String msg;
        TypedArray array = mContext.obtainStyledAttributes(new int[] { R.attr.wallpaperTextColor });
        int color = array.getColor(0, Color.WHITE);
        array.recycle();
        if (count < 2) {
            msg = rez.getString(R.string.kg_puk_enter_puk_hint);
        } else {
            SubscriptionInfo info = Dependency.get(KeyguardUpdateMonitor.class)
                    .getSubscriptionInfoForSubId(mSubId);
            CharSequence displayName = info != null ? info.getDisplayName() : "";
            msg = rez.getString(R.string.kg_puk_enter_puk_hint_multi, displayName);
            if (info != null) {
                color = info.getIconTint();
            }
        }
        if (isEsimLocked) {
            msg = rez.getString(R.string.kg_sim_lock_esim_instructions, msg);
        }
        if (mSecurityMessageDisplay != null) {
            mSecurityMessageDisplay.setMessage(msg);
        }
        mSimImageView.setImageTintList(ColorStateList.valueOf(color));

        // Sending empty PUK here to query the number of remaining PIN attempts
        new CheckSimPuk("", "", mSubId) {
            void onSimLockChangedResponse(final PinResult result) {
                if (result == null) Log.e(LOG_TAG, "onSimCheckResponse, pin result is NULL");
                else {
                    Log.d(LOG_TAG, "onSimCheckResponse " + " dummy One result "
                            + result.toString());
                    if (result.getAttemptsRemaining() >= 0) {
                        mRemainingAttempts = result.getAttemptsRemaining();
                        mSecurityMessageDisplay.setMessage(
                                getPukPasswordErrorMessage(result.getAttemptsRemaining(), true));
                    }
                }
            }
        }.start();
    }

    private void handleSubInfoChangeIfNeeded() {
        KeyguardUpdateMonitor monitor = Dependency.get(KeyguardUpdateMonitor.class);
        int subId = monitor.getNextSubIdForState(TelephonyManager.SIM_STATE_PUK_REQUIRED);
        if (subId != mSubId && SubscriptionManager.isValidSubscriptionId(subId)) {
            mSubId = subId;
            mShowDefaultMessage = true;
            mRemainingAttempts = -1;
        }
    }

    @Override
    protected int getPromptReasonStringRes(int reason) {
        // No message on SIM Puk
        return 0;
    }

    private String getPukPasswordErrorMessage(int attemptsRemaining, boolean isDefault) {
        String displayMessage;

        if (attemptsRemaining == 0) {
            displayMessage = getContext().getString(R.string.kg_password_wrong_puk_code_dead);
        } else if (attemptsRemaining > 0) {
            int msgId = isDefault ? R.plurals.kg_password_default_puk_message :
                    R.plurals.kg_password_wrong_puk_code;
            displayMessage = getContext().getResources()
                    .getQuantityString(msgId, attemptsRemaining, attemptsRemaining);
        } else {
            int msgId = isDefault ? R.string.kg_puk_enter_puk_hint :
                    R.string.kg_password_puk_failed;
            displayMessage = getContext().getString(msgId);
        }
        if (KeyguardEsimArea.isEsimLocked(mContext, mSubId)) {
            displayMessage = getResources()
                    .getString(R.string.kg_sim_lock_esim_instructions, displayMessage);
        }
        if (DEBUG) Log.d(LOG_TAG, "getPukPasswordErrorMessage:"
                + " attemptsRemaining=" + attemptsRemaining + " displayMessage=" + displayMessage);
        return displayMessage;
    }

    @Override
    public void resetState() {
        super.resetState();
        mStateMachine.reset();
    }

    @Override
    protected boolean shouldLockout(long deadline) {
        // SIM PUK doesn't have a timed lockout
        return false;
    }

    @Override
    protected int getPasswordTextViewId() {
        return R.id.pukEntry;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        if (mEcaView instanceof EmergencyCarrierArea) {
            ((EmergencyCarrierArea) mEcaView).setCarrierTextVisible(true);
        }
        mSimImageView = findViewById(R.id.keyguard_sim);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Dependency.get(KeyguardUpdateMonitor.class).registerCallback(mUpdateMonitorCallback);
        resetState();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Dependency.get(KeyguardUpdateMonitor.class).removeCallback(mUpdateMonitorCallback);
    }

    @Override
    public void showUsabilityHint() {
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
            if (DEBUG) Log.v(TAG, "call supplyPukReportResult()");
            TelephonyManager telephonyManager =
                    ((TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE))
                            .createForSubscriptionId(mSubId);
            final PinResult result = telephonyManager.supplyPukReportPinResult(mPuk, mPin);
            if (result == null) {
                Log.e(TAG, "Error result for supplyPukReportResult.");
                post(new Runnable() {
                    @Override
                    public void run() {
                        onSimLockChangedResponse(PinResult.getDefaultFailedResult());
                    }
                });
            } else {
                if (DEBUG) {
                    Log.v(TAG, "supplyPukReportResult returned: " + result.toString());
                }
                post(new Runnable() {
                    @Override
                    public void run() {
                        onSimLockChangedResponse(result);
                    }
                });
            }
        }
    }

    private Dialog getSimUnlockProgressDialog() {
        if (mSimUnlockProgressDialog == null) {
            mSimUnlockProgressDialog = new ProgressDialog(mContext);
            mSimUnlockProgressDialog.setMessage(
                    mContext.getString(R.string.kg_sim_unlock_progress_dialog_message));
            mSimUnlockProgressDialog.setIndeterminate(true);
            mSimUnlockProgressDialog.setCancelable(false);
            if (!(mContext instanceof Activity)) {
                mSimUnlockProgressDialog.getWindow().setType(
                        WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
            }
        }
        return mSimUnlockProgressDialog;
    }

    private Dialog getPukRemainingAttemptsDialog(int remaining) {
        String msg = getPukPasswordErrorMessage(remaining, false);
        if (mRemainingAttemptsDialog == null) {
            AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
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

    private boolean checkPuk() {
        // make sure the puk is at least 8 digits long.
        if (mPasswordEntry.getText().length() == 8) {
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
                    post(new Runnable() {
                        @Override
                        public void run() {
                            if (mSimUnlockProgressDialog != null) {
                                mSimUnlockProgressDialog.hide();
                            }
                            resetPasswordText(true /* animate */,
                                    /* announce */
                                    result.getType() != PinResult.PIN_RESULT_TYPE_SUCCESS);
                            if (result.getType() == PinResult.PIN_RESULT_TYPE_SUCCESS) {
                                Dependency.get(KeyguardUpdateMonitor.class)
                                        .reportSimUnlocked(mSubId);
                                mRemainingAttempts = -1;
                                mShowDefaultMessage = true;
                                if (mCallback != null) {
                                    mCallback.dismiss(true, KeyguardUpdateMonitor.getCurrentUser(),
                                        SecurityMode.SimPuk);
                                }
                            } else {
                                mShowDefaultMessage = false;
                                if (result.getType() == PinResult.PIN_RESULT_TYPE_INCORRECT) {
                                    // show message
                                    mSecurityMessageDisplay.setMessage(getPukPasswordErrorMessage(
                                            result.getAttemptsRemaining(), false));
                                    if (result.getAttemptsRemaining() <= 2) {
                                        // this is getting critical - show dialog
                                        getPukRemainingAttemptsDialog(
                                                result.getAttemptsRemaining()).show();
                                    } else {
                                        // show message
                                        mSecurityMessageDisplay.setMessage(
                                                getPukPasswordErrorMessage(
                                                        result.getAttemptsRemaining(), false));
                                    }
                                } else {
                                    mSecurityMessageDisplay.setMessage(getContext().getString(
                                            R.string.kg_password_puk_failed));
                                }
                                if (DEBUG) Log.d(LOG_TAG, "verifyPasswordAndUnlock "
                                        + " UpdateSim.onSimCheckResponse: "
                                        + " attemptsRemaining=" + result.getAttemptsRemaining());
                            }
                            mStateMachine.reset();
                            mCheckSimPukThread = null;
                        }
                    });
                }
            };
            mCheckSimPukThread.start();
        }
    }

    @Override
    protected void verifyPasswordAndUnlock() {
        mStateMachine.next();
    }

    @Override
    public void startAppearAnimation() {
        // noop.
    }

    @Override
    public boolean startDisappearAnimation(Runnable finishRunnable) {
        return false;
    }

    @Override
    public CharSequence getTitle() {
        return getContext().getString(
                com.android.internal.R.string.keyguard_accessibility_sim_puk_unlock);
    }

    @Override
    public SecurityMode getSecurityMode() {
        return SecurityMode.SimPuk;
    }
}


