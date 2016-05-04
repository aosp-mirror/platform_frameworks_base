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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import com.android.printservice.recommendation.PrintServicePlugin;
import com.android.printservice.recommendation.util.DiscoveryListenerMultiplexer;
import com.android.printservice.recommendation.util.NsdResolveQueue;

import java.util.HashSet;
import java.util.List;

/**
 * A plugin listening for mDNS results and only adding the ones that {@link
 * MDNSUtils#isVendorPrinter match} configured list
 */
public class MDNSFilterPlugin implements PrintServicePlugin, NsdManager.DiscoveryListener {
    private static final String LOG_TAG = "MDNSFilterPlugin";

    private static final String PRINTER_SERVICE_TYPE = "_ipp._tcp";

    /** Name of the print service this plugin is for */
    private final @StringRes int mName;

    /** Package name of the print service this plugin is for */
    private final @NonNull CharSequence mPackageName;

    /** mDNS names handled by the print service this plugin is for */
    private final @NonNull HashSet<String> mMDNSNames;

    /** Printer identifiers of the mPrinters found. */
    @GuardedBy("mLock")
    private final @NonNull HashSet<String> mPrinters;

    /** Context of the user of this plugin */
    private final @NonNull Context mContext;

    /**
     * Call back to report the number of mPrinters found.
     *
     * We assume that {@link #start} and {@link #stop} are never called in parallel, hence it is
     * safe to not synchronize access to this field.
     */
    private @Nullable PrinterDiscoveryCallback mCallback;

    /** Queue used to resolve nsd infos */
    private final @NonNull NsdResolveQueue mResolveQueue;

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
        mContext = Preconditions.checkNotNull(context, "context");
        mName = mContext.getResources().getIdentifier(Preconditions.checkStringNotEmpty(name,
                "name"), null, "com.android.printservice.recommendation");
        mPackageName = Preconditions.checkStringNotEmpty(packageName);
        mMDNSNames = new HashSet<>(Preconditions
                .checkCollectionNotEmpty(Preconditions.checkCollectionElementsNotNull(mDNSNames,
                        "mDNSNames"), "mDNSNames"));

        mResolveQueue = NsdResolveQueue.getInstance();
        mPrinters = new HashSet<>();
    }

    @Override
    public @NonNull CharSequence getPackageName() {
        return mPackageName;
    }

    /**
     * @return The NDS manager
     */
    private NsdManager getNDSManager() {
        return (NsdManager) mContext.getSystemService(Context.NSD_SERVICE);
    }

    @Override
    public void start(@NonNull PrinterDiscoveryCallback callback) throws Exception {
        mCallback = callback;

        DiscoveryListenerMultiplexer.addListener(getNDSManager(), PRINTER_SERVICE_TYPE, this);
    }

    @Override
    public @StringRes int getName() {
        return mName;
    }

    @Override
    public void stop() throws Exception {
        mCallback.onChanged(0);
        mCallback = null;

        DiscoveryListenerMultiplexer.removeListener(getNDSManager(), this);
    }

    @Override
    public void onStartDiscoveryFailed(String serviceType, int errorCode) {
        Log.w(LOG_TAG, "Failed to start network discovery for type " + serviceType + ": "
                + errorCode);
    }

    @Override
    public void onStopDiscoveryFailed(String serviceType, int errorCode) {
        Log.w(LOG_TAG, "Failed to stop network discovery for type " + serviceType + ": "
                + errorCode);
    }

    @Override
    public void onDiscoveryStarted(String serviceType) {
        // empty
    }

    @Override
    public void onDiscoveryStopped(String serviceType) {
        mPrinters.clear();
    }

    @Override
    public void onServiceFound(NsdServiceInfo serviceInfo) {
        mResolveQueue.resolve(getNDSManager(), serviceInfo,
                new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.w(LOG_TAG, "Service found: could not resolve " + serviceInfo + ": " +
                        errorCode);
            }

            @Override
            public void onServiceResolved(NsdServiceInfo serviceInfo) {
                if (MDNSUtils.isVendorPrinter(serviceInfo, mMDNSNames)) {
                    if (mCallback != null) {
                        boolean added = mPrinters.add(serviceInfo.getHost().getHostAddress());

                        if (added) {
                            mCallback.onChanged(mPrinters.size());
                        }
                    }
                }
            }
        });
    }

    @Override
    public void onServiceLost(NsdServiceInfo serviceInfo) {
        mResolveQueue.resolve(getNDSManager(), serviceInfo,
                new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.w(LOG_TAG, "Service lost: Could not resolve " + serviceInfo + ": "
                        + errorCode);
            }

            @Override
            public void onServiceResolved(NsdServiceInfo serviceInfo) {
                if (MDNSUtils.isVendorPrinter(serviceInfo, mMDNSNames)) {
                    if (mCallback != null) {
                        boolean removed = mPrinters
                                .remove(serviceInfo.getHost().getHostAddress());

                        if (removed) {
                            mCallback.onChanged(mPrinters.size());
                        }
                    }
                }
            }
        });
    }
}
