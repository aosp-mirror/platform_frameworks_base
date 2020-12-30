/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.content.Context;
import android.os.IBinder;
import android.os.ServiceManager;

import java.util.Objects;

/**
 * Provides a way to obtain the DnsResolver binder objects.
 *
 * @hide
 */
@SystemApi
public class DnsResolverServiceManager {
    /**
     * Name to retrieve a {@link android.net.IDnsResolver} IBinder.
     */
    private static final String DNS_RESOLVER_SERVICE = "dnsresolver";

    private DnsResolverServiceManager() {}

    /**
     * Get an {@link IBinder} representing the DnsResolver stable AIDL interface
     *
     * @param context the context for permission check.
     * @return {@link android.net.IDnsResolver} IBinder.
     */
    @NonNull
    @RequiresPermission(NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK)
    public static IBinder getService(@NonNull final Context context) {
        Objects.requireNonNull(context);
        context.enforceCallingOrSelfPermission(NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK,
                "DnsResolverServiceManager");
        try {
            return ServiceManager.getServiceOrThrow(DNS_RESOLVER_SERVICE);
        } catch (ServiceManager.ServiceNotFoundException e) {
            // Catch ServiceManager#ServiceNotFoundException and rethrow IllegalStateException
            // because ServiceManager#ServiceNotFoundException is @hide so that it can't be listed
            // on the system api. Thus, rethrow IllegalStateException if dns resolver service cannot
            // be found.
            throw new IllegalStateException("Cannot find dns resolver service.");
        }
    }
}
