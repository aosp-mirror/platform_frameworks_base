package com.android.server.location;

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
