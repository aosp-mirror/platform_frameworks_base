/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.webkit;

import android.app.ActivityThread;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebViewCore;


/**
 * Implements the Java side of GeolocationServiceAndroid.
 */
final class GeolocationService implements LocationListener {

    // Log tag
    private static final String TAG = "geolocationService";

    private long mNativeObject;
    private LocationManager mLocationManager;
    private boolean mIsGpsEnabled;
    private boolean mIsRunning;
    private boolean mIsNetworkProviderAvailable;
    private boolean mIsGpsProviderAvailable;

    /**
     * Constructor
     * @param context The context from which we obtain the system service.
     * @param nativeObject The native object to which this object will report position updates and
     *     errors.
     */
    public GeolocationService(Context context, long nativeObject) {
        mNativeObject = nativeObject;
        // Register newLocationAvailable with platform service.
        mLocationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (mLocationManager == null) {
            Log.e(TAG, "Could not get location manager.");
        }
     }

    /**
     * Start listening for location updates.
     */
    public boolean start() {
        registerForLocationUpdates();
        mIsRunning = true;
        return mIsNetworkProviderAvailable || mIsGpsProviderAvailable;
    }

    /**
     * Stop listening for location updates.
     */
    public void stop() {
        unregisterFromLocationUpdates();
        mIsRunning = false;
    }

    /**
     * Sets whether to use the GPS.
     * @param enable Whether to use the GPS.
     */
    public void setEnableGps(boolean enable) {
        if (mIsGpsEnabled != enable) {
            mIsGpsEnabled = enable;
            if (mIsRunning) {
                // There's no way to unregister from a single provider, so we can
                // only unregister from all, then reregister with all but the GPS.
                unregisterFromLocationUpdates();
                registerForLocationUpdates();
                // Check that the providers are still available after we re-register.
                maybeReportError("The last location provider is no longer available");
            }
        }
    }

    /**
     * LocationListener implementation.
     * Called when the location has changed.
     * @param location The new location, as a Location object.
     */
    public void onLocationChanged(Location location) {
        // Callbacks from the system location sevice are queued to this thread, so it's possible
        // that we receive callbacks after unregistering. At this point, the native object will no
        // longer exist.
        if (mIsRunning) {
            nativeNewLocationAvailable(mNativeObject, location);
        }
    }

    /**
     * LocationListener implementation.
     * Called when the provider status changes.
     * @param provider The name of the provider.
     * @param status The new status of the provider.
     * @param extras an optional Bundle with provider specific data.
     */
    public void onStatusChanged(String providerName, int status, Bundle extras) {
        boolean isAvailable = (status == LocationProvider.AVAILABLE);
        if (LocationManager.NETWORK_PROVIDER.equals(providerName)) {
            mIsNetworkProviderAvailable = isAvailable;
        } else if (LocationManager.GPS_PROVIDER.equals(providerName)) {
            mIsGpsProviderAvailable = isAvailable;
        }
        maybeReportError("The last location provider is no longer available");
    }

    /**
     * LocationListener implementation.
     * Called when the provider is enabled.
     * @param provider The name of the location provider that is now enabled.
     */
    public void onProviderEnabled(String providerName) {
        // No need to notify the native side. It's enough to start sending
        // valid position fixes again.
        if (LocationManager.NETWORK_PROVIDER.equals(providerName)) {
            mIsNetworkProviderAvailable = true;
        } else if (LocationManager.GPS_PROVIDER.equals(providerName)) {
            mIsGpsProviderAvailable = true;
        }
    }

    /**
     * LocationListener implementation.
     * Called when the provider is disabled.
     * @param provider The name of the location provider that is now disabled.
     */
    public void onProviderDisabled(String providerName) {
        if (LocationManager.NETWORK_PROVIDER.equals(providerName)) {
            mIsNetworkProviderAvailable = false;
        } else if (LocationManager.GPS_PROVIDER.equals(providerName)) {
            mIsGpsProviderAvailable = false;
        }
        maybeReportError("The last location provider was disabled");
    }

    /**
     * Registers this object with the location service.
     */
    private void registerForLocationUpdates() {
        try {
            // Registration may fail if providers are not present on the device.
            try {
                mLocationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, this);
                mIsNetworkProviderAvailable = true;
            } catch(IllegalArgumentException e) { }
            if (mIsGpsEnabled) {
                try {
                    mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, this);
                    mIsGpsProviderAvailable = true;
                } catch(IllegalArgumentException e) { }
            }
        } catch(SecurityException e) {
            Log.e(TAG, "Caught security exception registering for location updates from system. " +
                "This should only happen in DumpRenderTree.");
        }
    }

    /**
     * Unregisters this object from the location service.
     */
    private void unregisterFromLocationUpdates() {
        mLocationManager.removeUpdates(this);
        mIsNetworkProviderAvailable = false;
        mIsGpsProviderAvailable = false;
    }

    /**
     * Reports an error if neither the network nor the GPS provider is available.
     */
    private void maybeReportError(String message) {
        // Callbacks from the system location sevice are queued to this thread, so it's possible
        // that we receive callbacks after unregistering. At this point, the native object will no
        // longer exist.
        if (mIsRunning && !mIsNetworkProviderAvailable && !mIsGpsProviderAvailable) {
            nativeNewErrorAvailable(mNativeObject, message);
        }
    }

    // Native functions
    private static native void nativeNewLocationAvailable(long nativeObject, Location location);
    private static native void nativeNewErrorAvailable(long nativeObject, String message);
}
