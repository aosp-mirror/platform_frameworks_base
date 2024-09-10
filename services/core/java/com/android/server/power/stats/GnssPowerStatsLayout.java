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

import android.location.GnssSignalQuality;
import android.os.PersistableBundle;

class GnssPowerStatsLayout extends BinaryStatePowerStatsLayout {
    private static final String EXTRA_DEVICE_TIME_SIGNAL_LEVEL_POSITION = "dt-sig";
    private static final String EXTRA_UID_TIME_SIGNAL_LEVEL_POSITION = "ut-sig";

    private int mDeviceSignalLevelTimePosition;
    private int mUidSignalLevelTimePosition;

    GnssPowerStatsLayout() {
        mDeviceSignalLevelTimePosition = addDeviceSection(
                GnssSignalQuality.NUM_GNSS_SIGNAL_QUALITY_LEVELS, "level");
        mUidSignalLevelTimePosition = addUidSection(
                GnssSignalQuality.NUM_GNSS_SIGNAL_QUALITY_LEVELS, "level");
    }

    @Override
    public void fromExtras(PersistableBundle extras) {
        super.fromExtras(extras);
        mDeviceSignalLevelTimePosition = extras.getInt(EXTRA_DEVICE_TIME_SIGNAL_LEVEL_POSITION);
        mUidSignalLevelTimePosition = extras.getInt(EXTRA_UID_TIME_SIGNAL_LEVEL_POSITION);
    }

    @Override
    public void toExtras(PersistableBundle extras) {
        super.toExtras(extras);
        extras.putInt(EXTRA_DEVICE_TIME_SIGNAL_LEVEL_POSITION, mDeviceSignalLevelTimePosition);
        extras.putInt(EXTRA_UID_TIME_SIGNAL_LEVEL_POSITION, mUidSignalLevelTimePosition);
    }

    public void setDeviceSignalLevelTime(long[] stats, int signalLevel, long durationMillis) {
        stats[mDeviceSignalLevelTimePosition + signalLevel] = durationMillis;
    }

    public long getDeviceSignalLevelTime(long[] stats, int signalLevel) {
        return stats[mDeviceSignalLevelTimePosition + signalLevel];
    }

    public void setUidSignalLevelTime(long[] stats, int signalLevel, long durationMillis) {
        stats[mUidSignalLevelTimePosition + signalLevel] = durationMillis;
    }

    public long getUidSignalLevelTime(long[] stats, int signalLevel) {
        return stats[mUidSignalLevelTimePosition + signalLevel];
    }
}
