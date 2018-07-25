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

package com.android.location.provider;

import android.os.IBinder;

import android.location.Address;
import android.location.GeocoderParams;
import android.location.IGeocodeProvider;

import java.util.List;

/**
 * Base class for geocode providers implemented as unbundled services.
 *
 * <p>Geocode providers can be implemented as services and return the result of
 * {@link GeocodeProvider#getBinder()} in its getBinder() method.
 *
 * <p>IMPORTANT: This class is effectively a public API for unbundled
 * applications, and must remain API stable. See README.txt in the root
 * of this package for more information.
 * @hide
 */
public abstract class GeocodeProvider {

    private IGeocodeProvider.Stub mProvider = new IGeocodeProvider.Stub() {
        public String getFromLocation(double latitude, double longitude, int maxResults,
                GeocoderParams params, List<Address> addrs) {
            return GeocodeProvider.this.onGetFromLocation(latitude, longitude, maxResults,
                    params, addrs);
        }

        public String getFromLocationName(String locationName,
                double lowerLeftLatitude, double lowerLeftLongitude,
                double upperRightLatitude, double upperRightLongitude, int maxResults,
                GeocoderParams params, List<Address> addrs) {
            return GeocodeProvider.this.onGetFromLocationName(locationName, lowerLeftLatitude,
                    lowerLeftLongitude, upperRightLatitude, upperRightLongitude,
                    maxResults, params, addrs);
        }
    };

    /**
     * This method is overridden to implement the
     * {@link android.location.Geocoder#getFromLocation(double, double, int)} method.
     * Classes implementing this method should not hold a reference to the params parameter.
     */
    public abstract String onGetFromLocation(double latitude, double longitude, int maxResults,
            GeocoderParams params, List<Address> addrs);

    /**
     * This method is overridden to implement the
     * {@link android.location.Geocoder#getFromLocationName(String, int, double, double, double, double)} method.
     * Classes implementing this method should not hold a reference to the params parameter.
     */
    public abstract String onGetFromLocationName(String locationName,
            double lowerLeftLatitude, double lowerLeftLongitude,
            double upperRightLatitude, double upperRightLongitude, int maxResults,
            GeocoderParams params, List<Address> addrs);

    /**
     * Returns the Binder interface for the geocode provider.
     * This is intended to be used for the onBind() method of
     * a service that implements a geocoder service.
     *
     * @return the IBinder instance for the provider
     */
    public IBinder getBinder() {
        return mProvider;
    }
}
