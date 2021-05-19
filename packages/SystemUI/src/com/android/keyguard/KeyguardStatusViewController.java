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

import android.graphics.Rect;
import android.os.UserHandle;
import android.util.Slog;

import com.android.systemui.statusbar.notification.AnimatableProperty;
import com.android.systemui.statusbar.notification.PropertyAnimator;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.phone.DozeParameters;
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
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final ConfigurationController mConfigurationController;
    private final DozeParameters mDozeParameters;
    private final KeyguardVisibilityHelper mKeyguardVisibilityHelper;
    private final Rect mClipBounds = new Rect();

    @Inject
    public KeyguardStatusViewController(
            KeyguardStatusView keyguardStatusView,
            KeyguardSliceViewController keyguardSliceViewController,
            KeyguardClockSwitchController keyguardClockSwitchController,
            KeyguardStateController keyguardStateController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            ConfigurationController configurationController,
            DozeParameters dozeParameters) {
        super(keyguardStatusView);
        mKeyguardSliceViewController = keyguardSliceViewController;
        mKeyguardClockSwitchController = keyguardClockSwitchController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mConfigurationController = configurationController;
        mDozeParameters = dozeParameters;
        mKeyguardVisibilityHelper = new KeyguardVisibilityHelper(mView, keyguardStateController,
                dozeParameters);
    }

    @Override
    public void onInit() {
        mKeyguardClockSwitchController.init();
        mView.setEnableMarquee(mKeyguardUpdateMonitor.isDeviceInteractive());
        mView.updateLogoutView(shouldShowLogout());
    }

    @Override
    protected void onViewAttached() {
        mKeyguardUpdateMonitor.registerCallback(mInfoCallback);
        mConfigurationController.addCallback(mConfigurationListener);
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
        if (!mKeyguardVisibilityHelper.isVisibilityAnimating()) {
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
        PropertyAnimator.setProperty(mView, AnimatableProperty.Y, y, CLOCK_ANIMATION_PROPERTIES,
                animate);

        mKeyguardClockSwitchController.updatePosition(x, scale, CLOCK_ANIMATION_PROPERTIES,
                animate);
    }

    /**
     * @return {@code true} if we are currently animating the screen off from unlock
     */
    public boolean isAnimatingScreenOffFromUnlocked() {
        return mKeyguardVisibilityHelper.isAnimatingScreenOffFromUnlocked();
    }

    /**
     * Set the visibility of the keyguard status view based on some new state.
     */
    public void setKeyguardStatusViewVisibility(
            int statusBarState,
            boolean keyguardFadingAway,
            boolean goingToFullShade,
            int oldStatusBarState) {
        mKeyguardVisibilityHelper.setViewVisibility(
                statusBarState, keyguardFadingAway, goingToFullShade, oldStatusBarState);
    }

    private void refreshTime() {
        mKeyguardClockSwitchController.refresh();
    }

    private boolean shouldShowLogout() {
        return mKeyguardUpdateMonitor.isLogoutEnabled()
                && KeyguardUpdateMonitor.getCurrentUser() != UserHandle.USER_SYSTEM;
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
            mKeyguardSliceViewController.updateLockScreenMode(mode);
            mView.setCanShowOwnerInfo(false);
            mView.updateLogoutView(false);
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
        public void onKeyguardVisibilityChanged(boolean showing) {
            if (showing) {
                if (DEBUG) Slog.v(TAG, "refresh statusview showing:" + showing);
                refreshTime();
                mView.updateOwnerInfo();
                mView.updateLogoutView(shouldShowLogout());
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
            mView.updateOwnerInfo();
            mView.updateLogoutView(shouldShowLogout());
        }

        @Override
        public void onLogoutEnabledChanged() {
            mView.updateLogoutView(shouldShowLogout());
        }
    };

    /**
     * Rect that specifies how KSV should be clipped, on its parent's coordinates.
     */
    public void setClipBounds(Rect clipBounds) {
        if (clipBounds != null) {
            mClipBounds.set(clipBounds.left, (int) (clipBounds.top - mView.getY()),
                    clipBounds.right, (int) (clipBounds.bottom - mView.getY()));
            mView.setClipBounds(mClipBounds);
        } else {
            mView.setClipBounds(null);
        }
    }
}
