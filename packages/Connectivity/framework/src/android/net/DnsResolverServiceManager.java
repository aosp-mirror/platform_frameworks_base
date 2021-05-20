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
import android.os.IBinder;

/**
 * Provides a way to obtain the DnsResolver binder objects.
 *
 * @hide
 */
public class DnsResolverServiceManager {
    /** Service name for the DNS resolver. Keep in sync with DnsResolverService.h */
    public static final String DNS_RESOLVER_SERVICE = "dnsresolver";

    private final IBinder mResolver;

    DnsResolverServiceManager(IBinder resolver) {
        mResolver = resolver;
    }

    /**
     * Get an {@link IBinder} representing the DnsResolver stable AIDL interface
     *
     * @return {@link android.net.IDnsResolver} IBinder.
     */
    @NonNull
    public IBinder getService() {
        return mResolver;
    }
}
