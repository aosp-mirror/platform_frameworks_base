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

import android.content.res.Resources;
import android.graphics.Path;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.PathInterpolator;

import com.android.systemui.R;

/**
 * Utility class to calculate the clock position and top padding of notifications on Keyguard.
 */
public class KeyguardClockPositionAlgorithm {

    private static final float SLOW_DOWN_FACTOR = 0.4f;

    private static final float CLOCK_RUBBERBAND_FACTOR_MIN = 0.08f;
    private static final float CLOCK_RUBBERBAND_FACTOR_MAX = 0.8f;
    private static final float CLOCK_SCALE_FADE_START = 0.95f;
    private static final float CLOCK_SCALE_FADE_END = 0.75f;
    private static final float CLOCK_SCALE_FADE_END_NO_NOTIFS = 0.5f;

    private static final float CLOCK_ADJ_TOP_PADDING_MULTIPLIER_MIN = 1.4f;
    private static final float CLOCK_ADJ_TOP_PADDING_MULTIPLIER_MAX = 3.2f;

    private int mClockNotificationsMarginMin;
    private int mClockNotificationsMarginMax;
    private float mClockYFractionMin;
    private float mClockYFractionMax;
    private int mMaxKeyguardNotifications;
    private int mMaxPanelHeight;
    private float mExpandedHeight;
    private int mNotificationCount;
    private int mHeight;
    private int mKeyguardStatusHeight;
    private float mEmptyDragAmount;
    private float mDensity;

    /**
     * The number (fractional) of notifications the "more" card counts when calculating how many
     * notifications are currently visible for the y positioning of the clock.
     */
    private float mMoreCardNotificationAmount;

    private static final PathInterpolator sSlowDownInterpolator;

    static {
        Path path = new Path();
        path.moveTo(0, 0);
        path.cubicTo(0.3f, 0.875f, 0.6f, 1f, 1f, 1f);
        sSlowDownInterpolator = new PathInterpolator(path);
    }

    private AccelerateInterpolator mAccelerateInterpolator = new AccelerateInterpolator();

    /**
     * Refreshes the dimension values.
     */
    public void loadDimens(Resources res) {
        mClockNotificationsMarginMin = res.getDimensionPixelSize(
                R.dimen.keyguard_clock_notifications_margin_min);
        mClockNotificationsMarginMax = res.getDimensionPixelSize(
                R.dimen.keyguard_clock_notifications_margin_max);
        mClockYFractionMin = res.getFraction(R.fraction.keyguard_clock_y_fraction_min, 1, 1);
        mClockYFractionMax = res.getFraction(R.fraction.keyguard_clock_y_fraction_max, 1, 1);
        mMoreCardNotificationAmount =
                (float) res.getDimensionPixelSize(R.dimen.notification_summary_height) /
                        res.getDimensionPixelSize(R.dimen.notification_min_height);
        mDensity = res.getDisplayMetrics().density;
    }

    public void setup(int maxKeyguardNotifications, int maxPanelHeight, float expandedHeight,
            int notificationCount, int height, int keyguardStatusHeight, float emptyDragAmount) {
        mMaxKeyguardNotifications = maxKeyguardNotifications;
        mMaxPanelHeight = maxPanelHeight;
        mExpandedHeight = expandedHeight;
        mNotificationCount = notificationCount;
        mHeight = height;
        mKeyguardStatusHeight = keyguardStatusHeight;
        mEmptyDragAmount = emptyDragAmount;
    }

    public void run(Result result) {
        int y = getClockY() - mKeyguardStatusHeight / 2;
        float clockAdjustment = getClockYExpansionAdjustment();
        float topPaddingAdjMultiplier = getTopPaddingAdjMultiplier();
        result.stackScrollerPaddingAdjustment = (int) (clockAdjustment*topPaddingAdjMultiplier);
        int clockNotificationsPadding = getClockNotificationsPadding()
                + result.stackScrollerPaddingAdjustment;
        int padding = y + clockNotificationsPadding;
        result.clockY = y;
        result.stackScrollerPadding = mKeyguardStatusHeight + padding;
        result.clockScale = getClockScale(result.stackScrollerPadding,
                result.clockY,
                y + getClockNotificationsPadding() + mKeyguardStatusHeight);
        result.clockAlpha = getClockAlpha(result.clockScale);
    }

