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

import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;

import static com.android.systemui.util.PluralMessageFormaterKt.icuMessageFormat;

import android.annotation.NonNull;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
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
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import com.android.internal.util.LatencyTracker;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.keyguard.domain.interactor.KeyguardKeyboardInteractor;
import com.android.systemui.bouncer.ui.helper.BouncerHapticPlayer;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.res.R;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;

public class KeyguardSimPinViewController
        extends KeyguardPinBasedInputViewController<KeyguardSimPinView> {
    public static final String TAG = "KeyguardSimPinView";
    private static final String LOG_TAG = "KeyguardSimPinView";
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final TelephonyManager mTelephonyManager;

    private ProgressDialog mSimUnlockProgressDialog;
    private CheckSimPin mCheckSimPinThread;
    private int mRemainingAttempts = -1;
    // Below flag is set to true during power-up or when a new SIM card inserted on device.
    // When this is true and when SIM card is PIN locked state, on PIN lock screen, message would
    // be displayed to inform user about the number of remaining PIN attempts left.
    private boolean mShowDefaultMessage;
    private int mSubId = INVALID_SUBSCRIPTION_ID;
    private AlertDialog mRemainingAttemptsDialog;
    private ImageView mSimImageView;

    KeyguardUpdateMonitorCallback mUpdateMonitorCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onSimStateChanged(int subId, int slotId, int simState) {
            Log.v(TAG, "onSimStateChanged(subId=" + subId + ",state=" + simState + ")");
            // If subId has gone to PUK required then we need to go to the PUK screen.
            if (subId == mSubId && simState == TelephonyManager.SIM_STATE_PUK_REQUIRED) {
                getKeyguardSecurityCallback().showCurrentSecurityScreen();
                return;
            }

            if (simState == TelephonyManager.SIM_STATE_READY) {
                mRemainingAttempts = -1;
                resetState();
            } else {
                resetState();
            }
        }
    };

    protected KeyguardSimPinViewController(KeyguardSimPinView view,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            SecurityMode securityMode, LockPatternUtils lockPatternUtils,
            KeyguardSecurityCallback keyguardSecurityCallback,
            KeyguardMessageAreaController.Factory messageAreaControllerFactory,
            LatencyTracker latencyTracker, LiftToActivateListener liftToActivateListener,
            TelephonyManager telephonyManager, FalsingCollector falsingCollector,
            EmergencyButtonController emergencyButtonController, FeatureFlags featureFlags,
            SelectedUserInteractor selectedUserInteractor,
            KeyguardKeyboardInteractor keyguardKeyboardInteractor,
            BouncerHapticPlayer bouncerHapticPlayer,
            UserActivityNotifier userActivityNotifier) {
        super(view, keyguardUpdateMonitor, securityMode, lockPatternUtils, keyguardSecurityCallback,
                messageAreaControllerFactory, latencyTracker, liftToActivateListener,
                emergencyButtonController, falsingCollector, featureFlags, selectedUserInteractor,
                keyguardKeyboardInteractor, bouncerHapticPlayer, userActivityNotifier);
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
    void resetState() {
        super.resetState();
        Log.v(TAG, "Resetting state");
        handleSubInfoChangeIfNeeded();
        mMessageAreaController.setMessage("");
        if (mShowDefaultMessage) {
            showDefaultMessage();
        }

        mView.setESimLocked(KeyguardEsimArea.isEsimLocked(mView.getContext(), mSubId), mSubId);
    }

    @Override
    public boolean startDisappearAnimation(Runnable finishRunnable) {
        return false;
    }

    @Override
    public void onResume(int reason) {
        super.onResume(reason);
        mView.resetState();
    }

    @Override
    public void onPause() {
        super.onPause();

        // dismiss the dialog.
        if (mSimUnlockProgressDialog != null) {
            mSimUnlockProgressDialog.dismiss();
            mSimUnlockProgressDialog = null;
        }
    }

    @Override
    protected void verifyPasswordAndUnlock() {
        String entry = mPasswordEntry.getText();

        // A SIM PIN is 4 to 8 decimal digits according to 
        // GSM 02.17 version 5.0.1, Section 5.6 PIN Management
        if ((entry.length() < 4) || (entry.length() > 8)) {
            // otherwise, display a message to the user, and don't submit.
            mView.resetPasswordText(true /* animate */, true /* announce */);
            getKeyguardSecurityCallback().userActivity();
            mMessageAreaController.setMessage(
                    com.android.systemui.res.R.string.kg_invalid_sim_pin_hint);
            return;
        }

        getSimUnlockProgressDialog().show();

        if (mCheckSimPinThread == null) {
            mCheckSimPinThread = new CheckSimPin(mPasswordEntry.getText(), mSubId) {
                @Override
                void onSimCheckResponse(final PinResult result) {
                    mView.post(() -> {
                        mRemainingAttempts = result.getAttemptsRemaining();
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
                                    SecurityMode.SimPin);
                        } else {
                            mShowDefaultMessage = false;
                            if (result.getResult() == PinResult.PIN_RESULT_TYPE_INCORRECT) {
                                if (result.getAttemptsRemaining() <= 2) {
                                    // this is getting critical - show dialog
                                    getSimRemainingAttemptsDialog(
                                            result.getAttemptsRemaining()).show();
                                } else {
                                    // show message
                                    mMessageAreaController.setMessage(
                                            getPinPasswordErrorMessage(
                                                    result.getAttemptsRemaining(), false));
                                }
                            } else {
                                // "PIN operation failed!" - no idea what this was and no way to
                                // find out. :/
                                mMessageAreaController.setMessage(mView.getResources().getString(
                                        R.string.kg_password_pin_failed));
                            }
                            Log.d(LOG_TAG, "verifyPasswordAndUnlock "
                                    + " CheckSimPin.onSimCheckResponse: " + result
                                    + " attemptsRemaining=" + result.getAttemptsRemaining());
                        }
                        getKeyguardSecurityCallback().userActivity();
                        mCheckSimPinThread = null;
                    });
                }
            };
            mCheckSimPinThread.start();
        }
    }

    private Dialog getSimUnlockProgressDialog() {
        if (mSimUnlockProgressDialog == null) {
            mSimUnlockProgressDialog = new ProgressDialog(mView.getContext());
            mSimUnlockProgressDialog.setMessage(
                    mView.getResources().getString(R.string.kg_sim_unlock_progress_dialog_message));
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
            Builder builder = new AlertDialog.Builder(mView.getContext());
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


    private String getPinPasswordErrorMessage(int attemptsRemaining, boolean isDefault) {
        String displayMessage;
        int msgId;
        if (attemptsRemaining == 0) {
            displayMessage = mView.getResources().getString(
                    R.string.kg_password_wrong_pin_code_pukked);
        } else if (attemptsRemaining > 0) {
            msgId = isDefault ? R.string.kg_password_default_pin_message :
                    R.string.kg_password_wrong_pin_code;
            displayMessage = icuMessageFormat(mView.getResources(), msgId, attemptsRemaining);
        } else {
            msgId = isDefault ? R.string.kg_sim_pin_instructions : R.string.kg_password_pin_failed;
            displayMessage = mView.getResources().getString(msgId);
        }
        if (KeyguardEsimArea.isEsimLocked(mView.getContext(), mSubId)) {
            displayMessage = mView.getResources()
                    .getString(R.string.kg_sim_lock_esim_instructions, displayMessage);
        }
        Log.d(LOG_TAG, "getPinPasswordErrorMessage: attemptsRemaining="
                + attemptsRemaining + " displayMessage=" + displayMessage);
        return displayMessage;
    }

    private void showDefaultMessage() {
        setLockedSimMessage();
        if (mRemainingAttempts >= 0) {
            return;
        }

        // Sending empty PIN here to query the number of remaining PIN attempts
        new CheckSimPin("", mSubId) {
            void onSimCheckResponse(final PinResult result) {
                Log.d(LOG_TAG, "onSimCheckResponse " + " empty One result "
                        + result.toString());
                if (result.getAttemptsRemaining() >= 0) {
                    mRemainingAttempts = result.getAttemptsRemaining();
                    setLockedSimMessage();
                }
            }
        }.start();
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
            Log.v(TAG, "call supplyIccLockPin(subid=" + mSubId + ")");
            TelephonyManager telephonyManager = mTelephonyManager.createForSubscriptionId(mSubId);
            final PinResult result = telephonyManager.supplyIccLockPin(mPin);
            Log.v(TAG, "supplyIccLockPin returned: " + result.toString());
            mView.post(() -> onSimCheckResponse(result));
        }
    }

    private void setLockedSimMessage() {
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
            msg = rez.getString(R.string.kg_sim_pin_instructions);
        } else {
            SubscriptionInfo info = mKeyguardUpdateMonitor.getSubscriptionInfoForSubId(mSubId);
            CharSequence displayName = info != null ? info.getDisplayName() : ""; // don't crash
            if (!TextUtils.isEmpty(displayName)) {
                msg = rez.getString(R.string.kg_sim_pin_instructions_multi, displayName);
            } else {
                msg = rez.getString(R.string.kg_sim_pin_instructions);
            }
            if (info != null) {
                color = info.getIconTint();
            }
        }
        if (isEsimLocked) {
            msg = rez.getString(R.string.kg_sim_lock_esim_instructions, msg);
        }

        if (mView.getVisibility() == View.VISIBLE) {
            mMessageAreaController.setMessage(msg);
        }
        mSimImageView.setImageTintList(ColorStateList.valueOf(color));
    }

    private void handleSubInfoChangeIfNeeded() {
        int subId = mKeyguardUpdateMonitor
                .getNextSubIdForState(TelephonyManager.SIM_STATE_PIN_REQUIRED);
        if (subId != mSubId && SubscriptionManager.isValidSubscriptionId(subId)) {
            mSubId = subId;
            mShowDefaultMessage = true;
            mRemainingAttempts = -1;
        }
    }
}
