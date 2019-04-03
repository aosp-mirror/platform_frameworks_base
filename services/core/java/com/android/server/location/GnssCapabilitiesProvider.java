/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.location;

import android.location.GnssCapabilities;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

/**
 * Provides GNSS capabilities supported by the GNSS HAL implementation.
 */
public class GnssCapabilitiesProvider {
    private static final String TAG = "GnssCapabilitiesProvider";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    // Bit masks for capabilities in {@link android.location.GnssCapabilities}.
    private static final long GNSS_CAPABILITY_LOW_POWER_MODE =
            1L << GnssCapabilities.LOW_POWER_MODE;
    private static final long GNSS_CAPABILITY_SATELLITE_BLACKLIST =
            1L << GnssCapabilities.SATELLITE_BLACKLIST;
    private static final long GNSS_CAPABILITY_GEOFENCING = 1L << GnssCapabilities.GEOFENCING;
    private static final long GNSS_CAPABILITY_MEASUREMENTS = 1L << GnssCapabilities.MEASUREMENTS;
    private static final long GNSS_CAPABILITY_NAV_MESSAGES = 1L << GnssCapabilities.NAV_MESSAGES;
    private static final long GNSS_CAPABILITY_MEASUREMENT_CORRECTIONS =
            1L << GnssCapabilities.MEASUREMENT_CORRECTIONS;
    private static final long GNSS_CAPABILITY_MEASUREMENT_CORRECTIONS_LOS_SATS =
            1L << GnssCapabilities.MEASUREMENT_CORRECTIONS_LOS_SATS;
    private static final long GNSS_CAPABILITY_MEASUREMENT_CORRECTIONS_EXCESS_PATH_LENGTH =
            1L << GnssCapabilities.MEASUREMENT_CORRECTIONS_EXCESS_PATH_LENGTH;
    private static final long GNSS_CAPABILITY_MEASUREMENT_CORRECTIONS_REFLECTING_PLANE =
            1L << GnssCapabilities.MEASUREMENT_CORRECTIONS_REFLECTING_PLANE;

    private static final long GNSS_CAPABILITIES_TOP_HAL =
            GNSS_CAPABILITY_LOW_POWER_MODE | GNSS_CAPABILITY_SATELLITE_BLACKLIST
                    | GNSS_CAPABILITY_GEOFENCING | GNSS_CAPABILITY_MEASUREMENTS
                    | GNSS_CAPABILITY_NAV_MESSAGES;

    private static final long GNSS_CAPABILITIES_SUB_HAL_MEASUREMENT_CORRECTIONS =
            GNSS_CAPABILITY_MEASUREMENT_CORRECTIONS
                    | GNSS_CAPABILITY_MEASUREMENT_CORRECTIONS_LOS_SATS
                    | GNSS_CAPABILITY_MEASUREMENT_CORRECTIONS_EXCESS_PATH_LENGTH
                    | GNSS_CAPABILITY_MEASUREMENT_CORRECTIONS_REFLECTING_PLANE;

    // Capabilities in {@link android.location.GnssCapabilities} supported by GNSS chipset.
    @GuardedBy("this")
    private long mGnssCapabilities;

    /**
     * Returns the capabilities supported by the GNSS chipset.
     *
     * <p>The capabilities are described in {@link android.location.GnssCapabilities} and
     * their integer values correspond to the bit positions in the returned {@code long} value.
     */
    public long getGnssCapabilities() {
        synchronized (this) {
            return mGnssCapabilities;
        }
    }

    /**
     * Updates the general capabilities exposed through {@link android.location.GnssCapabilities}.
     */
    void setTopHalCapabilities(int topHalCapabilities) {
        long gnssCapabilities = 0;
        if (hasCapability(topHalCapabilities,
                GnssLocationProvider.GPS_CAPABILITY_LOW_POWER_MODE)) {
            gnssCapabilities |= GNSS_CAPABILITY_LOW_POWER_MODE;
        }
        if (hasCapability(topHalCapabilities,
                GnssLocationProvider.GPS_CAPABILITY_SATELLITE_BLACKLIST)) {
            gnssCapabilities |= GNSS_CAPABILITY_SATELLITE_BLACKLIST;
        }
        if (hasCapability(topHalCapabilities, GnssLocationProvider.GPS_CAPABILITY_GEOFENCING)) {
            gnssCapabilities |= GNSS_CAPABILITY_GEOFENCING;
        }
        if (hasCapability(topHalCapabilities, GnssLocationProvider.GPS_CAPABILITY_MEASUREMENTS)) {
            gnssCapabilities |= GNSS_CAPABILITY_MEASUREMENTS;
        }
        if (hasCapability(topHalCapabilities, GnssLocationProvider.GPS_CAPABILITY_NAV_MESSAGES)) {
            gnssCapabilities |= GNSS_CAPABILITY_NAV_MESSAGES;
        }

        synchronized (this) {
            mGnssCapabilities &= ~GNSS_CAPABILITIES_TOP_HAL;
            mGnssCapabilities |= gnssCapabilities;
            if (DEBUG) {
                Log.d(TAG, "setTopHalCapabilities, mGnssCapabilities=0x" + Long.toHexString(
                        mGnssCapabilities) + ", " + GnssCapabilities.of(mGnssCapabilities));
            }
        }
    }

    /**
     * Updates the measurement corrections related capabilities exposed through
     * {@link android.location.GnssCapabilities}.
     */
    void setSubHalMeasurementCorrectionsCapabilities(int measurementCorrectionsCapabilities) {
        long gnssCapabilities = GNSS_CAPABILITY_MEASUREMENT_CORRECTIONS;
        if (hasCapability(measurementCorrectionsCapabilities,
                GnssMeasurementCorrectionsProvider.CAPABILITY_LOS_SATS)) {
            gnssCapabilities |= GNSS_CAPABILITY_MEASUREMENT_CORRECTIONS_LOS_SATS;
        }
        if (hasCapability(measurementCorrectionsCapabilities,
                GnssMeasurementCorrectionsProvider.CAPABILITY_EXCESS_PATH_LENGTH)) {
            gnssCapabilities |= GNSS_CAPABILITY_MEASUREMENT_CORRECTIONS_EXCESS_PATH_LENGTH;
        }
        if (hasCapability(measurementCorrectionsCapabilities,
                GnssMeasurementCorrectionsProvider.CAPABILITY_REFLECTING_PLANE)) {
            gnssCapabilities |= GNSS_CAPABILITY_MEASUREMENT_CORRECTIONS_REFLECTING_PLANE;
        }

        synchronized (this) {
            mGnssCapabilities &= ~GNSS_CAPABILITIES_SUB_HAL_MEASUREMENT_CORRECTIONS;
            mGnssCapabilities |= gnssCapabilities;
            if (DEBUG) {
                Log.d(TAG, "setSubHalMeasurementCorrectionsCapabilities, mGnssCapabilities=0x"
                        + Long.toHexString(mGnssCapabilities) + ", " + GnssCapabilities.of(
                        mGnssCapabilities));
            }
        }
    }

    private static  boolean hasCapability(int halCapabilities, int capability) {
        return (halCapabilities & capability) != 0;
    }
}
