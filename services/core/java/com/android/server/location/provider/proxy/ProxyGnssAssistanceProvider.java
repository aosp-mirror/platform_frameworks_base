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

import android.annotation.Nullable;
import android.content.Context;
import android.location.provider.GnssAssistanceProviderBase;
import android.location.provider.IGnssAssistanceCallback;
import android.location.provider.IGnssAssistanceProvider;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.server.servicewatcher.CurrentUserServiceSupplier;
import com.android.server.servicewatcher.ServiceWatcher;

/**
 * Proxy for IGnssAssitanceProvider implementations.
 */
public class ProxyGnssAssistanceProvider {

    private static final String TAG = "GnssAssistanceProxy";
    /**
     * Creates and registers this proxy. If no suitable service is available for the proxy, returns
     * null.
     */
    @Nullable
    public static ProxyGnssAssistanceProvider createAndRegister(Context context) {
        ProxyGnssAssistanceProvider proxy = new ProxyGnssAssistanceProvider(context);
        if (proxy.register()) {
            return proxy;
        } else {
            return null;
        }
    }

    private final ServiceWatcher mServiceWatcher;

    private ProxyGnssAssistanceProvider(Context context) {
        mServiceWatcher =
                ServiceWatcher.create(
                        context,
                        TAG,
                        CurrentUserServiceSupplier.createFromConfig(
                                context,
                                GnssAssistanceProviderBase.ACTION_GNSS_ASSISTANCE_PROVIDER,
                                com.android.internal.R.bool.config_enableGnssAssistanceOverlay,
                                com.android.internal.R.string
                                        .config_gnssAssistanceProviderPackageName),
                        /* serviceListener= */ null);
    }

    private boolean register() {
        boolean resolves = mServiceWatcher.checkServiceResolves();
        if (resolves) {
            mServiceWatcher.register();
        }
        return resolves;
    }

    /**
     * Request GNSS assistance.
     */
    public void request(IGnssAssistanceCallback callback) {
        mServiceWatcher.runOnBinder(
                new ServiceWatcher.BinderOperation() {
                    @Override
                    public void run(IBinder binder) throws RemoteException {
                        IGnssAssistanceProvider.Stub.asInterface(binder).request(callback);
                    }

                    @Override
                    public void onError(Throwable t) {
                        try {
                            Log.w(TAG, "Error on requesting GnssAssistance: " + t);
                            callback.onError();
                        } catch (RemoteException e) {
                            // ignore
                        }
                    }
                });
    }
}
