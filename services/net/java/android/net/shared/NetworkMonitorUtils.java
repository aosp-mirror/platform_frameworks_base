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

package android.net.shared;

import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN;
import static android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED;

import android.net.NetworkCapabilities;

/** @hide */
public class NetworkMonitorUtils {

    // Network conditions broadcast constants
    public static final String ACTION_NETWORK_CONDITIONS_MEASURED =
            "android.net.conn.NETWORK_CONDITIONS_MEASURED";
    public static final String EXTRA_CONNECTIVITY_TYPE = "extra_connectivity_type";
    public static final String EXTRA_NETWORK_TYPE = "extra_network_type";
    public static final String EXTRA_RESPONSE_RECEIVED = "extra_response_received";
    public static final String EXTRA_IS_CAPTIVE_PORTAL = "extra_is_captive_portal";
    public static final String EXTRA_CELL_ID = "extra_cellid";
    public static final String EXTRA_SSID = "extra_ssid";
    public static final String EXTRA_BSSID = "extra_bssid";
    /** real time since boot */
    public static final String EXTRA_REQUEST_TIMESTAMP_MS = "extra_request_timestamp_ms";
    public static final String EXTRA_RESPONSE_TIMESTAMP_MS = "extra_response_timestamp_ms";
    public static final String PERMISSION_ACCESS_NETWORK_CONDITIONS =
            "android.permission.ACCESS_NETWORK_CONDITIONS";

    /**
     * Return whether validation is required for private DNS in strict mode.
     * @param nc Network capabilities of the network to test.
     */
    public static boolean isPrivateDnsValidationRequired(NetworkCapabilities nc) {
        // TODO: Consider requiring validation for DUN networks.
        return nc != null
                && nc.hasCapability(NET_CAPABILITY_INTERNET)
                && nc.hasCapability(NET_CAPABILITY_NOT_RESTRICTED)
                && nc.hasCapability(NET_CAPABILITY_TRUSTED);
    }

    /**
     * Return whether validation is required for a network.
     * @param nc Network capabilities of the network to test.
     */
    public static boolean isValidationRequired(NetworkCapabilities nc) {
        // TODO: Consider requiring validation for DUN networks.
        return isPrivateDnsValidationRequired(nc) && nc.hasCapability(NET_CAPABILITY_NOT_VPN);
    }
}
