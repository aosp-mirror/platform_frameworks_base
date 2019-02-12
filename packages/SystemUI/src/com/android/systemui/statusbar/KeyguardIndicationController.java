/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.face.FaceManager;
import android.hardware.fingerprint.FingerprintManager;
import android.os.BatteryManager;
import android.os.BatteryStats;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserManager;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.settingslib.Utils;
import com.android.systemui.Dependency;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.plugins.statusbar.StatusBarStateController.StateListener;
import com.android.systemui.statusbar.phone.KeyguardIndicationTextView;
import com.android.systemui.statusbar.phone.LockIcon;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.util.wakelock.SettableWakeLock;
import com.android.systemui.util.wakelock.WakeLock;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.text.NumberFormat;
import java.util.IllegalFormatConversionException;

/**
 * Controls the indications and error messages shown on the Keyguard
 */
public class KeyguardIndicationController implements StateListener {

    private static final String TAG = "KeyguardIndication";
    private static final boolean DEBUG_CHARGING_SPEED = false;

    private static final int MSG_HIDE_TRANSIENT = 1;
    private static final int MSG_CLEAR_BIOMETRIC_MSG = 2;
    private static final long TRANSIENT_BIOMETRIC_ERROR_TIMEOUT = 1300;

    private final Context mContext;
    private ViewGroup mIndicationArea;
    private KeyguardIndicationTextView mTextView;
    private KeyguardIndicationTextView mDisclosure;
    private final UserManager mUserManager;
    private final IBatteryStats mBatteryInfo;
    private final SettableWakeLock mWakeLock;

    private final int mSlowThreshold;
    private final int mFastThreshold;
    private LockIcon mLockIcon;
    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;

    private String mRestingIndication;
    private CharSequence mTransientIndication;
    private ColorStateList mTransientTextColorState;
    private ColorStateList mInitialTextColorState;
    private boolean mVisible;

    private boolean mPowerPluggedIn;
    private boolean mPowerPluggedInWired;
    private boolean mPowerCharged;
    private int mChargingSpeed;
    private int mChargingWattage;
    private int mBatteryLevel;
    private String mMessageToShowOnScreenOn;

    private KeyguardUpdateMonitorCallback mUpdateMonitorCallback;

    private final DevicePolicyManager mDevicePolicyManager;
    private boolean mDozing;
    private float mDarkAmount;

    /**
     * Creates a new KeyguardIndicationController and registers callbacks.
     */
    public KeyguardIndicationController(Context context, ViewGroup indicationArea,
            LockIcon lockIcon) {
        this(context, indicationArea, lockIcon,
                WakeLock.createPartial(context, "Doze:KeyguardIndication"));

        registerCallbacks(KeyguardUpdateMonitor.getInstance(context));
    }

    /**
     * Creates a new KeyguardIndicationController for testing. Does *not* register callbacks.
     */
    @VisibleForTesting
    KeyguardIndicationController(Context context, ViewGroup indicationArea, LockIcon lockIcon,
                WakeLock wakeLock) {
        mContext = context;
        mIndicationArea = indicationArea;
        mTextView = indicationArea.findViewById(R.id.keyguard_indication_text);
        mInitialTextColorState = mTextView != null ?
                mTextView.getTextColors() : ColorStateList.valueOf(Color.WHITE);
        mDisclosure = indicationArea.findViewById(R.id.keyguard_indication_enterprise_disclosure);
        mLockIcon = lockIcon;
        mWakeLock = new SettableWakeLock(wakeLock);

        Resources res = context.getResources();
        mSlowThreshold = res.getInteger(R.integer.config_chargingSlowlyThreshold);
        mFastThreshold = res.getInteger(R.integer.config_chargingFastThreshold);

        mUserManager = context.getSystemService(UserManager.class);
        mBatteryInfo = IBatteryStats.Stub.asInterface(
                ServiceManager.getService(BatteryStats.SERVICE_NAME));

        mDevicePolicyManager = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);

