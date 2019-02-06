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

import static android.content.pm.PackageManager.MATCH_SYSTEM_ONLY;

import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageManager;
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
import com.android.internal.os.TransferPipe;
import com.android.server.FgThread;
import com.android.server.LocationManagerService;
import com.android.server.ServiceWatcher;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Proxy for ILocationProvider implementations.
 */
public class LocationProviderProxy extends AbstractLocationProvider {

    private static final String TAG = "LocationProviderProxy";
    private static final boolean D = LocationManagerService.D;

    // used to ensure that updates to mProviderPackages are atomic
    private final Object mProviderPackagesLock = new Object();

    // used to ensure that updates to mRequest and mWorkSource are atomic
    private final Object mRequestLock = new Object();

    private final ILocationProviderManager.Stub mManager = new ILocationProviderManager.Stub() {
        // executed on binder thread
        @Override
        public void onSetAdditionalProviderPackages(List<String> packageNames) {
            LocationProviderProxy.this.onSetAdditionalProviderPackages(packageNames);
        }

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

    private final ServiceWatcher mServiceWatcher;

    @GuardedBy("mProviderPackagesLock")
    private final CopyOnWriteArrayList<String> mProviderPackages = new CopyOnWriteArrayList<>();

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
        super(context, locationProviderManager);

        mServiceWatcher = new ServiceWatcher(context, TAG, action, overlaySwitchResId,
                defaultServicePackageNameResId, initialPackageNamesResId,
                FgThread.getHandler()) {

            @Override
            protected void onBind() {
                runOnBinder(LocationProviderProxy.this::initializeService);
            }

            @Override
            protected void onUnbind() {
                resetProviderPackages(Collections.emptyList());
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

    private void initializeService(IBinder binder) throws RemoteException {
        ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
        if (D) Log.d(TAG, "applying state to connected service " + mServiceWatcher);

        resetProviderPackages(Collections.emptyList());

        service.setLocationProviderManager(mManager);

        synchronized (mRequestLock) {
            if (mRequest != null) {
                service.setRequest(mRequest, mWorkSource);
            }
        }
    }

    @Override
    public List<String> getProviderPackages() {
        synchronized (mProviderPackagesLock) {
            return mProviderPackages;
        }
    }

    @Override
    public void setRequest(ProviderRequest request, WorkSource source) {
        synchronized (mRequestLock) {
            mRequest = request;
            mWorkSource = source;
        }
        mServiceWatcher.runOnBinder(binder -> {
            ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
            service.setRequest(request, source);
        });
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("  service=" + mServiceWatcher);
        synchronized (mProviderPackagesLock) {
            if (mProviderPackages.size() > 1) {
                pw.println("  additional packages=" + mProviderPackages);
            }
        }
        mServiceWatcher.runOnBinderBlocking(binder -> {
            try {
                TransferPipe.dumpAsync(binder, fd, args);
            } catch (IOException | RemoteException e) {
                pw.println("  <failed to dump location provider: " + e + ">");
            }
            return null;
        }, null);
    }

    @Override
    public int getStatus(Bundle extras) {
        return mServiceWatcher.runOnBinderBlocking(binder -> {
            ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
            return service.getStatus(extras);
        }, LocationProvider.TEMPORARILY_UNAVAILABLE);
    }

    @Override
    public long getStatusUpdateTime() {
        return mServiceWatcher.runOnBinderBlocking(binder -> {
            ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
            return service.getStatusUpdateTime();
        }, 0L);
    }

    @Override
    public void sendExtraCommand(String command, Bundle extras) {
        mServiceWatcher.runOnBinder(binder -> {
            ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
            service.sendExtraCommand(command, extras);
        });
    }

    private void onSetAdditionalProviderPackages(List<String> packageNames) {
        resetProviderPackages(packageNames);
    }

    private void resetProviderPackages(List<String> additionalPackageNames) {
        ArrayList<String> permittedPackages = new ArrayList<>(additionalPackageNames.size());
        for (String packageName : additionalPackageNames) {
            try {
                mContext.getPackageManager().getPackageInfo(packageName, MATCH_SYSTEM_ONLY);
                permittedPackages.add(packageName);
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, mServiceWatcher + " specified unknown additional provider package: "
                        + packageName);
            }
        }

        synchronized (mProviderPackagesLock) {
            mProviderPackages.clear();
            String myPackage = mServiceWatcher.getCurrentPackageName();
            if (myPackage != null) {
                mProviderPackages.add(myPackage);
                mProviderPackages.addAll(permittedPackages);
            }
        }
    }
}
