/*
 * Copyright (C) 2018 The LineageOS Project
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

package com.android.systemui.statusbar.phone;

import android.util.Log;
import android.view.View;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.policy.Clock;
import com.android.systemui.tuner.TunerService;

public class ClockController implements TunerService.Tunable {

    private static final String TAG = "ClockController";

    private static final int CLOCK_POSITION_RIGHT = 0;
    private static final int CLOCK_POSITION_CENTER = 1;
    private static final int CLOCK_POSITION_LEFT = 2;

    public static final String CLOCK_POSITION = "lineagesystem:status_bar_clock";

    private Clock mActiveClock, mCenterClock, mLeftClock, mRightClock;

    private int mClockPosition = CLOCK_POSITION_LEFT;
    private boolean mBlackListed = false;

    public ClockController(View statusBar) {
        mCenterClock = statusBar.findViewById(R.id.clock_center);
        mLeftClock = statusBar.findViewById(R.id.clock);
        mRightClock = statusBar.findViewById(R.id.clock_right);

        mActiveClock = mLeftClock;

        Dependency.get(TunerService.class).addTunable(this,
                StatusBarIconController.ICON_BLACKLIST, CLOCK_POSITION);
    }

    public Clock getClock() {
        switch (mClockPosition) {
            case CLOCK_POSITION_RIGHT:
                return mRightClock;
            case CLOCK_POSITION_CENTER:
                return mCenterClock;
            case CLOCK_POSITION_LEFT:
            default:
                return mLeftClock;
        }
    }

    private void updateActiveClock() {
        mActiveClock.setClockVisibleByUser(false);
        mActiveClock = getClock();
        mActiveClock.setClockVisibleByUser(true);

        // Override any previous setting
        mActiveClock.setClockVisibleByUser(!mBlackListed);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        Log.d(TAG, "onTuningChanged key=" + key + " value=" + newValue);

        if (CLOCK_POSITION.equals(key)) {
            mClockPosition = newValue == null ? CLOCK_POSITION_LEFT : Integer.valueOf(newValue);
        } else {
            mBlackListed = StatusBarIconController.getIconBlacklist(newValue).contains("clock");
        }
        updateActiveClock();
    }
}
