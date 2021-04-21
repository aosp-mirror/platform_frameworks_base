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
import android.content.Context;

/**
 * Class for performing registration for all core connectivity services.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class ConnectivityFrameworkInitializer {
    private ConnectivityFrameworkInitializer() {}

    /**
     * Called by {@link SystemServiceRegistry}'s static initializer and registers all core
     * connectivity services to {@link Context}, so that {@link Context#getSystemService} can
     * return them.
     *
     * @throws IllegalStateException if this is called anywhere besides
     * {@link SystemServiceRegistry}.
     */
    public static void registerServiceWrappers() {
        // registerContextAwareService will throw if this is called outside of SystemServiceRegistry
        // initialization.
        SystemServiceRegistry.registerContextAwareService(
                Context.CONNECTIVITY_SERVICE,
                ConnectivityManager.class,
                (context, serviceBinder) -> {
                    IConnectivityManager icm = IConnectivityManager.Stub.asInterface(serviceBinder);
                    return new ConnectivityManager(context, icm);
                }
        );

        SystemServiceRegistry.registerContextAwareService(
                Context.CONNECTIVITY_DIAGNOSTICS_SERVICE,
                ConnectivityDiagnosticsManager.class,
                (context) -> {
                    final ConnectivityManager cm = context.getSystemService(
                            ConnectivityManager.class);
                    return cm.createDiagnosticsManager();
                }
        );

        SystemServiceRegistry.registerContextAwareService(
                Context.TEST_NETWORK_SERVICE,
                TestNetworkManager.class,
                context -> {
                    final ConnectivityManager cm = context.getSystemService(
                            ConnectivityManager.class);
                    return cm.startOrGetTestNetworkManager();
                }
        );

        SystemServiceRegistry.registerContextAwareService(
                DnsResolverServiceManager.DNS_RESOLVER_SERVICE,
                DnsResolverServiceManager.class,
                (context, serviceBinder) -> new DnsResolverServiceManager(serviceBinder)
        );
    }
}
