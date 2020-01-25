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
import com.android.internal.logging.nano.MetricsProto;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.ViewClippingUtil;
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
import com.android.systemui.statusbar.phone.LockscreenGestureLogger;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.phone.UnlockMethodCache;
import com.android.systemui.statusbar.policy.AccessibilityController;
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
public class KeyguardIndicationController implements StateListener,
        UnlockMethodCache.OnUnlockMethodChangedListener {

    private static final String TAG = "KeyguardIndication";
    private static final boolean DEBUG_CHARGING_SPEED = false;

    private static final int MSG_HIDE_TRANSIENT = 1;
    private static final int MSG_CLEAR_BIOMETRIC_MSG = 2;
    private static final int MSG_SWIPE_UP_TO_UNLOCK = 3;
    private static final long TRANSIENT_BIOMETRIC_ERROR_TIMEOUT = 1300;
    private static final float BOUNCE_ANIMATION_FINAL_Y = 0f;

    private final Context mContext;
    private final ShadeController mShadeController;
    private final AccessibilityController mAccessibilityController;
    private final UnlockMethodCache mUnlockMethodCache;
    private final StatusBarStateController mStatusBarStateController;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private ViewGroup mIndicationArea;
    private KeyguardIndicationTextView mTextView;
    private KeyguardIndicationTextView mDisclosure;
    private final UserManager mUserManager;
    private final IBatteryStats mBatteryInfo;
    private final SettableWakeLock mWakeLock;
    private final LockPatternUtils mLockPatternUtils;

    private final int mSlowThreshold;
    private final int mFastThreshold;
    private final LockIcon mLockIcon;
    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private LockscreenGestureLogger mLockscreenGestureLogger = new LockscreenGestureLogger();

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
    private final ViewClippingUtil.ClippingParameters mClippingParams =
            new ViewClippingUtil.ClippingParameters() {
                @Override
                public boolean shouldFinish(View view) {
                    return view == mIndicationArea;
                }
            };

    /**
     * Creates a new KeyguardIndicationController and registers callbacks.
     */
    public KeyguardIndicationController(Context context, ViewGroup indicationArea,
            LockIcon lockIcon) {
        this(context, indicationArea, lockIcon, new LockPatternUtils(context),
                WakeLock.createPartial(context, "Doze:KeyguardIndication"),
                Dependency.get(ShadeController.class),
                Dependency.get(AccessibilityController.class),
                UnlockMethodCache.getInstance(context),
                Dependency.get(StatusBarStateController.class),
                KeyguardUpdateMonitor.getInstance(context));
    }

    /**
     * Creates a new KeyguardIndicationController for testing.
     */
    @VisibleForTesting
    KeyguardIndicationController(Context context, ViewGroup indicationArea, LockIcon lockIcon,
            LockPatternUtils lockPatternUtils, WakeLock wakeLock, ShadeController shadeController,
            AccessibilityController accessibilityController, UnlockMethodCache unlockMethodCache,
            StatusBarStateController statusBarStateController,
            KeyguardUpdateMonitor keyguardUpdateMonitor) {
        mContext = context;
        mLockIcon = lockIcon;
        mShadeController = shadeController;
        mAccessibilityController = accessibilityController;
        mUnlockMethodCache = unlockMethodCache;
        mStatusBarStateController = statusBarStateController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        // lock icon is not used on all form factors.
        if (mLockIcon != null) {
            mLockIcon.setOnLongClickListener(this::handleLockLongClick);
            mLockIcon.setOnClickListener(this::handleLockClick);
        }
        mWakeLock = new SettableWakeLock(wakeLock, TAG);
        mLockPatternUtils = lockPatternUtils;

        Resources res = context.getResources();
        mSlowThreshold = res.getInteger(R.integer.config_chargingSlowlyThreshold);
        mFastThreshold = res.getInteger(R.integer.config_chargingFastThreshold);

        mUserManager = context.getSystemService(UserManager.class);
        mBatteryInfo = IBatteryStats.Stub.asInterface(
                ServiceManager.getService(BatteryStats.SERVICE_NAME));

        mDevicePolicyManager = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        setIndicationArea(indicationArea);
        updateDisclosure();

        mKeyguardUpdateMonitor.registerCallback(getKeyguardCallback());
        mKeyguardUpdateMonitor.registerCallback(mTickReceiver);
        mStatusBarStateController.addCallback(this);
        mUnlockMethodCache.addListener(this);
    }

    public void setIndicationArea(ViewGroup indicationArea) {
        mIndicationArea = indicationArea;
        mTextView = indicationArea.findViewById(R.id.keyguard_indication_text);
        mInitialTextColorState = mTextView != null ?
                mTextView.getTextColors() : ColorStateList.valueOf(Color.WHITE);
        mDisclosure = indicationArea.findViewById(R.id.keyguard_indication_enterprise_disclosure);
        updateIndication(false /* animate */);
    }

    private boolean handleLockLongClick(View view) {
        mLockscreenGestureLogger.write(MetricsProto.MetricsEvent.ACTION_LS_LOCK,
                0 /* lengthDp - N/A */, 0 /* velocityDp - N/A */);
        showTransientIndication(R.string.keyguard_indication_trust_disabled);
        mKeyguardUpdateMonitor.onLockIconPressed();
        mLockPatternUtils.requireCredentialEntry(KeyguardUpdateMonitor.getCurrentUser());

        return true;
    }

    private void handleLockClick(View view) {
        if (!mAccessibilityController.isAccessibilityEnabled()) {
            return;
        }
        mShadeController.animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE, true /* force */);
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
            mLockIcon.setAlpha(255);
        } else if (!visible) {
            // If we unlock and return to keyguard quickly, previous error should not be shown
            hideTransientIndication();
            mLockIcon.setAlpha(0);
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
    @VisibleForTesting
    String getTrustGrantedIndication() {
        return mContext.getString(R.string.keyguard_indication_trust_unlocked);
    }

    /**
     * Returns the indication text indicating that trust is currently being managed.
     *
     * @return {@code null} or an empty string if a trust managed text should not be shown.
     */
    private String getTrustManagedIndication() {
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
        mHandler.removeMessages(MSG_SWIPE_UP_TO_UNLOCK);
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
                // When dozing we ignore any text color and use white instead, because
                // colors can be hard to read in low brightness.
                mTextView.setTextColor(Color.WHITE);
                if (!TextUtils.isEmpty(mTransientIndication)) {
                    mTextView.switchIndication(mTransientIndication);
                } else if (mPowerPluggedIn) {
                    String indication = computePowerIndication();
                    if (animate) {
                        animateText(mTextView, indication);
                    } else {
                        mTextView.switchIndication(indication);
                    }
                } else {
                    String percentage = NumberFormat.getPercentInstance()
                            .format(mBatteryLevel / 100f);
                    mTextView.switchIndication(percentage);
                }
                return;
            }

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
                    && mKeyguardUpdateMonitor.getUserHasTrust(userId)) {
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
                    && mKeyguardUpdateMonitor.getUserTrustIsManaged(userId)
                    && !mKeyguardUpdateMonitor.getUserHasTrust(userId)) {
                mTextView.switchIndication(trustManagedIndication);
                mTextView.setTextColor(mInitialTextColorState);
            } else {
                mTextView.switchIndication(mRestingIndication);
                mTextView.setTextColor(mInitialTextColorState);
            }
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
        ViewClippingUtil.setClippingDeactivated(textView, true, mClippingParams);
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
                        textView.setTranslationY(BOUNCE_ANIMATION_FINAL_Y);
                        mCancelled = true;
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (mCancelled) {
                            ViewClippingUtil.setClippingDeactivated(textView, false,
                                    mClippingParams);
                            return;
                        }
                        textView.animate()
                                .setDuration(animateDownDuration)
                                .setInterpolator(Interpolators.BOUNCE)
                                .translationY(BOUNCE_ANIMATION_FINAL_Y)
                                .setListener(new AnimatorListenerAdapter() {
                                    @Override
                                    public void onAnimationEnd(Animator animation) {
                                        textView.setTranslationY(BOUNCE_ANIMATION_FINAL_Y);
                                        ViewClippingUtil.setClippingDeactivated(textView, false,
                                                mClippingParams);
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
            } else if (msg.what == MSG_SWIPE_UP_TO_UNLOCK) {
                showSwipeUpToUnlock();
            }
        }
    };

    private void showSwipeUpToUnlock() {
        if (mDozing) {
            return;
        }

        if (mStatusBarKeyguardViewManager.isBouncerShowing()) {
            String message = mContext.getString(R.string.keyguard_retry);
            mStatusBarKeyguardViewManager.showBouncerMessage(message, mInitialTextColorState);
        } else if (mKeyguardUpdateMonitor.isScreenOn()) {
            showTransientIndication(mContext.getString(R.string.keyguard_unlock));
            hideTransientIndicationDelayed(BaseKeyguardCallback.HIDE_DELAY_MS);
        }
    }

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

    @Override
    public void onStateChanged(int newState) {
        // don't care
    }

    @Override
    public void onDozingChanged(boolean isDozing) {
        setDozing(isDozing);
    }

    @Override
    public void onUnlockMethodStateChanged() {
        updateIndication(!mDozing);
    }

    protected class BaseKeyguardCallback extends KeyguardUpdateMonitorCallback {
        public static final int HIDE_DELAY_MS = 5000;

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
            boolean showSwipeToUnlock =
                    msgId == KeyguardUpdateMonitor.BIOMETRIC_HELP_FACE_NOT_RECOGNIZED;
            if (mStatusBarKeyguardViewManager.isBouncerShowing()) {
                mStatusBarKeyguardViewManager.showBouncerMessage(helpString,
                        mInitialTextColorState);
            } else if (updateMonitor.isScreenOn()) {
                showTransientIndication(helpString);
                if (!showSwipeToUnlock) {
                    hideTransientIndicationDelayed(TRANSIENT_BIOMETRIC_ERROR_TIMEOUT);
                }
            }
            if (showSwipeToUnlock) {
                mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_SWIPE_UP_TO_UNLOCK),
                        TRANSIENT_BIOMETRIC_ERROR_TIMEOUT);
            }
        }

        @Override
        public void onBiometricError(int msgId, String errString,
                BiometricSourceType biometricSourceType) {
            KeyguardUpdateMonitor updateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
            if (shouldSuppressBiometricError(msgId, biometricSourceType, updateMonitor)) {
                return;
            }
            animatePadlockError();
            if (msgId == FaceManager.FACE_ERROR_TIMEOUT) {
                // The face timeout message is not very actionable, let's ask the user to
                // manually retry.
                showSwipeUpToUnlock();
            } else if (mStatusBarKeyguardViewManager.isBouncerShowing()) {
                mStatusBarKeyguardViewManager.showBouncerMessage(errString, mInitialTextColorState);
            } else if (updateMonitor.isScreenOn()) {
                showTransientIndication(errString);
                // We want to keep this message around in case the screen was off
                hideTransientIndicationDelayed(HIDE_DELAY_MS);
            } else {
                mMessageToShowOnScreenOn = errString;
            }
        }

        private void animatePadlockError() {
            mLockIcon.setTransientBiometricsError(true);
            mHandler.removeMessages(MSG_CLEAR_BIOMETRIC_MSG);
            mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_CLEAR_BIOMETRIC_MSG),
                    TRANSIENT_BIOMETRIC_ERROR_TIMEOUT);
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
                // Let's hide any previous messages when authentication starts, otherwise
                // multiple auth attempts would overlap.
                hideTransientIndication();
                mMessageToShowOnScreenOn = null;
            }
        }

        @Override
        public void onBiometricAuthenticated(int userId, BiometricSourceType biometricSourceType) {
            super.onBiometricAuthenticated(userId, biometricSourceType);
            mHandler.sendEmptyMessage(MSG_HIDE_TRANSIENT);
        }

        @Override
        public void onUserUnlocked() {
            if (mVisible) {
                updateIndication(false);
            }
        }
    }
}
