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

import android.content.Context;
import android.location.Location;
import android.os.Bundle;
import android.os.WorkSource;

import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ProviderRequest;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collections;
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

    protected final Context mContext;
    private final LocationProviderManager mLocationProviderManager;

    protected AbstractLocationProvider(
            Context context, LocationProviderManager locationProviderManager) {
        mContext = context;
        mLocationProviderManager = locationProviderManager;
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
     * Invoked by the location service to return a list of packages currently associated with this
     * provider. May be called from any thread.
     */
    public List<String> getProviderPackages() {
        return Collections.singletonList(mContext.getPackageName());
    }

    /**
     * Invoked by the location service to deliver a new request for fulfillment to the provider.
     * Replaces any previous requests completely. Will always be invoked from the location service
     * thread with a cleared binder identity.
     */
    public abstract void onSetRequest(ProviderRequest request, WorkSource source);

    /**
     * Invoked by the location service to deliver a custom command to this provider. Will always be
     * invoked from the location service thread with a cleared binder identity.
     */
    public void onSendExtraCommand(int uid, int pid, String command, Bundle extras) {}

    /**
     * Invoked by the location service to dump debug or log information. May be invoked from any
     * thread.
     */
    public abstract void dump(FileDescriptor fd, PrintWriter pw, String[] args);
}
