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

package android.location.util;

import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.content.Context;
import android.location.ILocationListener;
import android.location.ILocationManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationRequest;
import android.os.RemoteException;

import com.android.internal.listeners.ListenerTransportManager;
import com.android.internal.listeners.RequestListenerTransport;

import java.util.concurrent.Executor;

/**
 * Utility class for managing location listeners.
 *
 * @hide
 */
public class LocationListenerTransportManager extends
        ListenerTransportManager<LocationListenerTransportManager.LocationListenerTransport> {

    protected class LocationListenerTransport extends
            RequestListenerTransport<LocationRequest, LocationListener> {

        private final ILocationListener mBinderTransport;

        private final String mPackageName;
        @Nullable private final String mAttributionTag;
        private final String mListenerId;

        LocationListenerTransport(Context context, LocationRequest locationRequest,
                Executor executor, LocationListener locationListener) {
            super(locationRequest, executor, locationListener);

            mBinderTransport = new LocationListenerBinder(this);

            mPackageName = context.getPackageName();
            mAttributionTag = context.getAttributionTag();
            mListenerId = AppOpsManager.toReceiverId(locationListener);
        }

        ILocationListener getTransport() {
            return mBinderTransport;
        }

        String getPackageName() {
            return mPackageName;
        }

        String getAttributionTag() {
            return mAttributionTag;
        }

        String getListenerId() {
            return mListenerId;
        }

        public void onLocationChanged(Location location) {
            execute(listener -> {
                listener.onLocationChanged(location);
                try {
                    mService.locationCallbackFinished(mBinderTransport);
                } catch (RemoteException e) {
                    e.rethrowFromSystemServer();
                }
            });
        }

        public void onProviderEnabled(String provider) {
            execute(listener -> {
                listener.onProviderEnabled(provider);
                try {
                    mService.locationCallbackFinished(mBinderTransport);
                } catch (RemoteException e) {
                    e.rethrowFromSystemServer();
                }
            });
        }

        public void onProviderDisabled(String provider) {
            execute(listener -> {
                listener.onProviderDisabled(provider);
                try {
                    mService.locationCallbackFinished(mBinderTransport);
                } catch (RemoteException e) {
                    e.rethrowFromSystemServer();
                }
            });
        }

        public void onRemoved() {
            // must be executed on the same executor so callbacks cannot be reordered
            execute(listener -> removeTransport(listener, this));
        }
    }

    final ILocationManager mService;

    public LocationListenerTransportManager(ILocationManager service) {
        mService = service;
    }

    /** Adds the given listener. */
    public void addListener(Context context, LocationRequest locationRequest, Executor executor,
            LocationListener listener) {
        registerListener(listener,
                new LocationListenerTransport(context, locationRequest, executor, listener));
    }

    /** Removes the given listener. */
    public void removeListener(LocationListener listener) {
        unregisterListener(listener);
    }

    @Override
    protected void registerWithServer(LocationListenerTransport transport) throws RemoteException {
        mService.registerLocationListener(transport.getRequest(), transport.getTransport(),
                transport.getPackageName(), transport.getAttributionTag(),
                transport.getListenerId());
    }

    @Override
    protected void unregisterWithServer(LocationListenerTransport transport)
            throws RemoteException {
        mService.unregisterLocationListener(transport.getTransport());
    }

    private static class LocationListenerBinder extends ILocationListener.Stub {

        private final LocationListenerTransport mListener;

        LocationListenerBinder(LocationListenerTransport listener) {
            mListener = listener;
        }

        @Override
        public void onLocationChanged(Location location) {
            mListener.onLocationChanged(location);
        }

        @Override
        public void onProviderEnabled(String provider) {
            mListener.onProviderEnabled(provider);
        }

        @Override
        public void onProviderDisabled(String provider) {
            mListener.onProviderDisabled(provider);
        }

        @Override
        public void onRemoved() {
            mListener.onRemoved();
        }
    }
}
