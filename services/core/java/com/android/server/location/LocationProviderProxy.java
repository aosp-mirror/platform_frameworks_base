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
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.location.ILocationProvider;
import com.android.internal.location.ILocationProviderManager;
import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ProviderRequest;
import com.android.server.FgThread;
import com.android.server.LocationManagerService;
import com.android.server.ServiceWatcher;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.List;

/**
 * Proxy for ILocationProvider implementations.
 */
public class LocationProviderProxy extends AbstractLocationProvider {

    private static final String TAG = "LocationProviderProxy";
    private static final boolean D = LocationManagerService.D;

    private static final int MAX_ADDITIONAL_PACKAGES = 2;

    private final ILocationProviderManager.Stub mManager = new ILocationProviderManager.Stub() {
        // executed on binder thread
        @Override
        public void onSetAdditionalProviderPackages(List<String> packageNames) {
            int maxCount = Math.min(MAX_ADDITIONAL_PACKAGES, packageNames.size()) + 1;
            ArraySet<String> allPackages = new ArraySet<>(maxCount);
            allPackages.add(mServiceWatcher.getCurrentPackageName());
            for (String packageName : packageNames) {
                if (packageNames.size() >= maxCount) {
                    return;
                }

                try {
                    mContext.getPackageManager().getPackageInfo(packageName, MATCH_SYSTEM_ONLY);
                    allPackages.add(packageName);
                } catch (PackageManager.NameNotFoundException e) {
                    Log.w(TAG, mServiceWatcher + " specified unknown additional provider package: "
                            + packageName);
                }
            }

            setPackageNames(allPackages);
        }

        // executed on binder thread
        @Override
        public void onSetEnabled(boolean enabled) {
            setEnabled(enabled);
        }

        // executed on binder thread
        @Override
        public void onSetProperties(ProviderProperties properties) {
            setProperties(properties);
        }

        // executed on binder thread
        @Override
        public void onReportLocation(Location location) {
            reportLocation(location);
        }
    };

    private final ServiceWatcher mServiceWatcher;

    @Nullable private ProviderRequest mRequest;

    /**
     * Creates a new LocationProviderProxy and immediately begins binding to the best applicable
     * service.
     */
    @Nullable
    public static LocationProviderProxy createAndBind(Context context, String action,
            int overlaySwitchResId, int defaultServicePackageNameResId,
            int initialPackageNamesResId) {
        LocationProviderProxy proxy = new LocationProviderProxy(context, FgThread.getHandler(),
                action, overlaySwitchResId, defaultServicePackageNameResId,
                initialPackageNamesResId);
        if (proxy.bind()) {
            return proxy;
        } else {
            return null;
        }
    }

    private LocationProviderProxy(Context context, Handler handler, String action,
            int overlaySwitchResId, int defaultServicePackageNameResId,
            int initialPackageNamesResId) {
        super(context, new HandlerExecutor(handler), Collections.emptySet());

        mServiceWatcher = new ServiceWatcher(context, TAG, action, overlaySwitchResId,
                defaultServicePackageNameResId, initialPackageNamesResId, handler) {

            @Override
            protected void onBind() {
                runOnBinder(LocationProviderProxy.this::initializeService);
            }

            @Override
            protected void onUnbind() {
                setState(State.EMPTY_STATE);
            }
        };

        mRequest = null;
    }

    private boolean bind() {
        return mServiceWatcher.start();
    }

    private void initializeService(IBinder binder) throws RemoteException {
        ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
        if (D) Log.d(TAG, "applying state to connected service " + mServiceWatcher);

        setPackageNames(Collections.singleton(mServiceWatcher.getCurrentPackageName()));

        service.setLocationProviderManager(mManager);

        if (mRequest != null) {
            service.setRequest(mRequest, mRequest.workSource);
        }
    }

    @Override
    public void onSetRequest(ProviderRequest request) {
        mServiceWatcher.runOnBinder(binder -> {
            mRequest = request;
            ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
            service.setRequest(request, request.workSource);
        });
    }

    @Override
    public void onExtraCommand(int uid, int pid, String command, Bundle extras) {
        mServiceWatcher.runOnBinder(binder -> {
            ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
            service.sendExtraCommand(command, extras);
        });
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("service=" + mServiceWatcher);
    }
}
