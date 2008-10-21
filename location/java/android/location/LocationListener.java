/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.os.Bundle;

/**
 * Used for receiving notifications from the LocationManager when
 * the location has changed. These methods are called if the
 * LocationListener has been registered with the location manager service
 * using the {@link LocationManager#requestLocationUpdates(String, long, float, LocationListener)}
 * method.
 */
public interface LocationListener {

    /**
     * Called when the location has changed.
     *
     * <p> There are no restrictions on the use of the supplied Location object.
     *
     * @param location The new location, as a Location object.
     */
    void onLocationChanged(Location location);

    /**
     * Called when the provider status changes. This method is called when
     * a provider is unable to fetch a location or if the provider has recently
     * become available after a period of unavailability.
     *
     * @param provider the name of the location provider associated with this
     * update.
     * @param status {@link LocationProvider#OUT_OF_SERVICE} if the
     * provider is out of service, and this is not expected to change in the
     * near future; {@link LocationProvider#TEMPORARILY_UNAVAILABLE} if
     * the provider is temporarily unavailable but is expected to be available
     * shortly; and {@link LocationProvider#AVAILABLE} if the
     * provider is currently available.
     * @param extras an optional Bundle which will contain provider specific
     * status variables.
     *
     * <p> A number of common key/value pairs for the extras Bundle are listed
     * below. Providers that use any of the keys on this list must
     * provide the corresponding value as described below.
     *
     * <ul>
     * <li> satellites - the number of satellites used to derive the fix
     * </ul>
     */
    void onStatusChanged(String provider, int status, Bundle extras);

    /**
     * Called when the provider is enabled by the user.
     *
     * @param provider the name of the location provider associated with this
     * update.
     */
    void onProviderEnabled(String provider);

    /**
     * Called when the provider is disabled by the user. If requestLocationUpdates
     * is called on an already disabled provider, this method is called
     * immediately.
     *
     * @param provider the name of the location provider associated with this
     * update.
     */
    void onProviderDisabled(String provider);
}
