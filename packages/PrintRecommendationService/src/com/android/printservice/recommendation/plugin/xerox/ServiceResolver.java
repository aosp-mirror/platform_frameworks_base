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
import android.net.nsd.NsdServiceInfo;
import android.text.TextUtils;
import com.android.printservice.recommendation.util.DiscoveryListenerMultiplexer;
import com.android.printservice.recommendation.util.NsdResolveQueue;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

class ServiceResolver {

    private final NsdManager mNSDManager;
    private final String[] mServiceType;
    private final Observer mObserver;
    private final VendorInfo mVendorInfo;
    private final String[] mPDLs;
    private final PrinterHashMap mPrinterHashMap = new PrinterHashMap();
    private final List<NsdManager.DiscoveryListener> mListeners = new ArrayList<>();
    private final NsdResolveQueue mNsdResolveQueue;

    public interface Observer {
        void dataSetChanged();
    }

    public ServiceResolver(Context context, Observer observer, VendorInfo vendorInfo, String[] serviceTypes, String[] pdls) {
        mNsdResolveQueue = NsdResolveQueue.getInstance();
        mObserver = observer;
        mServiceType = serviceTypes;
        mNSDManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        mVendorInfo = vendorInfo;
        mPDLs = pdls;
    }

    public void start() {
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
                    queueRequest(nsdServiceInfo);
                }

                @Override
                public void onServiceLost(NsdServiceInfo nsdServiceInfo) {
                    removeRequest(nsdServiceInfo);
                    printerRemoved(nsdServiceInfo);
                }
            };
            DiscoveryListenerMultiplexer.addListener(mNSDManager, service, listener);
            mListeners.add(listener);
        }
    }

    public void stop() {
        for (NsdManager.DiscoveryListener listener : mListeners) {
            DiscoveryListenerMultiplexer.removeListener(mNSDManager, listener);
        }
        mListeners.clear();
        clearRequests();
    }

    //Resolving nsd services
    private final LinkedList<NsdServiceInfo> mQueue = new LinkedList<>();
    private final Object mLock = new Object();
    private NsdServiceInfo mCurrentRequest = null;

    private void queueRequest(NsdServiceInfo serviceInfo) {
        synchronized (mLock) {
            if (mQueue.contains(serviceInfo)) return;
            mQueue.add(serviceInfo);
            makeNextRequest();
        }
    }

    private void removeRequest(NsdServiceInfo serviceInfo) {
        synchronized (mLock) {
            mQueue.remove(serviceInfo);
            if ((mCurrentRequest != null) && serviceInfo.equals(mCurrentRequest))
                mCurrentRequest = null;
        }
    }

    private void clearRequests() {
        synchronized (mLock) {
            mQueue.clear();
        }
    }

    private void makeNextRequest() {
        synchronized (mLock) {
            if (mCurrentRequest != null) return;
            if (mQueue.isEmpty()) return;
            mCurrentRequest = mQueue.removeFirst();
            mNsdResolveQueue.resolve(mNSDManager, mCurrentRequest, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo nsdServiceInfo, int i) {
                    synchronized (mLock) {
                        if (mCurrentRequest != null) mQueue.add(mCurrentRequest);
                        makeNextRequest();
                    }
                }

                @Override
                public void onServiceResolved(NsdServiceInfo nsdServiceInfo) {
                    synchronized (mLock) {
                        if (mCurrentRequest != null) {
                            printerFound(nsdServiceInfo);
                            mCurrentRequest = null;
                        }
                        makeNextRequest();
                    }
                }
            });

        }
    }

    private void printerFound(NsdServiceInfo nsdServiceInfo) {
        if (nsdServiceInfo == null) return;
        if (TextUtils.isEmpty(PrinterHashMap.getKey(nsdServiceInfo))) return;
        String vendor = MDnsUtils.getVendor(nsdServiceInfo);
        if (vendor == null) vendor = "";

        for (String vendorValues : mVendorInfo.mDNSValues) {
            if (vendor.equalsIgnoreCase(vendorValues)) {
                vendor = mVendorInfo.mVendorID;
                break;
            }
        }

        if ((vendor != mVendorInfo.mVendorID) &&
                MDnsUtils.isVendorPrinter(nsdServiceInfo, mVendorInfo.mDNSValues)) {
            vendor = mVendorInfo.mVendorID;
        }

        if (!(vendor == mVendorInfo.mVendorID)) {
            return;
        }

        if (!MDnsUtils.checkPDLSupport(nsdServiceInfo, mPDLs)) {
            return;
        }

        if ((mPrinterHashMap.addPrinter(nsdServiceInfo) == null)) {
            mObserver.dataSetChanged();
        }

    }

    private void printerRemoved(NsdServiceInfo nsdServiceInfo) {
        if ((mPrinterHashMap.removePrinter(nsdServiceInfo) != null)) {
            mObserver.dataSetChanged();
        }
    }

    public int getCount() {
        return mPrinterHashMap.size();
    }

}
