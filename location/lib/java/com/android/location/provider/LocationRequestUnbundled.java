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

/**
 * This class is an interface to LocationRequests for unbundled applications.
 *
 * <p>IMPORTANT: This class is effectively a public API for unbundled
 * applications, and must remain API stable. See README.txt in the root
 * of this package for more information.
 */
public final class LocationRequestUnbundled {
    /**
     * Returned by {@link #getQuality} when requesting the most accurate locations available.
     *
     * <p>This may be up to 1 meter accuracy, although this is implementation dependent.
     */
    public static final int ACCURACY_FINE = LocationRequest.ACCURACY_FINE;

    /**
     * Returned by {@link #getQuality} when requesting "block" level accuracy.
     *
     * <p>Block level accuracy is considered to be about 100 meter accuracy,
     * although this is implementation dependent. Using a coarse accuracy
     * such as this often consumes less power.
     */
    public static final int ACCURACY_BLOCK = LocationRequest.ACCURACY_BLOCK;

    /**
     * Returned by {@link #getQuality} when requesting "city" level accuracy.
     *
     * <p>City level accuracy is considered to be about 10km accuracy,
     * although this is implementation dependent. Using a coarse accuracy
     * such as this often consumes less power.
     */
    public static final int ACCURACY_CITY = LocationRequest.ACCURACY_CITY;

    /**
     * Returned by {@link #getQuality} when requiring no direct power impact (passive locations).
     *
     * <p>This location request will not trigger any active location requests,
     * but will receive locations triggered by other applications. Your application
     * will not receive any direct power blame for location work.
     */
    public static final int POWER_NONE = LocationRequest.POWER_NONE;

    /**
     * Returned by {@link #getQuality} when requesting low power impact.
     *
     * <p>This location request will avoid high power location work where
     * possible.
     */
    public static final int POWER_LOW = LocationRequest.POWER_LOW;

    /**
     * Returned by {@link #getQuality} when allowing high power consumption for location.
     *
     * <p>This location request will allow high power location work.
     */
    public static final int POWER_HIGH = LocationRequest.POWER_HIGH;

    private final LocationRequest delegate;

    LocationRequestUnbundled(LocationRequest delegate) {
        this.delegate = delegate;
    }

    /**
     * Get the desired interval of this request, in milliseconds.
     *
     * @return desired interval in milliseconds, inexact
     */
    public long getInterval() {
        return delegate.getInterval();
    }

    /**
     * Get the fastest interval of this request, in milliseconds.
     *
     * <p>The system will never provide location updates faster
     * than the minimum of {@link #getFastestInterval} and
     * {@link #getInterval}.
     *
     * @return fastest interval in milliseconds, exact
     */
    public long getFastestInterval() {
        return delegate.getFastestInterval();
    }

    /**
     * Get the quality of the request.
     *
     * @return an accuracy or power constant
     */
    public int getQuality() {
        return delegate.getQuality();
    }

    /**
     * Get the minimum distance between location updates, in meters.
     *
     * @return minimum distance between location updates in meters
     */
    public float getSmallestDisplacement() {
        return delegate.getSmallestDisplacement();
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
