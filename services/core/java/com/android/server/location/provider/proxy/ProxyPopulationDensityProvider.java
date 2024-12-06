/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static android.location.provider.PopulationDensityProviderBase.ACTION_POPULATION_DENSITY_PROVIDER;

import android.annotation.Nullable;
import android.content.Context;
import android.location.provider.IPopulationDensityProvider;
import android.location.provider.IS2CellIdsCallback;
import android.location.provider.IS2LevelCallback;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.server.servicewatcher.CurrentUserServiceSupplier;
import com.android.server.servicewatcher.ServiceWatcher;

/**
 * Proxy for IPopulationDensityProvider implementations.
 */
public class ProxyPopulationDensityProvider {

    public static final String TAG = "ProxyPopulationDensityProvider";

    final ServiceWatcher mServiceWatcher;

    /**
     * Creates and registers this proxy. If no suitable service is available for the proxy, returns
     * null.
     */
    @Nullable
    public static ProxyPopulationDensityProvider createAndRegister(Context context) {
        ProxyPopulationDensityProvider proxy = new ProxyPopulationDensityProvider(context);
        if (proxy.register()) {
            return proxy;
        } else {
            return null;
        }
    }

    private ProxyPopulationDensityProvider(Context context) {
        mServiceWatcher = ServiceWatcher.create(
                context,
                "PopulationDensityProxy",
                CurrentUserServiceSupplier.createFromConfig(
                        context,
                        ACTION_POPULATION_DENSITY_PROVIDER,
                        com.android.internal.R.bool.config_enablePopulationDensityProviderOverlay,
                        com.android.internal.R.string.config_populationDensityProviderPackageName),
                null);
    }

    private boolean register() {
        boolean resolves = mServiceWatcher.checkServiceResolves();
        if (resolves) {
            mServiceWatcher.register();
        }
        return resolves;
    }

    /** Gets the default coarsening level. */
    public void getDefaultCoarseningLevel(IS2LevelCallback callback) {
        mServiceWatcher.runOnBinder(
                new ServiceWatcher.BinderOperation() {
                    @Override
                    public void run(IBinder binder) throws RemoteException {
                        IPopulationDensityProvider.Stub.asInterface(binder)
                              .getDefaultCoarseningLevel(callback);
                    }

                    @Override
                    public void onError(Throwable t) {
                        try {
                            callback.onError();
                        } catch (RemoteException e) {
                            Log.w(TAG, "remote exception while querying default coarsening level");
                        }
                    }
                });
    }


    /** Gets the population density at the requested location. */
    public void getCoarsenedS2Cells(double latitudeDegrees, double longitudeDegrees,
              int numAdditionalCells, IS2CellIdsCallback callback) {
        mServiceWatcher.runOnBinder(
                new ServiceWatcher.BinderOperation() {
                    @Override
                    public void run(IBinder binder) throws RemoteException {
                        IPopulationDensityProvider.Stub.asInterface(binder)
                                .getCoarsenedS2Cells(latitudeDegrees, longitudeDegrees,
                                      numAdditionalCells, callback);
                    }

                    @Override
                    public void onError(Throwable t) {
                        try {
                            callback.onError();
                        } catch (RemoteException e) {
                            Log.w(TAG, "remote exception while querying coarsened S2 cell");
                        }
                    }
                });
    }
}
