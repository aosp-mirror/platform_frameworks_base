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

package com.android.systemui.statusbar.phone;

import static com.android.systemui.doze.util.BurnInHelperKt.getBurnInOffset;
import static com.android.systemui.statusbar.notification.NotificationUtils.interpolate;

import android.content.res.Resources;
import android.util.MathUtils;

import com.android.keyguard.KeyguardStatusView;
import com.android.systemui.Interpolators;
import com.android.systemui.R;

/**
 * Utility class to calculate the clock position and top padding of notifications on Keyguard.
 */
public class KeyguardClockPositionAlgorithm {

    /**
     * How much the clock height influences the shade position.
     * 0 means nothing, 1 means move the shade up by the height of the clock
     * 0.5f means move the shade up by half of the size of the clock.
     */
    private static float CLOCK_HEIGHT_WEIGHT = 0.7f;

    /**
     * Allow using the default clock Y calculations
     */
    public static int CLOCK_USE_DEFAULT_Y = -1;

    /**
     * Margin between the bottom of the clock and the notification shade.
     */
    private int mClockNotificationsMargin;

    /**
     * Height of the parent view - display size in px.
     */
    private int mHeight;

    /**
     * Height of {@link KeyguardStatusView}.
     */
    private int mKeyguardStatusHeight;

    /**
     * Preferred Y position of clock.
     */
    private int mClockPreferredY;

    /**
     * Whether or not there is a custom clock face on keyguard.
     */
    private boolean mHasCustomClock;

    /**
     * Whether or not the NSSL contains any visible notifications.
     */
    private boolean mHasVisibleNotifs;

    /**
     * Height of notification stack: Sum of height of each notification.
     */
    private int mNotificationStackHeight;

    /**
     * Minimum top margin to avoid overlap with status bar.
     */
    private int mMinTopMargin;

    /**
     * Maximum bottom padding to avoid overlap with {@link KeyguardBottomAreaView} or
     * the ambient indication.
     */
    private int mMaxShadeBottom;

    /**
     * Minimum distance from the status bar.
     */
    private int mContainerTopPadding;

    /**
     * @see NotificationPanelView#getExpandedFraction()
     */
    private float mPanelExpansion;

    /**
     * Burn-in prevention x translation.
     */
    private int mBurnInPreventionOffsetX;

    /**
     * Burn-in prevention y translation.
     */
    private int mBurnInPreventionOffsetY;

    /**
     * Doze/AOD transition amount.
     */
    private float mDarkAmount;

    private float mEmptyDragAmount;

    /**
     * Setting if bypass is enabled. If true the clock should always be positioned like it's dark
     * and other minor adjustments.
     */
    private boolean mBypassEnabled;

    /**
     * The stackscroller padding when unlocked
     */
    private int mUnlockedStackScrollerPadding;

    /**
     * Refreshes the dimension values.
     */
    public void loadDimens(Resources res) {
        mClockNotificationsMargin = res.getDimensionPixelSize(
                R.dimen.keyguard_clock_notifications_margin);
        // Consider the lock icon when determining the minimum top padding between the status bar
        // and top of the clock.
        mContainerTopPadding = Math.max(res.getDimensionPixelSize(
                R.dimen.keyguard_clock_top_margin),
                res.getDimensionPixelSize(R.dimen.keyguard_lock_height)
                        + res.getDimensionPixelSize(R.dimen.keyguard_lock_padding)
                        + res.getDimensionPixelSize(R.dimen.keyguard_clock_lock_margin));
        mBurnInPreventionOffsetX = res.getDimensionPixelSize(
                R.dimen.burn_in_prevention_offset_x);
        mBurnInPreventionOffsetY = res.getDimensionPixelSize(
                R.dimen.burn_in_prevention_offset_y);
    }

    public void setup(int minTopMargin, int maxShadeBottom, int notificationStackHeight,
            float panelExpansion, int parentHeight, int keyguardStatusHeight, int clockPreferredY,
            boolean hasCustomClock, boolean hasVisibleNotifs, float dark, float emptyDragAmount,
            boolean bypassEnabled, int unlockedStackScrollerPadding) {
        mMinTopMargin = minTopMargin + mContainerTopPadding;
        mMaxShadeBottom = maxShadeBottom;
        mNotificationStackHeight = notificationStackHeight;
        mPanelExpansion = panelExpansion;
        mHeight = parentHeight;
        mKeyguardStatusHeight = keyguardStatusHeight;
        mClockPreferredY = clockPreferredY;
        mHasCustomClock = hasCustomClock;
        mHasVisibleNotifs = hasVisibleNotifs;
        mDarkAmount = dark;
        mEmptyDragAmount = emptyDragAmount;
        mBypassEnabled = bypassEnabled;
        mUnlockedStackScrollerPadding = unlockedStackScrollerPadding;
    }

