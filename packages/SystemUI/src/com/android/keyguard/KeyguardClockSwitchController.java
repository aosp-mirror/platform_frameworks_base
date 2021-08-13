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

import static com.android.keyguard.KeyguardClockSwitch.LARGE;

import android.app.WallpaperManager;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
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
    private FrameLayout mClockFrame;
    private AnimatableClockController mLargeClockViewController;
    private FrameLayout mLargeClockFrame;

    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final KeyguardBypassController mBypassController;

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

    private ViewGroup mSmartspaceContainer;

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
        mSmartspaceContainer = mView.findViewById(R.id.keyguard_smartspace_container);
        mSmartspaceController.setKeyguardStatusContainer(mSmartspaceContainer);

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

        if (mOnlyClock) {
            View ksa = mView.findViewById(R.id.keyguard_status_area);
            ksa.setVisibility(View.GONE);

            View nic = mView.findViewById(
                    R.id.left_aligned_notification_icon_container);
            nic.setVisibility(View.GONE);
            return;
        }
        updateAodIcons();

        if (mSmartspaceController.isSmartspaceEnabled()) {
            // "Enabled" doesn't mean smartspace is displayed here - inside mSmartspaceContainer -
            // it might be a part of another view when in split shade. But it means that it CAN be
            // displayed here, so we want to hide keyguard_status_area and set views relations
            // accordingly.

            View ksa = mView.findViewById(R.id.keyguard_status_area);
            // we show either keyguard_status_area or smartspace, so when smartspace can be visible,
            // keyguard_status_area should be hidden
            ksa.setVisibility(View.GONE);

            updateClockLayout();

            View nic = mView.findViewById(R.id.left_aligned_notification_icon_container);
            RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) nic.getLayoutParams();
            lp.addRule(RelativeLayout.BELOW, mSmartspaceContainer.getId());
            nic.setLayoutParams(lp);
            mView.setSmartspaceView(mSmartspaceContainer);
            mSmartspaceTransitionController.setLockscreenSmartspace(mSmartspaceContainer);
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
        mSmartspaceContainer.removeAllViews();
    }

    /**
     * Apply dp changes on font/scale change
     */
    public void onDensityOrFontScaleChanged() {
        mView.onDensityOrFontScaleChanged();

        updateClockLayout();
    }

    private void updateClockLayout() {
        if (mSmartspaceController.isSmartspaceEnabled()) {
            RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(MATCH_PARENT,
                    MATCH_PARENT);
            lp.topMargin = getContext().getResources().getDimensionPixelSize(
                    R.dimen.keyguard_large_clock_top_margin);
            mLargeClockFrame.setLayoutParams(lp);
        }
    }

    /**
     * Set which clock should be displayed on the keyguard. The other one will be automatically
     * hidden.
     */
    public void displayClock(@KeyguardClockSwitch.ClockSize int clockSize) {
        boolean appeared = mView.switchToClock(clockSize);
        if (appeared && clockSize == LARGE) {
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

        if (mSmartspaceContainer != null) {
            PropertyAnimator.setProperty(mSmartspaceContainer, AnimatableProperty.TRANSLATION_X,
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

        if (mSmartspaceContainer != null) {
            excludedViews.add(mSmartspaceContainer);
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
