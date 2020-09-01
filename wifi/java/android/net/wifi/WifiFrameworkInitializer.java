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

import android.annotation.SystemApi;
import android.app.SystemServiceRegistry;
import android.content.Context;
import android.net.wifi.aware.IWifiAwareManager;
import android.net.wifi.aware.WifiAwareManager;
import android.net.wifi.p2p.IWifiP2pManager;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.rtt.IWifiRttManager;
import android.net.wifi.rtt.WifiRttManager;
import android.os.HandlerThread;
import android.os.Looper;

/**
 * Class for performing registration for all Wifi services.
 *
 * @hide
 */
@SystemApi
public class WifiFrameworkInitializer {

    /**
     * A class implementing the lazy holder idiom: the unique static instance
     * of {@link #INSTANCE} is instantiated in a thread-safe way (guaranteed by
     * the language specs) the first time that NoPreloadHolder is referenced in getInstanceLooper().
     *
     * This is necessary because we can't spawn a new thread in {@link #registerServiceWrappers()}.
     * {@link #registerServiceWrappers()} is called during the Zygote phase, which disallows
     * spawning new threads. Naming the class "NoPreloadHolder" ensures that the classloader will
     * not preload this class, inadvertently spawning the thread too early.
     */
    private static class NoPreloadHolder {
        private static final HandlerThread INSTANCE = createInstance();

        private static HandlerThread createInstance() {
            HandlerThread thread = new HandlerThread("WifiManagerThread");
            thread.start();
            return thread;
        }
    }

    private static Looper getInstanceLooper() {
        return NoPreloadHolder.INSTANCE.getLooper();
    }

    private WifiFrameworkInitializer() {}

    /**
     * Called by {@link SystemServiceRegistry}'s static initializer and registers all Wifi services
     * to {@link Context}, so that {@link Context#getSystemService} can return them.
     *
     * @throws IllegalStateException if this is called from anywhere besides
     * {@link SystemServiceRegistry}
     */
    public static void registerServiceWrappers() {
        SystemServiceRegistry.registerContextAwareService(
                Context.WIFI_SERVICE,
                WifiManager.class,
                (context, serviceBinder) -> {
                    IWifiManager service = IWifiManager.Stub.asInterface(serviceBinder);
                    return new WifiManager(context, service, getInstanceLooper());
                }
        );
        SystemServiceRegistry.registerStaticService(
                Context.WIFI_P2P_SERVICE,
                WifiP2pManager.class,
                serviceBinder -> {
                    IWifiP2pManager service = IWifiP2pManager.Stub.asInterface(serviceBinder);
                    return new WifiP2pManager(service);
                }
        );
        SystemServiceRegistry.registerContextAwareService(
                Context.WIFI_AWARE_SERVICE,
                WifiAwareManager.class,
                (context, serviceBinder) -> {
                    IWifiAwareManager service = IWifiAwareManager.Stub.asInterface(serviceBinder);
                    return new WifiAwareManager(context, service);
                }
        );
        SystemServiceRegistry.registerContextAwareService(
                Context.WIFI_SCANNING_SERVICE,
                WifiScanner.class,
                (context, serviceBinder) -> {
                    IWifiScanner service = IWifiScanner.Stub.asInterface(serviceBinder);
                    return new WifiScanner(context, service, getInstanceLooper());
                }
        );
        SystemServiceRegistry.registerContextAwareService(
                Context.WIFI_RTT_RANGING_SERVICE,
                WifiRttManager.class,
                (context, serviceBinder) -> {
                    IWifiRttManager service = IWifiRttManager.Stub.asInterface(serviceBinder);
                    return new WifiRttManager(context, service);
                }
        );
        SystemServiceRegistry.registerContextAwareService(
                Context.WIFI_RTT_SERVICE,
                RttManager.class,
                context -> {
                    WifiRttManager wifiRttManager = context.getSystemService(WifiRttManager.class);
                    return new RttManager(context, wifiRttManager);
                }
        );
    }
}
