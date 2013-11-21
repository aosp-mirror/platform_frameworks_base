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

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.hardware.location.GeofenceHardwareService;
import android.hardware.location.IGeofenceHardware;
import android.location.IGeofenceProvider;
import android.location.IGpsGeofenceHardware;
import android.location.IFusedGeofenceHardware;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import com.android.server.ServiceWatcher;

/**
 * @hide
 */
public final class GeofenceProxy {
    private static final String TAG = "GeofenceProxy";
    private static final String SERVICE_ACTION =
            "com.android.location.service.GeofenceProvider";
    private final ServiceWatcher mServiceWatcher;
    private final Context mContext;
    private final IGpsGeofenceHardware mGpsGeofenceHardware;
    private final IFusedGeofenceHardware mFusedGeofenceHardware;

    private final Object mLock = new Object();

    // Access to mGeofenceHardware needs to be synchronized by mLock.
    private IGeofenceHardware mGeofenceHardware;

    private static final int GEOFENCE_PROVIDER_CONNECTED = 1;
    private static final int GEOFENCE_HARDWARE_CONNECTED = 2;
    private static final int GEOFENCE_HARDWARE_DISCONNECTED = 3;
    private static final int GEOFENCE_GPS_HARDWARE_CONNECTED = 4;
    private static final int GEOFENCE_GPS_HARDWARE_DISCONNECTED = 5;

    private Runnable mRunnable = new Runnable() {
        @Override
        public void run() {
            mHandler.sendEmptyMessage(GEOFENCE_PROVIDER_CONNECTED);
        }
    };

    public static GeofenceProxy createAndBind(Context context,
            int overlaySwitchResId, int defaultServicePackageNameResId,
            int initialPackageNamesResId, Handler handler, IGpsGeofenceHardware gpsGeofence,
            IFusedGeofenceHardware fusedGeofenceHardware) {
        GeofenceProxy proxy = new GeofenceProxy(context, overlaySwitchResId,
            defaultServicePackageNameResId, initialPackageNamesResId, handler, gpsGeofence,
            fusedGeofenceHardware);
        if (proxy.bindGeofenceProvider()) {
            return proxy;
        } else {
            return null;
        }
    }

    private GeofenceProxy(Context context,
            int overlaySwitchResId, int defaultServicePackageNameResId,
            int initialPackageNamesResId, Handler handler, IGpsGeofenceHardware gpsGeofence,
            IFusedGeofenceHardware fusedGeofenceHardware) {
        mContext = context;
        mServiceWatcher = new ServiceWatcher(context, TAG, SERVICE_ACTION, overlaySwitchResId,
            defaultServicePackageNameResId, initialPackageNamesResId, mRunnable, handler);
        mGpsGeofenceHardware = gpsGeofence;
        mFusedGeofenceHardware = fusedGeofenceHardware;
        bindHardwareGeofence();
    }

    private boolean bindGeofenceProvider() {
        return mServiceWatcher.start();
    }

    private void bindHardwareGeofence() {
        mContext.bindServiceAsUser(new Intent(mContext, GeofenceHardwareService.class),
                mServiceConnection, Context.BIND_AUTO_CREATE, UserHandle.OWNER);
    }

    private ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mLock) {
                mGeofenceHardware = IGeofenceHardware.Stub.asInterface(service);
                mHandler.sendEmptyMessage(GEOFENCE_HARDWARE_CONNECTED);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (mLock) {
                mGeofenceHardware = null;
                mHandler.sendEmptyMessage(GEOFENCE_HARDWARE_DISCONNECTED);
            }
        }
    };

    private void setGeofenceHardwareInProviderLocked() {
        try {
            IGeofenceProvider provider = IGeofenceProvider.Stub.asInterface(
                      mServiceWatcher.getBinder());
            if (provider != null) {
                provider.setGeofenceHardware(mGeofenceHardware);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Remote Exception: setGeofenceHardwareInProviderLocked: " + e);
        }
    }

    private void setGpsGeofenceLocked() {
        try {
            mGeofenceHardware.setGpsGeofenceHardware(mGpsGeofenceHardware);
        } catch (RemoteException e) {
            Log.e(TAG, "Error while connecting to GeofenceHardwareService");
        }
    }

    private void setFusedGeofenceLocked() {
        try {
            mGeofenceHardware.setFusedGeofenceHardware(mFusedGeofenceHardware);
        } catch(RemoteException e) {
            Log.e(TAG, "Error while connecting to GeofenceHardwareService");
        }
    }

    // This needs to be reworked, when more services get added,
    // Might need a state machine or add a framework utility class,
    private Handler mHandler = new Handler() {

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case GEOFENCE_PROVIDER_CONNECTED:
                    synchronized (mLock) {
                        if (mGeofenceHardware != null) {
                            setGeofenceHardwareInProviderLocked();
                        }
                        // else: the geofence provider will be notified when the connection to
                        // GeofenceHardwareService is established.
                    }
                    break;
                case GEOFENCE_HARDWARE_CONNECTED:
                    synchronized (mLock) {
                        // Theoretically this won't happen because once the GeofenceHardwareService
                        // is connected to, we won't lose connection to it because it's a system
                        // service. But this check does make the code more robust.
                        if (mGeofenceHardware != null) {
                            setGpsGeofenceLocked();
                            setFusedGeofenceLocked();
                            setGeofenceHardwareInProviderLocked();
                        }
                    }
                    break;
                case GEOFENCE_HARDWARE_DISCONNECTED:
                    synchronized (mLock) {
                        if (mGeofenceHardware == null) {
                            setGeofenceHardwareInProviderLocked();
                        }
                    }
                    break;
            }
        }
    };
}
