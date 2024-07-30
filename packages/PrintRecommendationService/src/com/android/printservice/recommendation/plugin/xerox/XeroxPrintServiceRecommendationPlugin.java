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

import android.content.Context;
import android.net.nsd.NsdManager;

import androidx.annotation.NonNull;

import com.android.printservice.recommendation.PrintServicePlugin;
import com.android.printservice.recommendation.R;

public class XeroxPrintServiceRecommendationPlugin implements PrintServicePlugin, ServiceResolver.Observer {

    protected final Object mLock = new Object();
    protected PrinterDiscoveryCallback mDiscoveryCallback = null;
    protected final ServiceResolver mServiceResolver;
    protected final NsdManager mNSDManager;
    protected final VendorInfo mVendorInfo;
    private final int mVendorStringID = R.string.plugin_vendor_xerox;
    private final String PDL__PDF = "application/pdf";
    private final String[] mServices = new String[]{"_ipp._tcp"};

    public XeroxPrintServiceRecommendationPlugin(Context context) {
        mNSDManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        mVendorInfo = new VendorInfo(context.getResources(), R.array.known_print_vendor_info_for_xerox);
        mServiceResolver = new ServiceResolver(context, this, mVendorInfo, mServices, new String[]{PDL__PDF});
    }

    @Override
    public int getName() {
        return mVendorStringID;
    }

    @NonNull
    @Override
    public CharSequence getPackageName() {
        return mVendorInfo.mPackageName;
    }

    @Override
    public void start(@NonNull PrinterDiscoveryCallback callback) throws Exception {
        synchronized (mLock) {
            mDiscoveryCallback = callback;
            mServiceResolver.start();
        }
    }

    @Override
    public void stop() throws Exception {
        synchronized (mLock) {
            mDiscoveryCallback = null;
            mServiceResolver.stop();
        }
    }

    @Override
    public void dataSetChanged() {
        synchronized (mLock) {
            if (mDiscoveryCallback != null) {
                mDiscoveryCallback.onChanged(mServiceResolver.getPrinters());
            }
        }
    }
}
