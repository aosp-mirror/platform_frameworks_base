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

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintWriter;

import android.content.Context;
import android.location.ILocationManager;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.WorkSource;
import android.util.Log;

import com.android.internal.location.ILocationProvider;
import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ProviderRequest;

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
 */
public abstract class LocationProviderBase {
    private final String TAG;

    protected final ILocationManager mLocationManager;
    private final ProviderProperties mProperties;
    private final IBinder mBinder;

    /**
     * Bundle key for a version of the location containing no GPS data.
     * Allows location providers to flag locations as being safe to
     * feed to LocationFudger.
     */
    public static final String EXTRA_NO_GPS_LOCATION = Location.EXTRA_NO_GPS_LOCATION;

    /**
     * Name of the Fused location provider.
     *
     * <p>This provider combines inputs for all possible location sources
     * to provide the best possible Location fix.
     */
    public static final String FUSED_PROVIDER = LocationManager.FUSED_PROVIDER;

    private final class Service extends ILocationProvider.Stub {
        @Override
        public void enable() {
            onEnable();
        }
        @Override
        public void disable() {
            onDisable();
        }
        @Override
        public void setRequest(ProviderRequest request, WorkSource ws) {
            onSetRequest(new ProviderRequestUnbundled(request), ws);
        }
        @Override
        public ProviderProperties getProperties() {
            return mProperties;
        }
        @Override
        public int getStatus(Bundle extras) {
            return onGetStatus(extras);
        }
        @Override
        public long getStatusUpdateTime() {
            return onGetStatusUpdateTime();
        }
        @Override
        public boolean sendExtraCommand(String command, Bundle extras) {
            return onSendExtraCommand(command, extras);
        }
        @Override
        public void dump(FileDescriptor fd, String[] args) {
            PrintWriter pw = new PrintWriter(new FileOutputStream(fd));
            onDump(fd, pw, args);
            pw.flush();
        }
    }

    public LocationProviderBase(String tag, ProviderPropertiesUnbundled properties) {
        TAG = tag;
        IBinder b = ServiceManager.getService(Context.LOCATION_SERVICE);
        mLocationManager = ILocationManager.Stub.asInterface(b);
        mProperties = properties.getProviderProperties();
        mBinder = new Service();
    }

    public IBinder getBinder() {
        return mBinder;
    }

    /**
     * Used by the location provider to report new locations.
     *
     * @param location new Location to report
     *
     * Requires the android.permission.INSTALL_LOCATION_PROVIDER permission.
     */
    public final void reportLocation(Location location) {
        try {
            mLocationManager.reportLocation(location, false);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException", e);
        } catch (Exception e) {
            // never crash provider, might be running in a system process
            Log.e(TAG, "Exception", e);
        }
    }

    /**
     * Enable the location provider.
     * <p>The provider may initialize resources, but does
     * not yet need to report locations.
     */
    public abstract void onEnable();

    /**
     * Disable the location provider.
     * <p>The provider must release resources, and stop
     * performing work. It may no longer report locations.
     */
    public abstract void onDisable();

    /**
     * Set the {@link ProviderRequest} requirements for this provider.
     * <p>Each call to this method overrides all previous requests.
     * <p>This method might trigger the provider to start returning
     * locations, or to stop returning locations, depending on the
     * parameters in the request.
     */
    public abstract void onSetRequest(ProviderRequestUnbundled request, WorkSource source);

    /**
     * Dump debug information.
     */
    public void onDump(FileDescriptor fd, PrintWriter pw, String[] args) {
    }

    /**
     * Returns a information on the status of this provider.
     * <p>{@link android.location.LocationProvider#OUT_OF_SERVICE} is returned if the provider is
     * out of service, and this is not expected to change in the near
     * future; {@link android.location.LocationProvider#TEMPORARILY_UNAVAILABLE} is returned if
     * the provider is temporarily unavailable but is expected to be
     * available shortly; and {@link android.location.LocationProvider#AVAILABLE} is returned
     * if the provider is currently available.
     *
     * <p>If extras is non-null, additional status information may be
     * added to it in the form of provider-specific key/value pairs.
     */
    public abstract int onGetStatus(Bundle extras);

    /**
     * Returns the time at which the status was last updated. It is the
     * responsibility of the provider to appropriately set this value using
     * {@link android.os.SystemClock#elapsedRealtime SystemClock.elapsedRealtime()}.
     * there is a status update that it wishes to broadcast to all its
     * listeners. The provider should be careful not to broadcast
     * the same status again.
     *
     * @return time of last status update in millis since last reboot
     */
    public abstract long onGetStatusUpdateTime();

    /**
     * Implements addditional location provider specific additional commands.
     *
     * @param command name of the command to send to the provider.
     * @param extras optional arguments for the command (or null).
     * The provider may optionally fill the extras Bundle with results from the command.
     *
     * @return true if the command succeeds.
     */
    public boolean onSendExtraCommand(String command, Bundle extras) {
        // default implementation
        return false;
    }
}
