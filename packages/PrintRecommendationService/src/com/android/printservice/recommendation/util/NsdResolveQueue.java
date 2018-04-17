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

import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;

import java.util.LinkedList;

/**
 * Nsd resolve requests for the same info cancel each other. Hence this class synchronizes the
 * resolutions to hide this effect.
 */
public class NsdResolveQueue {
    /** Lock for {@link #sInstance} */
    private static final Object sLock = new Object();

    /** Instance of this singleton */
    @GuardedBy("sLock")
    private static NsdResolveQueue sInstance;

    /** Lock for {@link #mResolveRequests} */
    private final Object mLock = new Object();

    /** Current set of registered service info resolve attempts */
    @GuardedBy("mLock")
    private final LinkedList<NsdResolveRequest> mResolveRequests = new LinkedList<>();

    public static NsdResolveQueue getInstance() {
        synchronized (sLock) {
            if (sInstance == null) {
                sInstance = new NsdResolveQueue();
            }

            return sInstance;
        }
    }

    /**
     * Container for a request to resolve a serviceInfo.
     */
    private static class NsdResolveRequest {
        final @NonNull NsdManager nsdManager;
        final @NonNull NsdServiceInfo serviceInfo;
        final @NonNull NsdManager.ResolveListener listener;

        private NsdResolveRequest(@NonNull NsdManager nsdManager,
                @NonNull NsdServiceInfo serviceInfo, @NonNull NsdManager.ResolveListener listener) {
            this.nsdManager = nsdManager;
            this.serviceInfo = serviceInfo;
            this.listener = listener;
        }
    }

    /**
     * Resolve a serviceInfo or queue the request if there is a request currently in flight.
     *
     * @param nsdManager  The nsd manager to use
     * @param serviceInfo The service info to resolve
     * @param listener    The listener to call back once the info is resolved.
     */
    public void resolve(@NonNull NsdManager nsdManager, @NonNull NsdServiceInfo serviceInfo,
            @NonNull NsdManager.ResolveListener listener) {
        synchronized (mLock) {
            mResolveRequests.addLast(new NsdResolveRequest(nsdManager, serviceInfo,
                    new ListenerWrapper(listener)));

            if (mResolveRequests.size() == 1) {
                resolveNextRequest();
            }
        }
    }

    /**
     * Wrapper for a {@link NsdManager.ResolveListener}. Calls the listener and then
     * {@link #resolveNextRequest()}.
     */
    private class ListenerWrapper implements NsdManager.ResolveListener {
        private final @NonNull NsdManager.ResolveListener mListener;

        private ListenerWrapper(@NonNull NsdManager.ResolveListener listener) {
            mListener = listener;
        }

        @Override
        public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
            mListener.onResolveFailed(serviceInfo, errorCode);

            synchronized (mLock) {
                mResolveRequests.pop();
                resolveNextRequest();
            }
        }

        @Override
        public void onServiceResolved(NsdServiceInfo serviceInfo) {
            mListener.onServiceResolved(serviceInfo);

            synchronized (mLock) {
                mResolveRequests.pop();
                resolveNextRequest();
            }
        }
    }

    /**
     * Resolve the next request if there is one.
     */
    private void resolveNextRequest() {
        if (!mResolveRequests.isEmpty()) {
            NsdResolveRequest request = mResolveRequests.getFirst();

            request.nsdManager.resolveService(request.serviceInfo, request.listener);
        }
    }

}