    public void run(Result result) {
        final int y = getClockY(mPanelExpansion);
        result.clockY = y;
        result.clockAlpha = getClockAlpha(y);
        result.stackScrollerPadding = mBypassEnabled ? mUnlockedStackScrollerPadding
                : y + mKeyguardStatusHeight;
        result.stackScrollerPaddingExpanded = mBypassEnabled ? mUnlockedStackScrollerPadding
                : getClockY(1.0f) + mKeyguardStatusHeight;
        result.clockX = (int) interpolate(0, burnInPreventionOffsetX(), mDarkAmount);
    }

    public float getMinStackScrollerPadding() {
        return mBypassEnabled ? mUnlockedStackScrollerPadding
                : mMinTopMargin + mKeyguardStatusHeight + mClockNotificationsMargin;
    }

    private int getMaxClockY() {
        return mHeight / 2 - mKeyguardStatusHeight - mClockNotificationsMargin;
    }

    private int getPreferredAlternativeClockY(int alternative) {
        if (mClockPreferredY != CLOCK_USE_DEFAULT_Y) {
            return mClockPreferredY;
        } else  {
            return alternative;
        }
    }

    private int getExpandedPreferredClockY() {
        return (mHasCustomClock && (!mHasVisibleNotifs || mBypassEnabled))
                ? getPreferredAlternativeClockY(getExpandedClockPosition())
                : getExpandedClockPosition();
    }

    /**
     * Vertically align the clock and the shade in the available space considering only
     * a percentage of the clock height defined by {@code CLOCK_HEIGHT_WEIGHT}.
     * @return Clock Y in pixels.
     */
    public int getExpandedClockPosition() {
        final int availableHeight = mMaxShadeBottom - mMinTopMargin;
        final int containerCenter = mMinTopMargin + availableHeight / 2;

        float y = containerCenter - mKeyguardStatusHeight * CLOCK_HEIGHT_WEIGHT
                - mClockNotificationsMargin - mNotificationStackHeight / 2;
        if (y < mMinTopMargin) {
            y = mMinTopMargin;
        }

        // Don't allow the clock base to be under half of the screen
        final float maxClockY = getMaxClockY();
        if (y > maxClockY) {
            y = maxClockY;
        }

        return (int) y;
    }

    private int getClockY(float panelExpansion) {
        // Dark: Align the bottom edge of the clock at about half of the screen:
        float clockYDark = (mHasCustomClock ? getPreferredAlternativeClockY(getMaxClockY())
            : getMaxClockY()) + burnInPreventionOffsetY();
        clockYDark = MathUtils.max(0, clockYDark);

        float clockYRegular = getExpandedPreferredClockY();
        float clockYBouncer = -mKeyguardStatusHeight;

        // Move clock up while collapsing the shade
        float shadeExpansion = Interpolators.FAST_OUT_LINEAR_IN.getInterpolation(panelExpansion);
        float clockY = MathUtils.lerp(clockYBouncer, clockYRegular, shadeExpansion);
        clockYDark = MathUtils.lerp(clockYBouncer, clockYDark, shadeExpansion);

        float darkAmount = mBypassEnabled && !mHasCustomClock ? 1.0f : mDarkAmount;
        return (int) (MathUtils.lerp(clockY, clockYDark, darkAmount) + mEmptyDragAmount);
    }

    /**
     * We might want to fade out the clock when the user is swiping up.
     * One exception is when the bouncer will become visible, in this cause the clock
     * should always persist.
     *
     * @param y Current clock Y.
     * @return Alpha from 0 to 1.
     */
    private float getClockAlpha(int y) {
        float alphaKeyguard = Math.max(0, y / Math.max(1f, getClockY(1f)));
        alphaKeyguard = Interpolators.ACCELERATE.getInterpolation(alphaKeyguard);
        return MathUtils.lerp(alphaKeyguard, 1f, mDarkAmount);
    }

    private float burnInPreventionOffsetY() {
        return getBurnInOffset(mBurnInPreventionOffsetY * 2, false /* xAxis */)
                - mBurnInPreventionOffsetY;
    }

    private float burnInPreventionOffsetX() {
        return getBurnInOffset(mBurnInPreventionOffsetX * 2, true /* xAxis */)
                - mBurnInPreventionOffsetX;
    }

    public static class Result {

        /**
         * The x translation of the clock.
         */
        public int clockX;

        /**
         * The y translation of the clock.
         */
        public int clockY;

        /**
         * The alpha value of the clock.
         */
        public float clockAlpha;

        /**
         * The top padding of the stack scroller, in pixels.
         */
        public int stackScrollerPadding;

        /**
         * The top padding of the stack scroller, in pixels when fully expanded.
         */
        public int stackScrollerPaddingExpanded;
    }
}
