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
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
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
 * Displays a PIN pad for unlocking.
 */
public class KeyguardSimPinView extends KeyguardPinBasedInputView {
    private static final String LOG_TAG = "KeyguardSimPinView";
    private static final boolean DEBUG = KeyguardConstants.DEBUG_SIM_STATES;
    public static final String TAG = "KeyguardSimPinView";

    private ProgressDialog mSimUnlockProgressDialog = null;
    private CheckSimPin mCheckSimPinThread;

    // Below flag is set to true during power-up or when a new SIM card inserted on device.
    // When this is true and when SIM card is PIN locked state, on PIN lock screen, message would
    // be displayed to inform user about the number of remaining PIN attempts left.
    private boolean mShowDefaultMessage = true;
    private int mRemainingAttempts = -1;
    private AlertDialog mRemainingAttemptsDialog;
    private int mSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private ImageView mSimImageView;

    KeyguardUpdateMonitorCallback mUpdateMonitorCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onSimStateChanged(int subId, int slotId, int simState) {
            if (DEBUG) Log.v(TAG, "onSimStateChanged(subId=" + subId + ",state=" + simState + ")");
            switch(simState) {
                case TelephonyManager.SIM_STATE_READY: {
                    mRemainingAttempts = -1;
                    resetState();
                    break;
                }
                default:
                    resetState();
            }
        }
    };

    public KeyguardSimPinView(Context context) {
        this(context, null);
    }

    public KeyguardSimPinView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void resetState() {
        super.resetState();
        if (DEBUG) Log.v(TAG, "Resetting state");
        handleSubInfoChangeIfNeeded();
        if (mShowDefaultMessage) {
            showDefaultMessage();
        }
        boolean isEsimLocked = KeyguardEsimArea.isEsimLocked(mContext, mSubId);

        KeyguardEsimArea esimButton = findViewById(R.id.keyguard_esim_area);
        esimButton.setVisibility(isEsimLocked ? View.VISIBLE : View.GONE);
    }

    private void setLockedSimMessage() {
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
            msg = rez.getString(R.string.kg_sim_pin_instructions);
        } else {
            SubscriptionInfo info = Dependency.get(KeyguardUpdateMonitor.class)
                    .getSubscriptionInfoForSubId(mSubId);
            CharSequence displayName = info != null ? info.getDisplayName() : ""; // don't crash
            msg = rez.getString(R.string.kg_sim_pin_instructions_multi, displayName);
            if (info != null) {
                color = info.getIconTint();
            }
        }
        if (isEsimLocked) {
            msg = rez.getString(R.string.kg_sim_lock_esim_instructions, msg);
        }

        if (mSecurityMessageDisplay != null && getVisibility() == VISIBLE) {
            mSecurityMessageDisplay.setMessage(msg);
        }
        mSimImageView.setImageTintList(ColorStateList.valueOf(color));
    }

    private void showDefaultMessage() {
        setLockedSimMessage();
        if (mRemainingAttempts >= 0) {
            return;
        }

        // Sending empty PIN here to query the number of remaining PIN attempts
        new CheckSimPin("", mSubId) {
            void onSimCheckResponse(final PinResult result) {
                Log.d(LOG_TAG, "onSimCheckResponse " + " dummy One result "
                        + result.toString());
                if (result.getAttemptsRemaining() >= 0) {
                    mRemainingAttempts = result.getAttemptsRemaining();
                    setLockedSimMessage();
                }
            }
        }.start();
    }

    private void handleSubInfoChangeIfNeeded() {
        KeyguardUpdateMonitor monitor = Dependency.get(KeyguardUpdateMonitor.class);
        int subId = monitor.getNextSubIdForState(TelephonyManager.SIM_STATE_PIN_REQUIRED);
        if (subId != mSubId && SubscriptionManager.isValidSubscriptionId(subId)) {
            mSubId = subId;
            mShowDefaultMessage = true;
            mRemainingAttempts = -1;
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        resetState();
    }

    @Override
    protected int getPromptReasonStringRes(int reason) {
        // No message on SIM Pin
        return 0;
    }

    private String getPinPasswordErrorMessage(int attemptsRemaining, boolean isDefault) {
        String displayMessage;
        int msgId;
        if (attemptsRemaining == 0) {
            displayMessage = getContext().getString(R.string.kg_password_wrong_pin_code_pukked);
        } else if (attemptsRemaining > 0) {
            msgId = isDefault ? R.plurals.kg_password_default_pin_message :
                     R.plurals.kg_password_wrong_pin_code;
            displayMessage = getContext().getResources()
                    .getQuantityString(msgId, attemptsRemaining, attemptsRemaining);
        } else {
            msgId = isDefault ? R.string.kg_sim_pin_instructions : R.string.kg_password_pin_failed;
            displayMessage = getContext().getString(msgId);
        }
        if (KeyguardEsimArea.isEsimLocked(mContext, mSubId)) {
            displayMessage = getResources()
                    .getString(R.string.kg_sim_lock_esim_instructions, displayMessage);
        }
        if (DEBUG) Log.d(LOG_TAG, "getPinPasswordErrorMessage:"
                + " attemptsRemaining=" + attemptsRemaining + " displayMessage=" + displayMessage);
        return displayMessage;
    }

    @Override
    protected boolean shouldLockout(long deadline) {
        // SIM PIN doesn't have a timed lockout
        return false;
    }

    @Override
    protected int getPasswordTextViewId() {
        return R.id.simPinEntry;
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
    public void showUsabilityHint() {

    }

    @Override
    public void onResume(int reason) {
        super.onResume(reason);
        Dependency.get(KeyguardUpdateMonitor.class).registerCallback(mUpdateMonitorCallback);
        resetState();
    }

    @Override
    public void onPause() {
        // dismiss the dialog.
        if (mSimUnlockProgressDialog != null) {
            mSimUnlockProgressDialog.dismiss();
            mSimUnlockProgressDialog = null;
        }
        Dependency.get(KeyguardUpdateMonitor.class).removeCallback(mUpdateMonitorCallback);
    }

    /**
     * Since the IPC can block, we want to run the request in a separate thread
     * with a callback.
     */
    private abstract class CheckSimPin extends Thread {
        private final String mPin;
        private int mSubId;

        protected CheckSimPin(String pin, int subId) {
            mPin = pin;
            mSubId = subId;
        }

        abstract void onSimCheckResponse(@NonNull PinResult result);

        @Override
        public void run() {
            if (DEBUG) {
                Log.v(TAG, "call supplyPinReportResultForSubscriber(subid=" + mSubId + ")");
            }
            TelephonyManager telephonyManager =
                    ((TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE))
                            .createForSubscriptionId(mSubId);
            final PinResult result = telephonyManager.supplyPinReportPinResult(mPin);
            if (result == null) {
                Log.e(TAG, "Error result for supplyPinReportResult.");
                post(new Runnable() {
                    @Override
                    public void run() {
                        onSimCheckResponse(PinResult.getDefaultFailedResult());
                    }
                });
            } else {
                if (DEBUG) {
                    Log.v(TAG, "supplyPinReportResult returned: " + result.toString());
                }
                post(new Runnable() {
                    @Override
                    public void run() {
                        onSimCheckResponse(result);
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
            mSimUnlockProgressDialog.getWindow().setType(
                    WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        }
        return mSimUnlockProgressDialog;
    }

    private Dialog getSimRemainingAttemptsDialog(int remaining) {
        String msg = getPinPasswordErrorMessage(remaining, false);
        if (mRemainingAttemptsDialog == null) {
            Builder builder = new AlertDialog.Builder(mContext);
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
    protected void verifyPasswordAndUnlock() {
        String entry = mPasswordEntry.getText();

        if (entry.length() < 4) {
            // otherwise, display a message to the user, and don't submit.
            mSecurityMessageDisplay.setMessage(R.string.kg_invalid_sim_pin_hint);
            resetPasswordText(true /* animate */, true /* announce */);
            mCallback.userActivity();
            return;
        }

        getSimUnlockProgressDialog().show();

        if (mCheckSimPinThread == null) {
            mCheckSimPinThread = new CheckSimPin(mPasswordEntry.getText(), mSubId) {
                @Override
                void onSimCheckResponse(final PinResult result) {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            mRemainingAttempts = result.getAttemptsRemaining();
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
                                        SecurityMode.SimPin);
                                }
                            } else {
                                mShowDefaultMessage = false;
                                if (result.getType() == PinResult.PIN_RESULT_TYPE_INCORRECT) {
                                    if (result.getAttemptsRemaining() <= 2) {
                                        // this is getting critical - show dialog
                                        getSimRemainingAttemptsDialog(
                                                result.getAttemptsRemaining()).show();
                                    } else {
                                        // show message
                                        mSecurityMessageDisplay.setMessage(
                                                getPinPasswordErrorMessage(
                                                        result.getAttemptsRemaining(), false));
                                    }
                                } else {
                                    // "PIN operation failed!" - no idea what this was and no way to
                                    // find out. :/
                                    mSecurityMessageDisplay.setMessage(getContext().getString(
                                            R.string.kg_password_pin_failed));
                                }
                                if (DEBUG) Log.d(LOG_TAG, "verifyPasswordAndUnlock "
                                        + " CheckSimPin.onSimCheckResponse: " + result
                                        + " attemptsRemaining=" + result.getAttemptsRemaining());
                            }
                            mCallback.userActivity();
                            mCheckSimPinThread = null;
                        }
                    });
                }
            };
            mCheckSimPinThread.start();
        }
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
                com.android.internal.R.string.keyguard_accessibility_sim_pin_unlock);
    }

    @Override
    public SecurityMode getSecurityMode() {
        return SecurityMode.SimPin;
    }
}

