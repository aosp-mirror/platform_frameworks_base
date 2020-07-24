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

import android.annotation.NonNull;
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
     * Called when the location has changed. A wakelock is held on behalf on the listener for some
     * brief amount of time as this callback executes. If this callback performs long running
     * operations, it is the client's responsibility to obtain their own wakelock.
     *
     * @param location the updated location
     */
    void onLocationChanged(@NonNull Location location);

    /**
     * This callback will never be invoked on Android Q and above, and providers can be considered
     * as always in the {@link LocationProvider#AVAILABLE} state.
     *
     * @deprecated This callback will never be invoked on Android Q and above.
     */
    @Deprecated
    default void onStatusChanged(String provider, int status, Bundle extras) {}

    /**
     * Called when a provider this listener is registered with becomes enabled.
     *
     * @param provider the name of the location provider
     */
    default void onProviderEnabled(@NonNull String provider) {}

    /**
     * Called when the provider this listener is registered with becomes disabled. If a provider is
     * disabled when this listener is registered, this callback will be invoked immediately.
     *
     * @param provider the name of the location provider
     */
    default void onProviderDisabled(@NonNull String provider) {}
}
