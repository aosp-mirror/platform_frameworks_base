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
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.location.ILocationProvider;
import com.android.internal.location.ILocationProviderManager;
import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ProviderRequest;
import com.android.server.FgThread;
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

    private static final int MAX_ADDITIONAL_PACKAGES = 2;

    /**
     * Creates and registers this proxy. If no suitable service is available for the proxy, returns
     * null.
     */
    @Nullable
    public static LocationProviderProxy createAndRegister(Context context, String action,
            int enableOverlayResId, int nonOverlayPackageResId) {
        LocationProviderProxy proxy = new LocationProviderProxy(context, action, enableOverlayResId,
                nonOverlayPackageResId);
        if (proxy.register()) {
            return proxy;
        } else {
            return null;
        }
    }

    private final ILocationProviderManager.Stub mManager = new ILocationProviderManager.Stub() {
        // executed on binder thread
        @Override
        public void onSetAdditionalProviderPackages(List<String> packageNames) {
            int maxCount = Math.min(MAX_ADDITIONAL_PACKAGES, packageNames.size());
            ArraySet<String> allPackages = new ArraySet<>(maxCount);
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

            // add the binder package
            ComponentName service = mServiceWatcher.getBoundService().component;
            if (service != null) {
                allPackages.add(service.getPackageName());
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

    private LocationProviderProxy(Context context, String action, int enableOverlayResId,
            int nonOverlayPackageResId) {
        super(context, FgThread.getExecutor());

        mServiceWatcher = new ServiceWatcher(context, FgThread.getHandler(), action, this::onBind,
                this::onUnbind, enableOverlayResId, nonOverlayPackageResId);

        mRequest = null;
    }

    private boolean register() {
        return mServiceWatcher.register();
    }

    private void onBind(IBinder binder) throws RemoteException {
        ILocationProvider provider = ILocationProvider.Stub.asInterface(binder);

        ComponentName service = mServiceWatcher.getBoundService().component;
        if (service != null) {
            setPackageNames(Collections.singleton(service.getPackageName()));
        }

        provider.setLocationProviderManager(mManager);

        if (mRequest != null) {
            provider.setRequest(mRequest, mRequest.workSource);
        }
    }

    private void onUnbind() {
        setState(State.EMPTY_STATE);
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
