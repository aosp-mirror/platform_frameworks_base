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

import android.content.Context;
import android.content.res.Resources;
import android.util.MathUtils;

import com.android.app.animation.Interpolators;
import com.android.keyguard.BouncerPanelExpansionCalculator;
import com.android.keyguard.KeyguardStatusView;
import com.android.systemui.log.LogBuffer;
import com.android.systemui.log.core.Logger;
import com.android.systemui.log.dagger.KeyguardClockLog;
import com.android.systemui.res.R;
import com.android.systemui.shade.LargeScreenHeaderHelper;
import com.android.systemui.shade.ShadeViewController;

import javax.inject.Inject;

/**
 * Utility class to calculate the clock position and top padding of notifications on Keyguard.
 */
public class KeyguardClockPositionAlgorithm {
    private static final String TAG = "KeyguardClockPositionAlgorithm";
    private static final boolean DEBUG = false;

    /**
     * Margin between the bottom of the status view and the notification shade.
     */
    private int mStatusViewBottomMargin;

    /**
     * Height of {@link KeyguardStatusView}.
     */
    private int mKeyguardStatusHeight;

    /**
     * Minimum top margin to avoid overlap with status bar or multi-user switcher avatar.
     */
    private int mMinTopMargin;

    /**
     * Minimum top inset (in pixels) to avoid overlap with any display cutouts.
     */
    private int mCutoutTopInset = 0;

    /**
     * Recommended distance from the status bar.
     */
    private int mContainerTopPadding;

    /**
     * Top margin of notifications introduced by presence of split shade header / status bar
     */
    private int mSplitShadeTopNotificationsMargin;

    /**
     * Target margin for notifications and clock from the top of the screen in split shade
     */
    private int mSplitShadeTargetTopMargin;

    /**
     * @see ShadeViewController#getExpandedFraction()
     */
    private float mPanelExpansion;

    /**
     * Max burn-in prevention x translation.
     */
    private int mMaxBurnInPreventionOffsetX;

    /**
     * Max burn-in prevention y translation for clock layouts.
     */
    private int mMaxBurnInPreventionOffsetYClock;

    /**
     * Current burn-in prevention y translation.
     */
    private float mCurrentBurnInOffsetY;

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
     * Top location of the udfps icon. This includes the worst case (highest) burn-in
     * offset that would make the top physically highest on the screen.
     *
     * Set to -1 if udfps is not enrolled on the device.
     */
    private float mUdfpsTop;

    /**
     * Bottom y-position of the currently visible clock
     */
    private float mClockBottom;

    /**
     * If true, try to keep clock aligned to the top of the display. Else, assume the clock
     * is center aligned.
     */
    private boolean mIsClockTopAligned;

    private Logger mLogger;

    @Inject
    public KeyguardClockPositionAlgorithm(@KeyguardClockLog LogBuffer logBuffer) {
        mLogger = new Logger(logBuffer, TAG);
    }

    /** Refreshes the dimension values. */
    public void loadDimens(Context context, Resources res) {
        mStatusViewBottomMargin =
                res.getDimensionPixelSize(R.dimen.keyguard_status_view_bottom_margin);
        mSplitShadeTopNotificationsMargin =
                LargeScreenHeaderHelper.getLargeScreenHeaderHeight(context);
        mSplitShadeTargetTopMargin =
                res.getDimensionPixelSize(R.dimen.keyguard_split_shade_top_margin);

        mContainerTopPadding =
                res.getDimensionPixelSize(R.dimen.keyguard_clock_top_margin);
        mMaxBurnInPreventionOffsetX = res.getDimensionPixelSize(
                R.dimen.burn_in_prevention_offset_x);
        mMaxBurnInPreventionOffsetYClock = res.getDimensionPixelSize(
                R.dimen.burn_in_prevention_offset_y_clock);
    }

    /**
     * Sets up algorithm values.
     */
    public void setup(int keyguardStatusBarHeaderHeight, float panelExpansion,
            int keyguardStatusHeight, float dark, float overStretchAmount, boolean bypassEnabled,
            int unlockedStackScrollerPadding, float qsExpansion, int cutoutTopInset,
            boolean isSplitShade, float udfpsTop, float clockBottom, boolean isClockTopAligned) {
        mMinTopMargin = keyguardStatusBarHeaderHeight + mContainerTopPadding;
        mPanelExpansion = BouncerPanelExpansionCalculator
                .getKeyguardClockScaledExpansion(panelExpansion);
        mKeyguardStatusHeight = keyguardStatusHeight + mStatusViewBottomMargin;
        mDarkAmount = dark;
        mOverStretchAmount = overStretchAmount;
        mBypassEnabled = bypassEnabled;
        mUnlockedStackScrollerPadding = unlockedStackScrollerPadding;
        mQsExpansion = qsExpansion;
        mCutoutTopInset = cutoutTopInset;
        mIsSplitShade = isSplitShade;
        mUdfpsTop = udfpsTop;
        mClockBottom = clockBottom;
        mIsClockTopAligned = isClockTopAligned;
    }

    public void run(Result result) {
        final int y = getClockY(mPanelExpansion, mDarkAmount);
        result.clockY = y;
        result.clockYFullyDozing = getClockY(
                1.0f /* panelExpansion */, 1.0f /* darkAmount */);
        result.clockAlpha = getClockAlpha(y);
        result.stackScrollerPadding = getStackScrollerPadding(y);
        result.stackScrollerPaddingExpanded = getStackScrollerPaddingExpanded();
        result.clockX = (int) interpolate(0, burnInPreventionOffsetX(), mDarkAmount);
        result.clockScale = interpolate(getBurnInScale(), 1.0f, 1.0f - mDarkAmount);
    }

