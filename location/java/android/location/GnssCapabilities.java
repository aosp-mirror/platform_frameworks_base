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

package android.location;

import android.annotation.SystemApi;

/**
 * A container of supported GNSS chipset capabilities.
 */
public final class GnssCapabilities {
    /**
     * Bit mask indicating GNSS chipset supports low power mode.
     * @hide
     */
    public static final long LOW_POWER_MODE                                     = 1L << 0;

    /**
     * Bit mask indicating GNSS chipset supports blacklisting satellites.
     * @hide
     */
    public static final long SATELLITE_BLACKLIST                                = 1L << 1;

    /**
     * Bit mask indicating GNSS chipset supports geofencing.
     * @hide
     */
    public static final long GEOFENCING                                         = 1L << 2;

    /**
     * Bit mask indicating GNSS chipset supports measurements.
     * @hide
     */
    public static final long MEASUREMENTS                                       = 1L << 3;

    /**
     * Bit mask indicating GNSS chipset supports navigation messages.
     * @hide
     */
    public static final long NAV_MESSAGES                                       = 1L << 4;

    /**
     * Bit mask indicating GNSS chipset supports measurement corrections.
     * @hide
     */
    public static final long MEASUREMENT_CORRECTIONS                            = 1L << 5;

    /**
     * Bit mask indicating GNSS chipset supports line-of-sight satellite identification
     * measurement corrections.
     * @hide
     */
    public static final long MEASUREMENT_CORRECTIONS_LOS_SATS                   = 1L << 6;

    /**
     * Bit mask indicating GNSS chipset supports per satellite excess-path-length
     * measurement corrections.
     * @hide
     */
    public static final long MEASUREMENT_CORRECTIONS_EXCESS_PATH_LENGTH         = 1L << 7;

    /**
     * Bit mask indicating GNSS chipset supports reflecting planes measurement corrections.
     * @hide
     */
    public static final long MEASUREMENT_CORRECTIONS_REFLECTING_PLANE           = 1L << 8;

    /**
     * Bit mask indicating GNSS chipset supports GNSS antenna info.
     * @hide
     */
    public static final long ANTENNA_INFO                                       = 1L << 9;

    /** @hide */
    public static final long INVALID_CAPABILITIES = -1;

    /** A bitmask of supported GNSS capabilities. */
    private final long mGnssCapabilities;

    /** @hide */
    public static GnssCapabilities of(long gnssCapabilities) {
        return new GnssCapabilities(gnssCapabilities);
    }

    private GnssCapabilities(long gnssCapabilities) {
        mGnssCapabilities = gnssCapabilities;
    }

    /**
     * Returns {@code true} if GNSS chipset supports low power mode, {@code false} otherwise.
     *
     * @hide
     */
    @SystemApi
    public boolean hasLowPowerMode() {
        return hasCapability(LOW_POWER_MODE);
    }

    /**
     * Returns {@code true} if GNSS chipset supports blacklisting satellites, {@code false}
     * otherwise.
     *
     * @hide
     */
    @SystemApi
    public boolean hasSatelliteBlacklist() {
        return hasCapability(SATELLITE_BLACKLIST);
    }

    /**
     * Returns {@code true} if GNSS chipset supports geofencing, {@code false} otherwise.
     *
     * @hide
     */
    @SystemApi
    public boolean hasGeofencing() {
        return hasCapability(GEOFENCING);
    }

    /**
     * Returns {@code true} if GNSS chipset supports measurements, {@code false} otherwise.
     *
     * @hide
     */
    @SystemApi
    public boolean hasMeasurements() {
        return hasCapability(MEASUREMENTS);
    }

    /**
     * Returns {@code true} if GNSS chipset supports navigation messages, {@code false} otherwise.
     *
     * @hide
     */
    @SystemApi
    public boolean hasNavMessages() {
        return hasCapability(NAV_MESSAGES);
    }

    /**
     * Returns {@code true} if GNSS chipset supports measurement corrections, {@code false}
     * otherwise.
     *
     * @hide
     */
    @SystemApi
    public boolean hasMeasurementCorrections() {
        return hasCapability(MEASUREMENT_CORRECTIONS);
    }

    /**
     * Returns {@code true} if GNSS chipset supports line-of-sight satellite identification
     * measurement corrections, {@code false} otherwise.
     *
     * @hide
     */
    @SystemApi
    public boolean hasMeasurementCorrectionsLosSats() {
        return hasCapability(MEASUREMENT_CORRECTIONS_LOS_SATS);
    }

    /**
     * Returns {@code true} if GNSS chipset supports per satellite excess-path-length measurement
     * corrections, {@code false} otherwise.
     *
     * @hide
     */
    @SystemApi
    public boolean hasMeasurementCorrectionsExcessPathLength() {
        return hasCapability(MEASUREMENT_CORRECTIONS_EXCESS_PATH_LENGTH);
    }

    /**
     * Returns {@code true} if GNSS chipset supports reflecting planes measurement corrections,
     * {@code false} otherwise.
     *
     * @hide
     */
    @SystemApi
    public boolean hasMeasurementCorrectionsReflectingPane() {
        return hasCapability(MEASUREMENT_CORRECTIONS_REFLECTING_PLANE);
    }

    /**
     * Returns {@code true} if GNSS chipset supports antenna info, {@code false} otherwise.
     */
    public boolean hasGnssAntennaInfo() {
        return hasCapability(ANTENNA_INFO);
    }

    private boolean hasCapability(long capability) {
        return (mGnssCapabilities & capability) == capability;
    }
}
