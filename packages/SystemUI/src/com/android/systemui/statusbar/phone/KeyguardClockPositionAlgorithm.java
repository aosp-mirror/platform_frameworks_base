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

import android.content.Context;
import android.content.res.Resources;

import com.android.systemui.log.LogBuffer;
import com.android.systemui.log.core.Logger;
import com.android.systemui.log.dagger.KeyguardClockLog;
import com.android.systemui.res.R;
import com.android.systemui.shade.LargeScreenHeaderHelper;

import javax.inject.Inject;

/**
 * Utility class to calculate the clock position and top padding of notifications on Keyguard.
 */
public class KeyguardClockPositionAlgorithm {
    private static final String TAG = "KeyguardClockPositionAlgorithm";
    private static final boolean DEBUG = false;

    /**
     * Top margin of notifications introduced by presence of split shade header / status bar
     */
    private int mSplitShadeTopNotificationsMargin;

    /**
     * Target margin for notifications and clock from the top of the screen in split shade
     */
    private int mSplitShadeTargetTopMargin;

    /**
     * Doze/AOD transition amount.
     */
    private float mDarkAmount;

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

    private Logger mLogger;

    @Inject
    public KeyguardClockPositionAlgorithm(@KeyguardClockLog LogBuffer logBuffer) {
        mLogger = new Logger(logBuffer, TAG);
    }

    /** Refreshes the dimension values. */
    public void loadDimens(Context context, Resources res) {
        mSplitShadeTopNotificationsMargin =
                LargeScreenHeaderHelper.getLargeScreenHeaderHeight(context);
        mSplitShadeTargetTopMargin =
                res.getDimensionPixelSize(R.dimen.keyguard_split_shade_top_margin);
    }

    /**
     * Sets up algorithm values.
     */
    public void setup(float dark, float overStretchAmount, boolean bypassEnabled,
            int unlockedStackScrollerPadding, boolean isSplitShade) {
        mDarkAmount = dark;
        mOverStretchAmount = overStretchAmount;
        mBypassEnabled = bypassEnabled;
        mUnlockedStackScrollerPadding = unlockedStackScrollerPadding;
        mIsSplitShade = isSplitShade;
    }

    public void run(Result result) {
        result.stackScrollerPadding = getStackScrollerPadding();
        result.stackScrollerPaddingExpanded = getStackScrollerPaddingExpanded();
    }

    private int getStackScrollerPaddingExpanded() {
        if (mBypassEnabled) {
            return mUnlockedStackScrollerPadding;
        } else if (mIsSplitShade) {
            return mSplitShadeTargetTopMargin;
        } else {
            return 0;
        }
    }

    private int getStackScrollerPadding() {
        if (mBypassEnabled) {
            return (int) (mUnlockedStackScrollerPadding + mOverStretchAmount);
        } else if (mIsSplitShade) {
            // mCurrentBurnInOffsetY is subtracted to make notifications not follow clock adjustment
            // for burn-in. It can make pulsing notification go too high and it will get clipped
            return mSplitShadeTargetTopMargin - mSplitShadeTopNotificationsMargin;
        } else {
            return 0;
        }
    }

    public static class Result {
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
