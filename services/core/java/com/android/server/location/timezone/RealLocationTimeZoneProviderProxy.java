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
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.service.timezone.ITimeZoneProvider;
import android.service.timezone.ITimeZoneProviderManager;
import android.service.timezone.TimeZoneProviderSuggestion;
import android.util.IndentingPrintWriter;

import com.android.internal.annotations.GuardedBy;
import com.android.server.ServiceWatcher;

import java.util.Objects;

/**
 * System server-side proxy for ITimeZoneProvider implementations, i.e. this provides the
 * system server object used to communicate with a remote {@link
 * android.service.timezone.TimeZoneProviderService} over Binder, which could be running in a
 * different process. As "remote" providers are bound / unbound this proxy will rebind to the "best"
 * available remote process.
 */
class RealLocationTimeZoneProviderProxy extends LocationTimeZoneProviderProxy {

    @NonNull private final ServiceWatcher mServiceWatcher;

    @GuardedBy("mSharedLock")
    @Nullable private ManagerProxy mManagerProxy;

    @GuardedBy("mSharedLock")
    @NonNull private TimeZoneProviderRequest mRequest;

    RealLocationTimeZoneProviderProxy(
            @NonNull Context context, @NonNull Handler handler,
            @NonNull ThreadingDomain threadingDomain, @NonNull String action,
            int enableOverlayResId, int nonOverlayPackageResId) {
        super(context, threadingDomain);
        mManagerProxy = null;
        mRequest = TimeZoneProviderRequest.createStopUpdatesRequest();
        mServiceWatcher = new ServiceWatcher(context, handler, action, this::onBind, this::onUnbind,
                enableOverlayResId, nonOverlayPackageResId);
    }

    @Override
    void onInitialize() {
        if (!register()) {
            throw new IllegalStateException("Unable to register binder proxy");
        }
    }

    private boolean register() {
        boolean resolves = mServiceWatcher.checkServiceResolves();
        if (resolves) {
            mServiceWatcher.register();
        }
        return resolves;
    }

    private void onBind(IBinder binder, ComponentName componentName) {
        mThreadingDomain.assertCurrentThread();

        ITimeZoneProvider provider = ITimeZoneProvider.Stub.asInterface(binder);

        synchronized (mSharedLock) {
            try {
                mManagerProxy = new ManagerProxy();
                provider.setTimeZoneProviderManager(mManagerProxy);
                trySendCurrentRequest();
                mListener.onProviderBound();
            } catch (RemoteException e) {
                // This is not expected to happen.
                throw new RuntimeException(e);
            }
        }
    }

    private void onUnbind() {
        mThreadingDomain.assertCurrentThread();

        synchronized (mSharedLock) {
            mManagerProxy = null;
            mListener.onProviderUnbound();
        }
    }

    @Override
    final void setRequest(@NonNull TimeZoneProviderRequest request) {
        mThreadingDomain.assertCurrentThread();

        Objects.requireNonNull(request);
        synchronized (mSharedLock) {
            mRequest = request;

            trySendCurrentRequest();
        }
    }

    @GuardedBy("mSharedLock")
    private void trySendCurrentRequest() {
        TimeZoneProviderRequest request = mRequest;
        mServiceWatcher.runOnBinder(binder -> {
            ITimeZoneProvider service = ITimeZoneProvider.Stub.asInterface(binder);
            if (request.sendUpdates()) {
                service.startUpdates(request.getInitializationTimeout().toMillis());
            } else {
                service.stopUpdates();
            }
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
     * A system Server-side proxy for the ITimeZoneProviderManager, i.e. this is a local binder stub
     * Each "remote" TimeZoneProvider is passed a binder instance that it then uses to communicate
     * back with the system server, invoking the logic here.
     */
    private class ManagerProxy extends ITimeZoneProviderManager.Stub {

        // executed on binder thread
        @Override
        public void onTimeZoneProviderSuggestion(TimeZoneProviderSuggestion suggestion) {
            onTimeZoneProviderEvent(TimeZoneProviderEvent.createSuggestionEvent(suggestion));
        }

        // executed on binder thread
        @Override
        public void onTimeZoneProviderUncertain() {
            onTimeZoneProviderEvent(TimeZoneProviderEvent.createUncertainEvent());

        }

        // executed on binder thread
        @Override
        public void onTimeZoneProviderPermanentFailure(String failureReason) {
            onTimeZoneProviderEvent(
                    TimeZoneProviderEvent.createPermanentFailureEvent(failureReason));
        }

        private void onTimeZoneProviderEvent(TimeZoneProviderEvent event) {
            synchronized (mSharedLock) {
                if (mManagerProxy != this) {
                    return;
                }
            }
            handleTimeZoneProviderEvent(event);
        }
    }
}
