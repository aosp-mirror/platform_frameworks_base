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

package com.android.printservice.recommendation.plugin.samsung;

import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Pair;
import com.android.printservice.recommendation.util.NsdResolveQueue;

import java.util.LinkedList;

final class ServiceResolveQueue {

    private final NsdManager mNsdManager;
    private final LinkedList<Pair<NsdServiceInfo, ResolveCallback>> mQueue = new LinkedList<>();
    private final Object mLock = new Object();

    private static Object sLock = new Object();
    private static ServiceResolveQueue sInstance = null;
    private final NsdResolveQueue mNsdResolveQueue;
    private Pair<NsdServiceInfo, ResolveCallback> mCurrentRequest = null;

    public static void createInstance(NsdManager nsdManager) {
        if (sInstance == null) sInstance = new ServiceResolveQueue(nsdManager);
    }

    public static ServiceResolveQueue getInstance(NsdManager nsdManager) {
        synchronized (sLock) {
            createInstance(nsdManager);
            return sInstance;
        }
    }

    public static void destroyInstance() {
        sInstance = null;
    }

    public interface ResolveCallback {
        void serviceResolved(NsdServiceInfo nsdServiceInfo);
    }

    public ServiceResolveQueue(NsdManager nsdManager) {
        mNsdManager = nsdManager;
        mNsdResolveQueue = NsdResolveQueue.getInstance();
    }

    public void queueRequest(NsdServiceInfo serviceInfo, ResolveCallback callback) {
        synchronized (mLock) {
            Pair<NsdServiceInfo, ResolveCallback> newRequest = Pair.create(serviceInfo, callback);
            if (mQueue.contains(newRequest)) return;
            mQueue.add(newRequest);
            makeNextRequest();
        }
    }

    public void removeRequest(NsdServiceInfo serviceInfo, ResolveCallback callback) {
        synchronized (mLock) {
            Pair<NsdServiceInfo, ResolveCallback> newRequest = Pair.create(serviceInfo, callback);
            mQueue.remove(newRequest);
            if ((mCurrentRequest != null) && newRequest.equals(mCurrentRequest)) mCurrentRequest = null;
        }
    }

    private void makeNextRequest() {
        synchronized (mLock) {
            if (mCurrentRequest != null) return;
            if (mQueue.isEmpty()) return;
            mCurrentRequest = mQueue.removeFirst();
            mNsdResolveQueue.resolve(mNsdManager, mCurrentRequest.first,
                    new NsdManager.ResolveListener() {
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
                                    mCurrentRequest.second.serviceResolved(nsdServiceInfo);
                                    mCurrentRequest = null;
                                }
                                makeNextRequest();
                            }
                        }
                    });

        }
    }


}