    private int getStackScrollerPaddingExpanded() {
        if (mBypassEnabled) {
            return mUnlockedStackScrollerPadding;
        } else if (mIsSplitShade) {
            return getClockY(1.0f, mDarkAmount);
        } else {
            return getClockY(1.0f, mDarkAmount) + mKeyguardStatusHeight;
        }
    }

    private int getStackScrollerPadding(int clockYPosition) {
        if (mBypassEnabled) {
            return (int) (mUnlockedStackScrollerPadding + mOverStretchAmount);
        } else if (mIsSplitShade) {
            // mCurrentBurnInOffsetY is subtracted to make notifications not follow clock adjustment
            // for burn-in. It can make pulsing notification go too high and it will get clipped
            return clockYPosition - mSplitShadeTopNotificationsMargin
                    - (int) mCurrentBurnInOffsetY;
        } else {
            return clockYPosition + mKeyguardStatusHeight;
        }
    }

    /**
     * @param nsslTop NotificationStackScrollLayout top, which is below top of the srceen.
     * @return Distance from nsslTop to top of the first view in the lockscreen shade.
     */
    public float getLockscreenNotifPadding(float nsslTop) {
        if (mBypassEnabled) {
            return mUnlockedStackScrollerPadding - nsslTop;
        } else if (mIsSplitShade) {
            return mSplitShadeTargetTopMargin - nsslTop;
        } else {
            // Non-bypass portrait shade already uses values from nsslTop
            // so we don't need to subtract it here.
            return mMinTopMargin + mKeyguardStatusHeight;
        }
    }

    /**
     * give the static topMargin, used for lockscreen clocks to get the initial translationY
     * to do counter translation
     */
    public int getExpandedPreferredClockY() {
        if (mIsSplitShade) {
            return mSplitShadeTargetTopMargin;
        } else {
            return mMinTopMargin;
        }
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
        if (clockY - mMaxBurnInPreventionOffsetYClock < mCutoutTopInset) {
            shift = mCutoutTopInset - (clockY - mMaxBurnInPreventionOffsetYClock);
        }

        int burnInPreventionOffsetY = mMaxBurnInPreventionOffsetYClock; // requested offset
        final boolean hasUdfps = mUdfpsTop > -1;
        if (hasUdfps && !mIsClockTopAligned) {
            // ensure clock doesn't overlap with the udfps icon
            if (mUdfpsTop < mClockBottom) {
                // sometimes the clock textView extends beyond udfps, so let's just use the
                // space above the KeyguardStatusView/clock as our burn-in offset
                burnInPreventionOffsetY = (int) (clockY - mCutoutTopInset) / 2;
                if (mMaxBurnInPreventionOffsetYClock < burnInPreventionOffsetY) {
                    burnInPreventionOffsetY = mMaxBurnInPreventionOffsetYClock;
                }
                shift = -burnInPreventionOffsetY;
            } else {
                float upperSpace = clockY - mCutoutTopInset;
                float lowerSpace = mUdfpsTop - mClockBottom;
                // center the burn-in offset within the upper + lower space
                burnInPreventionOffsetY = (int) (lowerSpace + upperSpace) / 2;
                if (mMaxBurnInPreventionOffsetYClock < burnInPreventionOffsetY) {
                    burnInPreventionOffsetY = mMaxBurnInPreventionOffsetYClock;
                }
                shift = (lowerSpace - upperSpace) / 2;
            }
        }

        float fullyDarkBurnInOffset = burnInPreventionOffsetY(burnInPreventionOffsetY);
        float clockYDark = clockY + fullyDarkBurnInOffset + shift;
        mCurrentBurnInOffsetY = MathUtils.lerp(0, fullyDarkBurnInOffset, darkAmount);

        if (DEBUG) {
            final float finalShift = shift;
            final float finalBurnInPreventionOffsetY = burnInPreventionOffsetY;
            mLogger.i(msg -> {
                final String inputs = "panelExpansion: " + panelExpansion
                        + " darkAmount: " + darkAmount;
                final String outputs = "clockY: " + clockY
                        + " burnInPreventionOffsetY: " + finalBurnInPreventionOffsetY
                        + " fullyDarkBurnInOffset: " + fullyDarkBurnInOffset
                        + " shift: " + finalShift
                        + " mOverStretchAmount: " + mOverStretchAmount
                        + " mCurrentBurnInOffsetY: " + mCurrentBurnInOffsetY;
                return inputs + " -> " + outputs;
            }, msg -> {
                return kotlin.Unit.INSTANCE;
            });
        }
        return (int) (MathUtils.lerp(clockY, clockYDark, darkAmount) + mOverStretchAmount);
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
        if (!mIsSplitShade) {
            // in split shade QS are always expanded so this factor shouldn't apply
            float qsAlphaFactor = MathUtils.saturate(mQsExpansion / 0.3f);
            qsAlphaFactor = 1f - qsAlphaFactor;
            alphaKeyguard *= qsAlphaFactor;
        }
        alphaKeyguard = Interpolators.ACCELERATE.getInterpolation(alphaKeyguard);
        return MathUtils.lerp(alphaKeyguard, 1f, mDarkAmount);
    }

    private float burnInPreventionOffsetY(int offset) {
        return getBurnInOffset(offset * 2, false /* xAxis */) - offset;
    }

    private float burnInPreventionOffsetX() {
        return getBurnInOffset(mMaxBurnInPreventionOffsetX, true /* xAxis */);
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
