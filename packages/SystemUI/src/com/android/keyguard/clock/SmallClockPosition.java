/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.keyguard.clock;

import android.content.res.Resources;
import android.util.MathUtils;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Computes preferred position of clock by considering height of status bar and lock icon.
 */
class SmallClockPosition {

    /**
     * Dimensions used to determine preferred clock position.
     */
    private final int mStatusBarHeight;
    private final int mKeyguardLockPadding;
    private final int mKeyguardLockHeight;
    private final int mBurnInOffsetY;

    /**
     * Amount of transition between AOD and lock screen.
     */
    private float mDarkAmount;

    SmallClockPosition(Resources res) {
        this(res.getDimensionPixelSize(com.android.keyguard.R.dimen.status_bar_height),
                res.getDimensionPixelSize(com.android.keyguard.R.dimen.keyguard_lock_padding),
                res.getDimensionPixelSize(com.android.keyguard.R.dimen.keyguard_lock_height),
                res.getDimensionPixelSize(com.android.keyguard.R.dimen.burn_in_prevention_offset_y)
        );
    }

    @VisibleForTesting
    SmallClockPosition(int statusBarHeight, int lockPadding, int lockHeight, int burnInY) {
        mStatusBarHeight = statusBarHeight;
        mKeyguardLockPadding = lockPadding;
        mKeyguardLockHeight = lockHeight;
        mBurnInOffsetY = burnInY;
    }

    /**
     * See {@link ClockPlugin#setDarkAmount}.
     */
    void setDarkAmount(float darkAmount) {
        mDarkAmount = darkAmount;
    }

    /**
     * Gets the preferred Y position accounting for status bar and lock icon heights.
     */
    int getPreferredY() {
        // On AOD, clock needs to appear below the status bar with enough room for pixel shifting
        int aodY = mStatusBarHeight + mKeyguardLockHeight + 2 * mKeyguardLockPadding
                + mBurnInOffsetY;
        // On lock screen, clock needs to appear below the lock icon
        int lockY =  mStatusBarHeight + mKeyguardLockHeight + 2 * mKeyguardLockPadding;
        return (int) MathUtils.lerp(lockY, aodY, mDarkAmount);
    }
}
