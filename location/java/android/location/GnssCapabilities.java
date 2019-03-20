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

import android.annotation.IntDef;
import android.annotation.SystemApi;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A container of supported GNSS chipset capabilities.
 *
 * @hide
 */
@SystemApi
public final class GnssCapabilities {
    /** The GNSS chipset supports low power mode. */
    public static final int LOW_POWER_MODE                                              = 0;

    /** The GNSS chipset supports blacklisting satellites. */
    public static final int SATELLITE_BLACKLIST                                         = 1;

    /** The GNSS chipset supports geofencing. */
    public static final int GEOFENCING                                                  = 2;

    /** The GNSS chipset supports measurements.*/
    public static final int MEASUREMENTS                                                = 3;

    /** The GNSS chipset supports navigation messages. */
    public static final int NAV_MESSAGES                                                = 4;

    /** The GNSS chipset supports measurement corrections. */
    public static final int MEASUREMENT_CORRECTIONS                                     = 5;

    /** The GNSS chipset supports line-of-sight satellite identification measurement corrections. */
    public static final int MEASUREMENT_CORRECTIONS_LOS_SATS                            = 6;

    /** The GNSS chipset supports per satellite excess-path-length measurement corrections. */
    public static final int MEASUREMENT_CORRECTIONS_EXCESS_PATH_LENGTH                  = 7;

    /** The GNSS chipset supports reflecting planes measurement corrections. */
    public static final int MEASUREMENT_CORRECTIONS_REFLECTING_PLANE                    = 8;

    private static final int MIN_CAPABILITY = 0;
    private static final int MAX_CAPABILITY = MEASUREMENT_CORRECTIONS_REFLECTING_PLANE;

    /**
     * GNSS capability.
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            LOW_POWER_MODE,
            SATELLITE_BLACKLIST,
            GEOFENCING,
            MEASUREMENTS,
            NAV_MESSAGES,
            MEASUREMENT_CORRECTIONS,
            MEASUREMENT_CORRECTIONS_LOS_SATS,
            MEASUREMENT_CORRECTIONS_EXCESS_PATH_LENGTH,
            MEASUREMENT_CORRECTIONS_REFLECTING_PLANE
    })
    public @interface Capability {}

    /**
     * @hide
     */
    public static final long INVALID_CAPABILITIES = -1;

    /** A bitmask of supported GNSS capabilities. */
    private final long mGnssCapabilities;

    static GnssCapabilities of(long gnssCapabilities) {
        return new GnssCapabilities(gnssCapabilities);
    }

    private GnssCapabilities(long gnssCapabilities) {
        mGnssCapabilities = gnssCapabilities;
    }

    /**
     * Returns {@code true} if the {@code capability} is supported by the GNSS implementation.
     */
    public boolean hasCapability(@Capability int capability) {
        return isValidCapability(capability) && (mGnssCapabilities & (1 << capability)) != 0;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("GnssCapabilities: (");
        int capability = 0;
        boolean addSeparator = false;
        long gnssCapabilities = mGnssCapabilities;
        while (gnssCapabilities != 0) {
            if ((gnssCapabilities & 1) != 0) {
                if (addSeparator) {
                    sb.append(' ');
                } else {
                    addSeparator = true;
                }
                sb.append(toStringCapability(capability));
            }
            gnssCapabilities >>= 1;
            ++capability;
        }
        sb.append(")");
        return sb.toString();
    }

    private boolean isValidCapability(@Capability int capability) {
        return capability >= MIN_CAPABILITY && capability <= MAX_CAPABILITY;
    }

    private static String toStringCapability(@Capability int capability) {
        switch (capability) {
            case LOW_POWER_MODE:
                return "LOW_POWER_MODE";
            case SATELLITE_BLACKLIST:
                return "SATELLITE_BLACKLIST";
            case GEOFENCING:
                return "GEOFENCING";
            case MEASUREMENTS:
                return "MEASUREMENTS";
            case NAV_MESSAGES:
                return "NAV_MESSAGES";
            case MEASUREMENT_CORRECTIONS:
                return "MEASUREMENT_CORRECTIONS";
            case MEASUREMENT_CORRECTIONS_LOS_SATS:
                return "MEASUREMENT_CORRECTIONS_LOS_SATS";
            case MEASUREMENT_CORRECTIONS_EXCESS_PATH_LENGTH:
                return "MEASUREMENT_CORRECTIONS_EXCESS_PATH_LENGTH";
            case MEASUREMENT_CORRECTIONS_REFLECTING_PLANE:
                return "MEASUREMENT_CORRECTIONS_REFLECTING_PLANE";
            default:
                return "Unknown(" + capability + ")";
        }
    }
}
