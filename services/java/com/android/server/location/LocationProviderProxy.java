/*
 * Copyright (C) 2009 The Android Open Source Project
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
import android.location.ILocationProvider;
import android.location.Location;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import com.android.internal.location.DummyLocationProvider;

/**
 * A class for proxying location providers implemented as services.
 *
 * {@hide}
 */
public class LocationProviderProxy implements LocationProviderInterface {

    private static final String TAG = "LocationProviderProxy";

    private final Context mContext;
    private final String mName;
    private ILocationProvider mProvider;
    private Handler mHandler;
    private final Connection mServiceConnection = new Connection();

    // cached values set by the location manager
    private boolean mLocationTracking = false;
    private boolean mEnabled = false;
    private long mMinTime = -1;
    private int mNetworkState;
    private NetworkInfo mNetworkInfo;

    // for caching requiresNetwork, requiresSatellite, etc.
    private DummyLocationProvider mCachedAttributes;

    // constructor for proxying location providers implemented in a separate service
    public LocationProviderProxy(Context context, String name, String serviceName,
            Handler handler) {
        mContext = context;
        mName = name;
        mHandler = handler;
        mContext.bindService(new Intent(serviceName), mServiceConnection, Context.BIND_AUTO_CREATE);
    }

    private class Connection implements ServiceConnection {
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.d(TAG, "LocationProviderProxy.onServiceConnected " + className);
            synchronized (this) {
                mProvider = ILocationProvider.Stub.asInterface(service);
                if (mProvider != null) {
                    mHandler.post(mServiceConnectedTask);
                }
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            Log.d(TAG, "LocationProviderProxy.onServiceDisconnected " + className);
            synchronized (this) {
                mProvider = null;
            }
        }
    }

    private Runnable mServiceConnectedTask = new Runnable() {
        public void run() {
            ILocationProvider provider;
            synchronized (mServiceConnection) {
                provider = mProvider;
                if (provider == null) {
                    return;
                }
            }

            if (mCachedAttributes == null) {
                try {
                    mCachedAttributes = new DummyLocationProvider(mName);
                    mCachedAttributes.setRequiresNetwork(provider.requiresNetwork());
                    mCachedAttributes.setRequiresSatellite(provider.requiresSatellite());
                    mCachedAttributes.setRequiresCell(provider.requiresCell());
                    mCachedAttributes.setHasMonetaryCost(provider.hasMonetaryCost());
                    mCachedAttributes.setSupportsAltitude(provider.supportsAltitude());
                    mCachedAttributes.setSupportsSpeed(provider.supportsSpeed());
                    mCachedAttributes.setSupportsBearing(provider.supportsBearing());
                    mCachedAttributes.setPowerRequirement(provider.getPowerRequirement());
                    mCachedAttributes.setAccuracy(provider.getAccuracy());
                } catch (RemoteException e) {
                    mCachedAttributes = null;
                }
            }

            // resend previous values from the location manager if the service has restarted
            try {
                if (mEnabled) {
                    provider.enable();
                }
                if (mLocationTracking) {
                    provider.enableLocationTracking(true);
                }
                if (mMinTime >= 0) {
                    provider.setMinTime(mMinTime);
                }
                if (mNetworkInfo != null) {
                    provider.updateNetworkState(mNetworkState, mNetworkInfo);
                }
            } catch (RemoteException e) {
            }
        }
    };

    public String getName() {
        return mName;
    }

    public boolean requiresNetwork() {
        if (mCachedAttributes != null) {
            return mCachedAttributes.requiresNetwork();
        } else {
            return false;
        }
    }

    public boolean requiresSatellite() {
        if (mCachedAttributes != null) {
            return mCachedAttributes.requiresSatellite();
        } else {
            return false;
        }
    }

    public boolean requiresCell() {
        if (mCachedAttributes != null) {
            return mCachedAttributes.requiresCell();
        } else {
            return false;
        }
    }

    public boolean hasMonetaryCost() {
        if (mCachedAttributes != null) {
            return mCachedAttributes.hasMonetaryCost();
        } else {
            return false;
        }
    }

    public boolean supportsAltitude() {
        if (mCachedAttributes != null) {
            return mCachedAttributes.supportsAltitude();
        } else {
            return false;
        }
    }

