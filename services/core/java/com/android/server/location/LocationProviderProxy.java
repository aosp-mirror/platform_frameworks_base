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

import static com.android.internal.util.ConcurrentUtils.DIRECT_EXECUTOR;

import android.annotation.Nullable;
import android.content.Context;
import android.location.Location;
import android.location.util.identity.CallerIdentity;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.location.ILocationProvider;
import com.android.internal.location.ILocationProviderManager;
import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ProviderRequest;
import com.android.server.ServiceWatcher;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Proxy for ILocationProvider implementations.
 */
public class LocationProviderProxy extends AbstractLocationProvider {

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

    final Object mLock = new Object();

    final Context mContext;
    final ServiceWatcher mServiceWatcher;

    @GuardedBy("mLock")
    Proxy mProxy;

    private volatile ProviderRequest mRequest;

    private LocationProviderProxy(Context context, String action, int enableOverlayResId,
            int nonOverlayPackageResId) {
        // safe to use direct executor since our locks are not acquired in a code path invoked by
        // our owning provider
        super(DIRECT_EXECUTOR);

        mContext = context;
        mServiceWatcher = new ServiceWatcher(context, action, this::onBind,
                this::onUnbind, enableOverlayResId, nonOverlayPackageResId);

        mProxy = null;
        mRequest = ProviderRequest.EMPTY_REQUEST;
    }

    private boolean register() {
        return mServiceWatcher.register();
    }

    private void onBind(IBinder binder) throws RemoteException {
        ILocationProvider provider = ILocationProvider.Stub.asInterface(binder);

        synchronized (mLock) {
            mProxy = new Proxy();
            provider.setLocationProviderManager(mProxy);

            ProviderRequest request = mRequest;
            if (!request.equals(ProviderRequest.EMPTY_REQUEST)) {
                provider.setRequest(request, request.workSource);
            }
        }
    }

    private void onUnbind() {
        synchronized (mLock) {
            mProxy = null;
            setState(State.EMPTY_STATE);
        }
    }

    @Override
    public void onSetRequest(ProviderRequest request) {
        mRequest = request;
        mServiceWatcher.runOnBinder(binder -> {
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
        mServiceWatcher.dump(fd, pw, args);
    }

    private static String guessPackageName(Context context, int uid) {
        String[] packageNames = context.getPackageManager().getPackagesForUid(uid);
        if (packageNames == null || packageNames.length == 0) {
            // illegal state exception will propagate back through binders
            throw new IllegalStateException(
                    "location provider from uid " + uid + " has no package information");
        } else {
            return packageNames[0];
        }
    }

    private class Proxy extends ILocationProviderManager.Stub {

        Proxy() {}

        // executed on binder thread
        @Override
        public void onSetIdentity(@Nullable String packageName, @Nullable String attributionTag) {
            synchronized (mLock) {
                if (mProxy != this) {
                    return;
                }

                CallerIdentity identity;
                if (packageName == null) {
                    packageName = guessPackageName(mContext, Binder.getCallingUid());
                    // unsafe is ok since the package is coming direct from the package manager here
                    identity = CallerIdentity.fromBinderUnsafe(packageName, attributionTag);
                } else {
                    identity = CallerIdentity.fromBinder(mContext, packageName, attributionTag);
                }

                setIdentity(identity);
            }
        }

        // executed on binder thread
        @Override
        public void onSetProperties(ProviderProperties properties) {
            synchronized (mLock) {
                if (mProxy != this) {
                    return;
                }

                // if no identity is set yet, set it now
                if (getIdentity() == null) {
                    String packageName = guessPackageName(mContext, Binder.getCallingUid());
                    // unsafe is ok since the package is coming direct from the package manager here
                    setIdentity(CallerIdentity.fromBinderUnsafe(packageName, null));
                }

                setProperties(properties);
            }
        }

        // executed on binder thread
        @Override
        public void onSetAllowed(boolean allowed) {
            synchronized (mLock) {
                if (mProxy != this) {
                    return;
                }

                setAllowed(allowed);
            }
        }

        // executed on binder thread
        @Override
        public void onReportLocation(Location location) {
            synchronized (mLock) {
                if (mProxy != this) {
                    return;
                }
                reportLocation(location);
            }
        }
    }
}
