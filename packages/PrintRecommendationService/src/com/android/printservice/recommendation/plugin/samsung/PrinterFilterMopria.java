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
import android.text.TextUtils;
import android.util.Log;

import com.android.printservice.recommendation.util.MDNSFilteredDiscovery;
import com.android.printservice.recommendation.util.MDNSUtils;

import java.util.Set;

/**
 * Printer filter for Mopria printer models supported by the print service plugin
 */
class PrinterFilterMopria implements MDNSFilteredDiscovery.PrinterFilter {
    private static final String TAG = "PrinterFilterMopria";

    static final Set<String> MOPRIA_MDNS_SERVICES = Set.of("_ipp._tcp", "_ipps._tcp");

    private static final String PDL__PDF = "application/pdf";
    private static final String PDL__PCLM = "application/PCLm";
    private static final String PDL__PWG_RASTER = "image/pwg-raster";

    private static final String PDL_ATTRIBUTE = "pdl";

    @Override
    public boolean matchesCriteria(NsdServiceInfo nsdServiceInfo) {
        if (!MDNSUtils.isSupportedServiceType(nsdServiceInfo, MOPRIA_MDNS_SERVICES)) {
            return false;
        }

        String pdls = MDNSUtils.getString(nsdServiceInfo.getAttributes().get(PDL_ATTRIBUTE));
        boolean isMatch = !TextUtils.isEmpty(pdls)
                && (pdls.contains(PDL__PDF)
                || pdls.contains(PDL__PCLM)
                || pdls.contains(PDL__PWG_RASTER));

        if (isMatch) {
            Log.d(TAG, "Mopria printer found: " + nsdServiceInfo.getServiceName());
        }
        return isMatch;
    }
}
