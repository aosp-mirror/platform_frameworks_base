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
import static com.android.systemui.doze.util.BurnInHelperKt.getBurnInScale;
import static com.android.systemui.statusbar.notification.NotificationUtils.interpolate;

import android.content.res.Resources;
import android.util.MathUtils;

import com.android.keyguard.KeyguardStatusView;
import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.statusbar.policy.KeyguardUserSwitcherListView;

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
     * Margin between the bottom of the status view and the notification shade.
     */
    private int mStatusViewBottomMargin;

    /**
     * Height of the parent view - display size in px.
     */
    private int mHeight;

    /**
     * Height of {@link KeyguardStatusView}.
     */
    private int mKeyguardStatusHeight;

    /**
     * Height of user avatar used by the multi-user switcher. This could either be the
     * {@link KeyguardUserSwitcherListView} when it is closed and only the current user's icon is
     * visible, or it could be height of the avatar used by the
     * {@link com.android.systemui.statusbar.policy.KeyguardQsUserSwitchController}.
     */
    private int mUserSwitchHeight;

    /**
     * Preferred Y position of user avatar used by the multi-user switcher.
     */
    private int mUserSwitchPreferredY;

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
     * Minimum top margin to avoid overlap with status bar, lock icon, or multi-user switcher
     * avatar.
     */
    private int mMinTopMargin;

    /**
     * Minimum top inset (in pixels) to avoid overlap with any display cutouts.
     */
    private int mCutoutTopInset = 0;

    /**
     * Maximum bottom padding to avoid overlap with {@link KeyguardBottomAreaView} or
     * the ambient indication.
     */
    private int mMaxShadeBottom;

    /**
     * Recommended distance from the status bar.
     */
    private int mContainerTopPadding;

    /**
     * @see NotificationPanelViewController#getExpandedFraction()
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
     * Burn-in prevention y translation for large clock layouts.
     */
    private int mBurnInPreventionOffsetYLargeClock;

    /**
     * Doze/AOD transition amount.
     */
    private float mDarkAmount;

    /**
     * How visible the quick settings panel is.
     */
    private float mQsExpansion;

    private float mOverStretchAmount;

    /**
     * Setting if bypass is enabled. If true the clock should always be positioned like it's dark
     * and other minor adjustments.
     */
    private boolean mBypassEnabled;

    /**
     * The stackscroller padding when unlocked
     */
    private int mUnlockedStackScrollerPadding;

    private boolean mIsSplitShade;

    /**
     * Refreshes the dimension values.
     */
    public void loadDimens(Resources res) {
        mStatusViewBottomMargin = res.getDimensionPixelSize(
                R.dimen.keyguard_status_view_bottom_margin);

        mContainerTopPadding =
                res.getDimensionPixelSize(R.dimen.keyguard_clock_top_margin) / 2;
        mBurnInPreventionOffsetX = res.getDimensionPixelSize(
                R.dimen.burn_in_prevention_offset_x);
        mBurnInPreventionOffsetY = res.getDimensionPixelSize(
                R.dimen.burn_in_prevention_offset_y);
        mBurnInPreventionOffsetYLargeClock = res.getDimensionPixelSize(
                R.dimen.burn_in_prevention_offset_y_large_clock);
    }

    /**
     * Sets up algorithm values.
     */
    public void setup(int keyguardStatusBarHeaderHeight, int maxShadeBottom,
            int notificationStackHeight, float panelExpansion, int parentHeight,
            int keyguardStatusHeight, int userSwitchHeight, int userSwitchPreferredY,
            boolean hasCustomClock, boolean hasVisibleNotifs, float dark,
            float overStrechAmount, boolean bypassEnabled, int unlockedStackScrollerPadding,
            float qsExpansion, int cutoutTopInset, boolean isSplitShade) {
        mMinTopMargin = keyguardStatusBarHeaderHeight + Math.max(mContainerTopPadding,
                userSwitchHeight);
        mMaxShadeBottom = maxShadeBottom;
        mNotificationStackHeight = notificationStackHeight;
        mPanelExpansion = panelExpansion;
        mHeight = parentHeight;
        mKeyguardStatusHeight = keyguardStatusHeight + mStatusViewBottomMargin;
        mUserSwitchHeight = userSwitchHeight;
        mUserSwitchPreferredY = userSwitchPreferredY;
        mHasCustomClock = hasCustomClock;
        mHasVisibleNotifs = hasVisibleNotifs;
        mDarkAmount = dark;
        mOverStretchAmount = overStrechAmount;
        mBypassEnabled = bypassEnabled;
        mUnlockedStackScrollerPadding = unlockedStackScrollerPadding;
        mQsExpansion = qsExpansion;
        mCutoutTopInset = cutoutTopInset;
        mIsSplitShade = isSplitShade;
    }

    public void run(Result result) {
        final int y = getClockY(mPanelExpansion, mDarkAmount);
        result.clockY = y;
        result.userSwitchY = getUserSwitcherY(mPanelExpansion);
        result.clockYFullyDozing = getClockY(
                1.0f /* panelExpansion */, 1.0f /* darkAmount */);
        result.clockAlpha = getClockAlpha(y);
        result.stackScrollerPadding = getStackScrollerPadding(y);
        result.stackScrollerPaddingExpanded = mBypassEnabled ? mUnlockedStackScrollerPadding
                : getClockY(1.0f, mDarkAmount) + mKeyguardStatusHeight;
        result.clockX = (int) interpolate(0, burnInPreventionOffsetX(), mDarkAmount);
        result.clockScale = interpolate(getBurnInScale(), 1.0f, 1.0f - mDarkAmount);
    }

    private int getStackScrollerPadding(int clockYPosition) {
        if (mBypassEnabled) {
            return (int) (mUnlockedStackScrollerPadding + mOverStretchAmount);
        } else if (mIsSplitShade) {
            return clockYPosition;
        } else {
            return clockYPosition + mKeyguardStatusHeight;
        }
    }

    public float getMinStackScrollerPadding() {
        return mBypassEnabled ? mUnlockedStackScrollerPadding
                : mMinTopMargin + mKeyguardStatusHeight;
    }

    private int getExpandedPreferredClockY() {
        return mMinTopMargin + mUserSwitchHeight;
    }

    public int getLockscreenStatusViewHeight() {
        return mKeyguardStatusHeight;
    }

    private int getClockY(float panelExpansion, float darkAmount) {
        float clockYRegular = getExpandedPreferredClockY();

        // Dividing the height creates a smoother transition when the user swipes up to unlock
        float clockYBouncer = -mKeyguardStatusHeight / 3.0f;

        // Move clock up while collapsing the shade
        float shadeExpansion = Interpolators.FAST_OUT_LINEAR_IN.getInterpolation(panelExpansion);
        float clockY = MathUtils.lerp(clockYBouncer, clockYRegular, shadeExpansion);

        // This will keep the clock at the top but out of the cutout area
        float shift = 0;
        if (clockY - mBurnInPreventionOffsetYLargeClock < mCutoutTopInset) {
            shift = mCutoutTopInset - (clockY - mBurnInPreventionOffsetYLargeClock);
        }
        float clockYDark = clockY + burnInPreventionOffsetY() + shift;

        return (int) (MathUtils.lerp(clockY, clockYDark, darkAmount) + mOverStretchAmount);
    }

    private int getUserSwitcherY(float panelExpansion) {
        float userSwitchYRegular = mUserSwitchPreferredY;
        float userSwitchYBouncer = -mKeyguardStatusHeight - mUserSwitchHeight;

        // Move user-switch up while collapsing the shade
        float shadeExpansion = Interpolators.FAST_OUT_LINEAR_IN.getInterpolation(panelExpansion);
        float userSwitchY = MathUtils.lerp(userSwitchYBouncer, userSwitchYRegular, shadeExpansion);

        return (int) (userSwitchY + mOverStretchAmount);
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
        float alphaKeyguard = Math.max(0, y / Math.max(1f, getClockY(1f, mDarkAmount)));
        float qsAlphaFactor = MathUtils.saturate(mQsExpansion / 0.3f);
        qsAlphaFactor = 1f - qsAlphaFactor;
        alphaKeyguard *= qsAlphaFactor;
        alphaKeyguard = Interpolators.ACCELERATE.getInterpolation(alphaKeyguard);
        return MathUtils.lerp(alphaKeyguard, 1f, mDarkAmount);
    }

    private float burnInPreventionOffsetY() {
        int offset = mBurnInPreventionOffsetYLargeClock;

        return getBurnInOffset(offset * 2, false /* xAxis */) - offset;
    }

    private float burnInPreventionOffsetX() {
        return getBurnInOffset(mBurnInPreventionOffsetX, true /* xAxis */);
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
         * The y translation of the multi-user switch.
         */
        public int userSwitchY;

        /**
         * The y translation of the clock when we're fully dozing.
         */
        public int clockYFullyDozing;

        /**
         * The alpha value of the clock.
         */
        public float clockAlpha;

        /**
         * Amount to scale the large clock (0.0 - 1.0)
         */
        public float clockScale;

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
