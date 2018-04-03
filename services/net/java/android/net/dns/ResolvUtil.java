/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.net.dns;

import android.net.Network;
import android.net.NetworkUtils;
import android.system.GaiException;
import android.system.OsConstants;
import android.system.StructAddrinfo;

import libcore.io.Libcore;

import java.net.InetAddress;
import java.net.UnknownHostException;


/**
 * DNS resolution utility class.
 *
 * @hide
 */
public class ResolvUtil {
    // Non-portable DNS resolution flag.
    private static final long NETID_USE_LOCAL_NAMESERVERS = 0x80000000L;

    private ResolvUtil() {}

    public static InetAddress[] blockingResolveAllLocally(Network network, String name)
            throws UnknownHostException {
        final StructAddrinfo hints = new StructAddrinfo();
        // Unnecessary, but expressly no AI_ADDRCONFIG.
        hints.ai_flags = 0;
        // Fetch all IP addresses at once to minimize re-resolution.
        hints.ai_family = OsConstants.AF_UNSPEC;
        hints.ai_socktype = OsConstants.SOCK_DGRAM;

        final Network networkForResolv = getNetworkWithUseLocalNameserversFlag(network);

        try {
            return Libcore.os.android_getaddrinfo(name, hints, (int) networkForResolv.netId);
        } catch (GaiException gai) {
            gai.rethrowAsUnknownHostException(name + ": TLS-bypass resolution failed");
            return null;  // keep compiler quiet
        }
    }

    public static Network getNetworkWithUseLocalNameserversFlag(Network network) {
        final long netidForResolv = NETID_USE_LOCAL_NAMESERVERS | (long) network.netId;
        return new Network((int) netidForResolv);
    }
}
