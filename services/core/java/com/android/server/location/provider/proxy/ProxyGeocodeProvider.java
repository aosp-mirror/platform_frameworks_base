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

package com.android.server.location.provider.proxy;

import android.annotation.Nullable;
import android.content.Context;
import android.location.provider.ForwardGeocodeRequest;
import android.location.provider.GeocodeProviderBase;
import android.location.provider.IGeocodeCallback;
import android.location.provider.IGeocodeProvider;
import android.location.provider.ReverseGeocodeRequest;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.server.servicewatcher.CurrentUserServiceSupplier;
import com.android.server.servicewatcher.ServiceWatcher;

/** Proxy for IGeocodeProvider implementations. */
public class ProxyGeocodeProvider {

    /**
     * Creates and registers this proxy. If no suitable service is available for the proxy, returns
     * null.
     */
    @Nullable
    public static ProxyGeocodeProvider createAndRegister(Context context) {
        ProxyGeocodeProvider proxy = new ProxyGeocodeProvider(context);
        if (proxy.register()) {
            return proxy;
        } else {
            return null;
        }
    }

    private final ServiceWatcher mServiceWatcher;

    private ProxyGeocodeProvider(Context context) {
        mServiceWatcher =
                ServiceWatcher.create(
                        context,
                        "GeocoderProxy",
                        CurrentUserServiceSupplier.createFromConfig(
                                context,
                                GeocodeProviderBase.ACTION_GEOCODE_PROVIDER,
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

    /** Reverse geocodes. */
    public void reverseGeocode(ReverseGeocodeRequest request, IGeocodeCallback callback) {
        mServiceWatcher.runOnBinder(
                new ServiceWatcher.BinderOperation() {
                    @Override
                    public void run(IBinder binder) throws RemoteException {
                        IGeocodeProvider.Stub.asInterface(binder).reverseGeocode(request, callback);
                    }

                    @Override
                    public void onError(Throwable t) {
                        try {
                            callback.onError(t.toString());
                        } catch (RemoteException e) {
                            // ignore
                        }
                    }
                });
    }

    /** Forward geocodes. */
    public void forwardGeocode(ForwardGeocodeRequest request, IGeocodeCallback callback) {
        mServiceWatcher.runOnBinder(
                new ServiceWatcher.BinderOperation() {
                    @Override
                    public void run(IBinder binder) throws RemoteException {
                        IGeocodeProvider.Stub.asInterface(binder).forwardGeocode(request, callback);
                    }

                    @Override
                    public void onError(Throwable t) {
                        try {
                            callback.onError(t.toString());
                        } catch (RemoteException e) {
                            // ignore
                        }
                    }
                });
    }
}
