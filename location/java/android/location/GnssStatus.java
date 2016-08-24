/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.location;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * This class represents the current state of the GNSS engine.
 * This class is used in conjunction with the {@link GnssStatus.Callback}.
 */
public final class GnssStatus {
    // these must match the definitions in gps.h

    /** Unknown constellation type. */
    public static final int CONSTELLATION_UNKNOWN = 0;
    /** Constellation type constant for GPS. */
    public static final int CONSTELLATION_GPS = 1;
    /** Constellation type constant for SBAS. */
    public static final int CONSTELLATION_SBAS = 2;
    /** Constellation type constant for Glonass. */
    public static final int CONSTELLATION_GLONASS = 3;
    /** Constellation type constant for QZSS. */
    public static final int CONSTELLATION_QZSS = 4;
    /** Constellation type constant for Beidou. */
    public static final int CONSTELLATION_BEIDOU = 5;
    /** Constellation type constant for Galileo. */
    public static final int CONSTELLATION_GALILEO = 6;

    /** @hide */
    public static final int GNSS_SV_FLAGS_NONE = 0;
    /** @hide */
    public static final int GNSS_SV_FLAGS_HAS_EPHEMERIS_DATA = (1 << 0);
    /** @hide */
    public static final int GNSS_SV_FLAGS_HAS_ALMANAC_DATA = (1 << 1);
    /** @hide */
    public static final int GNSS_SV_FLAGS_USED_IN_FIX = (1 << 2);

    /** @hide */
    public static final int SVID_SHIFT_WIDTH = 7;
    /** @hide */
    public static final int CONSTELLATION_TYPE_SHIFT_WIDTH = 3;
    /** @hide */
    public static final int CONSTELLATION_TYPE_MASK = 0xf;

    /**
     * Used for receiving notifications when GNSS events happen.
     */
    public static abstract class Callback {
        /**
         * Called when GNSS system has started.
         */
        public void onStarted() {}

        /**
         * Called when GNSS system has stopped.
         */
        public void onStopped() {}

        /**
         * Called when the GNSS system has received its first fix since starting.
         * @param ttffMillis the time from start to first fix in milliseconds.
         */
        public void onFirstFix(int ttffMillis) {}

        /**
         * Called periodically to report GNSS satellite status.
         * @param status the current status of all satellites.
         */
        public void onSatelliteStatusChanged(GnssStatus status) {}
    }

    /**
     * Constellation type.
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({CONSTELLATION_UNKNOWN, CONSTELLATION_GPS, CONSTELLATION_SBAS, CONSTELLATION_GLONASS,
            CONSTELLATION_QZSS, CONSTELLATION_BEIDOU, CONSTELLATION_GALILEO})
    public @interface ConstellationType {}

    /* These package private values are modified by the LocationManager class */
    /* package */ int[] mSvidWithFlags;
    /* package */ float[] mCn0DbHz;
    /* package */ float[] mElevations;
    /* package */ float[] mAzimuths;
    /* package */ int mSvCount;

    GnssStatus(int svCount, int[] svidWithFlags, float[] cn0s, float[] elevations,
            float[] azimuths) {
        mSvCount = svCount;
        mSvidWithFlags = svidWithFlags;
        mCn0DbHz = cn0s;
        mElevations = elevations;
        mAzimuths = azimuths;
    }

    /** @removed */
    public int getNumSatellites() {
        return getSatelliteCount();
    }

    /**
     * Gets the total number of satellites in satellite list.
     */
    public int getSatelliteCount() {
        return mSvCount;
    }

    /**
     * Retrieves the constellation type of the satellite at the specified index.
     *
     * @param satIndex the index of the satellite in the list.
     */
    @ConstellationType
    public int getConstellationType(int satIndex) {
        return ((mSvidWithFlags[satIndex] >> CONSTELLATION_TYPE_SHIFT_WIDTH)
                & CONSTELLATION_TYPE_MASK);
    }

    /**
     * Gets the identification number for the satellite at the specific index.
     *
     * <p>This svid is pseudo-random number for most constellations. It is FCN &amp; OSN number for
     * Glonass.
     *
     * <p>The distinction is made by looking at constellation field
     * {@link #getConstellationType(int)} Expected values are in the range of:
     *
     * <ul>
     * <li>GPS: 1-32</li>
     * <li>SBAS: 120-151, 183-192</li>
     * <li>GLONASS: One of: OSN, or FCN+100
     * <ul>
     *   <li>1-24 as the orbital slot number (OSN) (preferred, if known)</li>
     *   <li>93-106 as the frequency channel number (FCN) (-7 to +6) plus 100.
     *   i.e. encode FCN of -7 as 93, 0 as 100, and +6 as 106</li>
     * </ul></li>
     * <li>QZSS: 193-200</li>
     * <li>Galileo: 1-36</li>
     * <li>Beidou: 1-37</li>
     * </ul>
     *
     * @param satIndex the index of the satellite in the list.
     */
    public int getSvid(int satIndex) {
        return mSvidWithFlags[satIndex] >> SVID_SHIFT_WIDTH;
    }

    /**
     * Retrieves the carrier-to-noise density at the antenna of the satellite at the specified index
     * in dB-Hz.
     *
     * @param satIndex the index of the satellite in the list.
     */
    public float getCn0DbHz(int satIndex) {
        return mCn0DbHz[satIndex];
    }

    /**
     * Retrieves the elevation of the satellite at the specified index.
     *
     * @param satIndex the index of the satellite in the list.
     */
    public float getElevationDegrees(int satIndex) {
        return mElevations[satIndex];
    }

    /**
     * Retrieves the azimuth the satellite at the specified index.
     *
     * @param satIndex the index of the satellite in the list.
     */
    public float getAzimuthDegrees(int satIndex) {
        return mAzimuths[satIndex];
    }

    /** @removed */
    public boolean hasEphemeris(int satIndex) {
        return hasEphemerisData(satIndex);
    }

    /**
     * Reports whether the satellite at the specified index has ephemeris data.
     *
     * @param satIndex the index of the satellite in the list.
     */
    public boolean hasEphemerisData(int satIndex) {
        return (mSvidWithFlags[satIndex] & GNSS_SV_FLAGS_HAS_EPHEMERIS_DATA) != 0;
    }

    /** @removed */
    public boolean hasAlmanac(int satIndex) {
        return hasAlmanacData(satIndex);
    }

    /**
     * Reports whether the satellite at the specified index has almanac data.
     *
     * @param satIndex the index of the satellite in the list.
     */
    public boolean hasAlmanacData(int satIndex) {
        return (mSvidWithFlags[satIndex] & GNSS_SV_FLAGS_HAS_ALMANAC_DATA) != 0;
    }

    /**
     * Reports whether the satellite at the specified index was used in the calculation of the most
     * recent position fix.
     *
     * @param satIndex the index of the satellite in the list.
     */
    public boolean usedInFix(int satIndex) {
        return (mSvidWithFlags[satIndex] & GNSS_SV_FLAGS_USED_IN_FIX) != 0;
    }
}
