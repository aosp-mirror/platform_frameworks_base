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

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;

import android.content.Context;
import android.location.LocationProvider;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.WorkSource;
import android.util.Log;

import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ILocationProvider;
import com.android.internal.location.ProviderRequest;
import com.android.internal.os.TransferPipe;
import com.android.server.LocationManagerService;
import com.android.server.ServiceWatcher;

/**
 * Proxy for ILocationProvider implementations.
 */
public class LocationProviderProxy implements LocationProviderInterface {
    private static final String TAG = "LocationProviderProxy";
    private static final boolean D = LocationManagerService.D;

    private final Context mContext;
    private final String mName;
    private final ServiceWatcher mServiceWatcher;

    private Object mLock = new Object();

    // cached values set by the location manager, synchronized on mLock
    private ProviderProperties mProperties;
    private boolean mEnabled = false;
    private ProviderRequest mRequest = null;
    private WorkSource mWorksource = new WorkSource();

    public static LocationProviderProxy createAndBind(
            Context context, String name, String action,
            int overlaySwitchResId, int defaultServicePackageNameResId,
            int initialPackageNamesResId, Handler handler) {
        LocationProviderProxy proxy = new LocationProviderProxy(context, name, action,
                overlaySwitchResId, defaultServicePackageNameResId, initialPackageNamesResId,
                handler);
        if (proxy.bind()) {
            return proxy;
        } else {
            return null;
        }
    }

    private LocationProviderProxy(Context context, String name, String action,
            int overlaySwitchResId, int defaultServicePackageNameResId,
            int initialPackageNamesResId, Handler handler) {
        mContext = context;
        mName = name;
        mServiceWatcher = new ServiceWatcher(mContext, TAG + "-" + name, action, overlaySwitchResId,
                defaultServicePackageNameResId, initialPackageNamesResId,
                mNewServiceWork, handler);
    }

    private boolean bind () {
        return mServiceWatcher.start();
    }

    private ILocationProvider getService() {
        return ILocationProvider.Stub.asInterface(mServiceWatcher.getBinder());
    }

    public String getConnectedPackageName() {
        return mServiceWatcher.getBestPackageName();
    }

    /**
     * Work to apply current state to a newly connected provider.
     * Remember we can switch the service that implements a providers
     * at run-time, so need to apply current state.
     */
    private Runnable mNewServiceWork = new Runnable() {
        @Override
        public void run() {
            if (D) Log.d(TAG, "applying state to connected service");

            boolean enabled;
            ProviderProperties properties = null;
            ProviderRequest request;
            WorkSource source;
            ILocationProvider service;
            synchronized (mLock) {
                enabled = mEnabled;
                request = mRequest;
                source = mWorksource;
                service = getService();
            }

            if (service == null) return;

            try {
                // load properties from provider
                properties = service.getProperties();
                if (properties == null) {
                    Log.e(TAG, mServiceWatcher.getBestPackageName() +
                            " has invalid locatino provider properties");
                }

                // apply current state to new service
                if (enabled) {
                    service.enable();
                    if (request != null) {
                        service.setRequest(request, source);
                    }
                }
            } catch (RemoteException e) {
                Log.w(TAG, e);
            } catch (Exception e) {
                // never let remote service crash system server
                Log.e(TAG, "Exception from " + mServiceWatcher.getBestPackageName(), e);
            }

            synchronized (mLock) {
                mProperties = properties;
            }
        }
    };

    @Override
    public String getName() {
        return mName;
    }

    @Override
    public ProviderProperties getProperties() {
        synchronized (mLock) {
            return mProperties;
        }
    }

    @Override
    public void enable() {
        synchronized (mLock) {
            mEnabled = true;
        }
        ILocationProvider service = getService();
        if (service == null) return;

        try {
            service.enable();
        } catch (RemoteException e) {
            Log.w(TAG, e);
        } catch (Exception e) {
            // never let remote service crash system server
            Log.e(TAG, "Exception from " + mServiceWatcher.getBestPackageName(), e);
        }
    }

    @Override
    public void disable() {
        synchronized (mLock) {
            mEnabled = false;
        }
        ILocationProvider service = getService();
        if (service == null) return;

        try {
            service.disable();
        } catch (RemoteException e) {
            Log.w(TAG, e);
        } catch (Exception e) {
            // never let remote service crash system server
            Log.e(TAG, "Exception from " + mServiceWatcher.getBestPackageName(), e);
        }
    }

    @Override
    public boolean isEnabled() {
        synchronized (mLock) {
            return mEnabled;
        }
    }

    @Override
    public void setRequest(ProviderRequest request, WorkSource source) {
        synchronized (mLock) {
            mRequest = request;
            mWorksource = source;
        }
        ILocationProvider service = getService();
        if (service == null) return;

        try {
            service.setRequest(request, source);
        } catch (RemoteException e) {
            Log.w(TAG, e);
        } catch (Exception e) {
            // never let remote service crash system server
            Log.e(TAG, "Exception from " + mServiceWatcher.getBestPackageName(), e);
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.append("REMOTE SERVICE");
        pw.append(" name=").append(mName);
        pw.append(" pkg=").append(mServiceWatcher.getBestPackageName());
        pw.append(" version=").append("" + mServiceWatcher.getBestVersion());
        pw.append('\n');

        ILocationProvider service = getService();
        if (service == null) {
            pw.println("service down (null)");
            return;
        }
        pw.flush();

        try {
            TransferPipe.dumpAsync(service.asBinder(), fd, args);
        } catch (IOException | RemoteException e) {
            pw.println("Failed to dump location provider: " + e);
        }
    }

    @Override
    public int getStatus(Bundle extras) {
        ILocationProvider service = getService();
        if (service == null) return LocationProvider.TEMPORARILY_UNAVAILABLE;

        try {
            return service.getStatus(extras);
        } catch (RemoteException e) {
            Log.w(TAG, e);
        } catch (Exception e) {
            // never let remote service crash system server
            Log.e(TAG, "Exception from " + mServiceWatcher.getBestPackageName(), e);
        }
        return LocationProvider.TEMPORARILY_UNAVAILABLE;
    }

    @Override
    public long getStatusUpdateTime() {
        ILocationProvider service = getService();
        if (service == null) return 0;

        try {
            return service.getStatusUpdateTime();
        } catch (RemoteException e) {
            Log.w(TAG, e);
        } catch (Exception e) {
            // never let remote service crash system server
            Log.e(TAG, "Exception from " + mServiceWatcher.getBestPackageName(), e);
        }
        return 0;
    }

    @Override
    public boolean sendExtraCommand(String command, Bundle extras) {
        ILocationProvider service = getService();
        if (service == null) return false;

        try {
            return service.sendExtraCommand(command, extras);
        } catch (RemoteException e) {
            Log.w(TAG, e);
        } catch (Exception e) {
            // never let remote service crash system server
            Log.e(TAG, "Exception from " + mServiceWatcher.getBestPackageName(), e);
        }
        return false;
    }
 }
