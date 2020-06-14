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

package android.net.util;

import static android.net.shared.IpConfigurationParcelableUtil.unparcelAddress;

import android.annotation.Nullable;
import android.net.DhcpResults;
import android.net.DhcpResultsParcelable;

import java.net.Inet4Address;

/**
 * Compatibility utility for code that still uses DhcpResults.
 *
 * TODO: remove this class when all usages of DhcpResults (including Wifi in AOSP) are removed.
 */
public class DhcpResultsCompatUtil {

    /**
     * Convert a DhcpResultsParcelable to DhcpResults.
     *
     * contract {
     *     returns(null) implies p == null
     *     returnsNotNull() implies p != null
     * }
     */
    @Nullable
    public static DhcpResults fromStableParcelable(@Nullable DhcpResultsParcelable p) {
        if (p == null) return null;
        final DhcpResults results = new DhcpResults(p.baseConfiguration);
        results.leaseDuration = p.leaseDuration;
        results.mtu = p.mtu;
        results.serverAddress = (Inet4Address) unparcelAddress(p.serverAddress);
        results.vendorInfo = p.vendorInfo;
        results.serverHostName = p.serverHostName;
        results.captivePortalApiUrl = p.captivePortalApiUrl;
        return results;
    }
}
