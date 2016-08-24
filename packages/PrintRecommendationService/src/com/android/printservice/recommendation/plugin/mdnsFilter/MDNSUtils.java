/*
 * (c) Copyright 2016 Mopria Alliance, Inc.
 * (c) Copyright 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.printservice.recommendation.plugin.mdnsFilter;

import android.annotation.NonNull;
import android.net.nsd.NsdServiceInfo;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * Utils for dealing with mDNS attributes
 */
class MDNSUtils {
    public static final String ATTRIBUTE_TY = "ty";
    public static final String ATTRIBUTE_PRODUCT = "product";
    public static final String ATTRIBUTE_USB_MFG = "usb_mfg";
    public static final String ATTRIBUTE_MFG = "mfg";

    /**
     * Check if the service has any of a set of vendor names.
     *
     * @param serviceInfo The service
     * @param vendorNames The vendors
     *
     * @return true iff the has any of the set of vendor names
     */
    public static boolean isVendorPrinter(@NonNull NsdServiceInfo serviceInfo,
            @NonNull Set<String> vendorNames) {
        for (Map.Entry<String, byte[]> entry : serviceInfo.getAttributes().entrySet()) {
            // keys are case insensitive
            String key = entry.getKey().toLowerCase();

            switch (key) {
                case ATTRIBUTE_TY:
                case ATTRIBUTE_PRODUCT:
                case ATTRIBUTE_USB_MFG:
                case ATTRIBUTE_MFG:
                    if (entry.getValue() != null) {
                        if (containsVendor(new String(entry.getValue(), StandardCharsets.UTF_8),
                                vendorNames)) {
                            return true;
                        }
                    }
                    break;
                default:
                    break;
            }
        }

        return false;
    }

    /**
     * Check if the attribute matches any of the vendor names, ignoring capitalization.
     *
     * @param attr        The attribute
     * @param vendorNames The vendor names
     *
     * @return true iff the attribute matches any of the vendor names
     */
    private static boolean containsVendor(@NonNull String attr, @NonNull Set<String> vendorNames) {
        for (String name : vendorNames) {
            if (containsString(attr.toLowerCase(), name.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if a string in another string.
     *
     * @param container The string that contains the string
     * @param contained The string that is contained
     *
     * @return true if the string is contained in the other
     */
    private static boolean containsString(@NonNull String container, @NonNull String contained) {
        return container.equalsIgnoreCase(contained) || container.contains(contained + " ");
    }
}
