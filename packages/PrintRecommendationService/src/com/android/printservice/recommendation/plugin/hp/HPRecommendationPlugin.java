/*
(c) Copyright 2016 HP Inc.
Copyright (C) 2016 The Android Open Source Project

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
package com.android.printservice.recommendation.plugin.hp;

import android.content.Context;
import android.net.nsd.NsdServiceInfo;
import android.text.TextUtils;

import com.android.printservice.recommendation.R;

import java.util.Locale;

public class HPRecommendationPlugin extends ServiceRecommendationPlugin {

    private static final String PDL__PCL = "application/vnd.hp-PCL";
    private static final String PDL__PCLM = "application/PCLm";
    private static final String PDL__PDF = "application/pdf";
    private static final String PDL__PWG_RASTER = "image/pwg-raster";

    private static final String TAG_DESIGNJET = "DESIGNJET";
    private static final String TAG_PAGEWIDE = "PAGEWIDE";
    private static final String TAG_LATEX = "LATEX";
    private static final String TAG_SCITEX = "SCITEX";
    private static final String TAG_XL = "XL";
    private static final String ATTRIBUTE_VALUE__TRUE = "T";
    private static final String MDNS_ATTRIBUTE__HPLFMOBILEPRINTER = "hplfpmobileprinter";
    private static final String MDNS_ATTRIBUTE__TY = "ty";


    private static String[] mSupportedDesignJet = new String[]{
        "HP DESIGNJET T120",
        "HP DESIGNJET T520",
        "HP DESIGNJET T930",
        "HP DESIGNJET T1530",
        "HP DESIGNJET T2530",
        "HP DESIGNJET T730",
        "HP DESIGNJET T830",
    };

    private boolean isPrintSupported(String printerModel) {
        boolean isSupported;
        if (!TextUtils.isEmpty(printerModel)) {
            String modelToUpper = printerModel.toUpperCase(Locale.US);
            if (modelToUpper.contains(TAG_DESIGNJET)) {
                isSupported = isSupportedDesignjet(printerModel);
            } else
                isSupported = !(modelToUpper.contains(TAG_LATEX) || modelToUpper.contains(TAG_SCITEX)) && !(modelToUpper.contains(TAG_PAGEWIDE) && modelToUpper.contains(TAG_XL));
        } else {
            isSupported = false;
        }

        return isSupported;
    }

    private static boolean isSupportedDesignjet(String printerModel) {
        boolean isSupported = false;
        if (!TextUtils.isEmpty(printerModel)) {
            String modelToUpper = printerModel.toUpperCase(Locale.US);
            for (String supportedPrinter : mSupportedDesignJet) {
                if (modelToUpper.contains(supportedPrinter)) {
                    isSupported = true;
                }
            }
        }
        return isSupported;
    }

    public HPRecommendationPlugin(Context context) {
        super(context, R.string.plugin_vendor_hp, new VendorInfo(context.getResources(), R.array.known_print_vendor_info_for_hp), new String[]{"_pdl-datastream._tcp","_ipp._tcp", "_ipps._tcp"});
    }

    @Override
    public boolean matchesCriteria(String vendor, NsdServiceInfo nsdServiceInfo) {
        if (!TextUtils.equals(vendor, mVendorInfo.mVendorID)) return false;

        String pdls = MDnsUtils.getString(nsdServiceInfo.getAttributes().get(PDL_ATTRIBUTE));
        boolean hasMobileSupport = TextUtils.equals(ATTRIBUTE_VALUE__TRUE, MDnsUtils.getString(nsdServiceInfo.getAttributes().get(MDNS_ATTRIBUTE__HPLFMOBILEPRINTER)));

        return (((hasMobileSupport || isPrintSupported(MDnsUtils.getString(nsdServiceInfo.getAttributes().get(MDNS_ATTRIBUTE__TY))))
                    &&!TextUtils.isEmpty(pdls))
                && (pdls.contains(PDL__PCL)
                || pdls.contains(PDL__PDF)
                || pdls.contains(PDL__PCLM)
                || pdls.contains(PDL__PWG_RASTER)));
    }
}
