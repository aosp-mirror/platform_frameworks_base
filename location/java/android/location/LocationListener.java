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
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about identifying user location, read the
 * <a href="{@docRoot}guide/topics/location/obtaining-user-location.html">Obtaining User
 * Location</a> developer guide.</p>
 * </div>
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
     * This callback will never be invoked and providers can be considers as always in the
     * {@link LocationProvider#AVAILABLE} state.
     *
     * @deprecated This callback will never be invoked.
     */
    @Deprecated
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
