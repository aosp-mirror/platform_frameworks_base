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

package com.android.server.power.stats.format;

import android.os.PersistableBundle;
import android.util.Slog;
import android.util.SparseIntArray;

public class SensorPowerStatsLayout extends PowerStatsLayout {
    private static final String TAG = "SensorPowerStatsLayout";
    private static final String EXTRA_DEVICE_SENSOR_HANDLES = "dsh";
    private static final String EXTRA_UID_SENSOR_POSITIONS = "usp";

    private final SparseIntArray mSensorPositions = new SparseIntArray();

    public void addUidSensorSection(int handle, String label) {
        mSensorPositions.put(handle, addUidSection(1, label, FLAG_OPTIONAL));
    }

    /**
     * Returns the position in the uid stats array of the duration element corresponding
     * to the specified sensor identified by its handle.
     */
    public int getUidSensorDurationPosition(int handle) {
        return mSensorPositions.get(handle, UNSUPPORTED);
    }

    /**
     * Adds the specified duration to the accumulated timer for the specified sensor.
     */
    public void addUidSensorDuration(long[] stats, int handle, long durationMs) {
        int position = mSensorPositions.get(handle, UNSUPPORTED);
        if (position == UNSUPPORTED) {
            Slog.e(TAG, "Unknown sensor: " + handle);
            return;
        }
        stats[position] += durationMs;
    }

    @Override
    public void toExtras(PersistableBundle extras) {
        super.toExtras(extras);

        int[] handlers = new int[mSensorPositions.size()];
        int[] uidDurationPositions = new int[mSensorPositions.size()];

        for (int i = 0; i < mSensorPositions.size(); i++) {
            handlers[i] = mSensorPositions.keyAt(i);
            uidDurationPositions[i] = mSensorPositions.valueAt(i);
        }

        extras.putIntArray(EXTRA_DEVICE_SENSOR_HANDLES, handlers);
        extras.putIntArray(EXTRA_UID_SENSOR_POSITIONS, uidDurationPositions);
    }

    @Override
    public void fromExtras(PersistableBundle extras) {
        super.fromExtras(extras);

        int[] handlers = extras.getIntArray(EXTRA_DEVICE_SENSOR_HANDLES);
        int[] uidDurationPositions = extras.getIntArray(EXTRA_UID_SENSOR_POSITIONS);

        for (int i = 0; i < handlers.length; i++) {
            mSensorPositions.put(handlers[i], uidDurationPositions[i]);
        }
    }
}
