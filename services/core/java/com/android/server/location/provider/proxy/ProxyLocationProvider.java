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
import static com.android.server.location.LocationManagerService.TAG;

import android.annotation.Nullable;
import android.content.Context;
import android.location.Location;
import android.location.LocationResult;
import android.location.provider.ILocationProvider;
import android.location.provider.ILocationProviderManager;
import android.location.provider.ProviderProperties;
import android.location.provider.ProviderRequest;
import android.location.util.identity.CallerIdentity;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.server.FgThread;
import com.android.server.location.provider.AbstractLocationProvider;
import com.android.server.servicewatcher.CurrentUserServiceSupplier;
import com.android.server.servicewatcher.CurrentUserServiceSupplier.BoundServiceInfo;
import com.android.server.servicewatcher.ServiceWatcher;
import com.android.server.servicewatcher.ServiceWatcher.ServiceListener;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Proxy for ILocationProvider implementations.
 */
public class ProxyLocationProvider extends AbstractLocationProvider implements
        ServiceListener<BoundServiceInfo> {

    private static final String EXTRA_LOCATION_TAGS = "android:location_allow_listed_tags";
    private static final String LOCATION_TAGS_SEPARATOR = ";";

    private static final long RESET_DELAY_MS = 10000;

    /**
     * Creates and registers this proxy. If no suitable service is available for the proxy, returns
     * null.
     */
    @Nullable
    public static ProxyLocationProvider create(Context context, String provider, String action,
            int enableOverlayResId, int nonOverlayPackageResId) {
        ProxyLocationProvider proxy = new ProxyLocationProvider(context, provider, action,
                enableOverlayResId, nonOverlayPackageResId);
        if (proxy.checkServiceResolves()) {
            return proxy;
        } else {
            return null;
        }
    }

    final Object mLock = new Object();

    final Context mContext;
    final ServiceWatcher mServiceWatcher;
    final String mName;

    @GuardedBy("mLock")
    final ArrayList<Runnable> mFlushListeners = new ArrayList<>(0);

    @GuardedBy("mLock")
    @Nullable Runnable mResetter;
    @GuardedBy("mLock")
    @Nullable Proxy mProxy;
    @GuardedBy("mLock")
    @Nullable BoundServiceInfo mBoundServiceInfo;

    private volatile ProviderRequest mRequest;

    private ProxyLocationProvider(Context context, String provider, String action,
            int enableOverlayResId, int nonOverlayPackageResId) {
        // safe to use direct executor since our locks are not acquired in a code path invoked by
        // our owning provider
        super(DIRECT_EXECUTOR, null, null, Collections.emptySet());

        mContext = context;
        mServiceWatcher = ServiceWatcher.create(context, provider,
                CurrentUserServiceSupplier.createFromConfig(context, action, enableOverlayResId,
                        nonOverlayPackageResId), this);
        mName = provider;

        mProxy = null;
        mRequest = ProviderRequest.EMPTY_REQUEST;
    }

    private boolean checkServiceResolves() {
        return mServiceWatcher.checkServiceResolves();
    }

    @Override
    public void onBind(IBinder binder, BoundServiceInfo boundServiceInfo) throws RemoteException {
        ILocationProvider provider = ILocationProvider.Stub.asInterface(binder);

        synchronized (mLock) {
            mProxy = new Proxy();
            mBoundServiceInfo = boundServiceInfo;

            provider.setLocationProviderManager(mProxy);

            ProviderRequest request = mRequest;
            if (!request.equals(ProviderRequest.EMPTY_REQUEST)) {
                provider.setRequest(request);
            }
        }
    }

    @Override
    public void onUnbind() {
        Runnable[] flushListeners;
        synchronized (mLock) {
            mProxy = null;
            mBoundServiceInfo = null;

            // we need to clear the state - but most disconnections are very temporary. we give a
            // grace period where we don't clear the state immediately so that transient
            // interruptions are not necessarily visible to downstream clients
            if (mResetter == null) {
                mResetter = new Runnable() {
                    @Override
                    public void run() {
                        synchronized (mLock) {
                            if (mResetter == this) {
                                setState(prevState -> State.EMPTY_STATE);
                            }
                        }
                    }
                };
                FgThread.getHandler().postDelayed(mResetter, RESET_DELAY_MS);
            }

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
        mServiceWatcher.runOnBinder(new ServiceWatcher.BinderOperation() {
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
            public void onError(Throwable t) {
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
        mServiceWatcher.dump(pw);
    }

    private class Proxy extends ILocationProviderManager.Stub {

        Proxy() {}

        // executed on binder thread
        @Override
        public void onInitialize(boolean allowed, ProviderProperties properties,
                @Nullable String attributionTag) {
            synchronized (mLock) {
                if (mProxy != this) {
                    return;
                }

                if (mResetter != null) {
                    FgThread.getHandler().removeCallbacks(mResetter);
                    mResetter = null;
                }

                // set extra attribution tags from manifest if necessary
                String[] attributionTags = new String[0];
                if (mBoundServiceInfo.getMetadata() != null) {
                    String tagsStr = mBoundServiceInfo.getMetadata().getString(EXTRA_LOCATION_TAGS);
                    if (!TextUtils.isEmpty(tagsStr)) {
                        attributionTags = tagsStr.split(LOCATION_TAGS_SEPARATOR);
                        Log.i(TAG, mName + " provider loaded extra attribution tags: "
                                + Arrays.toString(attributionTags));
                    }
                }
                ArraySet<String> extraAttributionTags = new ArraySet<>(attributionTags);

                // unsafe is ok since we trust the package name already
                CallerIdentity identity = CallerIdentity.fromBinderUnsafe(
                        mBoundServiceInfo.getComponentName().getPackageName(),
                        attributionTag);

                setState(prevState -> State.EMPTY_STATE
                        .withAllowed(allowed)
                        .withProperties(properties)
                        .withIdentity(identity)
                        .withExtraAttributionTags(extraAttributionTags));
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

                reportLocation(LocationResult.wrap(location));
            }
        }

        // executed on binder thread
        @Override
        public void onReportLocations(List<Location> locations) {
            synchronized (mLock) {
                if (mProxy != this) {
                    return;
                }
                reportLocation(LocationResult.wrap(locations));
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
