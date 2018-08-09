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

    private static final long MILLIS_PER_MINUTES = 1000 * 60;
    private static final float BURN_IN_PREVENTION_PERIOD_Y = 521;
    private static final float BURN_IN_PREVENTION_PERIOD_X = 83;

    /**
     * How much the clock height influences the shade position.
     * 0 means nothing, 1 means move the shade up by the height of the clock
     * 0.5f means move the shade up by half of the size of the clock.
     */
    private static float CLOCK_HEIGHT_WEIGHT = 0.7f;

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
     * Clock vertical padding when pulsing.
     */
    private int mPulsingPadding;

    /**
     * Doze/AOD transition amount.
     */
    private float mDarkAmount;

    /**
     * If keyguard will require a password or just fade away.
     */
    private boolean mCurrentlySecure;

    /**
     * Dozing and receiving a notification (AOD notification.)
     */
    private boolean mPulsing;

    /**
     * Distance in pixels between the top of the screen and the first view of the bouncer.
     */
    private int mBouncerTop;

    /**
     * Refreshes the dimension values.
     */
    public void loadDimens(Resources res) {
        mClockNotificationsMargin = res.getDimensionPixelSize(
                R.dimen.keyguard_clock_notifications_margin);
        mContainerTopPadding = res.getDimensionPixelSize(
                R.dimen.keyguard_clock_top_margin);
        mBurnInPreventionOffsetX = res.getDimensionPixelSize(
                R.dimen.burn_in_prevention_offset_x);
        mBurnInPreventionOffsetY = res.getDimensionPixelSize(
                R.dimen.burn_in_prevention_offset_y);
        mPulsingPadding = res.getDimensionPixelSize(
                R.dimen.widget_pulsing_bottom_padding);
    }

    public void setup(int minTopMargin, int maxShadeBottom, int notificationStackHeight,
            float panelExpansion, int parentHeight,
            int keyguardStatusHeight, float dark, boolean secure, boolean pulsing,
            int bouncerTop) {
        mMinTopMargin = minTopMargin + mContainerTopPadding;
        mMaxShadeBottom = maxShadeBottom;
        mNotificationStackHeight = notificationStackHeight;
        mPanelExpansion = panelExpansion;
        mHeight = parentHeight;
        mKeyguardStatusHeight = keyguardStatusHeight;
        mDarkAmount = dark;
        mCurrentlySecure = secure;
        mPulsing = pulsing;
        mBouncerTop = bouncerTop;
    }

    public void run(Result result) {
        final int y = getClockY();
        result.clockY = y;
        result.clockAlpha = getClockAlpha(y);
        result.stackScrollerPadding = y + (mPulsing ? 0 : mKeyguardStatusHeight);
        result.clockX = (int) interpolate(0, burnInPreventionOffsetX(), mDarkAmount);
    }

    public float getMinStackScrollerPadding() {
        return mMinTopMargin + mKeyguardStatusHeight + mClockNotificationsMargin;
    }

    private int getMaxClockY() {
        return mHeight / 2 - mKeyguardStatusHeight - mClockNotificationsMargin;
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

    private int getClockY() {
        // Dark: Align the bottom edge of the clock at about half of the screen:
        float clockYDark = getMaxClockY() + burnInPreventionOffsetY();
        if (mPulsing) {
            clockYDark -= mPulsingPadding;
        }

        float clockYRegular = getExpandedClockPosition();
        boolean hasEnoughSpace = mMinTopMargin + mKeyguardStatusHeight < mBouncerTop;
        float clockYTarget = mCurrentlySecure && hasEnoughSpace ?
                mMinTopMargin : -mKeyguardStatusHeight;

        // Move clock up while collapsing the shade
        float shadeExpansion = Interpolators.FAST_OUT_LINEAR_IN.getInterpolation(mPanelExpansion);
        final float clockY = MathUtils.lerp(clockYTarget, clockYRegular, shadeExpansion);

        return (int) MathUtils.lerp(clockY, clockYDark, mDarkAmount);
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
        float alphaKeyguard;
        if (mCurrentlySecure) {
            alphaKeyguard = 1;
        } else {
            alphaKeyguard = Math.max(0, y / Math.max(1f, getExpandedClockPosition()));
            alphaKeyguard = Interpolators.ACCELERATE.getInterpolation(alphaKeyguard);
        }
        return MathUtils.lerp(alphaKeyguard, 1f, mDarkAmount);
    }

    private float burnInPreventionOffsetY() {
        return zigzag(System.currentTimeMillis() / MILLIS_PER_MINUTES,
                mBurnInPreventionOffsetY * 2,
                BURN_IN_PREVENTION_PERIOD_Y)
                - mBurnInPreventionOffsetY;
    }

    private float burnInPreventionOffsetX() {
        return zigzag(System.currentTimeMillis() / MILLIS_PER_MINUTES,
                mBurnInPreventionOffsetX * 2,
                BURN_IN_PREVENTION_PERIOD_X)
                - mBurnInPreventionOffsetX;
    }

    /**
     * Implements a continuous, piecewise linear, periodic zig-zag function
     *
     * Can be thought of as a linear approximation of abs(sin(x)))
     *
     * @param period period of the function, ie. zigzag(x + period) == zigzag(x)
     * @param amplitude maximum value of the function
     * @return a value between 0 and amplitude
     */
    private float zigzag(float x, float amplitude, float period) {
        float xprime = (x % period) / (period / 2);
        float interpolationAmount = (xprime <= 1) ? xprime : (2 - xprime);
        return interpolate(0, amplitude, interpolationAmount);
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
    }
}
