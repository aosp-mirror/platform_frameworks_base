/* Copyright (c) 2012-2013, The Linux Foundation. All rights reserved.
 *
 * Not a Contribution, Apache license notifications and license are retained
 * for attribution purposes only.
 *
 * Copyright (C) 2007 The Android Open Source Project
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
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.app.PendingIntent;
import android.location.IGeoFencer;
import android.location.IGeoFenceListener;
import android.location.GeoFenceParams;

/**
 * A class for proxying IGeoFenceProvider implementations.
 *
 * {@hide}
 */
public class GeoFencerProxy extends GeoFencerBase {

    private static final String TAG = "GeoFencerProxy";
    private static final boolean LOGV_ENABLED = true;

    private final Context mContext;
    private final Intent mIntent;
    private IGeoFencer mGeoFencer;

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            synchronized (this) {
                mGeoFencer = IGeoFencer.Stub.asInterface(service);
                notifyAll();
            }
            Log.v(TAG, "onServiceConnected: mGeoFencer - "+mGeoFencer);
        }
        public void onServiceDisconnected(ComponentName className) {
            synchronized (this) {
                mGeoFencer = null;
            }
            Log.v(TAG, "onServiceDisconnected");
        }
    };

    private final IGeoFenceListener.Stub mListener = new IGeoFenceListener.Stub() {
        @Override
        public void geoFenceExpired(PendingIntent intent) throws RemoteException {
            logv("geoFenceExpired - "+intent);
            remove(intent, true);
        }
    };

    private static GeoFencerProxy mGeoFencerProxy;
    public static GeoFencerProxy getGeoFencerProxy(Context context, String serviceName) {
        if (mGeoFencerProxy == null) {
            mGeoFencerProxy = new GeoFencerProxy(context, serviceName);
        }
        return mGeoFencerProxy;
    }

    private GeoFencerProxy(Context context, String serviceName) {
        mContext = context;
        mIntent = new Intent();
        mIntent.setPackage(serviceName);
        mContext.bindService(mIntent, mServiceConnection,
                Context.BIND_AUTO_CREATE | Context.BIND_NOT_FOREGROUND
                | Context.BIND_ALLOW_OOM_MANAGEMENT);
    }

    public void removeCaller(int uid) {
        super.removeCaller(uid);
        if(mGeoFencer != null) {
            try {
                mGeoFencer.clearGeoFenceUser(uid);
            } catch (RemoteException re) {
            }
        }
        else
            Log.e(TAG, "removeCaller - mGeoFencer is null");
    }

    private boolean ensureGeoFencer() {
        if (mGeoFencer == null) {
            try {
                synchronized(mServiceConnection) {
                    logv("waiting...");
                    mServiceConnection.wait(60000);
                    logv("woke up!!!");
                }
            } catch (InterruptedException ie) {
                Log.w(TAG, "Interrupted while waiting for GeoFencer");
                return false;
            }

            if (mGeoFencer == null) {
                Log.w(TAG, "Timed out. No GeoFencer connection");
                return false;
            }
        }

        return true;
    }

    protected boolean start(GeoFenceParams geofence) {
        if (ensureGeoFencer()) {
            try {
                return mGeoFencer.setGeoFence(mListener, geofence);
            } catch (RemoteException re) {
            }
        }
        return false;
    }

    protected boolean stop(PendingIntent intent) {
        if (ensureGeoFencer()) {
            try {
                mGeoFencer.clearGeoFence(mListener, intent);
                return true;
            } catch (RemoteException re) {
            }
        }
        return false;
    }

    private void logv(String s) {
        if (LOGV_ENABLED) Log.v(TAG, s);
    }
}
