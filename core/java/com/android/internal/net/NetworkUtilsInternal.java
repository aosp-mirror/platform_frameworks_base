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

package com.android.internal.net;

import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.AF_INET6;

import android.annotation.NonNull;
import android.system.Os;

import java.io.FileDescriptor;

/** @hide */
public class NetworkUtilsInternal {

    private static final int[] ADDRESS_FAMILIES = new int[] {AF_INET, AF_INET6};

    /**
     * Allow/Disallow creating AF_INET/AF_INET6 sockets and DNS lookups for current process.
     *
     * @param allowNetworking whether to allow or disallow creating AF_INET/AF_INET6 sockets
     *                        and DNS lookups.
     */
    public static native void setAllowNetworkingForProcess(boolean allowNetworking);

    /**
     * Protect {@code fd} from VPN connections.  After protecting, data sent through
     * this socket will go directly to the underlying network, so its traffic will not be
     * forwarded through the VPN.
     */
    public static native boolean protectFromVpn(FileDescriptor fd);

    /**
     * Protect {@code socketfd} from VPN connections.  After protecting, data sent through
     * this socket will go directly to the underlying network, so its traffic will not be
     * forwarded through the VPN.
     */
    public static native boolean protectFromVpn(int socketfd);

    /**
     * Returns true if the hostname is weakly validated.
     * @param hostname Name of host to validate.
     * @return True if it's a valid-ish hostname.
     *
     * @hide
     */
    public static boolean isWeaklyValidatedHostname(@NonNull String hostname) {
        // TODO(b/34953048): Use a validation method that permits more accurate,
        // but still inexpensive, checking of likely valid DNS hostnames.
        final String weakHostnameRegex = "^[a-zA-Z0-9_.-]+$";
        if (!hostname.matches(weakHostnameRegex)) {
            return false;
        }

        for (int address_family : ADDRESS_FAMILIES) {
            if (Os.inet_pton(address_family, hostname) != null) {
                return false;
            }
        }

        return true;
    }
}
