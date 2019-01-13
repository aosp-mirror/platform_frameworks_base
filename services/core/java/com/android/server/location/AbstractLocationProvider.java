/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.server.location;

import android.location.Location;
import android.location.LocationProvider;
import android.os.Bundle;
import android.os.WorkSource;

import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ProviderRequest;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;

/**
 * Location Manager's interface for location providers. Always starts as disabled.
 *
 * @hide
 */
public abstract class AbstractLocationProvider {

    /**
     * Interface for communicating from a location provider back to the location service.
     */
    public interface LocationProviderManager {

        /**
         * May be called to inform the location service of a change in this location provider's
         * enabled/disabled state.
         */
        void onSetEnabled(boolean enabled);

        /**
         * May be called to inform the location service of a change in this location provider's
         * properties.
         */
        void onSetProperties(ProviderProperties properties);

        /**
         * May be called to inform the location service that this provider has a new location
         * available.
         */
        void onReportLocation(Location location);

        /**
         * May be called to inform the location service that this provider has a new location
         * available.
         */
        void onReportLocation(List<Location> locations);
    }

    private final LocationProviderManager mLocationProviderManager;

    protected AbstractLocationProvider(LocationProviderManager locationProviderManager) {
        mLocationProviderManager = locationProviderManager;
    }

    /**
     * Call this method to report a new location. May be called from any thread.
     */
    protected void reportLocation(Location location) {
        mLocationProviderManager.onReportLocation(location);
    }

    /**
     * Call this method to report a new location. May be called from any thread.
     */
    protected void reportLocation(List<Location> locations) {
        mLocationProviderManager.onReportLocation(locations);
    }

    /**
     * Call this method to report a change in provider enabled/disabled status. May be called from
     * any thread.
     */
    protected void setEnabled(boolean enabled) {
        mLocationProviderManager.onSetEnabled(enabled);
    }

    /**
     * Call this method to report a change in provider properties. May be called from
     * any thread.
     */
    protected void setProperties(ProviderProperties properties) {
        mLocationProviderManager.onSetProperties(properties);
    }

    /**
     * Called when the location service delivers a new request for fulfillment to the provider.
     * Replaces any previous requests completely.
     */
    public abstract void setRequest(ProviderRequest request, WorkSource source);

    /**
     * Called to dump debug or log information.
     */
    public abstract void dump(FileDescriptor fd, PrintWriter pw, String[] args);

    /**
     * Retrieves the current status of the provider.
     *
     * @deprecated Will be removed in a future release.
     */
    @Deprecated
    public int getStatus(Bundle extras) {
        return LocationProvider.AVAILABLE;
    }

    /**
     * Retrieves the last update time of the status of the provider.
     *
     * @deprecated Will be removed in a future release.
     */
    @Deprecated
    public long getStatusUpdateTime() {
        return 0;
    }

    /** Sends a custom command to this provider. */
    public abstract void sendExtraCommand(String command, Bundle extras);
}
