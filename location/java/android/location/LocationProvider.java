/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.annotation.Nullable;
import android.location.provider.ProviderProperties;

/**
 * Information about the properties of a location provider.
 *
 * @deprecated This class is incapable of representing unknown provider properties and may return
 * incorrect results on the rare occasion when a provider's properties are unknown. Prefer using
 * {@link LocationManager#getProviderProperties(String)} to retrieve {@link ProviderProperties}
 * instead.
 */
@Deprecated
public class LocationProvider {

    /**
     * @deprecated Location provider statuses are no longer supported.
     */
    @Deprecated
    public static final int OUT_OF_SERVICE = 0;

    /**
     * @deprecated Location provider statuses are no longer supported.
     */
    @Deprecated
    public static final int TEMPORARILY_UNAVAILABLE = 1;

    /**
     * @deprecated Location provider statuses are no longer supported.
     */
    @Deprecated
    public static final int AVAILABLE = 2;

    private final String mName;
    private final @Nullable ProviderProperties mProperties;

    LocationProvider(String name, @Nullable ProviderProperties properties) {
        mName = name;
        mProperties = properties;
    }

    /**
     * Returns the name of this provider.
     */
    public String getName() {
        return mName;
    }

    /**
     * Returns true if this provider meets the given criteria,
     * false otherwise.
     */
    public boolean meetsCriteria(Criteria criteria) {
        return propertiesMeetCriteria(mName, mProperties, criteria);
    }

    /**
     * @hide
     */
    public static boolean propertiesMeetCriteria(String name, ProviderProperties properties,
            Criteria criteria) {
        if (LocationManager.PASSIVE_PROVIDER.equals(name)) {
            // passive provider never matches
            return false;
        }
        if (properties == null) {
            // unfortunately this can happen for provider in remote services
            // that have not finished binding yet
            return false;
        }

        if (criteria.getAccuracy() != Criteria.NO_REQUIREMENT &&
                criteria.getAccuracy() < properties.getAccuracy()) {
            return false;
        }
        if (criteria.getPowerRequirement() != Criteria.NO_REQUIREMENT &&
                criteria.getPowerRequirement() < properties.getPowerUsage()) {
            return false;
        }
        if (criteria.isAltitudeRequired() && !properties.hasAltitudeSupport()) {
            return false;
        }
        if (criteria.isSpeedRequired() && !properties.hasSpeedSupport()) {
            return false;
        }
        if (criteria.isBearingRequired() && !properties.hasBearingSupport()) {
            return false;
        }
        if (!criteria.isCostAllowed() && properties.hasMonetaryCost()) {
            return false;
        }
        return true;
    }

    /**
     * Returns true if the provider requires access to a
     * data network (e.g., the Internet), false otherwise.
     */
    public boolean requiresNetwork() {
        if (mProperties == null) {
            return false;
        } else {
            return mProperties.hasNetworkRequirement();
        }
    }

    /**
     * Returns true if the provider requires access to a
     * satellite-based positioning system (e.g., GPS), false
     * otherwise.
     */
    public boolean requiresSatellite() {
        if (mProperties == null) {
            return false;
        } else {
            return mProperties.hasSatelliteRequirement();
        }
    }

    /**
     * Returns true if the provider requires access to an appropriate
     * cellular network (e.g., to make use of cell tower IDs), false
     * otherwise.
     */
    public boolean requiresCell() {
        if (mProperties == null) {
            return false;
        } else {
            return mProperties.hasCellRequirement();
        }
    }

    /**
     * Returns true if the use of this provider may result in a
     * monetary charge to the user, false if use is free.  It is up to
     * each provider to give accurate information.
     */
    public boolean hasMonetaryCost() {
        if (mProperties == null) {
            return false;
        } else {
            return mProperties.hasMonetaryCost();
        }
    }

    /**
     * Returns true if the provider is able to provide altitude
     * information, false otherwise.  A provider that reports altitude
     * under most circumstances but may occassionally not report it
     * should return true.
     */
    public boolean supportsAltitude() {
        if (mProperties == null) {
            return false;
        } else {
            return mProperties.hasAltitudeSupport();
        }
    }

    /**
     * Returns true if the provider is able to provide speed
     * information, false otherwise.  A provider that reports speed
     * under most circumstances but may occassionally not report it
     * should return true.
     */
    public boolean supportsSpeed() {
        if (mProperties == null) {
            return false;
        } else {
            return mProperties.hasSpeedSupport();
        }
    }

    /**
     * Returns true if the provider is able to provide bearing
     * information, false otherwise.  A provider that reports bearing
     * under most circumstances but may occassionally not report it
     * should return true.
     */
    public boolean supportsBearing() {
        if (mProperties == null) {
            return false;
        } else {
            return mProperties.hasBearingSupport();
        }
    }

    /**
     * Returns the power requirement for this provider, one of the ProviderProperties.POWER_USAGE_*
     * constants.
     */
    public int getPowerRequirement() {
        if (mProperties == null) {
            return ProviderProperties.POWER_USAGE_HIGH;
        } else {
            return mProperties.getPowerUsage();
        }
    }

    /**
     * Returns the rough accuracy of this provider, one of the ProviderProperties.ACCURACY_*
     * constants.
     */
    public int getAccuracy() {
        if (mProperties == null) {
            return ProviderProperties.ACCURACY_COARSE;
        } else {
            return mProperties.getAccuracy();
        }
    }
}
