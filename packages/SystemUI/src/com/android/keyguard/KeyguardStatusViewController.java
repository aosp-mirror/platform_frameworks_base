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

import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;

import android.util.Slog;
import android.view.View;

import com.android.systemui.Interpolators;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.AnimatableProperty;
import com.android.systemui.statusbar.notification.PropertyAnimator;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.NotificationIconAreaController;
import com.android.systemui.statusbar.phone.NotificationIconContainer;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.ViewController;

import java.util.TimeZone;

import javax.inject.Inject;

/**
 * Injectable controller for {@link KeyguardStatusView}.
 */
public class KeyguardStatusViewController extends ViewController<KeyguardStatusView> {
    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    private static final String TAG = "KeyguardStatusViewController";

    private static final AnimationProperties CLOCK_ANIMATION_PROPERTIES =
            new AnimationProperties().setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);

    private final KeyguardSliceViewController mKeyguardSliceViewController;
    private final KeyguardClockSwitchController mKeyguardClockSwitchController;
    private final KeyguardStateController mKeyguardStateController;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final ConfigurationController mConfigurationController;
    private final NotificationIconAreaController mNotificationIconAreaController;
    private final DozeParameters mDozeParameters;

    private boolean mKeyguardStatusViewVisibilityAnimating;
    private int mLockScreenMode = KeyguardUpdateMonitor.LOCK_SCREEN_MODE_NORMAL;

    @Inject
    public KeyguardStatusViewController(
            KeyguardStatusView keyguardStatusView,
            KeyguardSliceViewController keyguardSliceViewController,
            KeyguardClockSwitchController keyguardClockSwitchController,
            KeyguardStateController keyguardStateController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            ConfigurationController configurationController,
            NotificationIconAreaController notificationIconAreaController,
            DozeParameters dozeParameters) {
        super(keyguardStatusView);
        mKeyguardSliceViewController = keyguardSliceViewController;
        mKeyguardClockSwitchController = keyguardClockSwitchController;
        mKeyguardStateController = keyguardStateController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mConfigurationController = configurationController;
        mNotificationIconAreaController = notificationIconAreaController;
        mDozeParameters = dozeParameters;
    }

    @Override
    public void onInit() {
        mKeyguardClockSwitchController.init();
    }

    @Override
    protected void onViewAttached() {
        mKeyguardUpdateMonitor.registerCallback(mInfoCallback);
        mConfigurationController.addCallback(mConfigurationListener);
        updateAodIcons();
    }

    @Override
    protected void onViewDetached() {
        mKeyguardUpdateMonitor.removeCallback(mInfoCallback);
        mConfigurationController.removeCallback(mConfigurationListener);
    }

    /**
     * Updates views on doze time tick.
     */
    public void dozeTimeTick() {
        refreshTime();
        mKeyguardSliceViewController.refresh();
    }

    /**
     * The amount we're in doze.
     */
    public void setDarkAmount(float darkAmount) {
        mView.setDarkAmount(darkAmount);
    }

    /**
     * Set whether or not the lock screen is showing notifications.
     */
    public void setHasVisibleNotifications(boolean hasVisibleNotifications) {
        mKeyguardClockSwitchController.setHasVisibleNotifications(hasVisibleNotifications);
    }

    /**
     * If we're presenting a custom clock of just the default one.
     */
    public boolean hasCustomClock() {
        return mKeyguardClockSwitchController.hasCustomClock();
    }

    /**
     * Get the height of the logout button.
     */
    public int getLogoutButtonHeight() {
        return mView.getLogoutButtonHeight();
    }

    /**
     * Get the height of the owner information view.
     */
    public int getOwnerInfoHeight() {
        return mView.getOwnerInfoHeight();
    }

    /**
     * Set keyguard status view alpha.
     */
    public void setAlpha(float alpha) {
        if (!mKeyguardStatusViewVisibilityAnimating) {
            mView.setAlpha(alpha);
        }
    }

    /**
     * Set pivot x.
     */
    public void setPivotX(float pivot) {
        mView.setPivotX(pivot);
    }

    /**
     * Set pivot y.
     */
    public void setPivotY(float pivot) {
        mView.setPivotY(pivot);
    }

    /**
     * Get the clock text size.
     */
    public float getClockTextSize() {
        return mKeyguardClockSwitchController.getClockTextSize();
    }

    /**
     * Returns the preferred Y position of the clock.
     *
     * @param totalHeight The height available to position the clock.
     * @return Y position of clock.
     */
    public int getClockPreferredY(int totalHeight) {
        return mKeyguardClockSwitchController.getClockPreferredY(totalHeight);
    }

    /**
     * Get the height of the keyguard status view.
     */
    public int getHeight() {
        return mView.getHeight();
    }

    /**
     * Set whether the view accessibility importance mode.
     */
    public void setStatusAccessibilityImportance(int mode) {
        mView.setImportantForAccessibility(mode);
    }

    /**
     * Update position of the view with an optional animation
     */
    public void updatePosition(int x, int y, float scale, boolean animate) {
        // We animate the status view visible/invisible using Y translation, so don't change it
        // while the animation is running.
        if (!mKeyguardStatusViewVisibilityAnimating) {
            PropertyAnimator.setProperty(mView, AnimatableProperty.Y, y, CLOCK_ANIMATION_PROPERTIES,
                    animate);
        }

        if (mLockScreenMode == KeyguardUpdateMonitor.LOCK_SCREEN_MODE_LAYOUT_1) {
            // reset any prior movement
            PropertyAnimator.setProperty(mView, AnimatableProperty.X, 0,
                    CLOCK_ANIMATION_PROPERTIES, animate);

            mKeyguardClockSwitchController.updatePosition(x, scale, CLOCK_ANIMATION_PROPERTIES,
                    animate);
        } else {
            // reset any prior movement
            mKeyguardClockSwitchController.updatePosition(0, 0f, CLOCK_ANIMATION_PROPERTIES,
                    animate);

            PropertyAnimator.setProperty(mView, AnimatableProperty.X, x,
                    CLOCK_ANIMATION_PROPERTIES, animate);
        }
    }

    /**
     * Set the visibility of the keyguard status view based on some new state.
     */
    public void setKeyguardStatusViewVisibility(
            int statusBarState,
            boolean keyguardFadingAway,
            boolean goingToFullShade,
            int oldStatusBarState) {
        mView.animate().cancel();
        mKeyguardStatusViewVisibilityAnimating = false;
        if ((!keyguardFadingAway && oldStatusBarState == KEYGUARD
                && statusBarState != KEYGUARD) || goingToFullShade) {
            mKeyguardStatusViewVisibilityAnimating = true;
            mView.animate()
                    .alpha(0f)
                    .setStartDelay(0)
                    .setDuration(160)
                    .setInterpolator(Interpolators.ALPHA_OUT)
                    .withEndAction(
                    mAnimateKeyguardStatusViewGoneEndRunnable);
            if (keyguardFadingAway) {
                mView.animate()
                        .setStartDelay(mKeyguardStateController.getKeyguardFadingAwayDelay())
                        .setDuration(mKeyguardStateController.getShortenedFadingAwayDuration())
                        .start();
            }
        } else if (oldStatusBarState == StatusBarState.SHADE_LOCKED && statusBarState == KEYGUARD) {
            mView.setVisibility(View.VISIBLE);
            mKeyguardStatusViewVisibilityAnimating = true;
            mView.setAlpha(0f);
            mView.animate()
                    .alpha(1f)
                    .setStartDelay(0)
                    .setDuration(320)
                    .setInterpolator(Interpolators.ALPHA_IN)
                    .withEndAction(mAnimateKeyguardStatusViewVisibleEndRunnable);
        } else if (statusBarState == KEYGUARD) {
            if (keyguardFadingAway) {
                mKeyguardStatusViewVisibilityAnimating = true;
                mView.animate()
                        .alpha(0)
                        .translationYBy(-getHeight() * 0.05f)
                        .setInterpolator(Interpolators.FAST_OUT_LINEAR_IN)
                        .setDuration(125)
                        .setStartDelay(0)
                        .withEndAction(mAnimateKeyguardStatusViewInvisibleEndRunnable)
                        .start();
            } else if (mDozeParameters.shouldControlUnlockedScreenOff()) {
                mKeyguardStatusViewVisibilityAnimating = true;

                mView.setVisibility(View.VISIBLE);
                mView.setAlpha(0f);

                float curTranslationY = mView.getTranslationY();
                mView.setTranslationY(curTranslationY - getHeight() * 0.1f);
                mView.animate()
                        .setStartDelay((int) (StackStateAnimator.ANIMATION_DURATION_WAKEUP * .6f))
                        .setDuration(StackStateAnimator.ANIMATION_DURATION_WAKEUP)
                        .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                        .alpha(1f)
                        .translationY(curTranslationY)
                        .withEndAction(mAnimateKeyguardStatusViewVisibleEndRunnable)
                        .start();
            } else {
                mView.setVisibility(View.VISIBLE);
                mView.setAlpha(1f);
            }
        } else {
            mView.setVisibility(View.GONE);
            mView.setAlpha(1f);
        }
    }

    private void refreshTime() {
        mKeyguardClockSwitchController.refresh();
    }

    private void updateAodIcons() {
        NotificationIconContainer nic = (NotificationIconContainer)
                mView.findViewById(com.android.systemui.R.id.clock_notification_icon_container);
        if (mLockScreenMode == KeyguardUpdateMonitor.LOCK_SCREEN_MODE_NORMAL) {
            // alternate icon area is set in KeyguardClockSwitchController
            mNotificationIconAreaController.setupAodIcons(nic, mLockScreenMode);
        } else {
            nic.setVisibility(View.GONE);
        }
    }

    private final ConfigurationController.ConfigurationListener mConfigurationListener =
            new ConfigurationController.ConfigurationListener() {
        @Override
        public void onLocaleListChanged() {
            refreshTime();
        }

        @Override
        public void onDensityOrFontScaleChanged() {
            mKeyguardClockSwitchController.onDensityOrFontScaleChanged();
            mView.onDensityOrFontScaleChanged();
        }
    };

    private KeyguardUpdateMonitorCallback mInfoCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onLockScreenModeChanged(int mode) {
            mLockScreenMode = mode;
            mKeyguardClockSwitchController.updateLockScreenMode(mode);
            mKeyguardSliceViewController.updateLockScreenMode(mode);
            if (mLockScreenMode == KeyguardUpdateMonitor.LOCK_SCREEN_MODE_LAYOUT_1) {
                // align the top of keyguard_status_area with the top of the clock text instead
                // of the top of the view
                mKeyguardSliceViewController.updateTopMargin(
                        mKeyguardClockSwitchController.getClockTextTopPadding());
                mView.setCanShowOwnerInfo(false);
                mView.setCanShowLogout(false);
            } else {
                // reset margin
                mKeyguardSliceViewController.updateTopMargin(0);
                mView.setCanShowOwnerInfo(true);
                mView.setCanShowLogout(false);
            }
            updateAodIcons();
        }

        @Override
        public void onTimeChanged() {
            refreshTime();
        }

        @Override
        public void onTimeZoneChanged(TimeZone timeZone) {
            mKeyguardClockSwitchController.updateTimeZone(timeZone);
        }

        @Override
        public void onTimeFormatChanged(String timeFormat) {
            mKeyguardClockSwitchController.refreshFormat(timeFormat);
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            if (showing) {
                if (DEBUG) Slog.v(TAG, "refresh statusview showing:" + showing);
                refreshTime();
                mView.updateOwnerInfo();
                mView.updateLogoutView();
            }
        }

        @Override
        public void onStartedWakingUp() {
            mView.setEnableMarquee(true);
        }

        @Override
        public void onFinishedGoingToSleep(int why) {
            mView.setEnableMarquee(false);
        }

        @Override
        public void onUserSwitchComplete(int userId) {
            mKeyguardClockSwitchController.refreshFormat();
            mView.updateOwnerInfo();
            mView.updateLogoutView();
        }

        @Override
        public void onLogoutEnabledChanged() {
            mView.updateLogoutView();
        }
    };

    private final Runnable mAnimateKeyguardStatusViewInvisibleEndRunnable = () -> {
        mKeyguardStatusViewVisibilityAnimating = false;
        mView.setVisibility(View.INVISIBLE);
    };


    private final Runnable mAnimateKeyguardStatusViewGoneEndRunnable = () -> {
        mKeyguardStatusViewVisibilityAnimating = false;
        mView.setVisibility(View.GONE);
    };

    private final Runnable mAnimateKeyguardStatusViewVisibleEndRunnable = () -> {
        mKeyguardStatusViewVisibilityAnimating = false;
    };
}
