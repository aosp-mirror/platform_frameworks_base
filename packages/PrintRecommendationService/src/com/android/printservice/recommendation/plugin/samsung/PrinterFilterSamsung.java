/*
 * (c) Copyright 2016 Samsung Electronics
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
package com.android.printservice.recommendation.plugin.samsung;

import android.net.nsd.NsdServiceInfo;
import android.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;

import com.android.printservice.recommendation.util.MDNSFilteredDiscovery;
import com.android.printservice.recommendation.util.MDNSUtils;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Printer filter for Samsung printer models supported by the print service plugin
 */
class PrinterFilterSamsung implements MDNSFilteredDiscovery.PrinterFilter {
    private static final String TAG = "PrinterFilterSamsung";

    static final Set<String> SAMSUNG_MDNS_SERVICES = new HashSet<String>() {{
        add("_pdl-datastream._tcp");
    }};

    private static final String[] NOT_SUPPORTED_MODELS = new String[]{
            "SCX-5x15",
            "SF-555P",
            "CF-555P",
            "SCX-4x16",
            "SCX-4214F",
            "CLP-500",
            "CJX-",
            "MJC-"
    };
    private static final String ATTR_USB_MFG = "usb_MFG";
    private static final String ATTR_MFG = "mfg";
    private static final String ATTR_USB_MDL = "usb_MDL";
    private static final String ATTR_MDL = "mdl";
    private static final String ATTR_PRODUCT = "product";
    private static final String ATTR_TY = "ty";

    private static Set<String> SAMUNG_VENDOR_SET = new HashSet<String>() {{
        add("samsung");
    }};

    @Override
    public boolean matchesCriteria(NsdServiceInfo nsdServiceInfo) {
        if (!MDNSUtils.isSupportedServiceType(nsdServiceInfo, SAMSUNG_MDNS_SERVICES)) {
            return false;
        }

        if (!MDNSUtils.isVendorPrinter(nsdServiceInfo, SAMUNG_VENDOR_SET)) {
            return false;
        }

        String modelName = getSamsungModelName(nsdServiceInfo);
        if (modelName != null && isSupportedSamsungModel(modelName)) {
            Log.d(TAG, "Samsung printer found: " + nsdServiceInfo.getServiceName());
            return true;
        }
        return false;
    }

    private boolean isSupportedSamsungModel(String model) {
        if (!TextUtils.isEmpty(model)) {
            String modelToUpper = model.toUpperCase(Locale.US);
            for (String unSupportedPrinter : NOT_SUPPORTED_MODELS) {
                if (modelToUpper.contains(unSupportedPrinter)) {
                    return false;
                }
            }
        }
        return true;
    }

    private String getSamsungModelName(@NonNull NsdServiceInfo resolvedDevice) {
        Map<String,byte[]> attributes = resolvedDevice.getAttributes();
        String usb_mfg = MDNSUtils.getString(attributes.get(ATTR_USB_MFG));
        if (TextUtils.isEmpty(usb_mfg)) {
            usb_mfg = MDNSUtils.getString(attributes.get(ATTR_MFG));
        }

        String usb_mdl = MDNSUtils.getString(attributes.get(ATTR_USB_MDL));
        if (TextUtils.isEmpty(usb_mdl)) {
            usb_mdl = MDNSUtils.getString(attributes.get(ATTR_MDL));
        }

        String modelName;
        if (!TextUtils.isEmpty(usb_mfg) && !TextUtils.isEmpty(usb_mdl)) {
            modelName = usb_mfg.trim() + " " + usb_mdl.trim();
        } else {
            modelName = MDNSUtils.getString(attributes.get(ATTR_PRODUCT));
            if (TextUtils.isEmpty(modelName)) {
                modelName = MDNSUtils.getString(attributes.get(ATTR_TY));
            }
        }

        return modelName;
    }
}
