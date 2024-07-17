/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.power.stats;

import android.os.BatteryStats;
import android.os.PersistableBundle;

/**
 * Captures the positions and lengths of sections of the stats array, such as time-in-state,
 * power usage estimates etc.
 */
public class ScreenPowerStatsLayout extends PowerStatsLayout {
    private static final String EXTRA_DEVICE_SCREEN_COUNT = "dsc";
    private static final String EXTRA_DEVICE_SCREEN_ON_DURATION_POSITION = "dsd";
    private static final String EXTRA_DEVICE_BRIGHTNESS_DURATION_POSITIONS = "dbd";
    private static final String EXTRA_DEVICE_DOZE_DURATION_POSITION = "ddd";
    private static final String EXTRA_UID_FOREGROUND_DURATION = "uf";

    private int mDisplayCount;
    private int mDeviceScreenOnDurationPosition;
    private int[] mDeviceBrightnessDurationPositions;
    private int mDeviceScreenDozeDurationPosition;
    private int mUidTopActivityTimePosition;

    void addDeviceScreenUsageDurationSection(int displayCount) {
        mDisplayCount = displayCount;
        mDeviceScreenOnDurationPosition = addDeviceSection(displayCount, "on");
        mDeviceBrightnessDurationPositions = new int[BatteryStats.NUM_SCREEN_BRIGHTNESS_BINS];
        for (int level = 0; level < BatteryStats.NUM_SCREEN_BRIGHTNESS_BINS; level++) {
            mDeviceBrightnessDurationPositions[level] =
                    addDeviceSection(displayCount, BatteryStats.SCREEN_BRIGHTNESS_NAMES[level]);
        }
        mDeviceScreenDozeDurationPosition = addDeviceSection(displayCount, "doze");
    }

    public int getDisplayCount() {
        return mDisplayCount;
    }

    /**
     * Stores screen-on time for the specified display.
     */
    public void setScreenOnDuration(long[] stats, int display, long durationMs) {
        stats[mDeviceScreenOnDurationPosition + display] = durationMs;
    }

    /**
     * Returns screen-on time for the specified display.
     */
    public long getScreenOnDuration(long[] stats, int display) {
        return stats[mDeviceScreenOnDurationPosition + display];
    }

    /**
     * Stores time at the specified brightness level for the specified display.
     */
    public void setBrightnessLevelDuration(long[] stats, int display, int brightnessLevel,
            long durationMs) {
        stats[mDeviceBrightnessDurationPositions[brightnessLevel] + display] = durationMs;
    }

    /**
     * Returns time at the specified brightness level for the specified display.
     */
    public long getBrightnessLevelDuration(long[] stats, int display, int brightnessLevel) {
        return stats[mDeviceBrightnessDurationPositions[brightnessLevel] + display];
    }

    /**
     * Stores time in the doze (ambient) state for the specified display.
     */
    public void setScreenDozeDuration(long[] stats, int display, long durationMs) {
        stats[mDeviceScreenDozeDurationPosition + display] = durationMs;
    }

    /**
     * Retrieves time in the doze (ambient) state for the specified display.
     */
    public long getScreenDozeDuration(long[] stats, int display) {
        return stats[mDeviceScreenDozeDurationPosition + display];
    }

    void addUidTopActivitiyDuration() {
        mUidTopActivityTimePosition = addUidSection(1, "top");
    }

    /**
     * Stores time the UID spent in the TOP state.
     */
    public void setUidTopActivityDuration(long[] stats, long durationMs) {
        stats[mUidTopActivityTimePosition] = durationMs;
    }

    /**
     * Returns time the UID spent in the TOP state.
     */
    public long getUidTopActivityDuration(long[] stats) {
        return stats[mUidTopActivityTimePosition];
    }

    @Override
    public void toExtras(PersistableBundle extras) {
        super.toExtras(extras);
        extras.putInt(EXTRA_DEVICE_SCREEN_COUNT, mDisplayCount);
        extras.putInt(EXTRA_DEVICE_SCREEN_ON_DURATION_POSITION, mDeviceScreenOnDurationPosition);
        extras.putIntArray(EXTRA_DEVICE_BRIGHTNESS_DURATION_POSITIONS,
                mDeviceBrightnessDurationPositions);
        extras.putInt(EXTRA_DEVICE_DOZE_DURATION_POSITION, mDeviceScreenDozeDurationPosition);
        extras.putInt(EXTRA_UID_FOREGROUND_DURATION, mUidTopActivityTimePosition);
    }

    @Override
    public void fromExtras(PersistableBundle extras) {
        super.fromExtras(extras);
        mDisplayCount = extras.getInt(EXTRA_DEVICE_SCREEN_COUNT, 1);
        mDeviceScreenOnDurationPosition = extras.getInt(EXTRA_DEVICE_SCREEN_ON_DURATION_POSITION);
        mDeviceBrightnessDurationPositions = extras.getIntArray(
                EXTRA_DEVICE_BRIGHTNESS_DURATION_POSITIONS);
        mDeviceScreenDozeDurationPosition = extras.getInt(EXTRA_DEVICE_DOZE_DURATION_POSITION);
        mUidTopActivityTimePosition = extras.getInt(EXTRA_UID_FOREGROUND_DURATION);
    }
}
