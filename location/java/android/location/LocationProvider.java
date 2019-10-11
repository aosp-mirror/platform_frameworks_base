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


import com.android.internal.location.ProviderProperties;

/**
 * An abstract superclass for location providers.  A location provider
 * provides periodic reports on the geographical location of the
 * device.
 *
 * <p> Each provider has a set of criteria under which it may be used;
 * for example, some providers require GPS hardware and visibility to
 * a number of satellites; others require the use of the cellular
 * radio, or access to a specific carrier's network, or to the
 * internet.  They may also have different battery consumption
 * characteristics or monetary costs to the user.  The {@link
 * Criteria} class allows providers to be selected based on
 * user-specified criteria.
 */
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

    /**
     * A regular expression matching characters that may not appear
     * in the name of a LocationProvider
     * @hide
     */
    public static final String BAD_CHARS_REGEX = "[^a-zA-Z0-9]";

    private final String mName;
    private final ProviderProperties mProperties;

    /**
     * Constructs a LocationProvider with the given name.   Provider names must
     * consist only of the characters [a-zA-Z0-9].
     *
     * @throws IllegalArgumentException if name contains an illegal character
     *
     * @hide
     */
    public LocationProvider(String name, ProviderProperties properties) {
        if (name.matches(BAD_CHARS_REGEX)) {
            throw new IllegalArgumentException("provider name contains illegal character: " + name);
        }
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
                criteria.getAccuracy() < properties.mAccuracy) {
            return false;
        }
        if (criteria.getPowerRequirement() != Criteria.NO_REQUIREMENT &&
                criteria.getPowerRequirement() < properties.mPowerRequirement) {
            return false;
        }
        if (criteria.isAltitudeRequired() && !properties.mSupportsAltitude) {
            return false;
        }
        if (criteria.isSpeedRequired() && !properties.mSupportsSpeed) {
            return false;
        }
        if (criteria.isBearingRequired() && !properties.mSupportsBearing) {
            return false;
        }
        if (!criteria.isCostAllowed() && properties.mHasMonetaryCost) {
            return false;
        }
        return true;
    }

    /**
     * Returns true if the provider requires access to a
     * data network (e.g., the Internet), false otherwise.
     */
    public boolean requiresNetwork() {
        return mProperties.mRequiresNetwork;
    }

    /**
     * Returns true if the provider requires access to a
     * satellite-based positioning system (e.g., GPS), false
     * otherwise.
     */
    public boolean requiresSatellite() {
        return mProperties.mRequiresSatellite;
    }

    /**
     * Returns true if the provider requires access to an appropriate
     * cellular network (e.g., to make use of cell tower IDs), false
     * otherwise.
     */
    public boolean requiresCell() {
        return mProperties.mRequiresCell;
    }

    /**
     * Returns true if the use of this provider may result in a
     * monetary charge to the user, false if use is free.  It is up to
     * each provider to give accurate information.
     */
    public boolean hasMonetaryCost() {
        return mProperties.mHasMonetaryCost;
    }

    /**
     * Returns true if the provider is able to provide altitude
     * information, false otherwise.  A provider that reports altitude
     * under most circumstances but may occassionally not report it
     * should return true.
     */
    public boolean supportsAltitude() {
        return mProperties.mSupportsAltitude;
    }

    /**
     * Returns true if the provider is able to provide speed
     * information, false otherwise.  A provider that reports speed
     * under most circumstances but may occassionally not report it
     * should return true.
     */
    public boolean supportsSpeed() {
        return mProperties.mSupportsSpeed;
    }

    /**
     * Returns true if the provider is able to provide bearing
     * information, false otherwise.  A provider that reports bearing
     * under most circumstances but may occassionally not report it
     * should return true.
     */
    public boolean supportsBearing() {
        return mProperties.mSupportsBearing;
    }

    /**
     * Returns the power requirement for this provider.
     *
     * @return the power requirement for this provider, as one of the
     * constants Criteria.POWER_REQUIREMENT_*.
     */
    public int getPowerRequirement() {
        return mProperties.mPowerRequirement;
    }

    /**
     * Returns a constant describing horizontal accuracy of this provider.
     * If the provider returns finer grain or exact location,
     * {@link Criteria#ACCURACY_FINE} is returned, otherwise if the
     * location is only approximate then {@link Criteria#ACCURACY_COARSE}
     * is returned.
     */
    public int getAccuracy() {
        return mProperties.mAccuracy;
    }
}
