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

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.IccCardConstants.State;
import com.android.internal.telephony.PhoneConstants;
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
        public void onSimStateChanged(int subId, int slotId, State simState) {
            if (DEBUG) Log.v(TAG, "onSimStateChanged(subId=" + subId + ",state=" + simState + ")");
            switch(simState) {
                // If the SIM is unlocked via a key sequence through the emergency dialer, it will
                // move into the READY state and the PUK lock keyguard should be removed.
                case READY: {
                    mRemainingAttempts = -1;
                    mShowDefaultMessage = true;
                    // mCallback can be null if onSimStateChanged callback is called when keyguard
                    // isn't active.
                    if (mCallback != null) {
                        mCallback.dismiss(true, KeyguardUpdateMonitor.getCurrentUser());
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
        int count = TelephonyManager.getDefault().getSimCount();
        Resources rez = getResources();
        String msg;
        TypedArray array = mContext.obtainStyledAttributes(new int[] { R.attr.wallpaperTextColor });
        int color = array.getColor(0, Color.WHITE);
        array.recycle();
        if (count < 2) {
            msg = rez.getString(R.string.kg_puk_enter_puk_hint);
        } else {
            SubscriptionInfo info = KeyguardUpdateMonitor.getInstance(mContext).
                    getSubscriptionInfoForSubId(mSubId);
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
            void onSimLockChangedResponse(final int result, final int attemptsRemaining) {
                Log.d(LOG_TAG, "onSimCheckResponse " + " dummy One result" + result +
                        " attemptsRemaining=" + attemptsRemaining);
                if (attemptsRemaining >= 0) {
                    mRemainingAttempts = attemptsRemaining;
                    mSecurityMessageDisplay.setMessage(
                            getPukPasswordErrorMessage(attemptsRemaining, true));
                }
            }
        }.start();
    }

    private void handleSubInfoChangeIfNeeded() {
        KeyguardUpdateMonitor monitor = KeyguardUpdateMonitor.getInstance(mContext);
        int subId = monitor.getNextSubIdForState(IccCardConstants.State.PUK_REQUIRED);
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
        KeyguardUpdateMonitor.getInstance(mContext).registerCallback(mUpdateMonitorCallback);
        resetState();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        KeyguardUpdateMonitor.getInstance(mContext).removeCallback(mUpdateMonitorCallback);
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

        abstract void onSimLockChangedResponse(final int result, final int attemptsRemaining);

        @Override
        public void run() {
            try {
                if (DEBUG) Log.v(TAG, "call supplyPukReportResult()");
                final int[] result = ITelephony.Stub.asInterface(ServiceManager
                    .checkService("phone")).supplyPukReportResultForSubscriber(mSubId, mPuk, mPin);
                if (DEBUG) {
                    Log.v(TAG, "supplyPukReportResult returned: " + result[0] + " " + result[1]);
                }
                post(new Runnable() {
                    @Override
                    public void run() {
                        onSimLockChangedResponse(result[0], result[1]);
                    }
                });
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException for supplyPukReportResult:", e);
                post(new Runnable() {
                    @Override
                    public void run() {
                        onSimLockChangedResponse(PhoneConstants.PIN_GENERAL_FAILURE, -1);
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
                void onSimLockChangedResponse(final int result, final int attemptsRemaining) {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            if (mSimUnlockProgressDialog != null) {
                                mSimUnlockProgressDialog.hide();
                            }
                            resetPasswordText(true /* animate */,
                                    result != PhoneConstants.PIN_RESULT_SUCCESS /* announce */);
                            if (result == PhoneConstants.PIN_RESULT_SUCCESS) {
                                KeyguardUpdateMonitor.getInstance(getContext())
                                        .reportSimUnlocked(mSubId);
                                mRemainingAttempts = -1;
                                mShowDefaultMessage = true;
                                if (mCallback != null) {
                                    mCallback.dismiss(true, KeyguardUpdateMonitor.getCurrentUser());
                                }
                            } else {
                                mShowDefaultMessage = false;
                                if (result == PhoneConstants.PIN_PASSWORD_INCORRECT) {
                                    // show message
                                    mSecurityMessageDisplay.setMessage(getPukPasswordErrorMessage(
                                            attemptsRemaining, false));
                                    if (attemptsRemaining <= 2) {
                                        // this is getting critical - show dialog
                                        getPukRemainingAttemptsDialog(attemptsRemaining).show();
                                    } else {
                                        // show message
                                        mSecurityMessageDisplay.setMessage(
                                                getPukPasswordErrorMessage(
                                                attemptsRemaining, false));
                                    }
                                } else {
                                    mSecurityMessageDisplay.setMessage(getContext().getString(
                                            R.string.kg_password_puk_failed));
                                }
                                if (DEBUG) Log.d(LOG_TAG, "verifyPasswordAndUnlock "
                                        + " UpdateSim.onSimCheckResponse: "
                                        + " attemptsRemaining=" + attemptsRemaining);
                                mStateMachine.reset();
                            }
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
}


