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
import android.location.Address;
import android.location.GeocoderParams;
import android.location.IGeocodeProvider;

import com.android.server.FgThread;
import com.android.server.ServiceWatcher;

import java.util.List;

/**
 * Proxy for IGeocodeProvider implementations.
 */
public class GeocoderProxy {
    private static final String TAG = "GeocoderProxy";

    private static final String SERVICE_ACTION = "com.android.location.service.GeocodeProvider";

    private final ServiceWatcher mServiceWatcher;

    public static GeocoderProxy createAndBind(Context context,
            int overlaySwitchResId, int defaultServicePackageNameResId,
            int initialPackageNamesResId) {
        GeocoderProxy proxy = new GeocoderProxy(context, overlaySwitchResId,
                defaultServicePackageNameResId, initialPackageNamesResId);
        if (proxy.bind()) {
            return proxy;
        } else {
            return null;
        }
    }

    private GeocoderProxy(Context context,
            int overlaySwitchResId, int defaultServicePackageNameResId,
            int initialPackageNamesResId) {
        mServiceWatcher = new ServiceWatcher(context, TAG, SERVICE_ACTION, overlaySwitchResId,
                defaultServicePackageNameResId, initialPackageNamesResId,
                FgThread.getHandler());
    }

    private boolean bind() {
        return mServiceWatcher.start();
    }

    public String getConnectedPackageName() {
        return mServiceWatcher.getCurrentPackageName();
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
