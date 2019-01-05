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
import android.location.Location;
import android.location.LocationProvider;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.WorkSource;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.location.ILocationProvider;
import com.android.internal.location.ILocationProviderManager;
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
public class LocationProviderProxy extends AbstractLocationProvider {

    private static final String TAG = "LocationProviderProxy";
    private static final boolean D = LocationManagerService.D;

    // used to ensure that updates to mRequest and mWorkSource are atomic
    private final Object mRequestLock = new Object();

    private final ServiceWatcher mServiceWatcher;

    private final ILocationProviderManager.Stub mManager = new ILocationProviderManager.Stub() {
        // executed on binder thread
        @Override
        public void onSetEnabled(boolean enabled) {
            LocationProviderProxy.this.setEnabled(enabled);
        }

        // executed on binder thread
        @Override
        public void onSetProperties(ProviderProperties properties) {
            LocationProviderProxy.this.setProperties(properties);
        }

        // executed on binder thread
        @Override
        public void onReportLocation(Location location) {
            LocationProviderProxy.this.reportLocation(location);
        }
    };

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
            Context context, LocationProviderManager locationProviderManager, String action,
            int overlaySwitchResId, int defaultServicePackageNameResId,
            int initialPackageNamesResId) {
        LocationProviderProxy proxy = new LocationProviderProxy(context, locationProviderManager,
                action, overlaySwitchResId, defaultServicePackageNameResId,
                initialPackageNamesResId);
        if (proxy.bind()) {
            return proxy;
        } else {
            return null;
        }
    }

    private LocationProviderProxy(Context context, LocationProviderManager locationProviderManager,
            String action, int overlaySwitchResId, int defaultServicePackageNameResId,
            int initialPackageNamesResId) {
        super(locationProviderManager);

        mServiceWatcher = new ServiceWatcher(context, TAG, action, overlaySwitchResId,
                defaultServicePackageNameResId, initialPackageNamesResId,
                BackgroundThread.getHandler()) {

            @Override
            protected void onBind() {
                runOnBinder(LocationProviderProxy.this::initializeService);
            }

            @Override
            protected void onUnbind() {
                setEnabled(false);
                setProperties(null);
            }
        };

        mRequest = null;
        mWorkSource = new WorkSource();
    }

    private boolean bind() {
        return mServiceWatcher.start();
    }

    private void initializeService(IBinder binder) {
        ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
        if (D) Log.d(TAG, "applying state to connected service " + mServiceWatcher);

        try {
            service.setLocationProviderManager(mManager);

            synchronized (mRequestLock) {
                if (mRequest != null) {
                    service.setRequest(mRequest, mWorkSource);
                }
            }
        } catch (RemoteException e) {
            Log.w(TAG, e);
        }
    }

    @Nullable
    public String getConnectedPackageName() {
        return mServiceWatcher.getCurrentPackageName();
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
        pw.println(" service=" + mServiceWatcher);
        mServiceWatcher.runOnBinder(binder -> {
            try {
                TransferPipe.dumpAsync(binder, fd, args);
            } catch (IOException | RemoteException e) {
                pw.println(" failed to dump location provider: " + e);
            }
        });
    }

    @Override
    public int getStatus(Bundle extras) {
        int[] status = new int[] {LocationProvider.TEMPORARILY_UNAVAILABLE};
        mServiceWatcher.runOnBinder(binder -> {
            ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
            try {
                status[0] = service.getStatus(extras);
            } catch (RemoteException e) {
                Log.w(TAG, e);
            }
        });
        return status[0];
    }

    @Override
    public long getStatusUpdateTime() {
        long[] updateTime = new long[] {0L};
        mServiceWatcher.runOnBinder(binder -> {
            ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
            try {
                updateTime[0] = service.getStatusUpdateTime();
            } catch (RemoteException e) {
                Log.w(TAG, e);
            }
        });
        return updateTime[0];
    }

    @Override
    public void sendExtraCommand(String command, Bundle extras) {
        mServiceWatcher.runOnBinder(binder -> {
            ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
            try {
                service.sendExtraCommand(command, extras);
            } catch (RemoteException e) {
                Log.w(TAG, e);
            }
        });
    }
}
