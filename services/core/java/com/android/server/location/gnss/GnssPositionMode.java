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

package com.android.server.location.gnss;

import java.util.Arrays;

/**
 * Represents a GNSS position mode.
 */
public class GnssPositionMode {
    private final int mMode;
    private final int mRecurrence;
    private final int mMinInterval;
    private final int mPreferredAccuracy;
    private final int mPreferredTime;
    private final boolean mLowPowerMode;

    public GnssPositionMode(int mode, int recurrence, int minInterval,
            int preferredAccuracy, int preferredTime, boolean lowPowerMode) {
        this.mMode = mode;
        this.mRecurrence = recurrence;
        this.mMinInterval = minInterval;
        this.mPreferredAccuracy = preferredAccuracy;
        this.mPreferredTime = preferredTime;
        this.mLowPowerMode = lowPowerMode;
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof GnssPositionMode) {
            GnssPositionMode that = (GnssPositionMode) other;
            return mMode == that.mMode && mRecurrence == that.mRecurrence
                    && mMinInterval == that.mMinInterval
                    && mPreferredAccuracy == that.mPreferredAccuracy
                    && mPreferredTime == that.mPreferredTime && mLowPowerMode == that.mLowPowerMode
                    && this.getClass() == that.getClass();
        }

        return false;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(
                new Object[]{mMode, mRecurrence, mMinInterval, mPreferredAccuracy, mPreferredTime,
                        mLowPowerMode, getClass()});
    }
}
