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

import android.annotation.SystemApi;
import android.location.Address;
import android.location.GeocoderParams;
import android.location.provider.ForwardGeocodeRequest;
import android.location.provider.IGeocodeCallback;
import android.location.provider.IGeocodeProvider;
import android.location.provider.ReverseGeocodeRequest;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.List;

/**
 * This class was originally shipped out-of-band from the normal API processes as a separate drop
 * before SystemApi existed. Now that SystemApi does exist, this class has been retroactively
 * published through SystemApi.
 *
 * @deprecated Use {@link android.location.provider.GeocodeProviderBase} instead.
 * @hide
 */
@Deprecated
@SystemApi
public abstract class GeocodeProvider {

    private final IGeocodeProvider.Stub mProvider =
            new IGeocodeProvider.Stub() {
                @Override
                public void reverseGeocode(
                        ReverseGeocodeRequest request, IGeocodeCallback callback) {
                    List<Address> results = new ArrayList<>();
                    String error =
                            onGetFromLocation(
                                    request.getLatitude(),
                                    request.getLongitude(),
                                    request.getMaxResults(),
                                    new GeocoderParams(
                                            request.getCallingUid(),
                                            request.getCallingPackage(),
                                            request.getCallingAttributionTag(),
                                            request.getLocale()),
                                    results);
                    try {
                        if (error != null) {
                            callback.onError(error);
                        } else {
                            callback.onResults(results);
                        }
                    } catch (RemoteException e) {
                        // ignore
                    }
                }

                @Override
                public void forwardGeocode(
                        ForwardGeocodeRequest request, IGeocodeCallback callback) {
                    List<Address> results = new ArrayList<>();
                    String error =
                            onGetFromLocationName(
                                    request.getLocationName(),
                                    request.getLowerLeftLatitude(),
                                    request.getLowerLeftLongitude(),
                                    request.getUpperRightLatitude(),
                                    request.getUpperRightLongitude(),
                                    request.getMaxResults(),
                                    new GeocoderParams(
                                            request.getCallingUid(),
                                            request.getCallingPackage(),
                                            request.getCallingAttributionTag(),
                                            request.getLocale()),
                                    results);
                    try {
                        if (error != null) {
                            callback.onError(error);
                        } else {
                            callback.onResults(results);
                        }
                    } catch (RemoteException e) {
                        // ignore
                    }
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
