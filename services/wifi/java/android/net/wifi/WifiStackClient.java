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
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.util.List;

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
            registerWifiStackService(service);

            IWifiStackConnector connector = IWifiStackConnector.Stub.asInterface(service);

            List<WifiApiServiceInfo> wifiApiServiceInfos;
            try {
                wifiApiServiceInfos = connector.getWifiApiServiceInfos();
            } catch (RemoteException e) {
                throw new RuntimeException("Failed to getWifiApiServiceInfos()", e);
            }

            for (WifiApiServiceInfo wifiApiServiceInfo : wifiApiServiceInfos) {
                String serviceName = wifiApiServiceInfo.name;
                IBinder binder = wifiApiServiceInfo.binder;
                Log.i(TAG, "Registering " + serviceName);
                ServiceManager.addService(serviceName, binder);
            }
        }
    }

    private void registerWifiStackService(@NonNull IBinder service) {
        ServiceManager.addService(Context.WIFI_STACK_SERVICE, service,
                false /* allowIsolated */,
                DUMP_FLAG_PRIORITY_HIGH | DUMP_FLAG_PRIORITY_NORMAL);
        Log.i(TAG, "Wifi stack service registered");
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
