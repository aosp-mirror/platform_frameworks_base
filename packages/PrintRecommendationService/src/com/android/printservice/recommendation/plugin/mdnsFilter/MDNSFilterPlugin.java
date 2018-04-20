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

package com.android.printservice.recommendation.plugin.mdnsFilter;

import android.content.Context;
import android.net.nsd.NsdServiceInfo;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.android.printservice.recommendation.PrintServicePlugin;
import com.android.printservice.recommendation.util.MDNSFilteredDiscovery;
import com.android.printservice.recommendation.util.MDNSUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A plugin listening for mDNS results and only adding the ones that {@link
 * MDNSUtils#isVendorPrinter match} configured list
 */
public class MDNSFilterPlugin implements PrintServicePlugin {

    /** The mDNS service types supported */
    private static final Set<String> PRINTER_SERVICE_TYPES = new HashSet<String>() {{
        add("_ipp._tcp");
    }};

    /**
     * The printer filter for {@link MDNSFilteredDiscovery} passing only mDNS results
     * that {@link MDNSUtils#isVendorPrinter match} configured list
     */
    private static class VendorNameFilter implements MDNSFilteredDiscovery.PrinterFilter {
        /** mDNS names handled by the print service this plugin is for */
        private final @NonNull Set<String> mMDNSNames;

        /**
         * Filter constructor
         *
         * @param vendorNames The vendor names to pass
         */
        VendorNameFilter(@NonNull Set<String> vendorNames) {
            mMDNSNames = new HashSet<>(vendorNames);
        }

        @Override
        public boolean matchesCriteria(NsdServiceInfo nsdServiceInfo) {
            return MDNSUtils.isVendorPrinter(nsdServiceInfo, mMDNSNames);
        }
    }

    /** Name of the print service this plugin is for */
    private final @StringRes int mName;

    /** Package name of the print service this plugin is for */
    private final @NonNull CharSequence mPackageName;

    /** The mDNS filtered discovery */
    private final MDNSFilteredDiscovery mMDNSFilteredDiscovery;

    /**
     * Create new stub that assumes that a print service can be used to print on all mPrinters
     * matching some mDNS names.
     *
     * @param context     The context the plugin runs in
     * @param name        The user friendly name of the print service
     * @param packageName The package name of the print service
     * @param mDNSNames   The mDNS names of the printer.
     */
    public MDNSFilterPlugin(@NonNull Context context, @NonNull String name,
            @NonNull CharSequence packageName, @NonNull List<String> mDNSNames) {
        mName = context.getResources().getIdentifier(name, null,
                "com.android.printservice.recommendation");
        mPackageName = packageName;
        mMDNSFilteredDiscovery = new MDNSFilteredDiscovery(context, PRINTER_SERVICE_TYPES,
                new VendorNameFilter(new HashSet<>(mDNSNames)));
    }

    @Override
    public @NonNull CharSequence getPackageName() {
        return mPackageName;
    }

    @Override
    public void start(@NonNull PrinterDiscoveryCallback callback) throws Exception {
        mMDNSFilteredDiscovery.start(callback);
    }

    @Override
    public @StringRes int getName() {
        return mName;
    }

    @Override
    public void stop() throws Exception {
        mMDNSFilteredDiscovery.stop();
    }
}
