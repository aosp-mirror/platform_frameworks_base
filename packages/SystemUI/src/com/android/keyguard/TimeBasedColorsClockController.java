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

import com.android.systemui.util.ViewController;

import java.util.Calendar;
import java.util.GregorianCalendar;

/**
 * Changes the color of the text clock based on the time of day.
 */
public class TimeBasedColorsClockController extends ViewController<GradientTextClock> {
    private final int[] mGradientColors = new int[3];
    private final float[] mPositions = new float[3];

    public TimeBasedColorsClockController(GradientTextClock view) {
        super(view);
    }

    @Override
    protected void onViewAttached() {
        refreshTime(System.currentTimeMillis());
    }

    @Override
    protected void onViewDetached() {

    }

    /**
     * Updates the time for this view. Also updates any color changes.
     */
    public void refreshTime(long timeInMillis) {
        Calendar now = new GregorianCalendar();
        now.setTimeInMillis(timeInMillis);
        updateColors(now);
        updatePositions(now);
        mView.refreshTime();
    }

    private int getTimeIndex(Calendar now) {
        int hour = now.get(Calendar.HOUR_OF_DAY); // 0 - 23
        if (hour < mTimes[0]) {
            return mTimes.length - 1;
        }

        for (int i = 1; i < mTimes.length; i++) {
            if (hour < mTimes[i]) {
                return i - 1;
            }
        }

        return mTimes.length - 1;
    }

    private void updateColors(Calendar now) {
        final int index = getTimeIndex(now);
        for (int i = 0; i < mGradientColors.length; i++) {
            mGradientColors[i] = COLORS[index][i];
        }
        mView.setGradientColors(mGradientColors);
    }

    private void updatePositions(Calendar now) {
        final int index = getTimeIndex(now);

        final Calendar startTime = new GregorianCalendar();
        startTime.setTimeInMillis(now.getTimeInMillis());
        startTime.set(Calendar.HOUR_OF_DAY, mTimes[index]);
        if (startTime.getTimeInMillis() > now.getTimeInMillis()) {
            // start should be earlier than 'now'
            startTime.add(Calendar.DATE, -1);
        }

        final Calendar endTime = new GregorianCalendar();
        endTime.setTimeInMillis(now.getTimeInMillis());
        if (index == mTimes.length - 1) {
            endTime.set(Calendar.HOUR_OF_DAY, mTimes[0]);
            endTime.add(Calendar.DATE, 1); // end time is tomorrow
        } else {
            endTime.set(Calendar.HOUR_OF_DAY, mTimes[index + 1]);
        }

        long totalTimeInThisColorGradient = endTime.getTimeInMillis() - startTime.getTimeInMillis();
        long timeIntoThisColorGradient = now.getTimeInMillis() - startTime.getTimeInMillis();
        float percentageWithinGradient =
                (float) timeIntoThisColorGradient / (float) totalTimeInThisColorGradient;

        for (int i = 0; i < mPositions.length; i++) {
            // currently hard-coded .3 movement of gradient
            mPositions[i] = POSITIONS[index][i] - (.3f * percentageWithinGradient);
        }
        mView.setColorPositions(mPositions);
    }

    private static final int[] SUNRISE = new int[] {0xFF6F75AA, 0xFFAFF0FF, 0xFFFFDEBF};
    private static final int[] DAY = new int[] {0xFF9BD8FB, 0xFFD7F5FF, 0xFFFFF278};
    private static final int[] NIGHT = new int[] {0xFF333D5E, 0xFFC5A1D6, 0xFF907359};

    private static final float[] SUNRISE_START_POSITIONS = new float[] {.3f, .5f, .8f};
    private static final float[] DAY_START_POSITIONS = new float[] {.4f, .8f, 1f};
    private static final float[] NIGHT_START_POSITIONS = new float[] {.25f, .5f, .8f};

    // TODO (b/170228350): use TwilightManager to set sunrise/sunset times
    private final int mSunriseTime = 6; // 6am
    private final int mDaytime = 9; // 9 am
    private final int mNightTime = 19; // 7pm

    private int[] mTimes = new int[] {
            mSunriseTime,
            mDaytime,
            mNightTime
    };
    private static final int[][] COLORS = new int[][] {
            SUNRISE,
            DAY,
            NIGHT
    };
    private static final float[][] POSITIONS = new float[][] {
            SUNRISE_START_POSITIONS,
            DAY_START_POSITIONS,
            NIGHT_START_POSITIONS
    };
}
