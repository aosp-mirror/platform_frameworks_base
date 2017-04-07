/*
 * (c) Copyright 2016 Mopria Alliance, Inc.
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.printservice.recommendation.plugin.mopria;

import android.content.Context;
import android.net.nsd.NsdServiceInfo;
import android.text.TextUtils;

import com.android.printservice.recommendation.R;
import com.android.printservice.recommendation.plugin.hp.MDnsUtils;
import com.android.printservice.recommendation.plugin.hp.ServiceRecommendationPlugin;
import com.android.printservice.recommendation.plugin.hp.VendorInfo;

import java.net.InetAddress;
import java.util.ArrayList;

public class MopriaRecommendationPlugin extends ServiceRecommendationPlugin {

    private static final String PDL__PDF = "application/pdf";
    private static final String PDL__PCLM = "application/PCLm";
    private static final String PDL__PWG_RASTER = "image/pwg-raster";

    public MopriaRecommendationPlugin(Context context) {
        super(context, R.string.plugin_vendor_morpia, new VendorInfo(context.getResources(), R.array.known_print_vendor_info_for_mopria), new String[]{"_ipp._tcp", "_ipps._tcp"});
    }

    @Override
    public boolean matchesCriteria(String vendor, NsdServiceInfo nsdServiceInfo) {
        String pdls = MDnsUtils.getString(nsdServiceInfo.getAttributes().get(PDL_ATTRIBUTE));
        return (!TextUtils.isEmpty(pdls)
                && (pdls.contains(PDL__PDF)
                || pdls.contains(PDL__PCLM)
                || pdls.contains(PDL__PWG_RASTER)));
    }

    @Override
    public ArrayList<InetAddress> getPrinters() {
        return mListener.getPrinters();
    }
}
