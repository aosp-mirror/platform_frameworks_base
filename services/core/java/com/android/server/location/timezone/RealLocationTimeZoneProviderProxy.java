/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location.timezone;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.location.timezone.LocationTimeZoneEvent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.IndentingPrintWriter;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.location.timezone.ILocationTimeZoneProvider;
import com.android.internal.location.timezone.ILocationTimeZoneProviderManager;
import com.android.internal.location.timezone.LocationTimeZoneProviderRequest;
import com.android.server.ServiceWatcher;

import java.util.Objects;

/**
 * System server-side proxy for ILocationTimeZoneProvider implementations, i.e. this provides the
 * system server object used to communicate with a remote LocationTimeZoneProvider over Binder,
 * which could be running in a different process. As "remote" LocationTimeZoneProviders are bound /
 * unbound this proxy will rebind to the "best" available remote process.
 */
class RealLocationTimeZoneProviderProxy extends LocationTimeZoneProviderProxy {

    /**
     * Creates and registers this proxy. If no suitable service is available for the proxy, returns
     * null.
     */
    @Nullable
    static LocationTimeZoneProviderProxy createAndRegister(
            @NonNull Context context, @NonNull ThreadingDomain threadingDomain,
            @NonNull String action, int enableOverlayResId, int nonOverlayPackageResId) {
        RealLocationTimeZoneProviderProxy proxy = new RealLocationTimeZoneProviderProxy(
                context, threadingDomain, action, enableOverlayResId, nonOverlayPackageResId);
        if (proxy.register()) {
            return proxy;
        } else {
            return null;
        }
    }

    @NonNull private final ServiceWatcher mServiceWatcher;

    @GuardedBy("mProxyLock")
    @Nullable private ManagerProxy mManagerProxy;

    @GuardedBy("mProxyLock")
    @NonNull private LocationTimeZoneProviderRequest mRequest;

    private RealLocationTimeZoneProviderProxy(
            @NonNull Context context, @NonNull ThreadingDomain threadingDomain,
            @NonNull String action, int enableOverlayResId,
            int nonOverlayPackageResId) {
        super(context, threadingDomain);
        mManagerProxy = null;
        mRequest = LocationTimeZoneProviderRequest.EMPTY_REQUEST;
        mServiceWatcher = new ServiceWatcher(context, action, this::onBind, this::onUnbind,
                enableOverlayResId, nonOverlayPackageResId);
    }

    private boolean register() {
        boolean resolves = mServiceWatcher.checkServiceResolves();
        if (resolves) {
            mServiceWatcher.register();
        }
        return resolves;
    }

    private void onBind(IBinder binder, ComponentName componentName) {
        processServiceWatcherCallbackOnThreadingDomainThread(() -> onBindOnHandlerThread(binder));
    }

    private void onUnbind() {
        processServiceWatcherCallbackOnThreadingDomainThread(this::onUnbindOnHandlerThread);
    }

    private void processServiceWatcherCallbackOnThreadingDomainThread(@NonNull Runnable runnable) {
        // For simplicity, this code just post()s the runnable to the mThreadingDomain Thread in all
        // cases. This adds a delay if ServiceWatcher and ThreadingDomain happen to be using the
        // same thread, but nothing here should be performance critical.
        mThreadingDomain.post(runnable);
    }

    private void onBindOnHandlerThread(@NonNull IBinder binder) {
        mThreadingDomain.assertCurrentThread();

        ILocationTimeZoneProvider provider = ILocationTimeZoneProvider.Stub.asInterface(binder);

        synchronized (mSharedLock) {
            try {
                mManagerProxy = new ManagerProxy();
                provider.setLocationTimeZoneProviderManager(mManagerProxy);
                trySendCurrentRequest();
                mListener.onProviderBound();
            } catch (RemoteException e) {
                // This is not expected to happen.
                throw new RuntimeException(e);
            }
        }
    }

    private void onUnbindOnHandlerThread() {
        mThreadingDomain.assertCurrentThread();

        synchronized (mSharedLock) {
            mManagerProxy = null;
            mListener.onProviderUnbound();
        }
    }

    @Override
    final void setRequest(@NonNull LocationTimeZoneProviderRequest request) {
        mThreadingDomain.assertCurrentThread();

        Objects.requireNonNull(request);
        synchronized (mSharedLock) {
            mRequest = request;

            trySendCurrentRequest();
        }
    }

    @GuardedBy("mProxyLock")
    private void trySendCurrentRequest() {
        LocationTimeZoneProviderRequest request = mRequest;
        mServiceWatcher.runOnBinder(binder -> {
            ILocationTimeZoneProvider service =
                    ILocationTimeZoneProvider.Stub.asInterface(binder);
            service.setRequest(request);
        });
    }

    @Override
    public void dump(@NonNull IndentingPrintWriter ipw, @Nullable String[] args) {
        synchronized (mSharedLock) {
            ipw.println("mRequest=" + mRequest);
            mServiceWatcher.dump(null, ipw, args);
        }
    }

    /**
     * A system Server-side proxy for the ILocationTimeZoneProviderManager, i.e. this is a local
     * binder stub. Each "remote" LocationTimeZoneProvider is passed a binder instance that it
     * then uses to communicate back with the system server, invoking the logic here.
     */
    private class ManagerProxy extends ILocationTimeZoneProviderManager.Stub {

        // executed on binder thread
        @Override
        public void onLocationTimeZoneEvent(LocationTimeZoneEvent locationTimeZoneEvent) {
            synchronized (mSharedLock) {
                if (mManagerProxy != this) {
                    return;
                }
            }
            handleLocationTimeZoneEvent(locationTimeZoneEvent);
        }
    }
}
