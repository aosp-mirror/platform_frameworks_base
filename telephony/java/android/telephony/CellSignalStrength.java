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

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Abstract base class for cell phone signal strength related information.
 */
public abstract class CellSignalStrength {

    /** @hide */
    public static final int SIGNAL_STRENGTH_NONE_OR_UNKNOWN = 0;
    /** @hide */
    public static final int SIGNAL_STRENGTH_POOR = 1;
    /** @hide */
    public static final int SIGNAL_STRENGTH_MODERATE = 2;
    /** @hide */
    public static final int SIGNAL_STRENGTH_GOOD = 3;
    /** @hide */
    public static final int SIGNAL_STRENGTH_GREAT = 4;
    /** @hide */
    public static final int NUM_SIGNAL_STRENGTH_BINS = 5;
    /** @hide */
    public static final String[] SIGNAL_STRENGTH_NAMES = {
        "none", "poor", "moderate", "good", "great"
    };

    /** @hide */
    protected CellSignalStrength() {
    }

    /** @hide */
    public abstract void setDefaultValues();

    /**
     * Get signal level as an int from 0..4
     */
    public abstract int getLevel();

    /**
     * Get the signal level as an asu value between 0..31, 99 is unknown
     */
    public abstract int getAsuLevel();

    /**
     * Get the signal strength as dBm
     */
    public abstract int getDbm();

    /**
     * Copies the CellSignalStrength.
     *
     * @return A deep copy of this class.
     * @hide
     */
    public abstract CellSignalStrength copy();

    @Override
    public abstract int hashCode();

    @Override
    public abstract boolean equals (Object o);
}
