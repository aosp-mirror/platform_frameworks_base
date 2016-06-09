/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.printservice.recommendation.plugin.xerox;

import android.net.nsd.NsdServiceInfo;
import android.text.TextUtils;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

class MDnsUtils {
    public static final String ATTRIBUTE__TY = "ty";
    public static final String ATTRIBUTE__PRODUCT = "product";
    public static final String ATTRIBUTE__USB_MFG = "usb_MFG";
    public static final String ATTRIBUTE__USB_MDL = "usb_MDL";
    public static final String ATTRIBUTE__MFG = "mfg";
    public static final String EXCLUDE_FUJI = "fuji";
    public static final String PDL_ATTRIBUTE = "pdl";

    public static boolean isVendorPrinter(NsdServiceInfo networkDevice, String[] vendorValues) {

        Map<String, byte[]> attributes = networkDevice.getAttributes();
        String product = getString(attributes.get(ATTRIBUTE__PRODUCT));
        String ty = getString(attributes.get(ATTRIBUTE__TY));
        String usbMfg = getString(attributes.get(ATTRIBUTE__USB_MFG));
        String usbMdl = getString(attributes.get(ATTRIBUTE__USB_MDL));
        String mfg = getString(attributes.get(ATTRIBUTE__MFG));
        return (containsVendor(product, vendorValues) || containsVendor(ty, vendorValues) ||
                containsVendor(usbMfg, vendorValues) || containsVendor(mfg, vendorValues)) &&
                !(containsString(ty, EXCLUDE_FUJI) || containsString(product, EXCLUDE_FUJI) ||
                        containsString(usbMdl, EXCLUDE_FUJI));
    }

    public static String getVendor(NsdServiceInfo networkDevice) {
        String vendor;

        Map<String, byte[]> attributes = networkDevice.getAttributes();
        vendor = getString(attributes.get(ATTRIBUTE__MFG));
        if (!TextUtils.isEmpty(vendor)) return vendor;
        vendor = getString(attributes.get(ATTRIBUTE__USB_MFG));
        if (!TextUtils.isEmpty(vendor)) return vendor;

        return null;
    }

    public static boolean checkPDLSupport(NsdServiceInfo networkDevice, String[] pdlFormats) {
        if (pdlFormats == null) return false;

        String pdls = MDnsUtils.getString(networkDevice.getAttributes().get(PDL_ATTRIBUTE));
        if (pdls != null) {
            for (String pdl : pdlFormats) {
                if (pdls.contains(pdl)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsVendor(String container, String[] vendorValues) {
        if ((container == null) || (vendorValues == null)) return false;
        for (String value : vendorValues) {
            if (containsString(container, value)
                    || containsString(container.toLowerCase(Locale.US), value.toLowerCase(Locale.US))
                    || containsString(container.toUpperCase(Locale.US), value.toUpperCase(Locale.US)))
                return true;
        }
        return false;
    }

    private static String getString(byte[] value) {
        if (value != null) return new String(value, StandardCharsets.UTF_8);
        return null;
    }

    private static boolean containsString(String container, String contained) {
        return (container != null) && (contained != null) && (container.equalsIgnoreCase(contained) || container.contains(contained + " "));
    }
}
