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

import android.content.Context;
import android.net.nsd.NsdServiceInfo;

import androidx.annotation.NonNull;

import com.android.printservice.recommendation.PrintServicePlugin;
import com.android.printservice.recommendation.R;
import com.android.printservice.recommendation.util.MDNSFilteredDiscovery;

import java.util.HashSet;
import java.util.Set;

public class SamsungRecommendationPlugin implements PrintServicePlugin {
    private static final Set<String> ALL_MDNS_SERVICES = new HashSet<String>();
    static {
        ALL_MDNS_SERVICES.addAll(PrinterFilterMopria.MOPRIA_MDNS_SERVICES);
        ALL_MDNS_SERVICES.addAll(PrinterFilterSamsung.SAMSUNG_MDNS_SERVICES);
    }

    private final @NonNull Context mContext;
    private final @NonNull MDNSFilteredDiscovery mMDNSFilteredDiscovery;

    private final @NonNull PrinterFilterSamsung mPrinterFilterSamsung = new PrinterFilterSamsung();
    private final @NonNull PrinterFilterMopria mPrinterFilterMopria = new PrinterFilterMopria();

    public SamsungRecommendationPlugin(@NonNull Context context) {
        mContext = context;
        mMDNSFilteredDiscovery = new MDNSFilteredDiscovery(context, ALL_MDNS_SERVICES,
                (NsdServiceInfo nsdServiceInfo) ->
                        mPrinterFilterSamsung.matchesCriteria(nsdServiceInfo) ||
                                mPrinterFilterMopria.matchesCriteria(nsdServiceInfo));
    }

    @Override
    public int getName() {
        return R.string.plugin_vendor_samsung;
    }

    @Override
    public @NonNull CharSequence getPackageName() {
        return mContext.getString(R.string.plugin_package_samsung);
    }

    @Override
    public void start(@NonNull PrinterDiscoveryCallback callback) throws Exception {
        mMDNSFilteredDiscovery.start(callback);
    }

    @Override
    public void stop() throws Exception {
        mMDNSFilteredDiscovery.stop();
    }
}