        updateDisclosure();
    }

    private void registerCallbacks(KeyguardUpdateMonitor monitor) {
        monitor.registerCallback(getKeyguardCallback());

        KeyguardUpdateMonitor.getInstance(mContext).registerCallback(mTickReceiver);
        Dependency.get(StatusBarStateController.class).addCallback(this);
    }

    /**
     * Used by {@link com.android.systemui.statusbar.phone.StatusBar} to give the indication
     * controller a chance to unregister itself as a receiver.
     *
     * //TODO: This can probably be converted to a fragment and not have to be manually recreated
     */
    public void destroy() {
        KeyguardUpdateMonitor.getInstance(mContext).removeCallback(mTickReceiver);
        Dependency.get(StatusBarStateController.class).removeCallback(this);
    }

    /**
     * Gets the {@link KeyguardUpdateMonitorCallback} instance associated with this
     * {@link KeyguardIndicationController}.
     *
     * <p>Subclasses may override this method to extend or change the callback behavior by extending
     * the {@link BaseKeyguardCallback}.
     *
     * @return A KeyguardUpdateMonitorCallback. Multiple calls to this method <b>must</b> return the
     * same instance.
     */
    protected KeyguardUpdateMonitorCallback getKeyguardCallback() {
        if (mUpdateMonitorCallback == null) {
            mUpdateMonitorCallback = new BaseKeyguardCallback();
        }
        return mUpdateMonitorCallback;
    }

    private void updateDisclosure() {
        if (mDevicePolicyManager == null) {
            return;
        }

        if (!mDozing && mDevicePolicyManager.isDeviceManaged()) {
            final CharSequence organizationName =
                    mDevicePolicyManager.getDeviceOwnerOrganizationName();
            if (organizationName != null) {
                mDisclosure.switchIndication(mContext.getResources().getString(
                        R.string.do_disclosure_with_name, organizationName));
            } else {
                mDisclosure.switchIndication(R.string.do_disclosure_generic);
            }
            mDisclosure.setVisibility(View.VISIBLE);
        } else {
            mDisclosure.setVisibility(View.GONE);
        }
    }

    public void setVisible(boolean visible) {
        mVisible = visible;
        mIndicationArea.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            // If this is called after an error message was already shown, we should not clear it.
            // Otherwise the error message won't be shown
            if  (!mHandler.hasMessages(MSG_HIDE_TRANSIENT)) {
                hideTransientIndication();
            }
            updateIndication(false);
        } else if (!visible) {
            // If we unlock and return to keyguard quickly, previous error should not be shown
            hideTransientIndication();
        }
    }

    /**
     * Sets the indication that is shown if nothing else is showing.
     */
    public void setRestingIndication(String restingIndication) {
        mRestingIndication = restingIndication;
        updateIndication(false);
    }

    /**
     * Sets the active controller managing changes and callbacks to user information.
     */
    public void setUserInfoController(UserInfoController userInfoController) {
    }

    /**
     * Returns the indication text indicating that trust has been granted.
     *
     * @return {@code null} or an empty string if a trust indication text should not be shown.
     */
    protected String getTrustGrantedIndication() {
        return null;
    }

    /**
     * Returns the indication text indicating that trust is currently being managed.
     *
     * @return {@code null} or an empty string if a trust managed text should not be shown.
     */
    protected String getTrustManagedIndication() {
        return null;
    }

    /**
     * Hides transient indication in {@param delayMs}.
     */
    public void hideTransientIndicationDelayed(long delayMs) {
        mHandler.sendMessageDelayed(
                mHandler.obtainMessage(MSG_HIDE_TRANSIENT), delayMs);
    }

    /**
     * Shows {@param transientIndication} until it is hidden by {@link #hideTransientIndication}.
     */
    public void showTransientIndication(int transientIndication) {
        showTransientIndication(mContext.getResources().getString(transientIndication));
    }

    /**
     * Shows {@param transientIndication} until it is hidden by {@link #hideTransientIndication}.
     */
    public void showTransientIndication(CharSequence transientIndication) {
        showTransientIndication(transientIndication, mInitialTextColorState);
    }

    /**
     * Shows {@param transientIndication} until it is hidden by {@link #hideTransientIndication}.
     */
    public void showTransientIndication(CharSequence transientIndication,
            ColorStateList textColorState) {
        mTransientIndication = transientIndication;
        mTransientTextColorState = textColorState;
        mHandler.removeMessages(MSG_HIDE_TRANSIENT);
        if (mDozing && !TextUtils.isEmpty(mTransientIndication)) {
            // Make sure this doesn't get stuck and burns in. Acquire wakelock until its cleared.
            mWakeLock.setAcquired(true);
            hideTransientIndicationDelayed(BaseKeyguardCallback.HIDE_DELAY_MS);
        }

        updateIndication(false);
    }

    /**
     * Hides transient indication.
     */
    public void hideTransientIndication() {
        if (mTransientIndication != null) {
            mTransientIndication = null;
            mHandler.removeMessages(MSG_HIDE_TRANSIENT);
            updateIndication(false);
        }
    }

    protected final void updateIndication(boolean animate) {
        if (TextUtils.isEmpty(mTransientIndication)) {
            mWakeLock.setAcquired(false);
        }

        if (mVisible) {
            // Walk down a precedence-ordered list of what indication
            // should be shown based on user or device state
            if (mDozing) {
                if (!TextUtils.isEmpty(mTransientIndication)) {
                    mTextView.setTextColor(Color.WHITE);
                    mTextView.switchIndication(mTransientIndication);
                }
                updateAlphas();
                return;
            }

            KeyguardUpdateMonitor updateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
            int userId = KeyguardUpdateMonitor.getCurrentUser();
            String trustGrantedIndication = getTrustGrantedIndication();
            String trustManagedIndication = getTrustManagedIndication();
            if (!mUserManager.isUserUnlocked(userId)) {
                mTextView.switchIndication(com.android.internal.R.string.lockscreen_storage_locked);
                mTextView.setTextColor(mInitialTextColorState);
            } else if (!TextUtils.isEmpty(mTransientIndication)) {
                mTextView.switchIndication(mTransientIndication);
                mTextView.setTextColor(mTransientTextColorState);
            } else if (!TextUtils.isEmpty(trustGrantedIndication)
                    && updateMonitor.getUserHasTrust(userId)) {
                mTextView.switchIndication(trustGrantedIndication);
                mTextView.setTextColor(mInitialTextColorState);
            } else if (mPowerPluggedIn) {
                String indication = computePowerIndication();
                if (DEBUG_CHARGING_SPEED) {
                    indication += ",  " + (mChargingWattage / 1000) + " mW";
                }
                mTextView.setTextColor(mInitialTextColorState);
                if (animate) {
                    animateText(mTextView, indication);
                } else {
                    mTextView.switchIndication(indication);
                }
            } else if (!TextUtils.isEmpty(trustManagedIndication)
                    && updateMonitor.getUserTrustIsManaged(userId)
                    && !updateMonitor.getUserHasTrust(userId)) {
                mTextView.switchIndication(trustManagedIndication);
                mTextView.setTextColor(mInitialTextColorState);
            } else {
                mTextView.switchIndication(mRestingIndication);
                mTextView.setTextColor(mInitialTextColorState);
            }
        }
    }

    private void updateAlphas() {
        if (!TextUtils.isEmpty(mTransientIndication)) {
            mTextView.setAlpha(1f);
        } else {
            mTextView.setAlpha(1f - mDarkAmount);
        }
    }

    // animates textView - textView moves up and bounces down
    private void animateText(KeyguardIndicationTextView textView, String indication) {
        int yTranslation = mContext.getResources().getInteger(
                R.integer.wired_charging_keyguard_text_animation_distance);
        int animateUpDuration = mContext.getResources().getInteger(
                R.integer.wired_charging_keyguard_text_animation_duration_up);
        int animateDownDuration = mContext.getResources().getInteger(
                R.integer.wired_charging_keyguard_text_animation_duration_down);
        textView.animate().cancel();
        float translation = textView.getTranslationY();
        textView.animate()
                .translationYBy(yTranslation)
                .setInterpolator(Interpolators.LINEAR)
                .setDuration(animateUpDuration)
                .setListener(new AnimatorListenerAdapter() {
                    private boolean mCancelled;

                    @Override
                    public void onAnimationStart(Animator animation) {
                        textView.switchIndication(indication);
                    }

                    @Override
                    public void onAnimationCancel(Animator animation) {
                        textView.setTranslationY(translation);
                        mCancelled = true;
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (mCancelled) {
                            return;
                        }
                        textView.animate()
                                .setDuration(animateDownDuration)
                                .setInterpolator(Interpolators.BOUNCE)
                                .translationY(translation)
                                .setListener(new AnimatorListenerAdapter() {
                                    @Override
                                    public void onAnimationCancel(Animator animation) {
                                        textView.setTranslationY(translation);
                                    }
                                });
                    }
                });
    }

    private String computePowerIndication() {
        if (mPowerCharged) {
            return mContext.getResources().getString(R.string.keyguard_charged);
        }

        // Try fetching charging time from battery stats.
        long chargingTimeRemaining = 0;
        try {
            chargingTimeRemaining = mBatteryInfo.computeChargeTimeRemaining();

        } catch (RemoteException e) {
            Log.e(TAG, "Error calling IBatteryStats: ", e);
        }
        final boolean hasChargingTime = chargingTimeRemaining > 0;

        int chargingId;
        if (mPowerPluggedInWired) {
            switch (mChargingSpeed) {
                case KeyguardUpdateMonitor.BatteryStatus.CHARGING_FAST:
                    chargingId = hasChargingTime
                            ? R.string.keyguard_indication_charging_time_fast
                            : R.string.keyguard_plugged_in_charging_fast;
                    break;
                case KeyguardUpdateMonitor.BatteryStatus.CHARGING_SLOWLY:
                    chargingId = hasChargingTime
                            ? R.string.keyguard_indication_charging_time_slowly
                            : R.string.keyguard_plugged_in_charging_slowly;
                    break;
                default:
                    chargingId = hasChargingTime
                            ? R.string.keyguard_indication_charging_time
                            : R.string.keyguard_plugged_in;
                    break;
            }
        } else {
            chargingId = hasChargingTime
                    ? R.string.keyguard_indication_charging_time_wireless
                    : R.string.keyguard_plugged_in_wireless;
        }

        String percentage = NumberFormat.getPercentInstance()
                .format(mBatteryLevel / 100f);
        if (hasChargingTime) {
            // We now have battery percentage in these strings and it's expected that all
            // locales will also have it in the future. For now, we still have to support the old
            // format until all languages get the new translations.
            String chargingTimeFormatted = Formatter.formatShortElapsedTimeRoundingUpToMinutes(
                    mContext, chargingTimeRemaining);
            try {
                return mContext.getResources().getString(chargingId, chargingTimeFormatted,
                        percentage);
            } catch (IllegalFormatConversionException e) {
                return mContext.getResources().getString(chargingId, chargingTimeFormatted);
            }
        } else {
            // Same as above
            try {
                return mContext.getResources().getString(chargingId, percentage);
            } catch (IllegalFormatConversionException e) {
                return mContext.getResources().getString(chargingId);
            }
        }
    }

    public void setStatusBarKeyguardViewManager(
            StatusBarKeyguardViewManager statusBarKeyguardViewManager) {
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
    }

    private final KeyguardUpdateMonitorCallback mTickReceiver =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onTimeChanged() {
                    if (mVisible) {
                        updateIndication(false /* animate */);
                    }
                }
            };

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_HIDE_TRANSIENT) {
                hideTransientIndication();
            } else if (msg.what == MSG_CLEAR_BIOMETRIC_MSG) {
                mLockIcon.setTransientBiometricsError(false);
            }
        }
    };

    public void setDozing(boolean dozing) {
        if (mDozing == dozing) {
            return;
        }
        mDozing = dozing;
        updateIndication(false);
        updateDisclosure();
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("KeyguardIndicationController:");
        pw.println("  mTransientTextColorState: " + mTransientTextColorState);
        pw.println("  mInitialTextColorState: " + mInitialTextColorState);
        pw.println("  mPowerPluggedInWired: " + mPowerPluggedInWired);
        pw.println("  mPowerPluggedIn: " + mPowerPluggedIn);
        pw.println("  mPowerCharged: " + mPowerCharged);
        pw.println("  mChargingSpeed: " + mChargingSpeed);
        pw.println("  mChargingWattage: " + mChargingWattage);
        pw.println("  mMessageToShowOnScreenOn: " + mMessageToShowOnScreenOn);
        pw.println("  mDozing: " + mDozing);
        pw.println("  mBatteryLevel: " + mBatteryLevel);
        pw.println("  mTextView.getText(): " + (mTextView == null ? null : mTextView.getText()));
        pw.println("  computePowerIndication(): " + computePowerIndication());
    }

    public void setDarkAmount(float darkAmount) {
        if (mDarkAmount == darkAmount) {
            return;
        }
        mDarkAmount = darkAmount;
        updateAlphas();
    }

    @Override
    public void onStateChanged(int newState) {
        // don't care
    }

    @Override
    public void onDozingChanged(boolean isDozing) {
        setDozing(isDozing);
    }

    protected class BaseKeyguardCallback extends KeyguardUpdateMonitorCallback {
        public static final int HIDE_DELAY_MS = 5000;
        private int mLastSuccessiveErrorMessage = -1;

        @Override
        public void onRefreshBatteryInfo(KeyguardUpdateMonitor.BatteryStatus status) {
            boolean isChargingOrFull = status.status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status.status == BatteryManager.BATTERY_STATUS_FULL;
            boolean wasPluggedIn = mPowerPluggedIn;
            mPowerPluggedInWired = status.isPluggedInWired() && isChargingOrFull;
            mPowerPluggedIn = status.isPluggedIn() && isChargingOrFull;
            mPowerCharged = status.isCharged();
            mChargingWattage = status.maxChargingWattage;
            mChargingSpeed = status.getChargingSpeed(mSlowThreshold, mFastThreshold);
            mBatteryLevel = status.level;
            updateIndication(!wasPluggedIn && mPowerPluggedInWired);
            if (mDozing) {
                if (!wasPluggedIn && mPowerPluggedIn) {
                    showTransientIndication(computePowerIndication());
                    hideTransientIndicationDelayed(HIDE_DELAY_MS);
                } else if (wasPluggedIn && !mPowerPluggedIn) {
                    hideTransientIndication();
                }
            }
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            if (showing) {
                updateDisclosure();
            }
        }

        @Override
        public void onBiometricHelp(int msgId, String helpString,
                BiometricSourceType biometricSourceType) {
            KeyguardUpdateMonitor updateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
            if (!updateMonitor.isUnlockingWithBiometricAllowed()) {
                return;
            }
            ColorStateList errorColorState = Utils.getColorError(mContext);
            if (mStatusBarKeyguardViewManager.isBouncerShowing()) {
                mStatusBarKeyguardViewManager.showBouncerMessage(helpString,
                        errorColorState);
            } else if (updateMonitor.isScreenOn()) {
                mLockIcon.setTransientBiometricsError(true);
                showTransientIndication(helpString, errorColorState);
                hideTransientIndicationDelayed(TRANSIENT_BIOMETRIC_ERROR_TIMEOUT);
                mHandler.removeMessages(MSG_CLEAR_BIOMETRIC_MSG);
                mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_CLEAR_BIOMETRIC_MSG),
                        TRANSIENT_BIOMETRIC_ERROR_TIMEOUT);
            }
            // Help messages indicate that there was actually a try since the last error, so those
            // are not two successive error messages anymore.
            mLastSuccessiveErrorMessage = -1;
        }

        @Override
        public void onBiometricError(int msgId, String errString,
                BiometricSourceType biometricSourceType) {
            KeyguardUpdateMonitor updateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
            if (shouldSuppressBiometricError(msgId, biometricSourceType, updateMonitor)) {
                return;
            }
            ColorStateList errorColorState = Utils.getColorError(mContext);
            if (mStatusBarKeyguardViewManager.isBouncerShowing()) {
                // When swiping up right after receiving a biometric error, the bouncer calls
                // authenticate leading to the same message being shown again on the bouncer.
                // We want to avoid this, as it may confuse the user when the message is too
                // generic.
                if (mLastSuccessiveErrorMessage != msgId) {
                    mStatusBarKeyguardViewManager.showBouncerMessage(errString,
                            errorColorState);
                }
            } else if (updateMonitor.isScreenOn()) {
                showTransientIndication(errString, errorColorState);
                // We want to keep this message around in case the screen was off
                hideTransientIndicationDelayed(HIDE_DELAY_MS);
            } else {
                mMessageToShowOnScreenOn = errString;
            }
            mLastSuccessiveErrorMessage = msgId;
        }

        private boolean shouldSuppressBiometricError(int msgId,
                BiometricSourceType biometricSourceType, KeyguardUpdateMonitor updateMonitor) {
            if (biometricSourceType == BiometricSourceType.FINGERPRINT)
                return shouldSuppressFingerprintError(msgId, updateMonitor);
            if (biometricSourceType == BiometricSourceType.FACE)
                return shouldSuppressFaceError(msgId, updateMonitor);
            return false;
        }

        private boolean shouldSuppressFingerprintError(int msgId,
                KeyguardUpdateMonitor updateMonitor) {
            return ((!updateMonitor.isUnlockingWithBiometricAllowed()
                    && msgId != FingerprintManager.FINGERPRINT_ERROR_LOCKOUT_PERMANENT)
                    || msgId == FingerprintManager.FINGERPRINT_ERROR_CANCELED);
        }

        private boolean shouldSuppressFaceError(int msgId, KeyguardUpdateMonitor updateMonitor) {
            return ((!updateMonitor.isUnlockingWithBiometricAllowed()
                    && msgId != FaceManager.FACE_ERROR_LOCKOUT_PERMANENT)
                    || msgId == FaceManager.FACE_ERROR_CANCELED);
        }

        @Override
        public void onTrustAgentErrorMessage(CharSequence message) {
            showTransientIndication(message, Utils.getColorError(mContext));
        }

        @Override
        public void onScreenTurnedOn() {
            if (mMessageToShowOnScreenOn != null) {
                showTransientIndication(mMessageToShowOnScreenOn, Utils.getColorError(mContext));
                // We want to keep this message around in case the screen was off
                hideTransientIndicationDelayed(HIDE_DELAY_MS);
                mMessageToShowOnScreenOn = null;
            }
        }

        @Override
        public void onBiometricRunningStateChanged(boolean running,
                BiometricSourceType biometricSourceType) {
            if (running) {
                mMessageToShowOnScreenOn = null;
            }
        }

        @Override
        public void onBiometricAuthenticated(int userId, BiometricSourceType biometricSourceType) {
            super.onBiometricAuthenticated(userId, biometricSourceType);
            mLastSuccessiveErrorMessage = -1;
        }

        @Override
        public void onBiometricAuthFailed(BiometricSourceType biometricSourceType) {
            super.onBiometricAuthFailed(biometricSourceType);
            mLastSuccessiveErrorMessage = -1;
        }

        @Override
        public void onUserUnlocked() {
            if (mVisible) {
                updateIndication(false);
            }
        }
    };
}
