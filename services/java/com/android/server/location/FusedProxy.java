/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (The "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.location;

import com.android.server.ServiceWatcher;

import android.Manifest;
import android.content.Context;
import android.hardware.location.IFusedLocationHardware;
import android.location.IFusedProvider;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;

/**
 * Proxy that helps bind GCore FusedProvider implementations to the Fused Hardware instances.
 *
 * @hide
 */
public final class FusedProxy {
    private final String TAG = "FusedProxy";
    private final ServiceWatcher mServiceWatcher;
    private final FusedLocationHardwareSecure mLocationHardware;

    /**
     * Constructor of the class.
     * This is private as the class follows a factory pattern for construction.
     *
     * @param context           The context needed for construction.
     * @param handler           The handler needed for construction.
     * @param locationHardware  The instance of the Fused location hardware assigned to the proxy.
     */
    private FusedProxy(
            Context context,
            Handler handler,
            IFusedLocationHardware locationHardware,
            int overlaySwitchResId,
            int defaultServicePackageNameResId,
            int initialPackageNameResId) {
        mLocationHardware = new FusedLocationHardwareSecure(
                locationHardware,
                context,
                Manifest.permission.LOCATION_HARDWARE);
        Runnable newServiceWork = new Runnable() {
            @Override
            public void run() {
                bindProvider(mLocationHardware);
            }
        };

        // prepare the connection to the provider
        mServiceWatcher = new ServiceWatcher(
                context,
                TAG,
                "com.android.location.service.FusedProvider",
                overlaySwitchResId,
                defaultServicePackageNameResId,
                initialPackageNameResId,
                newServiceWork,
                handler);
    }

    /**
     * Creates an instance of the proxy and binds it to the appropriate FusedProvider.
     *
     * @param context           The context needed for construction.
     * @param handler           The handler needed for construction.
     * @param locationHardware  The instance of the Fused location hardware assigned to the proxy.
     *
     * @return An instance of the proxy if it could be bound, null otherwise.
     */
    public static FusedProxy createAndBind(
            Context context,
            Handler handler,
            IFusedLocationHardware locationHardware,
            int overlaySwitchResId,
            int defaultServicePackageNameResId,
            int initialPackageNameResId) {
        FusedProxy fusedProxy = new FusedProxy(
                context,
                handler,
                locationHardware,
                overlaySwitchResId,
                defaultServicePackageNameResId,
                initialPackageNameResId);

        // try to bind the Fused provider
        if (!fusedProxy.mServiceWatcher.start()) {
            return null;
        }

        return fusedProxy;
    }

    /**
     * Helper function to bind the FusedLocationHardware to the appropriate FusedProvider instance.
     *
     * @param locationHardware  The FusedLocationHardware instance to use for the binding operation.
     */
    private void bindProvider(IFusedLocationHardware locationHardware) {
        IFusedProvider provider = IFusedProvider.Stub.asInterface(mServiceWatcher.getBinder());

        if (provider == null) {
            Log.e(TAG, "No instance of FusedProvider found on FusedLocationHardware connected.");
            return;
        }

        try {
            provider.onFusedLocationHardwareChange(locationHardware);
        } catch (RemoteException e) {
            Log.e(TAG, e.toString());
        }
    }
}
