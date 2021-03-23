/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.location.provider;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.location.ILocationManager;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.location.provider.ILocationProvider;
import android.location.provider.ILocationProviderManager;
import android.location.provider.ProviderProperties;
import android.location.provider.ProviderRequest;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.WorkSource;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class for location providers implemented as unbundled services.
 *
 * <p>The network location provider must export a service with action
 * "com.android.location.service.v2.NetworkLocationProvider"
 * and a valid minor version in a meta-data field on the service, and
 * then return the result of {@link #getBinder()} on service binding.
 *
 * <p>The fused location provider must export a service with action
 * "com.android.location.service.FusedLocationProvider"
 * and a valid minor version in a meta-data field on the service, and
 * then return the result of {@link #getBinder()} on service binding.
 *
 * <p>IMPORTANT: This class is effectively a public API for unbundled
 * applications, and must remain API stable. See README.txt in the root
 * of this package for more information.
 *
 * @deprecated This class is not part of the standard API surface - use
 * {@link android.location.provider.LocationProviderBase} instead.
 */
@Deprecated
public abstract class LocationProviderBase {

    /**
     * Callback to be invoked when a flush operation is complete and all flushed locations have been
     * reported.
     */
    protected interface OnFlushCompleteCallback {

        /**
         * Should be invoked once the flush is complete.
         */
        void onFlushComplete();
    }

    /**
     * Bundle key for a version of the location containing no GPS data.
     * Allows location providers to flag locations as being safe to
     * feed to LocationFudger.
     *
     * @deprecated Do not use from Android R onwards.
     */
    @Deprecated
    public static final String EXTRA_NO_GPS_LOCATION = Location.EXTRA_NO_GPS_LOCATION;

    /**
     * Name of the Fused location provider.
     *
     * <p>This provider combines inputs for all possible location sources
     * to provide the best possible Location fix.
     */
    public static final String FUSED_PROVIDER = LocationManager.FUSED_PROVIDER;

    final String mTag;
    @Nullable final String mAttributionTag;
    final IBinder mBinder;

    /**
     * This field may be removed in the future, do not rely on it.
     *
     * @deprecated Do not use this field! Use LocationManager APIs instead. If you use this field
     * you may be broken in the future.
     * @hide
     */
    @Deprecated
    protected final ILocationManager mLocationManager;

    // write locked on mBinder, read lock is optional depending on atomicity requirements
    @Nullable volatile ILocationProviderManager mManager;
    volatile ProviderProperties mProperties;
    volatile boolean mAllowed;

    /**
     * @deprecated Prefer
     * {@link #LocationProviderBase(Context, String, ProviderPropertiesUnbundled)}.
     */
    @Deprecated
    public LocationProviderBase(String tag, ProviderPropertiesUnbundled properties) {
        this(null, tag, properties);
    }

    /**
     * This constructor associates the feature id of the given context with this location provider.
     * The location service may afford special privileges to incoming calls identified as belonging
     * to this location provider.
     */
    @RequiresApi(VERSION_CODES.R)
    public LocationProviderBase(Context context, String tag,
            ProviderPropertiesUnbundled properties) {
        mTag = tag;
        mAttributionTag = context != null ? context.getAttributionTag() : null;
        mBinder = new Service();

        mLocationManager = ILocationManager.Stub.asInterface(
                ServiceManager.getService(Context.LOCATION_SERVICE));

        mManager = null;
        mProperties = properties.getProviderProperties();
        mAllowed = true;
    }

    public IBinder getBinder() {
        return mBinder;
    }

    /**
     * @deprecated Use {@link #setAllowed(boolean)} instead.
     */
    @Deprecated
    @RequiresApi(VERSION_CODES.Q)
    public void setEnabled(boolean enabled) {
        setAllowed(enabled);
    }

    /**
     * Sets whether this provider is currently allowed or not. Note that this is specific to the
     * provider only, and is not related to global location settings. This is a hint to the Location
     * Manager that this provider will generally be unable to fulfill incoming requests. This
     * provider may still receive callbacks to onSetRequest while not allowed, and must decide
     * whether to attempt to satisfy those requests or not.
     *
     * <p>Some guidelines: providers should set their own allowed/disallowed status based only on
     * state "owned" by that provider. For instance, providers should not take into account the
     * state of the location master setting when setting themselves allowed or disallowed, as this
     * state is not owned by a particular provider. If a provider requires some additional user
     * consent that is particular to the provider, this should be use to set the allowed/disallowed
     * state. If the provider proxies to another provider, the child provider's allowed/disallowed
     * state should be taken into account in the parent's allowed state. For most providers, it is
     * expected that they will be always allowed.
     */
    @RequiresApi(VERSION_CODES.R)
    public void setAllowed(boolean allowed) {
        synchronized (mBinder) {
            if (mAllowed == allowed) {
                return;
            }

            mAllowed = allowed;
        }

        ILocationProviderManager manager = mManager;
        if (manager != null) {
            try {
                manager.onSetAllowed(mAllowed);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            } catch (RuntimeException e) {
                Log.w(mTag, e);
            }
        }
    }

    /**
     * Sets the provider properties that may be queried by clients. Generally speaking, providers
     * should try to avoid changing their properties after construction.
     */
    @RequiresApi(VERSION_CODES.Q)
    public void setProperties(ProviderPropertiesUnbundled properties) {
        synchronized (mBinder) {
            mProperties = properties.getProviderProperties();
        }

        ILocationProviderManager manager = mManager;
        if (manager != null) {
            try {
                manager.onSetProperties(mProperties);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            } catch (RuntimeException e) {
                Log.w(mTag, e);
            }
        }
    }

    /**
     * Sets a list of additional packages that should be considered as part of this location
     * provider for the purposes of generating locations. This should generally only be used when
     * another package may issue location requests on behalf of this package in the course of
     * providing location. This will inform location services to treat the other packages as
     * location providers as well.
     *
     * @deprecated On Android R and above this has no effect.
     */
    @Deprecated
    @RequiresApi(VERSION_CODES.Q)
    public void setAdditionalProviderPackages(List<String> packageNames) {}

    /**
     * @deprecated Use {@link #isAllowed()} instead.
     */
    @Deprecated
    @RequiresApi(VERSION_CODES.Q)
    public boolean isEnabled() {
        return isAllowed();
    }

    /**
     * Returns true if this provider is allowed. Providers start as allowed on construction.
     */
    @RequiresApi(VERSION_CODES.R)
    public boolean isAllowed() {
        return mAllowed;
    }

    /**
     * Reports a new location from this provider.
     */
    public void reportLocation(@NonNull Location location) {
        ILocationProviderManager manager = mManager;
        if (manager != null) {
            try {
                manager.onReportLocation(stripExtras(location));
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            } catch (RuntimeException e) {
                Log.w(mTag, e);
            }
        }
    }

    /**
     * Reports a new batch of locations from this provider. Locations must be ordered in the list
     * from earliest first to latest last.
     */
    public void reportLocations(@NonNull List<Location> locations) {
        ILocationProviderManager manager = mManager;
        if (manager != null) {
            try {
                manager.onReportLocations(stripExtras(locations));
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            } catch (RuntimeException e) {
                Log.w(mTag, e);
            }
        }
    }

    protected void onInit() {
        // call once so that providers designed for APIs pre-Q are not broken
        onEnable();
    }

    /**
     * @deprecated This callback will be invoked once when the provider is created to maintain
     * backwards compatibility with providers not designed for Android Q and above. This method
     * should only be implemented in location providers that need to support SDKs below Android Q.
     * Even in this case, it is usually unnecessary to implement this callback with the correct
     * design. This method may be removed in the future.
     */
    @Deprecated
    protected void onEnable() {}

    /**
     * @deprecated This callback will be never be invoked on Android Q and above. This method should
     * only be implemented in location providers that need to support SDKs below Android Q. Even in
     * this case, it is usually unnecessary to implement this callback with the correct design. This
     * method may be removed in the future.
     */
    @Deprecated
    protected void onDisable() {}

    /**
     * Set the {@link ProviderRequest} requirements for this provider. Each call to this method
     * overrides all previous requests. This method might trigger the provider to start returning
     * locations, or to stop returning locations, depending on the parameters in the request.
     */
    protected abstract void onSetRequest(ProviderRequestUnbundled request, WorkSource source);

    /**
     * Requests a flush of any pending batched locations. The callback must always be invoked once
     * per invocation, and should be invoked after {@link #reportLocation(Location)} or
     * {@link #reportLocations(List)} has been invoked with any flushed locations. The callback may
     * be invoked immediately if no locations are flushed.
     */
    protected void onFlush(OnFlushCompleteCallback callback) {
        callback.onFlushComplete();
    }

    /**
     * @deprecated This callback will never be invoked on Android Q and above. This method may be
     * removed in the future. Prefer to dump provider state via the containing service instead.
     */
    @Deprecated
    protected void onDump(FileDescriptor fd, PrintWriter pw, String[] args) {}

    /**
     * This method will no longer be invoked.
     *
     * @deprecated This callback will be never be invoked on Android Q and above. This method should
     * only be implemented in location providers that need to support SDKs below Android Q. This
     * method may be removed in the future.
     */
    @Deprecated
    protected int onGetStatus(Bundle extras) {
        return LocationProvider.AVAILABLE;
    }

    /**
     * This method will no longer be invoked.
     *
     * @deprecated This callback will be never be invoked on Android Q and above. This method should
     * only be implemented in location providers that need to support SDKs below Android Q. This
     * method may be removed in the future.
     */
    @Deprecated
    protected long onGetStatusUpdateTime() {
        return 0;
    }

    /**
     * Implements location provider specific custom commands. The return value will be ignored on
     * Android Q and above.
     */
    protected boolean onSendExtraCommand(@Nullable String command, @Nullable Bundle extras) {
        return false;
    }

    private final class Service extends ILocationProvider.Stub {

        Service() {
        }

        @Override
        public void setLocationProviderManager(ILocationProviderManager manager) {
            synchronized (mBinder) {
                try {
                    manager.onInitialize(mAllowed, mProperties, mAttributionTag);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                } catch (RuntimeException e) {
                    Log.w(mTag, e);
                }

                mManager = manager;
            }

            onInit();
        }

        @Override
        public void setRequest(ProviderRequest request) {
            onSetRequest(new ProviderRequestUnbundled(request), request.getWorkSource());
        }

        @Override
        public void flush() {
            onFlush(this::onFlushComplete);
        }

        private void onFlushComplete() {
            ILocationProviderManager manager = mManager;
            if (manager != null) {
                try {
                    manager.onFlushComplete();
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                } catch (RuntimeException e) {
                    Log.w(mTag, e);
                }
            }
        }

        @Override
        public void sendExtraCommand(String command, Bundle extras) {
            onSendExtraCommand(command, extras);
        }
    }

    private static Location stripExtras(Location location) {
        Bundle extras = location.getExtras();
        if (extras != null && (extras.containsKey(EXTRA_NO_GPS_LOCATION)
                || extras.containsKey("indoorProbability")
                || extras.containsKey("coarseLocation"))) {
            location = new Location(location);
            extras = location.getExtras();
            extras.remove(EXTRA_NO_GPS_LOCATION);
            extras.remove("indoorProbability");
            extras.remove("coarseLocation");
            if (extras.isEmpty()) {
                location.setExtras(null);
            }
        }
        return location;
    }

    private static List<Location> stripExtras(List<Location> locations) {
        List<Location> mapped = locations;
        final int size = locations.size();
        int i = 0;
        for (Location location : locations) {
            Location newLocation = stripExtras(location);
            if (mapped != locations) {
                mapped.add(newLocation);
            } else if (newLocation != location) {
                mapped = new ArrayList<>(size);
                int j = 0;
                for (Location copiedLocation : locations) {
                    if (j >= i) {
                        break;
                    }
                    mapped.add(copiedLocation);
                    j++;
                }
                mapped.add(newLocation);
            }
            i++;
        }

        return mapped;
    }
}
