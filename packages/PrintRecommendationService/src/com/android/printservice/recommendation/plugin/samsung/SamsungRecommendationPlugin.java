/*
(c) Copyright 2016 Samsung Electronics..

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/


package com.android.printservice.recommendation.plugin.samsung;

import android.content.Context;
import android.net.nsd.NsdServiceInfo;
import android.text.TextUtils;

import java.util.Locale;
import java.util.Map;

import com.android.printservice.recommendation.R;

public class SamsungRecommendationPlugin extends ServiceRecommendationPlugin {

    private static final String TAG = "SamsungRecommendation";

    private static final String ATTR_USB_MFG = "usb_MFG";
    private static final String ATTR_MFG = "mfg";
    private static final String ATTR_USB_MDL = "usb_MDL";
    private static final String ATTR_MDL = "mdl";
    private static final String ATTR_PRODUCT = "product";
    private static final String ATTR_TY = "ty";

    private static String[] mNotSupportedDevices = new String[]{
            "SCX-5x15",
            "SF-555P",
            "CF-555P",
            "SCX-4x16",
            "SCX-4214F",
            "CLP-500",
            "CJX-",
            "MJC-"
    };

    private static boolean isSupportedModel(String model) {
        if (!TextUtils.isEmpty(model)) {
            String modelToUpper = model.toUpperCase(Locale.US);
            for (String unSupportedPrinter : mNotSupportedDevices) {
                if (modelToUpper.contains(unSupportedPrinter)) {
                    return  false;
                }
            }
        }
        return true;
    }

    public SamsungRecommendationPlugin(Context context) {
        super(context, R.string.plugin_vendor_samsung, new VendorInfo(context.getResources(), R.array.known_print_vendor_info_for_samsung), new String[]{"_pdl-datastream._tcp"});
    }

    @Override
    public boolean matchesCriteria(String vendor, NsdServiceInfo nsdServiceInfo) {
        if (!TextUtils.equals(vendor, mVendorInfo.mVendorID)) return false;

        String modelName = getModelName(nsdServiceInfo);
        if (modelName != null) {
            return (isSupportedModel(modelName));
        }
        return false;
    }

    private String getModelName(NsdServiceInfo resolvedDevice) {
        Map<String,byte[]> attributes = resolvedDevice.getAttributes();
        String usb_mfg = MDnsUtils.getString(attributes.get(ATTR_USB_MFG));
        if (TextUtils.isEmpty(usb_mfg)) {
            usb_mfg = MDnsUtils.getString(attributes.get(ATTR_MFG));
        }

        String usb_mdl = MDnsUtils.getString(attributes.get(ATTR_USB_MDL));
        if (TextUtils.isEmpty(usb_mdl)) {
            usb_mdl = MDnsUtils.getString(attributes.get(ATTR_MDL));
        }

        String modelName = null;
        if (!TextUtils.isEmpty(usb_mfg) && !TextUtils.isEmpty(usb_mdl)) {
            modelName = usb_mfg.trim() + " " + usb_mdl.trim();
        } else {
            modelName = MDnsUtils.getString(attributes.get(ATTR_PRODUCT));
            if (TextUtils.isEmpty(modelName)) {
                modelName = MDnsUtils.getString(attributes.get(ATTR_TY));
            }
        }

        return modelName;
    }
}