    private float getClockScale(int notificationPadding, int clockY, int startPadding) {
        float scaleMultiplier = getNotificationAmountT() == 0 ? 6.0f : 5.0f;
        float scaleEnd = clockY - mKeyguardStatusHeight * scaleMultiplier;
        float distanceToScaleEnd = notificationPadding - scaleEnd;
        float progress = distanceToScaleEnd / (startPadding - scaleEnd);
        progress = Math.max(0.0f, Math.min(progress, 1.0f));
        progress = mAccelerateInterpolator.getInterpolation(progress);
        progress *= Math.pow(1 + mEmptyDragAmount / mDensity / 300, 0.3f);
        return progress;
    }

    private int getClockNotificationsPadding() {
        float t = getNotificationAmountT();
        t = Math.min(t, 1.0f);
        return (int) (t * mClockNotificationsMarginMin + (1 - t) * mClockNotificationsMarginMax);
    }

    private float getClockYFraction() {
        float t = getNotificationAmountT();
        t = Math.min(t, 1.0f);
        return (1 - t) * mClockYFractionMax + t * mClockYFractionMin;
    }

    private int getClockY() {
        return (int) (getClockYFraction() * mHeight);
    }

    private float getClockYExpansionAdjustment() {
        float rubberbandFactor = getClockYExpansionRubberbandFactor();
        float value = (rubberbandFactor * (mMaxPanelHeight - mExpandedHeight));
        float t = value / mMaxPanelHeight;
        float slowedDownValue = -sSlowDownInterpolator.getInterpolation(t) * SLOW_DOWN_FACTOR
                * mMaxPanelHeight;
        if (mNotificationCount == 0) {
            return (-2*value + slowedDownValue)/3;
        } else {
            return slowedDownValue;
        }
    }

    private float getClockYExpansionRubberbandFactor() {
        float t = getNotificationAmountT();
        t = Math.min(t, 1.0f);
        t = (float) Math.pow(t, 0.3f);
        return (1 - t) * CLOCK_RUBBERBAND_FACTOR_MAX + t * CLOCK_RUBBERBAND_FACTOR_MIN;
    }

    private float getTopPaddingAdjMultiplier() {
        float t = getNotificationAmountT();
        t = Math.min(t, 1.0f);
        return (1 - t) * CLOCK_ADJ_TOP_PADDING_MULTIPLIER_MIN
                + t * CLOCK_ADJ_TOP_PADDING_MULTIPLIER_MAX;
    }

    private float getClockAlpha(float scale) {
        float fadeEnd = getNotificationAmountT() == 0.0f
                ? CLOCK_SCALE_FADE_END_NO_NOTIFS
                : CLOCK_SCALE_FADE_END;
        float alpha = (scale - fadeEnd)
                / (CLOCK_SCALE_FADE_START - fadeEnd);
        return Math.max(0, Math.min(1, alpha));
    }

    /**
     * @return a value from 0 to 1 depending on how many notification there are
     */
    private float getNotificationAmountT() {
        return mNotificationCount
                / (mMaxKeyguardNotifications + mMoreCardNotificationAmount);
    }

    public static class Result {

        /**
         * The y translation of the clock.
         */
        public int clockY;

        /**
         * The scale of the Clock
         */
        public float clockScale;

        /**
         * The alpha value of the clock.
         */
        public float clockAlpha;

        /**
         * The top padding of the stack scroller, in pixels.
         */
        public int stackScrollerPadding;

        /**
         * The top padding adjustment of the stack scroller, in pixels. This value is used to adjust
         * the padding, but not the overall panel size.
         */
        public int stackScrollerPaddingAdjustment;
    }
}
