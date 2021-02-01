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

package android.net.util;

import android.annotation.NonNull;
import android.content.Context;
import android.content.res.Resources;
import android.net.NetworkCapabilities;
import android.text.TextUtils;
import android.util.AndroidRuntimeException;

import com.android.internal.R;

/**
 * Collection of utilities for socket keepalive offload.
 *
 * @hide
 */
public final class KeepaliveUtils {

    public static final String TAG = "KeepaliveUtils";

    public static class KeepaliveDeviceConfigurationException extends AndroidRuntimeException {
        public KeepaliveDeviceConfigurationException(final String msg) {
            super(msg);
        }
    }

    /**
     * Read supported keepalive count for each transport type from overlay resource. This should be
     * used to create a local variable store of resource customization, and use it as the input for
     * {@link getSupportedKeepalivesForNetworkCapabilities}.
     *
     * @param context The context to read resource from.
     * @return An array of supported keepalive count for each transport type.
     */
    @NonNull
    public static int[] getSupportedKeepalives(@NonNull Context context) {
        String[] res = null;
        try {
            res = context.getResources().getStringArray(
                    R.array.config_networkSupportedKeepaliveCount);
        } catch (Resources.NotFoundException unused) {
        }
        if (res == null) throw new KeepaliveDeviceConfigurationException("invalid resource");

        final int[] ret = new int[NetworkCapabilities.MAX_TRANSPORT + 1];
        for (final String row : res) {
            if (TextUtils.isEmpty(row)) {
                throw new KeepaliveDeviceConfigurationException("Empty string");
            }
            final String[] arr = row.split(",");
            if (arr.length != 2) {
                throw new KeepaliveDeviceConfigurationException("Invalid parameter length");
            }

            int transport;
            int supported;
            try {
                transport = Integer.parseInt(arr[0]);
                supported = Integer.parseInt(arr[1]);
            } catch (NumberFormatException e) {
                throw new KeepaliveDeviceConfigurationException("Invalid number format");
            }

            if (!NetworkCapabilities.isValidTransport(transport)) {
                throw new KeepaliveDeviceConfigurationException("Invalid transport " + transport);
            }

            if (supported < 0) {
                throw new KeepaliveDeviceConfigurationException(
                        "Invalid supported count " + supported + " for "
                                + NetworkCapabilities.transportNameOf(transport));
            }
            ret[transport] = supported;
        }
        return ret;
    }

    /**
     * Get supported keepalive count for the given {@link NetworkCapabilities}.
     *
     * @param supportedKeepalives An array of supported keepalive count for each transport type.
     * @param nc The {@link NetworkCapabilities} of the network the socket keepalive is on.
     *
     * @return Supported keepalive count for the given {@link NetworkCapabilities}.
     */
    public static int getSupportedKeepalivesForNetworkCapabilities(
            @NonNull int[] supportedKeepalives, @NonNull NetworkCapabilities nc) {
        final int[] transports = nc.getTransportTypes();
        if (transports.length == 0) return 0;
        int supportedCount = supportedKeepalives[transports[0]];
        // Iterate through transports and return minimum supported value.
        for (final int transport : transports) {
            if (supportedCount > supportedKeepalives[transport]) {
                supportedCount = supportedKeepalives[transport];
            }
        }
        return supportedCount;
    }
}
