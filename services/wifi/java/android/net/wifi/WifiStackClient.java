/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.net.wifi;

import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_HIGH;
import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_NORMAL;

import android.annotation.NonNull;
import android.content.Context;
import android.net.ConnectivityModuleConnector;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

/**
 * Service used to communicate with the wifi stack, which could be running in a separate
 * module.
 * @hide
 */
public class WifiStackClient {
    public static final String PERMISSION_MAINLINE_WIFI_STACK =
            "android.permission.MAINLINE_WIFI_STACK";

    private static final String TAG = WifiStackClient.class.getSimpleName();
    private static WifiStackClient sInstance;

    private WifiStackClient() { }

    /**
     * Get the WifiStackClient singleton instance.
     */
    public static synchronized WifiStackClient getInstance() {
        if (sInstance == null) {
            sInstance = new WifiStackClient();
        }
        return sInstance;
    }

    private class WifiStackConnection implements
            ConnectivityModuleConnector.ModuleServiceCallback {
        @Override
        public void onModuleServiceConnected(IBinder service) {
            Log.i(TAG, "Wifi stack connected");

            // spin up a new thread to not block system_server main thread
            HandlerThread thread = new HandlerThread("InitWifiServicesThread");
            thread.start();
            thread.getThreadHandler().post(() -> {
                registerWifiStackService(service);
                IWifiStackConnector connector = IWifiStackConnector.Stub.asInterface(service);
                registerApiServiceAndStart(connector, Context.WIFI_SCANNING_SERVICE);
                registerApiServiceAndStart(connector, Context.WIFI_SERVICE);
                registerApiServiceAndStart(connector, Context.WIFI_P2P_SERVICE);
                registerApiServiceAndStart(connector, Context.WIFI_AWARE_SERVICE);
                registerApiServiceAndStart(connector, Context.WIFI_RTT_RANGING_SERVICE);

                thread.quitSafely();
            });
        }
    }

    private void registerWifiStackService(@NonNull IBinder service) {
        ServiceManager.addService(Context.WIFI_STACK_SERVICE, service,
                false /* allowIsolated */,
                DUMP_FLAG_PRIORITY_HIGH | DUMP_FLAG_PRIORITY_NORMAL);
        Log.i(TAG, "Wifi stack service registered");
    }

    private void registerApiServiceAndStart(
            IWifiStackConnector stackConnector, String serviceName) {
        IBinder service = null;
        try {
            service = stackConnector.retrieveApiServiceImpl(serviceName);
        } catch (RemoteException e) {
            throw new RuntimeException("Failed to retrieve service impl " + serviceName, e);
        }
        if (service == null) {
            Log.i(TAG, "Service " + serviceName + " not available");
            return;
        }
        Log.i(TAG, "Registering " + serviceName);
        ServiceManager.addService(serviceName, service);

        boolean success = false;
        try {
            success = stackConnector.startApiService(serviceName);
        } catch (RemoteException e) {
            throw new RuntimeException("Failed to start service " + serviceName, e);
        }
        if (!success) {
            throw new RuntimeException("Service " + serviceName + " start failed");
        }
    }

    /**
     * Start the wifi stack. Should be called only once on device startup.
     *
     * <p>This method will start the wifi stack either in the wifi stack
     * process, or inside the system server on devices that do not support the wifi stack
     * module.
     */
    public void start() {
        Log.i(TAG, "Starting wifi stack");
        ConnectivityModuleConnector.getInstance().startModuleService(
                IWifiStackConnector.class.getName(), PERMISSION_MAINLINE_WIFI_STACK,
                new WifiStackConnection());
    }
}
