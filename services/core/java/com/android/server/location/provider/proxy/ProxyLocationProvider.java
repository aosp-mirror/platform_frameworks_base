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

package com.android.server.location.provider.proxy;

import static com.android.internal.util.ConcurrentUtils.DIRECT_EXECUTOR;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.location.Location;
import android.location.LocationResult;
import android.location.provider.ILocationProvider;
import android.location.provider.ILocationProviderManager;
import android.location.provider.ProviderProperties;
import android.location.provider.ProviderRequest;
import android.location.util.identity.CallerIdentity;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArraySet;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;
import com.android.server.location.provider.AbstractLocationProvider;
import com.android.server.servicewatcher.ServiceWatcher;
import com.android.server.servicewatcher.ServiceWatcher.BoundService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Proxy for ILocationProvider implementations.
 */
public class ProxyLocationProvider extends AbstractLocationProvider {

    private static final String KEY_LOCATION_TAGS = "android:location_allow_listed_tags";
    private static final String LOCATION_TAGS_SEPARATOR = ";";

    /**
     * Creates and registers this proxy. If no suitable service is available for the proxy, returns
     * null.
     */
    @Nullable
    public static ProxyLocationProvider create(Context context, String action,
            int enableOverlayResId, int nonOverlayPackageResId) {
        ProxyLocationProvider proxy = new ProxyLocationProvider(context, action, enableOverlayResId,
                nonOverlayPackageResId);
        if (proxy.checkServiceResolves()) {
            return proxy;
        } else {
            return null;
        }
    }

    final Object mLock = new Object();

    final Context mContext;
    final ServiceWatcher mServiceWatcher;

    @GuardedBy("mLock")
    final ArrayList<Runnable> mFlushListeners = new ArrayList<>(0);

    @GuardedBy("mLock")
    Proxy mProxy;
    @GuardedBy("mLock")
    @Nullable ComponentName mService;

    private volatile ProviderRequest mRequest;

    private ProxyLocationProvider(Context context, String action, int enableOverlayResId,
            int nonOverlayPackageResId) {
        // safe to use direct executor since our locks are not acquired in a code path invoked by
        // our owning provider
        super(DIRECT_EXECUTOR, null, null, null);

        mContext = context;
        mServiceWatcher = new ServiceWatcher(context, action, this::onBind,
                this::onUnbind, enableOverlayResId, nonOverlayPackageResId);

        mProxy = null;
        mRequest = ProviderRequest.EMPTY_REQUEST;
    }

    private void updateLocationTagInfo(@NonNull BoundService boundService) {
        if (boundService.metadata != null) {
            final String tagsList = boundService.metadata.getString(KEY_LOCATION_TAGS);
            if (tagsList != null) {
                final String[] tags = tagsList.split(LOCATION_TAGS_SEPARATOR);
                setLocationTags(new ArraySet<>(tags));
            }
        }
    }

    private boolean checkServiceResolves() {
        return mServiceWatcher.checkServiceResolves();
    }

    private void onBind(IBinder binder, BoundService boundService) throws RemoteException {
        ILocationProvider provider = ILocationProvider.Stub.asInterface(binder);

        synchronized (mLock) {
            mProxy = new Proxy();
            mService = boundService.component;
            provider.setLocationProviderManager(mProxy);

            ProviderRequest request = mRequest;
            if (!request.equals(ProviderRequest.EMPTY_REQUEST)) {
                provider.setRequest(request);
            }

            updateLocationTagInfo(boundService);
        }
    }

    private void onUnbind() {
        Runnable[] flushListeners;
        synchronized (mLock) {
            mProxy = null;
            mService = null;
            setState(prevState -> State.EMPTY_STATE);
            flushListeners = mFlushListeners.toArray(new Runnable[0]);
            mFlushListeners.clear();
        }

        final int size = flushListeners.length;
        for (int i = 0; i < size; ++i) {
            flushListeners[i].run();
        }
    }

    @Override
    protected void onStart() {
        mServiceWatcher.register();
    }

