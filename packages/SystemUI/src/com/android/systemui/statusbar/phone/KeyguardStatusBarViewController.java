/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;
import static com.android.systemui.statusbar.events.SystemStatusAnimationSchedulerKt.ANIMATING_IN;
import static com.android.systemui.statusbar.events.SystemStatusAnimationSchedulerKt.ANIMATING_OUT;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.res.Resources;
import android.hardware.biometrics.BiometricSourceType;
import android.view.View;

import androidx.annotation.NonNull;

import com.android.keyguard.CarrierTextController;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.battery.BatteryMeterViewController;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.events.SystemStatusAnimationCallback;
import com.android.systemui.statusbar.events.SystemStatusAnimationScheduler;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.util.ViewController;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;

/** View Controller for {@link com.android.systemui.statusbar.phone.KeyguardStatusBarView}. */
public class KeyguardStatusBarViewController extends ViewController<KeyguardStatusBarView> {
    private final CarrierTextController mCarrierTextController;
    private final ConfigurationController mConfigurationController;
    private final SystemStatusAnimationScheduler mAnimationScheduler;
    private final BatteryController mBatteryController;
    private final UserInfoController mUserInfoController;
    private final StatusBarIconController mStatusBarIconController;
    private final StatusBarIconController.TintedIconManager.Factory mTintedIconManagerFactory;
    private final BatteryMeterViewController mBatteryMeterViewController;
    private final ViewStateProvider mViewStateProvider;
    private final KeyguardStateController mKeyguardStateController;
    private final KeyguardBypassController mKeyguardBypassController;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final BiometricUnlockController mBiometricUnlockController;
    private final SysuiStatusBarStateController mStatusBarStateController;

    private final ConfigurationController.ConfigurationListener mConfigurationListener =
            new ConfigurationController.ConfigurationListener() {
                @Override
                public void onDensityOrFontScaleChanged() {
                    mView.loadDimens();
                }

                @Override
                public void onOverlayChanged() {
                    KeyguardStatusBarViewController.this.onThemeChanged();
                    mView.onOverlayChanged();
                }

                @Override
                public void onThemeChanged() {
                    KeyguardStatusBarViewController.this.onThemeChanged();
                }
            };

    private final SystemStatusAnimationCallback mAnimationCallback =
            new SystemStatusAnimationCallback() {
                @Override
                public void onSystemChromeAnimationStart() {
                    mView.onSystemChromeAnimationStart(
                            mAnimationScheduler.getAnimationState() == ANIMATING_OUT);
                }

                @Override
                public void onSystemChromeAnimationEnd() {
                    mView.onSystemChromeAnimationEnd(
                            mAnimationScheduler.getAnimationState() == ANIMATING_IN);
                }

                @Override
                public void onSystemChromeAnimationUpdate(@NonNull ValueAnimator anim) {
                    mView.onSystemChromeAnimationUpdate((float) anim.getAnimatedValue());
                }
            };

