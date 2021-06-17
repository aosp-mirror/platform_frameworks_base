/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location.countrydetector;

import android.content.Context;
import android.location.Address;
import android.location.Country;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Bundle;
import android.util.Slog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * This class detects which country the user currently is in through the enabled
 * location providers and the GeoCoder
 * <p>
 * Use {@link #detectCountry} to start querying. If the location can not be
 * resolved within the given time, the last known location will be used to get
 * the user country through the GeoCoder. The IllegalStateException will be
 * thrown if there is a ongoing query.
 * <p>
 * The current query can be stopped by {@link #stop()}
 *
 * @hide
 */
public class LocationBasedCountryDetector extends CountryDetectorBase {
    private final static String TAG = "LocationBasedCountryDetector";
    private final static long QUERY_LOCATION_TIMEOUT = 1000 * 60 * 5; // 5 mins

    /**
     * Used for canceling location query
     */
    protected Timer mTimer;

    /**
     * The thread to query the country from the GeoCoder.
     */
    protected Thread mQueryThread;
    protected List<LocationListener> mLocationListeners;

    private LocationManager mLocationManager;
    private List<String> mEnabledProviders;

    public LocationBasedCountryDetector(Context ctx) {
        super(ctx);
        mLocationManager = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
    }

    /**
     * @return the ISO 3166-1 two letters country code from the location
     */
    protected String getCountryFromLocation(Location location) {
        String country = null;
        Geocoder geoCoder = new Geocoder(mContext);
        try {
            List<Address> addresses = geoCoder.getFromLocation(
                    location.getLatitude(), location.getLongitude(), 1);
            if (addresses != null && addresses.size() > 0) {
                country = addresses.get(0).getCountryCode();
            }
        } catch (IOException e) {
            Slog.w(TAG, "Exception occurs when getting country from location");
        }
        return country;
    }

    protected boolean isAcceptableProvider(String provider) {
        // We don't want to actively initiate a location fix here (with gps or network providers).
        return LocationManager.PASSIVE_PROVIDER.equals(provider);
    }

    /**
     * Register a listener with a provider name
     */
    protected void registerListener(String provider, LocationListener listener) {
        final long bid = Binder.clearCallingIdentity();
        try {
            mLocationManager.requestLocationUpdates(provider, 0, 0, listener);
        } finally {
            Binder.restoreCallingIdentity(bid);
        }
    }

    /**
     * Unregister an already registered listener
     */
    protected void unregisterListener(LocationListener listener) {
        final long bid = Binder.clearCallingIdentity();
        try {
            mLocationManager.removeUpdates(listener);
        } finally {
            Binder.restoreCallingIdentity(bid);
        }
    }

    /**
     * @return the last known location from all providers
     */
    protected Location getLastKnownLocation() {
        final long bid = Binder.clearCallingIdentity();
        try {
            List<String> providers = mLocationManager.getAllProviders();
            Location bestLocation = null;
            for (String provider : providers) {
                Location lastKnownLocation = mLocationManager.getLastKnownLocation(provider);
                if (lastKnownLocation != null) {
                    if (bestLocation == null ||
                            bestLocation.getElapsedRealtimeNanos() <
                            lastKnownLocation.getElapsedRealtimeNanos()) {
                        bestLocation = lastKnownLocation;
                    }
                }
            }
            return bestLocation;
        } finally {
            Binder.restoreCallingIdentity(bid);
        }
    }

    /**
     * @return the timeout for querying the location.
     */
    protected long getQueryLocationTimeout() {
        return QUERY_LOCATION_TIMEOUT;
    }

    protected List<String> getEnabledProviders() {
        if (mEnabledProviders == null) {
            mEnabledProviders = mLocationManager.getProviders(true);
        }
        return mEnabledProviders;
    }

    /**
     * Start detecting the country.
     * <p>
     * Queries the location from all location providers, then starts a thread to query the
     * country from GeoCoder.
     */
    @Override
    public synchronized Country detectCountry() {
        if (mLocationListeners  != null) {
            throw new IllegalStateException();
        }
        // Request the location from all enabled providers.
        List<String> enabledProviders = getEnabledProviders();
        int totalProviders = enabledProviders.size();
        if (totalProviders > 0) {
            mLocationListeners = new ArrayList<LocationListener>(totalProviders);
            for (int i = 0; i < totalProviders; i++) {
                String provider = enabledProviders.get(i);
                if (isAcceptableProvider(provider)) {
                    LocationListener listener = new LocationListener () {
                        @Override
                        public void onLocationChanged(Location location) {
                            if (location != null) {
                                LocationBasedCountryDetector.this.stop();
                                queryCountryCode(location);
                            }
                        }
                        @Override
                        public void onProviderDisabled(String provider) {
                        }
                        @Override
                        public void onProviderEnabled(String provider) {
                        }
                        @Override
                        public void onStatusChanged(String provider, int status, Bundle extras) {
                        }
                    };
                    mLocationListeners.add(listener);
                    registerListener(provider, listener);
                }
            }

            mTimer = new Timer();
            mTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    mTimer = null;
                    LocationBasedCountryDetector.this.stop();
                    // Looks like no provider could provide the location, let's try the last
                    // known location.
                    queryCountryCode(getLastKnownLocation());
                }
            }, getQueryLocationTimeout());
        } else {
            // There is no provider enabled.
            queryCountryCode(getLastKnownLocation());
        }
        return mDetectedCountry;
    }

    /**
     * Stop the current query without notifying the listener.
     */
    @Override
    public synchronized void stop() {
        if (mLocationListeners != null) {
            for (LocationListener listener : mLocationListeners) {
                unregisterListener(listener);
            }
            mLocationListeners = null;
        }
        if (mTimer != null) {
            mTimer.cancel();
            mTimer = null;
        }
    }

    /**
     * Start a new thread to query the country from Geocoder.
     */
    private synchronized void queryCountryCode(final Location location) {
        if (mQueryThread != null) return;
        mQueryThread = new Thread(new Runnable() {
            @Override
            public void run() {
                if (location == null) {
                    notifyListener(null);
                    return;
                }
                String countryIso = getCountryFromLocation(location);
                if (countryIso != null) {
                    mDetectedCountry = new Country(countryIso, Country.COUNTRY_SOURCE_LOCATION);
                } else {
                    mDetectedCountry = null;
                }
                notifyListener(mDetectedCountry);
                mQueryThread = null;
            }
        });
        mQueryThread.start();
    }
}
