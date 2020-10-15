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

package com.android.location.provider;

import android.location.LocationRequest;
import android.location.LocationRequest.Quality;

/**
 * This class is an interface to LocationRequests for unbundled applications.
 *
 * <p>IMPORTANT: This class is effectively a public API for unbundled
 * applications, and must remain API stable. See README.txt in the root
 * of this package for more information.
 *
 * @deprecated Do not use.
 */
@Deprecated
public final class LocationRequestUnbundled {

    /**
     * @deprecated Use {@link LocationRequest#QUALITY_HIGH_ACCURACY} instead.
     */
    @Deprecated
    public static final int ACCURACY_FINE = LocationRequest.ACCURACY_FINE;

    /**
     * @deprecated Use {@link LocationRequest#QUALITY_BALANCED_POWER_ACCURACY} instead.
     */
    @Deprecated
    public static final int ACCURACY_BLOCK = LocationRequest.ACCURACY_BLOCK;


    /**
     * @deprecated Use {@link LocationRequest#QUALITY_LOW_POWER} instead.
     */
    @Deprecated
    public static final int ACCURACY_CITY = LocationRequest.ACCURACY_CITY;


    /**
     * @deprecated Do not use.
     */
    @Deprecated
    public static final int POWER_NONE = LocationRequest.POWER_NONE;


    /**
     * @deprecated Use {@link LocationRequest#QUALITY_LOW_POWER} instead.
     */
    @Deprecated
    public static final int POWER_LOW = LocationRequest.POWER_LOW;


    /**
     * @deprecated Use {@link LocationRequest#QUALITY_BALANCED_POWER_ACCURACY} instead.
     */
    @Deprecated
    public static final int POWER_HIGH = LocationRequest.POWER_HIGH;

    private final LocationRequest delegate;

    LocationRequestUnbundled(LocationRequest delegate) {
        this.delegate = delegate;
    }

    /**
     * Get the location update interval.
     *
     * @return location update interval
     */
    public long getInterval() {
        return delegate.getIntervalMillis();
    }

    /**
     * Get the minimum delivery interval.
     *
     * @return minimum delivery interval
     */
    public long getFastestInterval() {
        return delegate.getMinUpdateIntervalMillis();
    }

    /**
     * Get the quality of the request.
     *
     * @return a {@link LocationRequest} QUALITY_* constant
     */
    public @Quality int getQuality() {
        return delegate.getQuality();
    }

    /**
     * Get the minimum distance between location updates, in meters.
     *
     * @return minimum distance between location updates in meters
     */
    public float getSmallestDisplacement() {
        return delegate.getMinUpdateDistanceMeters();
    }

    /**
     * Returns true if location settings will be ignored in order to satisfy this request.
     *
     * @return true if location settings will be ignored in order to satisfy this request
     */
    public boolean isLocationSettingsIgnored() {
        return delegate.isLocationSettingsIgnored();
    }

    @Override
    public String toString() {
      return delegate.toString();
    }
}
