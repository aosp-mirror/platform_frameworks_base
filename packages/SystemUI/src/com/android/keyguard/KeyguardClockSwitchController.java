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

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.app.WallpaperManager;
import android.text.TextUtils;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.keyguard.clock.ClockManager;
import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.plugins.ClockPlugin;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shared.system.smartspace.SmartspaceTransitionController;
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController;
import com.android.systemui.statusbar.notification.AnimatableProperty;
import com.android.systemui.statusbar.notification.PropertyAnimator;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.NotificationIconAreaController;
import com.android.systemui.statusbar.phone.NotificationIconContainer;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.util.ViewController;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

import javax.inject.Inject;

/**
 * Injectable controller for {@link KeyguardClockSwitch}.
 */
public class KeyguardClockSwitchController extends ViewController<KeyguardClockSwitch> {
    private static final boolean CUSTOM_CLOCKS_ENABLED = true;

    private final StatusBarStateController mStatusBarStateController;
    private final SysuiColorExtractor mColorExtractor;
    private final ClockManager mClockManager;
    private final KeyguardSliceViewController mKeyguardSliceViewController;
    private final NotificationIconAreaController mNotificationIconAreaController;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final BatteryController mBatteryController;
    private final LockscreenSmartspaceController mSmartspaceController;

    /**
     * Clock for both small and large sizes
     */
    private AnimatableClockController mClockViewController;
    private FrameLayout mClockFrame; // top aligned clock
    private AnimatableClockController mLargeClockViewController;
    private FrameLayout mLargeClockFrame; // centered clock

    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final KeyguardBypassController mBypassController;

    private int mLargeClockTopMargin = 0;
    private int mKeyguardClockTopMargin = 0;

    /**
     * Listener for changes to the color palette.
     *
     * The color palette changes when the wallpaper is changed.
     */
    private final ColorExtractor.OnColorsChangedListener mColorsListener =
            (extractor, which) -> {
                if ((which & WallpaperManager.FLAG_LOCK) != 0) {
                    mView.updateColors(getGradientColors());
                }
            };

    private final ClockManager.ClockChangedListener mClockChangedListener = this::setClockPlugin;

    // If set, will replace keyguard_status_area
    private View mSmartspaceView;

    private final KeyguardUnlockAnimationController mKeyguardUnlockAnimationController;
    private SmartspaceTransitionController mSmartspaceTransitionController;

    private boolean mOnlyClock = false;

    @Inject
    public KeyguardClockSwitchController(
            KeyguardClockSwitch keyguardClockSwitch,
            StatusBarStateController statusBarStateController,
            SysuiColorExtractor colorExtractor,
            ClockManager clockManager,
            KeyguardSliceViewController keyguardSliceViewController,
            NotificationIconAreaController notificationIconAreaController,
            BroadcastDispatcher broadcastDispatcher,
            BatteryController batteryController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            KeyguardBypassController bypassController,
            LockscreenSmartspaceController smartspaceController,
            KeyguardUnlockAnimationController keyguardUnlockAnimationController,
            SmartspaceTransitionController smartspaceTransitionController) {
        super(keyguardClockSwitch);
        mStatusBarStateController = statusBarStateController;
        mColorExtractor = colorExtractor;
        mClockManager = clockManager;
        mKeyguardSliceViewController = keyguardSliceViewController;
        mNotificationIconAreaController = notificationIconAreaController;
        mBroadcastDispatcher = broadcastDispatcher;
        mBatteryController = batteryController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mBypassController = bypassController;
        mSmartspaceController = smartspaceController;

        mKeyguardUnlockAnimationController = keyguardUnlockAnimationController;
        mSmartspaceTransitionController = smartspaceTransitionController;
    }

    /**
     * Mostly used for alternate displays, limit the information shown
     */
    public void setOnlyClock(boolean onlyClock) {
        mOnlyClock = onlyClock;
    }

    /**
     * Attach the controller to the view it relates to.
     */
    @Override
    public void onInit() {
        mKeyguardSliceViewController.init();

        mClockFrame = mView.findViewById(R.id.lockscreen_clock_view);
        mLargeClockFrame = mView.findViewById(R.id.lockscreen_clock_view_large);

        mClockViewController =
                new AnimatableClockController(
                        mView.findViewById(R.id.animatable_clock_view),
                        mStatusBarStateController,
                        mBroadcastDispatcher,
                        mBatteryController,
                        mKeyguardUpdateMonitor,
                        mBypassController);
        mClockViewController.init();

        mLargeClockViewController =
                new AnimatableClockController(
                        mView.findViewById(R.id.animatable_clock_view_large),
                        mStatusBarStateController,
                        mBroadcastDispatcher,
                        mBatteryController,
                        mKeyguardUpdateMonitor,
                        mBypassController);
        mLargeClockViewController.init();
    }

