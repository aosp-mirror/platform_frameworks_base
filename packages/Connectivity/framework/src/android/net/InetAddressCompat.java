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

import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Compatibility utility for InetAddress core platform APIs.
 *
 * Connectivity has access to such APIs, but they are not part of the module_current stubs yet
 * (only core_current). Most stable core platform APIs are included manually in the connectivity
 * build rules, but because InetAddress is also part of the base java SDK that is earlier on the
 * classpath, the extra core platform APIs are not seen.
 *
 * TODO (b/183097033): remove this utility as soon as core_current is part of module_current
 * @hide
 */
public class InetAddressCompat {

    /**
     * @see InetAddress#clearDnsCache()
     */
    public static void clearDnsCache() {
        try {
            InetAddress.class.getMethod("clearDnsCache").invoke(null);
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            Log.wtf(InetAddressCompat.class.getSimpleName(), "Error clearing DNS cache", e);
        }
    }

    /**
     * @see InetAddress#getAllByNameOnNet(String, int)
     */
    public static InetAddress[] getAllByNameOnNet(String host, int netId) throws
            UnknownHostException {
        try {
            return (InetAddress[]) InetAddress.class.getMethod("getAllByNameOnNet",
                    String.class, int.class).invoke(null, host, netId);
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            Log.wtf(InetAddressCompat.class.getSimpleName(), "Error calling getAllByNameOnNet", e);
            throw new IllegalStateException("Error querying via getAllNameOnNet", e);
        }
    }

    /**
     * @see InetAddress#getByNameOnNet(String, int)
     */
    public static InetAddress getByNameOnNet(String host, int netId) throws
            UnknownHostException {
        try {
            return (InetAddress) InetAddress.class.getMethod("getByNameOnNet",
                    String.class, int.class).invoke(null, host, netId);
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            Log.wtf(InetAddressCompat.class.getSimpleName(), "Error calling getAllByNameOnNet", e);
            throw new IllegalStateException("Error querying via getByNameOnNet", e);
        }
    }
}
