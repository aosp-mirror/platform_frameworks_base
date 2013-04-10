/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.hardware.location.GeofenceHardware;
import android.hardware.location.IGeofenceHardware;
import android.os.IBinder;

import android.location.IGeofenceProvider;
import android.util.Log;

import java.lang.Long;

/**
 * Base class for geofence providers implemented as unbundled services.
 *
 * <p>Geofence providers can be implemented as services and return the result of
 * {@link com.android.location.provider.GeofenceProvider#getBinder()} in its getBinder() method.
 *
 * <p>IMPORTANT: This class is effectively a public API for unbundled
 * applications, and must remain API stable. See README.txt in the root
 * of this package for more information.
 */
public abstract class GeofenceProvider {

    private GeofenceHardware mGeofenceHardware;

    private IGeofenceProvider.Stub mProvider = new IGeofenceProvider.Stub() {
        public void setGeofenceHardware(IGeofenceHardware hardwareProxy) {
            mGeofenceHardware = new GeofenceHardware(hardwareProxy);
            onGeofenceHardwareChange(mGeofenceHardware);
        }
    };

    /**
     * Returns the Binder interface for the geofence provider.
     * This is intended to be used for the onBind() method of
     * a service that implements a geofence service.
     *
     * @return the IBinder instance for the provider
     */
    public IBinder getBinder() {
        return mProvider;
    }

    /**
     * Called when GeofenceHardware object becomes available.
     *
     * @param geofenceHardware Geofence Hardware object. This can be null
     *        when for some reason the service connection gets disconnected.
     */
    public abstract void onGeofenceHardwareChange(GeofenceHardware geofenceHardware);
}