    @Override
    protected void onStop() {
        mServiceWatcher.unregister();
    }

    @Override
    protected void onSetRequest(ProviderRequest request) {
        mRequest = request;
        mServiceWatcher.runOnBinder(binder -> {
            ILocationProvider provider = ILocationProvider.Stub.asInterface(binder);
            provider.setRequest(request);
        });
    }

    @Override
    protected void onFlush(Runnable callback) {
        mServiceWatcher.runOnBinder(new ServiceWatcher.BinderRunner() {
            @Override
            public void run(IBinder binder) throws RemoteException {
                ILocationProvider provider = ILocationProvider.Stub.asInterface(binder);

                // at first glance it would be more straightforward to pass the flush callback
                // through to the provider and allow it to be invoked directly. however, in this
                // case the binder calls 1) provider delivering flushed locations 2) provider
                // delivering flush complete, while correctly ordered within the provider, would
                // be invoked on different binder objects and thus would have no defined order
                // on the system server side. thus, we ensure that both (1) and (2) are invoked
                // on the same binder object (the ILocationProviderManager) and have a well
                // defined ordering, so that the flush callback will always happen after
                // location delivery.
                synchronized (mLock) {
                    mFlushListeners.add(callback);
                }
                provider.flush();
            }

            @Override
            public void onError() {
                synchronized (mLock) {
                    mFlushListeners.remove(callback);
                }
                callback.run();
            }
        });
    }

    @Override
    public void onExtraCommand(int uid, int pid, String command, Bundle extras) {
        mServiceWatcher.runOnBinder(binder -> {
            ILocationProvider provider = ILocationProvider.Stub.asInterface(binder);
            provider.sendExtraCommand(command, extras);
        });
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mServiceWatcher.dump(fd, pw, args);
    }

    private static String guessPackageName(Context context, int uid, String packageName) {
        String[] packageNames = context.getPackageManager().getPackagesForUid(uid);
        if (packageNames == null || packageNames.length == 0) {
            // illegal state exception will propagate back through binders
            throw new IllegalStateException(
                    "location provider from uid " + uid + " has no package information");
        } else if (ArrayUtils.contains(packageNames, packageName)) {
            return packageName;
        } else {
            return packageNames[0];
        }
    }

    private class Proxy extends ILocationProviderManager.Stub {

        Proxy() {}

        // executed on binder thread
        @Override
        public void onInitialize(boolean allowed, ProviderProperties properties,
                @Nullable String packageName, @Nullable String attributionTag) {
            synchronized (mLock) {
                if (mProxy != this) {
                    return;
                }

                CallerIdentity identity;
                if (packageName == null) {
                    packageName = guessPackageName(mContext, Binder.getCallingUid(),
                            Objects.requireNonNull(mService).getPackageName());
                    // unsafe is ok since the package is coming direct from the package manager here
                    identity = CallerIdentity.fromBinderUnsafe(packageName, attributionTag);
                } else {
                    identity = CallerIdentity.fromBinder(mContext, packageName, attributionTag);
                }

                setState(prevState -> prevState
                        .withAllowed(allowed)
                        .withProperties(properties)
                        .withIdentity(identity));
            }
        }

        // executed on binder thread
        @Override
        public void onSetProperties(ProviderProperties properties) {
            synchronized (mLock) {
                if (mProxy != this) {
                    return;
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

                reportLocation(LocationResult.wrap(location).validate());
            }
        }

        // executed on binder thread
        @Override
        public void onReportLocations(List<Location> locations) {
            synchronized (mLock) {
                if (mProxy != this) {
                    return;
                }

                reportLocation(LocationResult.wrap(locations).validate());
            }
        }

        // executed on binder thread
        @Override
        public void onFlushComplete() {
            Runnable callback = null;
            synchronized (mLock) {
                if (mProxy != this) {
                    return;
                }
                if (!mFlushListeners.isEmpty()) {
                    callback = mFlushListeners.remove(0);
                }
            }

            if (callback != null) {
                callback.run();
            }
        }
    }
}
