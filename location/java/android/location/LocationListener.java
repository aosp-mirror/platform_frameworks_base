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

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Used for receiving notifications when the device location has changed. These methods are called
 * when the listener has been registered with the LocationManager.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about identifying user location, read the
 * <a href="{@docRoot}guide/topics/location/obtaining-user-location.html">Obtaining User
 * Location</a> developer guide.</p>
 * </div>
 *
 * @see LocationManager#requestLocationUpdates(String, LocationRequest, Executor, LocationListener)
 */
public interface LocationListener {

    /**
     * Called when the location has changed. A wakelock may be held on behalf on the listener for
     * some brief amount of time as this callback executes. If this callback performs long running
     * operations, it is the client's responsibility to obtain their own wakelock if necessary.
     *
     * @param location the updated location
     */
    void onLocationChanged(@NonNull Location location);

    /**
     * Called when the location has changed and locations are being delivered in batches. The
     * default implementation calls through to {@link #onLocationChanged(Location)} with all
     * locations in the batch. The list of locations is always guaranteed to be non-empty, and is
     * always guaranteed to be ordered from earliest location to latest location (so that the
     * earliest location in the batch is at index 0 in the list, and the latest location in the
     * batch is at index size-1 in the list).
     *
     * @see LocationRequest#getMaxUpdateDelayMillis()
     * @param locations the location list
     */
    default void onLocationChanged(@NonNull List<Location> locations) {
        final int size = locations.size();
        for (int i = 0; i < size; i++) {
            onLocationChanged(locations.get(i));
        }
    }

    /**
     * Invoked when a flush operation is complete and after flushed locations have been delivered.
     *
     * @param requestCode the request code passed into
     *                    {@link LocationManager#requestFlush(String, LocationListener, int)}
     */
    default void onFlushComplete(int requestCode) {}

    /**
     * This callback will never be invoked on Android Q and above, and providers can be considered
     * as always in the {@link LocationProvider#AVAILABLE} state.
     *
     * <p class="note">Note that this method only has a default implementation on Android R and
     * above, and this method must still be overridden in order to run successfully on Android
     * versions below R. LocationListenerCompat from the compat libraries may be used to avoid the
     * need to override for older platforms.
     *
     * @deprecated This callback will never be invoked on Android Q and above.
     */
    @Deprecated
    default void onStatusChanged(String provider, int status, Bundle extras) {}

    /**
     * Called when a provider this listener is registered with becomes enabled.
     *
     * <p class="note">Note that this method only has a default implementation on Android R and
     * above, and this method must still be overridden in order to run successfully on Android
     * versions below R. LocationListenerCompat from the compat libraries may be used to avoid the
     * need to override for older platforms.
     *
     * @param provider the name of the location provider
     */
    default void onProviderEnabled(@NonNull String provider) {}

    /**
     * Called when the provider this listener is registered with becomes disabled. If a provider is
     * disabled when this listener is registered, this callback will be invoked immediately.
     *
     * <p class="note">Note that this method only has a default implementation on Android R and
     * above, and this method must still be overridden in order to run successfully on Android
     * versions below R. LocationListenerCompat from the compat libraries may be used to avoid the
     * need to override for older platforms.
     *
     * @param provider the name of the location provider
     */
    default void onProviderDisabled(@NonNull String provider) {}
}
