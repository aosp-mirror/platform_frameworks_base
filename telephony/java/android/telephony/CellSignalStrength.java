/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.telephony;

import android.annotation.IntRange;
import android.os.PersistableBundle;

/**
 * Abstract base class for cell phone signal strength related information.
 */
public abstract class CellSignalStrength {

    public static final int SIGNAL_STRENGTH_NONE_OR_UNKNOWN =
            TelephonyProtoEnums.SIGNAL_STRENGTH_NONE_OR_UNKNOWN; // 0

    public static final int SIGNAL_STRENGTH_POOR =
            TelephonyProtoEnums.SIGNAL_STRENGTH_POOR; // 1

    public static final int SIGNAL_STRENGTH_MODERATE =
            TelephonyProtoEnums.SIGNAL_STRENGTH_MODERATE; // 2

    public static final int SIGNAL_STRENGTH_GOOD =
            TelephonyProtoEnums.SIGNAL_STRENGTH_GOOD; // 3

    public static final int SIGNAL_STRENGTH_GREAT =
            TelephonyProtoEnums.SIGNAL_STRENGTH_GREAT; // 4

    /** @hide */
    public static final int NUM_SIGNAL_STRENGTH_BINS = 5;

    /** @hide */
    protected static final int NUM_SIGNAL_STRENGTH_THRESHOLDS = NUM_SIGNAL_STRENGTH_BINS - 1;

    /** @hide */
    protected CellSignalStrength() {
    }

    /** @hide */
    public abstract void setDefaultValues();

    /**
     * Retrieve an abstract level value for the overall signal quality.
     *
     * @return a single integer from 0 to 4 representing the general signal quality.
     *     0 represents very poor or unknown signal quality while 4 represents excellent
     *     signal quality.
     */
    @IntRange(from = SIGNAL_STRENGTH_NONE_OR_UNKNOWN, to = SIGNAL_STRENGTH_GREAT)
    public abstract int getLevel();

    /**
     * Get the technology-specific signal strength in Arbitrary Strength Units, calculated from the
     * strength of the pilot signal or equivalent.
     */
    public abstract int getAsuLevel();

    /**
     * Get the technology-specific signal strength in dBm, which is the signal strength of the
     * pilot signal or equivalent.
     */
    public abstract int getDbm();

    /**
     * Copies the CellSignalStrength.
     *
     * @return A deep copy of this class.
     * @hide
     */
    public abstract CellSignalStrength copy();

    /**
     * Checks and returns whether there are any non-default values in this CellSignalStrength.
     *
     * Checks all the values in the subclass of CellSignalStrength and returns true if any of them
     * have been set to a value other than their default.
     *
     * @hide
     */
    public abstract boolean isValid();

    @Override
    public abstract int hashCode();

    @Override
    public abstract boolean equals (Object o);

    /**
     * Calculate and set the carrier-influenced values such as the signal "Level".
     *
     * @hide
     */
    public abstract void updateLevel(PersistableBundle cc, ServiceState ss);

    // Range for RSSI in ASU (0-31, 99) as defined in TS 27.007 8.69
    /** @hide */
    protected static final int getRssiDbmFromAsu(int asu) {
        if (asu > 31 || asu < 0) return CellInfo.UNAVAILABLE;
        return -113 + (2 * asu);
    }

    // Range for RSSI in ASU (0-31, 99) as defined in TS 27.007 8.69
    /** @hide */
    protected static final int getAsuFromRssiDbm(int dbm) {
        if (dbm == CellInfo.UNAVAILABLE) return 99;
        return (dbm + 113) / 2;
    }

    // Range for RSCP in ASU (0-96, 255) as defined in TS 27.007 8.69
    /** @hide */
    protected static final int getRscpDbmFromAsu(int asu) {
        if (asu > 96 || asu < 0) return CellInfo.UNAVAILABLE;
        return asu - 120;
    }

    // Range for RSCP in ASU (0-96, 255) as defined in TS 27.007 8.69
    /** @hide */
    protected static final int getAsuFromRscpDbm(int dbm) {
        if (dbm == CellInfo.UNAVAILABLE) return 255;
        return dbm + 120;
    }

    // Range for SNR in ASU (0-49, 255) as defined in TS 27.007 8.69
    /** @hide */
    protected static final int getEcNoDbFromAsu(int asu) {
        if (asu > 49 || asu < 0) return CellInfo.UNAVAILABLE;
        return -24 + (asu / 2);
    }

    /** @hide */
    protected static final int inRangeOrUnavailable(int value, int rangeMin, int rangeMax) {
        if (value < rangeMin || value > rangeMax) return CellInfo.UNAVAILABLE;
        return value;
    }

    /** @hide */
    protected static final int inRangeOrUnavailable(
            int value, int rangeMin, int rangeMax, int special) {
        if ((value < rangeMin || value > rangeMax) && value != special) return CellInfo.UNAVAILABLE;
        return value;
    }

    /**
     * Returns the number of signal strength levels.
     * @return Number of signal strength levels, enforced to be 5
     *
     * @hide
     */
    public static final int getNumSignalStrengthLevels() {
        return NUM_SIGNAL_STRENGTH_BINS;
    }
}
