/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.net;

import android.annotation.SystemApi;
import android.app.SystemServiceRegistry;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.net.nsd.INsdManager;
import android.net.nsd.NsdManager;

/**
 * Class for performing registration for Connectivity services which are exposed via updatable APIs
 * since Android T.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class ConnectivityFrameworkInitializerTiramisu {
    private ConnectivityFrameworkInitializerTiramisu() {}

    /**
     * Called by {@link SystemServiceRegistry}'s static initializer and registers NetworkStats, nsd,
     * ipsec and ethernet services to {@link Context}, so that {@link Context#getSystemService} can
     * return them.
     *
     * @throws IllegalStateException if this is called anywhere besides
     * {@link SystemServiceRegistry}.
     */
    public static void registerServiceWrappers() {
        SystemServiceRegistry.registerContextAwareService(
                Context.NSD_SERVICE,
                NsdManager.class,
                (context, serviceBinder) -> {
                    INsdManager service = INsdManager.Stub.asInterface(serviceBinder);
                    return new NsdManager(context, service);
                }
        );

        SystemServiceRegistry.registerContextAwareService(
                Context.IPSEC_SERVICE,
                IpSecManager.class,
                (context, serviceBinder) -> {
                    IIpSecService service = IIpSecService.Stub.asInterface(serviceBinder);
                    return new IpSecManager(context, service);
                }
        );

        SystemServiceRegistry.registerContextAwareService(
                Context.NETWORK_STATS_SERVICE,
                NetworkStatsManager.class,
                (context, serviceBinder) -> {
                    INetworkStatsService service =
                            INetworkStatsService.Stub.asInterface(serviceBinder);
                    return new NetworkStatsManager(context, service);
                }
        );

        SystemServiceRegistry.registerContextAwareService(
                Context.ETHERNET_SERVICE,
                EthernetManager.class,
                (context, serviceBinder) -> {
                    IEthernetManager service = IEthernetManager.Stub.asInterface(serviceBinder);
                    return new EthernetManager(context, service);
                }
        );
    }
}
