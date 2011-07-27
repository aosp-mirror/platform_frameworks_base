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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.location.Address;
import android.location.GeocoderParams;
import android.location.IGeocodeProvider;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.util.List;

/**
 * A class for proxying IGeocodeProvider implementations.
 *
 * {@hide}
 */
public class GeocoderProxy {

    private static final String TAG = "GeocoderProxy";

    private final Context mContext;
    private final Intent mIntent;
    private final Object mMutex = new Object();  // synchronizes access to mServiceConnection
    private Connection mServiceConnection = new Connection();  // never null

    public GeocoderProxy(Context context, String serviceName) {
        mContext = context;
        mIntent = new Intent(serviceName);
        mContext.bindService(mIntent, mServiceConnection,
                Context.BIND_AUTO_CREATE | Context.BIND_NOT_FOREGROUND
                | Context.BIND_ALLOW_OOM_MANAGEMENT);
    }

    /**
     * When unbundled NetworkLocationService package is updated, we
     * need to unbind from the old version and re-bind to the new one.
     */
    public void reconnect() {
        synchronized (mMutex) {
            mContext.unbindService(mServiceConnection);
            mServiceConnection = new Connection();
            mContext.bindService(mIntent, mServiceConnection,
                    Context.BIND_AUTO_CREATE | Context.BIND_NOT_FOREGROUND
                    | Context.BIND_ALLOW_OOM_MANAGEMENT);
        }
    }

    private class Connection implements ServiceConnection {

        private IGeocodeProvider mProvider;

        public void onServiceConnected(ComponentName className, IBinder service) {
            synchronized (this) {
                mProvider = IGeocodeProvider.Stub.asInterface(service);
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            synchronized (this) {
                mProvider = null;
            }
        }

        public IGeocodeProvider getProvider() {
            synchronized (this) {
                return mProvider;
            }
        }
    }

    public String getFromLocation(double latitude, double longitude, int maxResults,
            GeocoderParams params, List<Address> addrs) {
        IGeocodeProvider provider;
        synchronized (mMutex) {
            provider = mServiceConnection.getProvider();
        }
        if (provider != null) {
            try {
                return provider.getFromLocation(latitude, longitude, maxResults,
                        params, addrs);
            } catch (RemoteException e) {
                Log.e(TAG, "getFromLocation failed", e);
            }
        }
        return "Service not Available";
    }

    public String getFromLocationName(String locationName,
            double lowerLeftLatitude, double lowerLeftLongitude,
            double upperRightLatitude, double upperRightLongitude, int maxResults,
            GeocoderParams params, List<Address> addrs) {
        IGeocodeProvider provider;
        synchronized (mMutex) {
            provider = mServiceConnection.getProvider();
        }
        if (provider != null) {
            try {
                return provider.getFromLocationName(locationName, lowerLeftLatitude,
                        lowerLeftLongitude, upperRightLatitude, upperRightLongitude,
                        maxResults, params, addrs);
            } catch (RemoteException e) {
                Log.e(TAG, "getFromLocationName failed", e);
            }
        }
        return "Service not Available";
    }
}