    private final BatteryController.BatteryStateChangeCallback mBatteryStateChangeCallback =
            new BatteryController.BatteryStateChangeCallback() {
                @Override
                public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
                    mView.onBatteryLevelChanged(charging);
                }
            };

    private final UserInfoController.OnUserInfoChangedListener mOnUserInfoChangedListener =
            (name, picture, userAccount) -> mView.onUserInfoChanged(picture);

    private final ValueAnimator.AnimatorUpdateListener mAnimatorUpdateListener =
            animation -> {
                mKeyguardStatusBarAnimateAlpha = (float) animation.getAnimatedValue();
                updateViewState();
            };

    private final KeyguardUpdateMonitorCallback mKeyguardUpdateMonitorCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onBiometricAuthenticated(
                        int userId,
                        BiometricSourceType biometricSourceType,
                        boolean isStrongBiometric) {
                    if (mFirstBypassAttempt
                            && mKeyguardUpdateMonitor.isUnlockingWithBiometricAllowed(
                                    isStrongBiometric)) {
                        mDelayShowingKeyguardStatusBar = true;
                    }
                }

                @Override
                public void onBiometricRunningStateChanged(
                        boolean running,
                        BiometricSourceType biometricSourceType) {
                    boolean keyguardOrShadeLocked =
                            mStatusBarState == KEYGUARD
                                    || mStatusBarState == StatusBarState.SHADE_LOCKED;
                    if (!running
                            && mFirstBypassAttempt
                            && keyguardOrShadeLocked
                            && !mDozing
                            && !mDelayShowingKeyguardStatusBar
                            && !mBiometricUnlockController.isBiometricUnlock()) {
                        mFirstBypassAttempt = false;
                        animateKeyguardStatusBarIn();
                    }
                }

                @Override
                public void onFinishedGoingToSleep(int why) {
                    mFirstBypassAttempt = mKeyguardBypassController.getBypassEnabled();
                    mDelayShowingKeyguardStatusBar = false;
                }
            };

    private final StatusBarStateController.StateListener mStatusBarStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onStateChanged(int newState) {
                    mStatusBarState = newState;
                }
            };

    private final List<String> mBlockedIcons;

    private boolean mBatteryListening;
    private StatusBarIconController.TintedIconManager mTintedIconManager;

    private float mKeyguardStatusBarAnimateAlpha = 1f;
    /**
     * If face auth with bypass is running for the first time after you turn on the screen.
     * (From aod or screen off)
     */
    private boolean mFirstBypassAttempt;
    /**
     * If auth happens successfully during {@code mFirstBypassAttempt}, and we should wait until
     * the keyguard is dismissed to show the status bar.
     */
    private boolean mDelayShowingKeyguardStatusBar;
    private int mStatusBarState;
    private boolean mDozing;

    @Inject
    public KeyguardStatusBarViewController(
            KeyguardStatusBarView view,
            CarrierTextController carrierTextController,
            ConfigurationController configurationController,
            SystemStatusAnimationScheduler animationScheduler,
            BatteryController batteryController,
            UserInfoController userInfoController,
            StatusBarIconController statusBarIconController,
            StatusBarIconController.TintedIconManager.Factory tintedIconManagerFactory,
            BatteryMeterViewController batteryMeterViewController,
            ViewStateProvider viewStateProvider,
            KeyguardStateController keyguardStateController,
            KeyguardBypassController bypassController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            BiometricUnlockController biometricUnlockController,
            SysuiStatusBarStateController statusBarStateController) {
        super(view);
        mCarrierTextController = carrierTextController;
        mConfigurationController = configurationController;
        mAnimationScheduler = animationScheduler;
        mBatteryController = batteryController;
        mUserInfoController = userInfoController;
        mStatusBarIconController = statusBarIconController;
        mTintedIconManagerFactory = tintedIconManagerFactory;
        mBatteryMeterViewController = batteryMeterViewController;
        mViewStateProvider = viewStateProvider;
        mKeyguardStateController = keyguardStateController;
        mKeyguardBypassController = bypassController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mBiometricUnlockController = biometricUnlockController;
        mStatusBarStateController = statusBarStateController;

        mFirstBypassAttempt = mKeyguardBypassController.getBypassEnabled();
        mKeyguardStateController.addCallback(
                new KeyguardStateController.Callback() {
                    @Override
                    public void onKeyguardFadingAwayChanged() {
                        if (!mKeyguardStateController.isKeyguardFadingAway()) {
                            mFirstBypassAttempt = false;
                            mDelayShowingKeyguardStatusBar = false;
                        }
                    }
                }
        );

        Resources r = getResources();
        mBlockedIcons = Collections.unmodifiableList(Arrays.asList(
                r.getString(com.android.internal.R.string.status_bar_volume),
                r.getString(com.android.internal.R.string.status_bar_alarm_clock),
                r.getString(com.android.internal.R.string.status_bar_call_strength)));
    }

    @Override
    protected void onInit() {
        super.onInit();
        mCarrierTextController.init();
        mBatteryMeterViewController.init();
    }

    @Override
    protected void onViewAttached() {
        mConfigurationController.addCallback(mConfigurationListener);
        mAnimationScheduler.addCallback(mAnimationCallback);
        mUserInfoController.addCallback(mOnUserInfoChangedListener);
        mStatusBarStateController.addCallback(mStatusBarStateListener);
        mKeyguardUpdateMonitor.registerCallback(mKeyguardUpdateMonitorCallback);
        if (mTintedIconManager == null) {
            mTintedIconManager =
                    mTintedIconManagerFactory.create(mView.findViewById(R.id.statusIcons));
            mTintedIconManager.setBlockList(mBlockedIcons);
            mStatusBarIconController.addIconGroup(mTintedIconManager);
        }
        onThemeChanged();
    }

    @Override
    protected void onViewDetached() {
        mConfigurationController.removeCallback(mConfigurationListener);
        mAnimationScheduler.removeCallback(mAnimationCallback);
        mUserInfoController.removeCallback(mOnUserInfoChangedListener);
        mStatusBarStateController.removeCallback(mStatusBarStateListener);
        mKeyguardUpdateMonitor.removeCallback(mKeyguardUpdateMonitorCallback);
        if (mTintedIconManager != null) {
            mStatusBarIconController.removeIconGroup(mTintedIconManager);
        }
    }

    /** Should be called when the theme changes. */
    public void onThemeChanged() {
        mView.onThemeChanged(mTintedIconManager);
    }

    /** Sets whether user switcher is enabled. */
    public void setKeyguardUserSwitcherEnabled(boolean enabled) {
        mView.setKeyguardUserSwitcherEnabled(enabled);
    }

    /** Sets whether this controller should listen to battery updates. */
    public void setBatteryListening(boolean listening) {
        if (listening == mBatteryListening) {
            return;
        }
        mBatteryListening = listening;
        if (mBatteryListening) {
            mBatteryController.addCallback(mBatteryStateChangeCallback);
        } else {
            mBatteryController.removeCallback(mBatteryStateChangeCallback);
        }
    }

    /** Set the view to have no top clipping. */
    public void setNoTopClipping() {
        mView.setTopClipping(0);
    }

    /**
     * Update the view's top clipping based on the value of notificationPanelTop and the view's
     * current top.
     *
     * @param notificationPanelTop the current top of the notification panel view.
     */
    public void updateTopClipping(int notificationPanelTop) {
        mView.setTopClipping(notificationPanelTop - mView.getTop());
    }

    /** Sets the dozing state. */
    public void setDozing(boolean dozing) {
        mDozing = dozing;
    }

    /** Animate the keyguard status bar in. */
    public void animateKeyguardStatusBarIn() {
        mView.setVisibility(View.VISIBLE);
        mView.setAlpha(0f);
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.addUpdateListener(mAnimatorUpdateListener);
        anim.setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);
        anim.setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);
        anim.start();
    }

    /** Animate the keyguard status bar out. */
    public void animateKeyguardStatusBarOut(long startDelay, long duration) {
        ValueAnimator anim = ValueAnimator.ofFloat(mView.getAlpha(), 0f);
        anim.addUpdateListener(mAnimatorUpdateListener);
        anim.setStartDelay(startDelay);
        anim.setDuration(duration);
        anim.setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mView.setVisibility(View.INVISIBLE);
                mView.setAlpha(1f);
                mKeyguardStatusBarAnimateAlpha = 1f;
            }
        });
        anim.start();
    }

    /**
     * Updates the {@link KeyguardStatusBarView} state based on what the {@link ViewStateProvider}
     * and other controllers provide.
     */
    public void updateViewState() {
        ViewState newViewState = mViewStateProvider.provideViewState();
        if (!isKeyguardShowing()) {
            return;
        }

        float newAlpha = newViewState.mAlpha * mKeyguardStatusBarAnimateAlpha;

        boolean hideForBypass =
                mFirstBypassAttempt && mKeyguardUpdateMonitor.shouldListenForFace()
                        || mDelayShowingKeyguardStatusBar;
        int newVisibility = newAlpha != 0f && !mDozing && !hideForBypass
                ? View.VISIBLE : View.INVISIBLE;

        updateViewState(newAlpha, newVisibility);
    }

    /**
     * Updates the {@link KeyguardStatusBarView} state based on the provided values.
     */
    public void updateViewState(float alpha, int visibility) {
        mView.setAlpha(alpha);
        mView.setVisibility(visibility);
    }

    private boolean isKeyguardShowing() {
        return mStatusBarState == KEYGUARD;
    }

    /** */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("KeyguardStatusBarView:");
        pw.println("  mBatteryListening: " + mBatteryListening);
        mView.dump(fd, pw, args);
    }

    /** An interface that provides the desired state of {@link KeyguardStatusBarView}. */
    public interface ViewStateProvider {
        /** Provides the state. */
        ViewState provideViewState();
    }

    /** A POJO for the desired state of {@link KeyguardStatusBarView}. */
    static class ViewState {
        final float mAlpha;

        ViewState(float alpha) {
            this.mAlpha = alpha;
        }
    }
}