    public boolean supportsSpeed() {
        if (mCachedAttributes != null) {
            return mCachedAttributes.supportsSpeed();
        } else {
            return false;
        }
    }

     public boolean supportsBearing() {
        if (mCachedAttributes != null) {
            return mCachedAttributes.supportsBearing();
        } else {
            return false;
        }
    }

    public int getPowerRequirement() {
        if (mCachedAttributes != null) {
            return mCachedAttributes.getPowerRequirement();
        } else {
            return -1;
        }
    }

    public int getAccuracy() {
        if (mCachedAttributes != null) {
            return mCachedAttributes.getAccuracy();
        } else {
            return -1;
        }
    }

    public void enable() {
        mEnabled = true;
        ILocationProvider provider;
        synchronized (mServiceConnection) {
            provider = mProvider;
        }
        if (provider != null) {
            try {
                provider.enable();
            } catch (RemoteException e) {
            }
        }
    }

    public void disable() {
        mEnabled = false;
        ILocationProvider provider;
        synchronized (mServiceConnection) {
            provider = mProvider;
        }
        if (provider != null) {
            try {
                provider.disable();
            } catch (RemoteException e) {
            }
        }
    }

    public boolean isEnabled() {
        return mEnabled;
    }

    public int getStatus(Bundle extras) {
        ILocationProvider provider;
        synchronized (mServiceConnection) {
            provider = mProvider;
        }
        if (provider != null) {
            try {
                return provider.getStatus(extras);
            } catch (RemoteException e) {
            }
        }
        return 0;
    }

    public long getStatusUpdateTime() {
         ILocationProvider provider;
        synchronized (mServiceConnection) {
            provider = mProvider;
        }
        if (provider != null) {
            try {
                return provider.getStatusUpdateTime();
            } catch (RemoteException e) {
            }
        }
        return 0;
     }

    public String getInternalState() {
        try {
            return mProvider.getInternalState();
        } catch (RemoteException e) {
            Log.e(TAG, "getInternalState failed", e);
            return null;
        }
    }

    public boolean isLocationTracking() {
        return mLocationTracking;
    }

    public void enableLocationTracking(boolean enable) {
        mLocationTracking = enable;
        if (!enable) {
            mMinTime = -1;
        }
        ILocationProvider provider;
        synchronized (mServiceConnection) {
            provider = mProvider;
        }
        if (provider != null) {
            try {
                provider.enableLocationTracking(enable);
            } catch (RemoteException e) {
            }
        }
    }

    public long getMinTime() {
        return mMinTime;
    }

    public void setMinTime(long minTime) {
       mMinTime = minTime;
        ILocationProvider provider;
        synchronized (mServiceConnection) {
            provider = mProvider;
        }
        if (provider != null) {
            try {
                provider.setMinTime(minTime);
            } catch (RemoteException e) {
            }
        }
    }

    public void updateNetworkState(int state, NetworkInfo info) {
        mNetworkState = state;
        mNetworkInfo = info;
        ILocationProvider provider;
        synchronized (mServiceConnection) {
            provider = mProvider;
        }
        if (provider != null) {
            try {
                provider.updateNetworkState(state, info);
            } catch (RemoteException e) {
            }
        }
    }

    public void updateLocation(Location location) {
        ILocationProvider provider;
        synchronized (mServiceConnection) {
            provider = mProvider;
        }
        if (provider != null) {
            try {
                provider.updateLocation(location);
            } catch (RemoteException e) {
            }
        }
    }

    public boolean sendExtraCommand(String command, Bundle extras) {
        ILocationProvider provider;
        synchronized (mServiceConnection) {
            provider = mProvider;
        }
        if (provider != null) {
            try {
                provider.sendExtraCommand(command, extras);
            } catch (RemoteException e) {
            }
        }
        return false;
    }

    public void addListener(int uid) {
        ILocationProvider provider;
        synchronized (mServiceConnection) {
            provider = mProvider;
        }
        if (provider != null) {
            try {
                provider.addListener(uid);
            } catch (RemoteException e) {
            }
        }
    }

    public void removeListener(int uid) {
        ILocationProvider provider;
        synchronized (mServiceConnection) {
            provider = mProvider;
        }
        if (provider != null) {
            try {
                provider.removeListener(uid);
            } catch (RemoteException e) {
            }
        }
    }
}