    @Override
    protected void onViewAttached() {
        if (CUSTOM_CLOCKS_ENABLED) {
            mClockManager.addOnClockChangedListener(mClockChangedListener);
        }
        mColorExtractor.addOnColorsChangedListener(mColorsListener);
        mView.updateColors(getGradientColors());
        mKeyguardClockTopMargin =
                mView.getResources().getDimensionPixelSize(R.dimen.keyguard_clock_top_margin);

        if (mOnlyClock) {
            View ksa = mView.findViewById(R.id.keyguard_status_area);
            ksa.setVisibility(View.GONE);

            View nic = mView.findViewById(
                    R.id.left_aligned_notification_icon_container);
            nic.setVisibility(View.GONE);
            return;
        }
        updateAodIcons();

        if (mSmartspaceController.isEnabled()) {
            mSmartspaceView = mSmartspaceController.buildAndConnectView(mView);

            View ksa = mView.findViewById(R.id.keyguard_status_area);
            int ksaIndex = mView.indexOfChild(ksa);
            ksa.setVisibility(View.GONE);

            // Place smartspace view below normal clock...
            RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                    MATCH_PARENT, WRAP_CONTENT);
            lp.addRule(RelativeLayout.BELOW, R.id.lockscreen_clock_view);

            mView.addView(mSmartspaceView, ksaIndex, lp);
            int startPadding = getContext().getResources()
                    .getDimensionPixelSize(R.dimen.below_clock_padding_start);
            int endPadding = getContext().getResources()
                    .getDimensionPixelSize(R.dimen.below_clock_padding_end);
            mSmartspaceView.setPaddingRelative(startPadding, 0, endPadding, 0);

            updateClockLayout();

            View nic = mView.findViewById(
                    R.id.left_aligned_notification_icon_container);
            lp = (RelativeLayout.LayoutParams) nic.getLayoutParams();
            lp.addRule(RelativeLayout.BELOW, mSmartspaceView.getId());
            nic.setLayoutParams(lp);

            mView.setSmartspaceView(mSmartspaceView);
            mSmartspaceTransitionController.setLockscreenSmartspace(mSmartspaceView);
        }
    }

    int getNotificationIconAreaHeight() {
        return mNotificationIconAreaController.getHeight();
    }

    @Override
    protected void onViewDetached() {
        if (CUSTOM_CLOCKS_ENABLED) {
            mClockManager.removeOnClockChangedListener(mClockChangedListener);
        }
        mColorExtractor.removeOnColorsChangedListener(mColorsListener);
        mView.setClockPlugin(null, mStatusBarStateController.getState());

        mSmartspaceController.disconnect();

        // TODO: This is an unfortunate necessity since smartspace plugin retains a single instance
        // of the smartspace view -- if we don't remove the view, it can't be reused by a later
        // instance of this class. In order to fix this, we need to modify the plugin so that
        // (a) we get a new view each time and (b) we can properly clean up an old view by making
        // it unregister itself as a plugin listener.
        if (mSmartspaceView != null) {
            mView.removeView(mSmartspaceView);
            mSmartspaceView = null;
        }
    }

    /**
     * Apply dp changes on font/scale change
     */
    public void onDensityOrFontScaleChanged() {
        mView.onDensityOrFontScaleChanged();
        mKeyguardClockTopMargin =
                mView.getResources().getDimensionPixelSize(R.dimen.keyguard_clock_top_margin);

        updateClockLayout();
    }

    private void updateClockLayout() {
        if (mSmartspaceController.isEnabled()) {
            RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(MATCH_PARENT,
                    MATCH_PARENT);
            mLargeClockTopMargin = getContext().getResources().getDimensionPixelSize(
                    R.dimen.keyguard_large_clock_top_margin);
            lp.topMargin = mLargeClockTopMargin;
            mLargeClockFrame.setLayoutParams(lp);
        } else {
            mLargeClockTopMargin = 0;
        }
    }

    /**
     * Set whether or not the lock screen is showing notifications.
     */
    public void setHasVisibleNotifications(boolean hasVisibleNotifications) {
        if (mView.willSwitchToLargeClock(hasVisibleNotifications)) {
            mLargeClockViewController.animateAppear();
        }
    }

    /**
     * If we're presenting a custom clock of just the default one.
     */
    public boolean hasCustomClock() {
        return mView.hasCustomClock();
    }

    /**
     * Get the clock text size.
     */
    public float getClockTextSize() {
        return mView.getTextSize();
    }

    /**
     * Refresh clock. Called in response to TIME_TICK broadcasts.
     */
    void refresh() {
        if (mClockViewController != null) {
            mClockViewController.refreshTime();
            mLargeClockViewController.refreshTime();
        }
        if (mSmartspaceController != null) {
            mSmartspaceController.requestSmartspaceUpdate();
        }

        mView.refresh();
    }

    /**
     * Update position of the view, with optional animation. Move the slice view and the clock
     * slightly towards the center in order to prevent burn-in. Y positioning occurs at the
     * view parent level. The large clock view will scale instead of using x position offsets, to
     * keep the clock centered.
     */
    void updatePosition(int x, float scale, AnimationProperties props, boolean animate) {
        x = getCurrentLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -x : x;

        PropertyAnimator.setProperty(mClockFrame, AnimatableProperty.TRANSLATION_X,
                x, props, animate);
        PropertyAnimator.setProperty(mLargeClockFrame, AnimatableProperty.SCALE_X,
                scale, props, animate);
        PropertyAnimator.setProperty(mLargeClockFrame, AnimatableProperty.SCALE_Y,
                scale, props, animate);

        if (mSmartspaceView != null) {
            PropertyAnimator.setProperty(mSmartspaceView, AnimatableProperty.TRANSLATION_X,
                    x, props, animate);

            // If we're unlocking with the SmartSpace shared element transition, let the controller
            // know that it should re-position our SmartSpace.
            if (mKeyguardUnlockAnimationController.isUnlockingWithSmartSpaceTransition()) {
                mKeyguardUnlockAnimationController.updateLockscreenSmartSpacePosition();
            }
        }

        mKeyguardSliceViewController.updatePosition(x, props, animate);
        mNotificationIconAreaController.updatePosition(x, props, animate);
    }

    /** Sets an alpha value on every child view except for the smartspace. */
    public void setChildrenAlphaExcludingSmartspace(float alpha) {
        final Set<View> excludedViews = new HashSet<>();

        if (mSmartspaceView != null) {
            excludedViews.add(mSmartspaceView);
        }

        setChildrenAlphaExcluding(alpha, excludedViews);
    }

    /** Sets an alpha value on every child view except for the views in the provided set. */
    public void setChildrenAlphaExcluding(float alpha, Set<View> excludedViews) {
        for (int i = 0; i < mView.getChildCount(); i++) {
            final View child = mView.getChildAt(i);

            if (!excludedViews.contains(child)) {
                child.setAlpha(alpha);
            }
        }
    }

    void updateTimeZone(TimeZone timeZone) {
        mView.onTimeZoneChanged(timeZone);
        if (mClockViewController != null) {
            mClockViewController.onTimeZoneChanged(timeZone);
            mLargeClockViewController.onTimeZoneChanged(timeZone);
        }
    }

    void refreshFormat() {
        if (mClockViewController != null) {
            mClockViewController.refreshFormat();
            mLargeClockViewController.refreshFormat();
        }
    }

    /**
     * Get y-bottom position of the currently visible clock on the keyguard.
     * We can't directly getBottom() because clock changes positions in AOD for burn-in
     */
    int getClockBottom(int statusBarHeaderHeight) {
        if (mLargeClockFrame.getVisibility() == View.VISIBLE) {
            View clock = mLargeClockFrame.findViewById(
                    com.android.systemui.R.id.animatable_clock_view_large);
            int frameHeight = mLargeClockFrame.getHeight();
            int clockHeight = clock.getHeight();
            return frameHeight / 2 + clockHeight / 2;
        } else {
            return mClockFrame.findViewById(
                    com.android.systemui.R.id.animatable_clock_view).getHeight()
                    + statusBarHeaderHeight + mKeyguardClockTopMargin;
        }
    }

    boolean isClockTopAligned() {
        return mLargeClockFrame.getVisibility() != View.VISIBLE;
    }

    private void updateAodIcons() {
        NotificationIconContainer nic = (NotificationIconContainer)
                mView.findViewById(
                        com.android.systemui.R.id.left_aligned_notification_icon_container);
        mNotificationIconAreaController.setupAodIcons(nic);
    }

    private void setClockPlugin(ClockPlugin plugin) {
        mView.setClockPlugin(plugin, mStatusBarStateController.getState());
    }

    private ColorExtractor.GradientColors getGradientColors() {
        return mColorExtractor.getColors(WallpaperManager.FLAG_LOCK);
    }

    private int getCurrentLayoutDirection() {
        return TextUtils.getLayoutDirectionFromLocale(Locale.getDefault());
    }
}
