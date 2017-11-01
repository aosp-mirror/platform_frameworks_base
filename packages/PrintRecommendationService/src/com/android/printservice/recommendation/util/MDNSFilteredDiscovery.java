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
package com.android.printservice.recommendation.util;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import com.android.printservice.recommendation.PrintServicePlugin;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * A discovery listening for mDNS results and only adding the ones that {@link
 * PrinterFilter#matchesCriteria match} configured list
 */
public class MDNSFilteredDiscovery implements NsdManager.DiscoveryListener  {
    private static final String LOG_TAG = "MDNSFilteredDiscovery";

    /**
     * mDNS service filter interface.
     * Implement {@link PrinterFilter#matchesCriteria} to filter out supported services
     */
    public interface PrinterFilter {
        /**
         * Main filter method. Should return true if mDNS service is supported
         * by the print service plugin
         *
         * @param nsdServiceInfo The service info to check
         *
         * @return True if service is supported by the print service plugin
         */
        boolean matchesCriteria(NsdServiceInfo nsdServiceInfo);
    }

    /** Printer identifiers of the mPrinters found. */
    @GuardedBy("mLock")
    private final @NonNull HashSet<InetAddress> mPrinters;

    /** Service types discovered by this plugin */
    private final @NonNull HashSet<String> mServiceTypes;

    /** Context of the user of this plugin */
    private final @NonNull Context mContext;

    /** mDNS services filter */
    private final @NonNull PrinterFilter mPrinterFilter;

    /**
     * Call back to report the number of mPrinters found.
     *
     * We assume that {@link #start} and {@link #stop} are never called in parallel, hence it is
     * safe to not synchronize access to this field.
     */
    private @Nullable PrintServicePlugin.PrinterDiscoveryCallback mCallback;

    /** Queue used to resolve nsd infos */
    private final @NonNull NsdResolveQueue mResolveQueue;

    /**
     * Create new stub that assumes that a print service can be used to print on all mPrinters
     * matching some mDNS names.
     *
     * @param context       The context the plugin runs in
     * @param serviceTypes  The mDNS service types to listen to.
     * @param printerFilter The filter for mDNS services
     */
    public MDNSFilteredDiscovery(@NonNull Context context,
            @NonNull Set<String> serviceTypes,
            @NonNull PrinterFilter printerFilter) {
        mContext = Preconditions.checkNotNull(context, "context");
        mServiceTypes = new HashSet<>(Preconditions
                .checkCollectionNotEmpty(Preconditions.checkCollectionElementsNotNull(serviceTypes,
                        "serviceTypes"), "serviceTypes"));
        mPrinterFilter = Preconditions.checkNotNull(printerFilter, "printerFilter");

        mResolveQueue = NsdResolveQueue.getInstance();
        mPrinters = new HashSet<>();
    }

    /**
     * @return The NDS manager
     */
    private NsdManager getNDSManager() {
        return (NsdManager) mContext.getSystemService(Context.NSD_SERVICE);
    }

    /**
     * Start the discovery.
     *
     * @param callback Callbacks used by this plugin.
     */
    public void start(@NonNull PrintServicePlugin.PrinterDiscoveryCallback callback) {
        mCallback = callback;
        mCallback.onChanged(new ArrayList<>(mPrinters));

        for (String serviceType : mServiceTypes) {
            DiscoveryListenerMultiplexer.addListener(getNDSManager(), serviceType, this);
        }
    }

    /**
     * Stop the discovery. This can only return once the plugin is completely finished and cleaned up.
     */
    public void stop() {
        mCallback.onChanged(null);
        mCallback = null;

        for (int i = 0; i < mServiceTypes.size(); ++i) {
            DiscoveryListenerMultiplexer.removeListener(getNDSManager(), this);
        }
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
                        if (mPrinterFilter.matchesCriteria(serviceInfo)) {
                            if (mCallback != null) {
                                boolean added = mPrinters.add(serviceInfo.getHost());
                                if (added) {
                                    mCallback.onChanged(new ArrayList<>(mPrinters));
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
                        if (mPrinterFilter.matchesCriteria(serviceInfo)) {
                            if (mCallback != null) {
                                boolean removed = mPrinters.remove(serviceInfo.getHost());

                                if (removed) {
                                    mCallback.onChanged(new ArrayList<>(mPrinters));
                                }
                            }
                        }
                    }
                });
    }
}