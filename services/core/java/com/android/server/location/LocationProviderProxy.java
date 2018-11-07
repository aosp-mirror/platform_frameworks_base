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

import android.annotation.Nullable;
import android.content.Context;
import android.location.LocationProvider;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.WorkSource;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.location.ILocationProvider;
import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ProviderRequest;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.TransferPipe;
import com.android.server.LocationManagerService;
import com.android.server.ServiceWatcher;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Proxy for ILocationProvider implementations.
 */
public class LocationProviderProxy implements LocationProviderInterface {
    private static final String TAG = "LocationProviderProxy";
    private static final boolean D = LocationManagerService.D;

    private final ServiceWatcher mServiceWatcher;

    private final String mName;

    // used to ensure that updates to mRequest and mWorkSource are atomic
    private final Object mRequestLock = new Object();


    private volatile boolean mEnabled = false;
    @Nullable
    private volatile ProviderProperties mProperties;

    @GuardedBy("mRequestLock")
    @Nullable
    private ProviderRequest mRequest;
    @GuardedBy("mRequestLock")
    private WorkSource mWorkSource;

    /**
     * Creates a new LocationProviderProxy and immediately begins binding to the best applicable
     * service.
     */
    @Nullable
    public static LocationProviderProxy createAndBind(
            Context context, String name, String action,
            int overlaySwitchResId, int defaultServicePackageNameResId,
            int initialPackageNamesResId) {
        LocationProviderProxy proxy = new LocationProviderProxy(context, name,
                action, overlaySwitchResId, defaultServicePackageNameResId,
                initialPackageNamesResId);
        if (proxy.bind()) {
            return proxy;
        } else {
            return null;
        }
    }

    private LocationProviderProxy(Context context, String name,
            String action, int overlaySwitchResId, int defaultServicePackageNameResId,
            int initialPackageNamesResId) {

        mServiceWatcher = new ServiceWatcher(context, TAG, action, overlaySwitchResId,
                defaultServicePackageNameResId, initialPackageNamesResId,
                BackgroundThread.getHandler()) {
            @Override
            protected void onBind() {
                runOnBinder(LocationProviderProxy.this::initializeService);
            }
        };
        mName = name;

        mProperties = null;
        mRequest = null;
        mWorkSource = new WorkSource();
    }

    private boolean bind() {
        return mServiceWatcher.start();
    }

    private void initializeService(IBinder binder) {
        ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
        if (D) Log.d(TAG, "applying state to connected service");

        ProviderProperties[] properties = new ProviderProperties[1];
        ProviderRequest request;
        WorkSource source;
        synchronized (mRequestLock) {
            request = mRequest;
            source = mWorkSource;
        }

        try {
            // load properties from provider
            properties[0] = service.getProperties();
            if (properties[0] == null) {
                Log.e(TAG, mServiceWatcher.getCurrentPackageName()
                        + " has invalid location provider properties");
            }

            // apply current state to new service
            if (mEnabled) {
                service.enable();
                if (request != null) {
                    service.setRequest(request, source);
                }
            }
        } catch (RemoteException e) {
            Log.w(TAG, e);
        }

        mProperties = properties[0];
    }

    public String getConnectedPackageName() {
        return mServiceWatcher.getCurrentPackageName();
    }

    @Override
    public String getName() {
        return mName;
    }

    @Override
    public ProviderProperties getProperties() {
        return mProperties;
    }

    @Override
    public void enable() {
        mEnabled = true;
        mServiceWatcher.runOnBinder(binder -> {
            ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
            try {
                service.enable();
            } catch (RemoteException e) {
                Log.w(TAG, e);
            }
        });
    }

    @Override
    public void disable() {
        mEnabled = false;
        mServiceWatcher.runOnBinder(binder -> {
            ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
            try {
                service.disable();
            } catch (RemoteException e) {
                Log.w(TAG, e);
            }
        });
    }

    @Override
    public boolean isEnabled() {
        return mEnabled;
    }

    @Override
    public void setRequest(ProviderRequest request, WorkSource source) {
        synchronized (mRequestLock) {
            mRequest = request;
            mWorkSource = source;
        }
        mServiceWatcher.runOnBinder(binder -> {
            ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
            try {
                service.setRequest(request, source);
            } catch (RemoteException e) {
                Log.w(TAG, e);
            }
        });
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.append("REMOTE SERVICE");
        pw.append(" name=").append(mName);
        pw.append(" pkg=").append(mServiceWatcher.getCurrentPackageName());
        pw.append(" version=").append(Integer.toString(mServiceWatcher.getCurrentPackageVersion()));
        pw.append('\n');
        mServiceWatcher.runOnBinder(binder -> {
            ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
            try {
                TransferPipe.dumpAsync(service.asBinder(), fd, args);
            } catch (IOException | RemoteException e) {
                pw.println("Failed to dump location provider: " + e);
            }
        });
    }

    @Override
    public int getStatus(Bundle extras) {
        int[] result = new int[]{LocationProvider.TEMPORARILY_UNAVAILABLE};
        mServiceWatcher.runOnBinder(binder -> {
            ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
            try {
                result[0] = service.getStatus(extras);
            } catch (RemoteException e) {
                Log.w(TAG, e);
            }
        });
        return result[0];
    }

    @Override
    public long getStatusUpdateTime() {
        long[] result = new long[]{0L};
        mServiceWatcher.runOnBinder(binder -> {
            ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
            try {
                result[0] = service.getStatusUpdateTime();
            } catch (RemoteException e) {
                Log.w(TAG, e);
            }
        });
        return result[0];
    }

    @Override
    public boolean sendExtraCommand(String command, Bundle extras) {
        boolean[] result = new boolean[]{false};
        mServiceWatcher.runOnBinder(binder -> {
            ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
            try {
                result[0] = service.sendExtraCommand(command, extras);
            } catch (RemoteException e) {
                Log.w(TAG, e);
            }
        });
        return result[0];
    }
}
