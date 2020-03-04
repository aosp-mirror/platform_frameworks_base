/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.content.res.TypedArray;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.logging.nano.MetricsProto;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.R;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.policy.AccessibilityController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;

import javax.inject.Inject;
import javax.inject.Singleton;

/** Controls the {@link LockIcon} in the lockscreen. */
@Singleton
public class LockscreenLockIconController {

    private final LockscreenGestureLogger mLockscreenGestureLogger;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final LockPatternUtils mLockPatternUtils;
    private final ShadeController mShadeController;
    private final AccessibilityController mAccessibilityController;
    private final KeyguardIndicationController mKeyguardIndicationController;
    private final StatusBarStateController mStatusBarStateController;
    private final ConfigurationController mConfigurationController;
    private LockIcon mLockIcon;

    private View.OnAttachStateChangeListener mOnAttachStateChangeListener =
            new View.OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(View v) {
            mStatusBarStateController.addCallback(mSBStateListener);
            mConfigurationController.addCallback(mConfigurationListener);

            mConfigurationListener.onThemeChanged();
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
            mStatusBarStateController.removeCallback(mSBStateListener);
            mConfigurationController.removeCallback(mConfigurationListener);
        }
    };

    private final StatusBarStateController.StateListener mSBStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onDozingChanged(boolean isDozing) {
                    mLockIcon.setDozing(isDozing);
                }

                @Override
                public void onDozeAmountChanged(float linear, float eased) {
                    mLockIcon.setDozeAmount(eased);
                }

                @Override
                public void onStateChanged(int newState) {
                    mLockIcon.setStatusBarState(newState);
                }
            };

    private final ConfigurationListener mConfigurationListener = new ConfigurationListener() {
        @Override
        public void onThemeChanged() {
            TypedArray typedArray = mLockIcon.getContext().getTheme().obtainStyledAttributes(
                    null, new int[]{ R.attr.wallpaperTextColor }, 0, 0);
            int iconColor = typedArray.getColor(0, Color.WHITE);
            typedArray.recycle();
            mLockIcon.setIconColor(iconColor);
        }

        @Override
        public void onDensityOrFontScaleChanged() {
            ViewGroup.LayoutParams lp = mLockIcon.getLayoutParams();
            if (lp == null) {
                return;
            }
            lp.width = mLockIcon.getResources().getDimensionPixelSize(R.dimen.keyguard_lock_width);
            lp.height = mLockIcon.getResources().getDimensionPixelSize(
                    R.dimen.keyguard_lock_height);
            mLockIcon.setLayoutParams(lp);
            mLockIcon.update(true /* force */);
        }

        @Override
        public void onLocaleListChanged() {
            mLockIcon.setContentDescription(
                    mLockIcon.getResources().getText(R.string.accessibility_unlock_button));
            mLockIcon.update(true /* force */);
        }
    };

    @Inject
    public LockscreenLockIconController(LockscreenGestureLogger lockscreenGestureLogger,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            LockPatternUtils lockPatternUtils,
            ShadeController shadeController,
            AccessibilityController accessibilityController,
            KeyguardIndicationController keyguardIndicationController,
            StatusBarStateController statusBarStateController,
            ConfigurationController configurationController) {
        mLockscreenGestureLogger = lockscreenGestureLogger;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mLockPatternUtils = lockPatternUtils;
        mShadeController = shadeController;
        mAccessibilityController = accessibilityController;
        mKeyguardIndicationController = keyguardIndicationController;
        mStatusBarStateController = statusBarStateController;
        mConfigurationController = configurationController;

        mKeyguardIndicationController.setLockIconController(this);
    }

    /**
     * Associate the controller with a {@link LockIcon}
     */
    public void attach(LockIcon lockIcon) {
        mLockIcon = lockIcon;

        mLockIcon.setOnClickListener(this::handleClick);
        mLockIcon.setOnLongClickListener(this::handleLongClick);

        if (mLockIcon.isAttachedToWindow()) {
            mOnAttachStateChangeListener.onViewAttachedToWindow(mLockIcon);
        }
        mLockIcon.addOnAttachStateChangeListener(mOnAttachStateChangeListener);
        mLockIcon.setStatusBarState(mStatusBarStateController.getState());
    }

    public LockIcon getView() {
        return mLockIcon;
    }

    /**
     * Called whenever the scrims become opaque, transparent or semi-transparent.
     */
    public void onScrimVisibilityChanged(Integer scrimsVisible) {
        if (mLockIcon != null) {
            mLockIcon.onScrimVisibilityChanged(scrimsVisible);
        }
    }

    /**
     * Propagate {@link StatusBar} pulsing state.
     */
    public void setPulsing(boolean pulsing) {
        if (mLockIcon != null) {
            mLockIcon.setPulsing(pulsing);
        }
    }

    /**
     * Called when the biometric authentication mode changes.
     *
     * @param wakeAndUnlock If the type is {@link BiometricUnlockController#isWakeAndUnlock()}
     * @param isUnlock      If the type is {@link BiometricUnlockController#isBiometricUnlock()} ()
     */
    public void onBiometricAuthModeChanged(boolean wakeAndUnlock, boolean isUnlock) {
        if (mLockIcon != null) {
            mLockIcon.onBiometricAuthModeChanged(wakeAndUnlock, isUnlock);
        }
    }

    /**
     * When we're launching an affordance, like double pressing power to open camera.
     */
    public void onShowingLaunchAffordanceChanged(Boolean showing) {
        if (mLockIcon != null) {
            mLockIcon.onShowingLaunchAffordanceChanged(showing);
        }
    }

    /** Sets whether the bouncer is showing. */
    public void setBouncerShowingScrimmed(boolean bouncerShowing) {
        if (mLockIcon != null) {
            mLockIcon.setBouncerShowingScrimmed(bouncerShowing);
        }
    }

    /**
     * When {@link KeyguardBouncer} starts to be dismissed and starts to play its animation.
     */
    public void onBouncerPreHideAnimation() {
        if (mLockIcon != null) {
            mLockIcon.onBouncerPreHideAnimation();
        }
    }

    /**
     * If we're currently presenting an authentication error message.
     */
    public void setTransientBiometricsError(boolean transientBiometricsError) {
        if (mLockIcon != null) {
            mLockIcon.setTransientBiometricsError(transientBiometricsError);
        }
    }

    private boolean handleLongClick(View view) {
        mLockscreenGestureLogger.write(MetricsProto.MetricsEvent.ACTION_LS_LOCK,
                0 /* lengthDp - N/A */, 0 /* velocityDp - N/A */);
        mKeyguardIndicationController.showTransientIndication(
                R.string.keyguard_indication_trust_disabled);
        mKeyguardUpdateMonitor.onLockIconPressed();
        mLockPatternUtils.requireCredentialEntry(KeyguardUpdateMonitor.getCurrentUser());

        return true;
    }


    private void handleClick(View view) {
        if (!mAccessibilityController.isAccessibilityEnabled()) {
            return;
        }
        mShadeController.animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE, true /* force */);
    }
}
