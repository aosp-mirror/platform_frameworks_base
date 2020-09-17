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
import android.location.Address;
import android.location.GeocoderParams;
import android.location.IGeocodeProvider;

import com.android.internal.os.BackgroundThread;
import com.android.server.ServiceWatcher;

import java.util.List;

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
        mServiceWatcher = new ServiceWatcher(context, BackgroundThread.getHandler(), SERVICE_ACTION,
                null, null,
                com.android.internal.R.bool.config_enableGeocoderOverlay,
                com.android.internal.R.string.config_geocoderProviderPackageName);
    }

    private boolean register() {
        return mServiceWatcher.register();
    }

    public String getFromLocation(double latitude, double longitude, int maxResults,
            GeocoderParams params, List<Address> addrs) {
        return mServiceWatcher.runOnBinderBlocking(binder -> {
            IGeocodeProvider provider = IGeocodeProvider.Stub.asInterface(binder);
            return provider.getFromLocation(latitude, longitude, maxResults, params, addrs);
        }, "Service not Available");
    }

    public String getFromLocationName(String locationName,
            double lowerLeftLatitude, double lowerLeftLongitude,
            double upperRightLatitude, double upperRightLongitude, int maxResults,
            GeocoderParams params, List<Address> addrs) {
        return mServiceWatcher.runOnBinderBlocking(binder -> {
            IGeocodeProvider provider = IGeocodeProvider.Stub.asInterface(binder);
            return provider.getFromLocationName(locationName, lowerLeftLatitude,
                    lowerLeftLongitude, upperRightLatitude, upperRightLongitude,
                    maxResults, params, addrs);
        }, "Service not Available");
    }
}
