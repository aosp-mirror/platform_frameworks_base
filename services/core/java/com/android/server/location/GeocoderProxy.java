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

import android.annotation.Nullable;
import android.content.Context;
import android.location.GeocoderParams;
import android.location.IGeocodeListener;
import android.location.IGeocodeProvider;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.server.servicewatcher.CurrentUserServiceSupplier;
import com.android.server.servicewatcher.ServiceWatcher;

import java.util.Collections;

/**
 * Proxy for IGeocodeProvider implementations.
 *
 * @hide
 */
public class GeocoderProxy {

    private static final String SERVICE_ACTION = "com.android.location.service.GeocodeProvider";

    /**
     * Creates and registers this proxy. If no suitable service is available for the proxy, returns
     * null.
     */
    @Nullable
    public static GeocoderProxy createAndRegister(Context context) {
        GeocoderProxy proxy = new GeocoderProxy(context);
        if (proxy.register()) {
            return proxy;
        } else {
            return null;
        }
    }

    private final ServiceWatcher mServiceWatcher;

    private GeocoderProxy(Context context) {
        mServiceWatcher = ServiceWatcher.create(context, "GeocoderProxy",
                new CurrentUserServiceSupplier(context, SERVICE_ACTION,
                        com.android.internal.R.bool.config_enableGeocoderOverlay,
                        com.android.internal.R.string.config_geocoderProviderPackageName),
                null);
    }

    private boolean register() {
        boolean resolves = mServiceWatcher.checkServiceResolves();
        if (resolves) {
            mServiceWatcher.register();
        }
        return resolves;
    }

    /**
     * Geocodes stuff.
     */
    public void getFromLocation(double latitude, double longitude, int maxResults,
            GeocoderParams params, IGeocodeListener listener) {
        mServiceWatcher.runOnBinder(new ServiceWatcher.BinderOperation() {
            @Override
            public void run(IBinder binder) throws RemoteException {
                IGeocodeProvider provider = IGeocodeProvider.Stub.asInterface(binder);
                provider.getFromLocation(latitude, longitude, maxResults, params, listener);
            }

            @Override
            public void onError() {
                try {
                    listener.onResults("Service not Available", Collections.emptyList());
                } catch (RemoteException e) {
                    // ignore
                }
            }
        });
    }

    /**
     * Geocodes stuff.
     */
    public void getFromLocationName(String locationName,
            double lowerLeftLatitude, double lowerLeftLongitude,
            double upperRightLatitude, double upperRightLongitude, int maxResults,
            GeocoderParams params, IGeocodeListener listener) {
        mServiceWatcher.runOnBinder(new ServiceWatcher.BinderOperation() {
            @Override
            public void run(IBinder binder) throws RemoteException {
                IGeocodeProvider provider = IGeocodeProvider.Stub.asInterface(binder);
                provider.getFromLocationName(locationName, lowerLeftLatitude,
                        lowerLeftLongitude, upperRightLatitude, upperRightLongitude,
                        maxResults, params, listener);
            }

            @Override
            public void onError() {
                try {
                    listener.onResults("Service not Available", Collections.emptyList());
                } catch (RemoteException e) {
                    // ignore
                }
            }
        });
    }
}
