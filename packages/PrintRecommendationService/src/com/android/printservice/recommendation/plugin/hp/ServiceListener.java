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

package com.android.printservice.recommendation.plugin.hp;

import android.content.Context;
import android.content.res.TypedArray;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.text.TextUtils;

import androidx.annotation.GuardedBy;

import com.android.printservice.recommendation.R;
import com.android.printservice.recommendation.util.DiscoveryListenerMultiplexer;
import com.android.printservice.recommendation.util.PrinterHashMap;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ServiceListener implements ServiceResolveQueue.ResolveCallback {

    private final NsdManager mNSDManager;
    private final Map<String, VendorInfo> mVendorInfoHashMap;
    private final String[] mServiceType;
    private final Observer mObserver;
    private final ServiceResolveQueue mResolveQueue;
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private List<NsdManager.DiscoveryListener> mListeners = new ArrayList<>();

    @GuardedBy("mLock")
    public HashMap<String, PrinterHashMap> mVendorHashMap = new HashMap<>();

    public interface Observer {
        boolean matchesCriteria(String vendor, NsdServiceInfo serviceInfo);
        void dataSetChanged();
    }

    public ServiceListener(Context context, Observer observer, String[] serviceTypes) {
        mObserver = observer;
        mServiceType = serviceTypes;
        mNSDManager = (NsdManager)context.getSystemService(Context.NSD_SERVICE);
        mResolveQueue = ServiceResolveQueue.getInstance(mNSDManager);

        Map<String, VendorInfo> vendorInfoMap = new HashMap<>();
        TypedArray testArray = context.getResources().obtainTypedArray(R.array.known_print_plugin_vendors);
        for(int i = 0; i < testArray.length(); i++) {
            int arrayID = testArray.getResourceId(i, 0);
            if (arrayID != 0) {
                VendorInfo info = new VendorInfo(context.getResources(), arrayID);
                vendorInfoMap.put(info.mVendorID, info);
                vendorInfoMap.put(info.mPackageName, info);
            }
        }
        testArray.recycle();
        mVendorInfoHashMap = vendorInfoMap;
    }

    @Override
    public void serviceResolved(NsdServiceInfo nsdServiceInfo) {
        printerFound(nsdServiceInfo);
    }

    private void printerFound(NsdServiceInfo nsdServiceInfo) {
        if (nsdServiceInfo == null) return;
        if (TextUtils.isEmpty(PrinterHashMap.getKey(nsdServiceInfo))) return;
        String vendor = MDnsUtils.getVendor(nsdServiceInfo);
        if (vendor == null) vendor = "";

        boolean mapsChanged;
        synchronized (mLock) {
            for (Map.Entry<String, VendorInfo> entry : mVendorInfoHashMap.entrySet()) {
                for (String vendorValues : entry.getValue().mDNSValues) {
                    if (vendor.equalsIgnoreCase(vendorValues)) {
                        vendor = entry.getValue().mVendorID;
                        break;
                    }
                }
                // intentional pointer check
                //noinspection StringEquality
                if ((vendor != entry.getValue().mVendorID) &&
                        MDnsUtils.isVendorPrinter(nsdServiceInfo, entry.getValue().mDNSValues)) {
                    vendor = entry.getValue().mVendorID;
                }
                // intentional pointer check
                //noinspection StringEquality
                if (vendor == entry.getValue().mVendorID) break;
            }

            if (TextUtils.isEmpty(vendor)) {
                return;
            }

            if (!mObserver.matchesCriteria(vendor, nsdServiceInfo))
                return;

            PrinterHashMap vendorHash = mVendorHashMap.get(vendor);
            if (vendorHash == null) {
                vendorHash = new PrinterHashMap();
            }
            mapsChanged = (vendorHash.addPrinter(nsdServiceInfo) == null);
            mVendorHashMap.put(vendor, vendorHash);
        }

        if (mapsChanged) {
            mObserver.dataSetChanged();
        }
    }

    private void printerRemoved(NsdServiceInfo nsdServiceInfo) {
        boolean wasRemoved = false;

        synchronized (mLock) {
            Set<String> vendors = mVendorHashMap.keySet();
            for (String vendor : vendors) {
                PrinterHashMap map = mVendorHashMap.get(vendor);
                wasRemoved |= (map.removePrinter(nsdServiceInfo) != null);
                if (map.isEmpty()) wasRemoved |= (mVendorHashMap.remove(vendor) != null);
            }
        }

        if (wasRemoved) {
            mObserver.dataSetChanged();
        }
    }

    public void start() {
        synchronized (mLock) {
            stop();

            for (final String service : mServiceType) {
                NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
                    @Override
                    public void onStartDiscoveryFailed(String s, int i) {

                    }

                    @Override
                    public void onStopDiscoveryFailed(String s, int i) {

                    }

                    @Override
                    public void onDiscoveryStarted(String s) {

                    }

                    @Override
                    public void onDiscoveryStopped(String s) {

                    }

                    @Override
                    public void onServiceFound(NsdServiceInfo nsdServiceInfo) {
                        mResolveQueue.queueRequest(nsdServiceInfo, ServiceListener.this);
                    }

                    @Override
                    public void onServiceLost(NsdServiceInfo nsdServiceInfo) {
                        mResolveQueue.removeRequest(nsdServiceInfo, ServiceListener.this);
                        printerRemoved(nsdServiceInfo);
                    }
                };
                DiscoveryListenerMultiplexer.addListener(mNSDManager, service, listener);
                mListeners.add(listener);
            }
        }
    }

    public void stop() {
        synchronized (mLock) {
            for (NsdManager.DiscoveryListener listener : mListeners) {
                DiscoveryListenerMultiplexer.removeListener(mNSDManager, listener);
            }
            mVendorHashMap.clear();
            mListeners.clear();
        }
    }

    /**
     * @return The {@link InetAddress addresses} of the discovered printers
     */
    public ArrayList<InetAddress> getPrinters() {
        ArrayList<InetAddress> printerAddressess = new ArrayList<>();

        synchronized (mLock) {
            for (PrinterHashMap oneVendorPrinters : mVendorHashMap.values()) {
                printerAddressess.addAll(oneVendorPrinters.getPrinterAddresses());
            }
        }

        return printerAddressess;
    }
}
