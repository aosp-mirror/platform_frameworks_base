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
 *
 * @hide
 */
@SystemApi
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
     */
    public boolean hasLowPowerMode() {
        return hasCapability(LOW_POWER_MODE);
    }

    /**
     * Returns {@code true} if GNSS chipset supports blacklisting satellites, {@code false}
     * otherwise.
     */
    public boolean hasSatelliteBlacklist() {
        return hasCapability(SATELLITE_BLACKLIST);
    }

    /**
     * Returns {@code true} if GNSS chipset supports geofencing, {@code false} otherwise.
     */
    public boolean hasGeofencing() {
        return hasCapability(GEOFENCING);
    }

    /**
     * Returns {@code true} if GNSS chipset supports measurements, {@code false} otherwise.
     */
    public boolean hasMeasurements() {
        return hasCapability(MEASUREMENTS);
    }

    /**
     * Returns {@code true} if GNSS chipset supports navigation messages, {@code false} otherwise.
     */
    public boolean hasNavMessages() {
        return hasCapability(NAV_MESSAGES);
    }

    /**
     * Returns {@code true} if GNSS chipset supports measurement corrections, {@code false}
     * otherwise.
     */
    public boolean hasMeasurementCorrections() {
        return hasCapability(MEASUREMENT_CORRECTIONS);
    }

    /**
     * Returns {@code true} if GNSS chipset supports line-of-sight satellite identification
     * measurement corrections, {@code false} otherwise.
     */
    public boolean hasMeasurementCorrectionsLosSats() {
        return hasCapability(MEASUREMENT_CORRECTIONS_LOS_SATS);
    }

    /**
     * Returns {@code true} if GNSS chipset supports per satellite excess-path-length measurement
     * corrections, {@code false} otherwise.
     */
    public boolean hasMeasurementCorrectionsExcessPathLength() {
        return hasCapability(MEASUREMENT_CORRECTIONS_EXCESS_PATH_LENGTH);
    }

    /**
     * Returns {@code true} if GNSS chipset supports reflecting planes measurement corrections,
     * {@code false} otherwise.
     */
    public boolean hasMeasurementCorrectionsReflectingPane() {
        return hasCapability(MEASUREMENT_CORRECTIONS_REFLECTING_PLANE);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("GnssCapabilities: ( ");
        if (hasLowPowerMode()) sb.append("LOW_POWER_MODE ");
        if (hasSatelliteBlacklist()) sb.append("SATELLITE_BLACKLIST ");
        if (hasGeofencing()) sb.append("GEOFENCING ");
        if (hasMeasurements()) sb.append("MEASUREMENTS ");
        if (hasNavMessages()) sb.append("NAV_MESSAGES ");
        if (hasMeasurementCorrections()) sb.append("MEASUREMENT_CORRECTIONS ");
        if (hasMeasurementCorrectionsLosSats()) sb.append("MEASUREMENT_CORRECTIONS_LOS_SATS ");
        if (hasMeasurementCorrectionsExcessPathLength()) {
            sb.append("MEASUREMENT_CORRECTIONS_EXCESS_PATH_LENGTH ");
        }
        if (hasMeasurementCorrectionsReflectingPane()) {
            sb.append("MEASUREMENT_CORRECTIONS_REFLECTING_PLANE ");
        }
        sb.append(")");
        return sb.toString();
    }

    private boolean hasCapability(long capability) {
        return (mGnssCapabilities & capability) == capability;
    }
}
