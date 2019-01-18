/*
 * Copyright (C) 2013 The Android Open Source Project
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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.hardware.location.GeofenceHardwareService;
import android.hardware.location.IGeofenceHardware;
import android.location.IFusedGeofenceHardware;
import android.location.IGeofenceProvider;
import android.location.IGpsGeofenceHardware;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.os.BackgroundThread;
import com.android.server.ServiceWatcher;

/**
 * @hide
 */
public final class GeofenceProxy {

    private static final String TAG = "GeofenceProxy";
    private static final String SERVICE_ACTION = "com.android.location.service.GeofenceProvider";

    private final Context mContext;
    private final ServiceWatcher mServiceWatcher;

    @Nullable
    private final IGpsGeofenceHardware mGpsGeofenceHardware;
    @Nullable
    private final IFusedGeofenceHardware mFusedGeofenceHardware;

    private volatile IGeofenceHardware mGeofenceHardware;

    private final ServiceWatcher.BinderRunner mUpdateGeofenceHardware = (binder) -> {
        IGeofenceProvider provider = IGeofenceProvider.Stub.asInterface(binder);
        try {
            provider.setGeofenceHardware(mGeofenceHardware);
        } catch (RemoteException e) {
            Log.w(TAG, e);
        }
    };

    public static GeofenceProxy createAndBind(Context context,
            int overlaySwitchResId, int defaultServicePackageNameResId,
            int initialPackageNamesResId, @Nullable IGpsGeofenceHardware gpsGeofence,
            @Nullable IFusedGeofenceHardware fusedGeofenceHardware) {
        GeofenceProxy proxy = new GeofenceProxy(context, overlaySwitchResId,
                defaultServicePackageNameResId, initialPackageNamesResId, gpsGeofence,
                fusedGeofenceHardware);

        if (proxy.bind()) {
            return proxy;
        } else {
            return null;
        }
    }

    private GeofenceProxy(Context context,
            int overlaySwitchResId, int defaultServicePackageNameResId,
            int initialPackageNamesResId, @Nullable IGpsGeofenceHardware gpsGeofence,
            @Nullable IFusedGeofenceHardware fusedGeofenceHardware) {
        mContext = context;
        mServiceWatcher = new ServiceWatcher(context, TAG, SERVICE_ACTION, overlaySwitchResId,
                defaultServicePackageNameResId, initialPackageNamesResId,
                BackgroundThread.getHandler()) {
            @Override
            protected void onBind() {
                runOnBinder(mUpdateGeofenceHardware);
            }
        };

        mGpsGeofenceHardware = gpsGeofence;
        mFusedGeofenceHardware = fusedGeofenceHardware;

        mGeofenceHardware = null;
    }

    private boolean bind() {
        if (mServiceWatcher.start()) {
            mContext.bindServiceAsUser(new Intent(mContext, GeofenceHardwareService.class),
                    new GeofenceProxyServiceConnection(), Context.BIND_AUTO_CREATE,
                    UserHandle.SYSTEM);
            return true;
        }

        return false;
    }

    private class GeofenceProxyServiceConnection implements ServiceConnection {

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            IGeofenceHardware geofenceHardware = IGeofenceHardware.Stub.asInterface(service);

            try {
                if (mGpsGeofenceHardware != null) {
                    geofenceHardware.setGpsGeofenceHardware(mGpsGeofenceHardware);
                }
                if (mFusedGeofenceHardware != null) {
                    geofenceHardware.setFusedGeofenceHardware(mFusedGeofenceHardware);
                }

                mGeofenceHardware = geofenceHardware;
                mServiceWatcher.runOnBinder(mUpdateGeofenceHardware);
            } catch (Exception e) {
                Log.w(TAG, e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mGeofenceHardware = null;
            mServiceWatcher.runOnBinder(mUpdateGeofenceHardware);
        }
    }
}
