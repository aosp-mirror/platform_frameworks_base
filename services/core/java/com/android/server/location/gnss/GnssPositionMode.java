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
    private final int mode;
    private final int recurrence;
    private final int minInterval;
    private final int preferredAccuracy;
    private final int preferredTime;
    private final boolean lowPowerMode;

    public GnssPositionMode(int mode, int recurrence, int minInterval,
            int preferredAccuracy, int preferredTime, boolean lowPowerMode) {
        this.mode = mode;
        this.recurrence = recurrence;
        this.minInterval = minInterval;
        this.preferredAccuracy = preferredAccuracy;
        this.preferredTime = preferredTime;
        this.lowPowerMode = lowPowerMode;
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof GnssPositionMode) {
            GnssPositionMode that = (GnssPositionMode) other;
            return mode == that.mode && recurrence == that.recurrence
                    && minInterval == that.minInterval
                    && preferredAccuracy == that.preferredAccuracy
                    && preferredTime == that.preferredTime && lowPowerMode == that.lowPowerMode
                    && this.getClass() == that.getClass();
        }

        return false;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(
                new Object[]{mode, recurrence, minInterval, preferredAccuracy, preferredTime,
                        lowPowerMode, getClass()});
    }
}
