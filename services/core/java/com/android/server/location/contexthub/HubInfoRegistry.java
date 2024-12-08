/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.location.contexthub;

import android.hardware.contexthub.HubEndpointInfo;
import android.hardware.contexthub.HubServiceInfo;
import android.hardware.contexthub.IContextHubEndpointDiscoveryCallback;
import android.hardware.location.HubInfo;
import android.os.DeadObjectException;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.IndentingPrintWriter;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;

class HubInfoRegistry implements ContextHubHalEndpointCallback.IEndpointLifecycleCallback {
    private static final String TAG = "HubInfoRegistry";
    private final Object mLock = new Object();

    private final IContextHubWrapper mContextHubWrapper;

    @GuardedBy("mLock")
    private List<HubInfo> mHubsInfo;

    @GuardedBy("mLock")
    private final ArrayMap<HubEndpointInfo.HubEndpointIdentifier, HubEndpointInfo>
            mHubEndpointInfos = new ArrayMap<>();

    /**
     * A wrapper class that is used to store arguments to
     * ContextHubManager.registerEndpointCallback.
     */
    private static class DiscoveryCallback {
        private final IContextHubEndpointDiscoveryCallback mCallback;
        private final Optional<Long> mEndpointId;
        private final Optional<String> mServiceDescriptor;

        DiscoveryCallback(IContextHubEndpointDiscoveryCallback callback, long endpointId) {
            mCallback = callback;
            mEndpointId = Optional.of(endpointId);
            mServiceDescriptor = Optional.empty();
        }

        DiscoveryCallback(IContextHubEndpointDiscoveryCallback callback, String serviceDescriptor) {
            mCallback = callback;
            mEndpointId = Optional.empty();
            mServiceDescriptor = Optional.of(serviceDescriptor);
        }

        public IContextHubEndpointDiscoveryCallback getCallback() {
            return mCallback;
        }

        /**
         * @param info The hub endpoint info to check
         * @return true if info matches
         */
        public boolean isMatch(HubEndpointInfo info) {
            if (mEndpointId.isPresent()) {
                return mEndpointId.get() == info.getIdentifier().getEndpoint();
            }
            if (mServiceDescriptor.isPresent()) {
                for (HubServiceInfo serviceInfo : info.getServiceInfoCollection()) {
                    if (mServiceDescriptor.get().equals(serviceInfo.getServiceDescriptor())) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /* The list of discovery callbacks registered with the service */
    @GuardedBy("mCallbackLock")
    private final List<DiscoveryCallback> mEndpointDiscoveryCallbacks = new ArrayList<>();

    private final Object mCallbackLock = new Object();

    HubInfoRegistry(IContextHubWrapper contextHubWrapper) {
        mContextHubWrapper = contextHubWrapper;
        refreshCachedHubs();
        refreshCachedEndpoints();
    }

    /** Retrieve the list of hubs available. */
    List<HubInfo> getHubs() {
        synchronized (mLock) {
            return mHubsInfo;
        }
    }

    private void refreshCachedHubs() {
        List<HubInfo> hubInfos;
        try {
            hubInfos = mContextHubWrapper.getHubs();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException while getting Hub info", e);
            hubInfos = Collections.emptyList();
        }

        synchronized (mLock) {
            mHubsInfo = hubInfos;
        }
    }

    private void refreshCachedEndpoints() {
        List<HubEndpointInfo> endpointInfos;
        try {
            endpointInfos = mContextHubWrapper.getEndpoints();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException while getting Hub info", e);
            endpointInfos = Collections.emptyList();
        }

        synchronized (mLock) {
            mHubEndpointInfos.clear();
            for (HubEndpointInfo endpointInfo : endpointInfos) {
                mHubEndpointInfos.put(endpointInfo.getIdentifier(), endpointInfo);
            }
        }
    }

    public HubEndpointInfo getEndpointInfo(HubEndpointInfo.HubEndpointIdentifier id) {
        synchronized (mLock) {
            return mHubEndpointInfos.get(id);
        }
    }

    /** Invoked when HAL restarts */
    public void onHalRestart() {
        synchronized (mLock) {
            refreshCachedHubs();
            refreshCachedEndpoints();
        }
    }

    @Override
    public void onEndpointStarted(HubEndpointInfo[] endpointInfos) {
        synchronized (mLock) {
            for (HubEndpointInfo endpointInfo : endpointInfos) {
                mHubEndpointInfos.remove(endpointInfo.getIdentifier());
                mHubEndpointInfos.put(endpointInfo.getIdentifier(), endpointInfo);
            }
        }

        invokeForMatchingEndpoints(
                endpointInfos,
                (cb, infoList) -> {
                    try {
                        cb.onEndpointsStarted(infoList);
                    } catch (RemoteException e) {
                        if (e instanceof DeadObjectException) {
                            Log.w(TAG, "onEndpointStarted: callback died, unregistering");
                            unregisterEndpointDiscoveryCallback(cb);
                        } else {
                            Log.e(TAG, "Exception while calling onEndpointsStarted", e);
                        }
                    }
                });
    }

    @Override
    public void onEndpointStopped(
            HubEndpointInfo.HubEndpointIdentifier[] endpointIds, byte reason) {
        ArrayList<HubEndpointInfo> removedInfoList = new ArrayList<>();
        synchronized (mLock) {
            for (HubEndpointInfo.HubEndpointIdentifier endpointId : endpointIds) {
                HubEndpointInfo info = mHubEndpointInfos.remove(endpointId);
                if (info != null) {
                    removedInfoList.add(info);
                }
            }
        }

        invokeForMatchingEndpoints(
                removedInfoList.toArray(new HubEndpointInfo[removedInfoList.size()]),
                (cb, infoList) -> {
                    try {
                        cb.onEndpointsStopped(infoList, reason);
                    } catch (RemoteException e) {
                        if (e instanceof DeadObjectException) {
                            Log.w(TAG, "onEndpointStopped: callback died, unregistering");
                            unregisterEndpointDiscoveryCallback(cb);
                        } else {
                            Log.e(TAG, "Exception while calling onEndpointsStopped", e);
                        }
                    }
                });
    }

    /** Return a list of {@link HubEndpointInfo} that represents endpoints with the matching id. */
    public List<HubEndpointInfo> findEndpoints(long endpointIdQuery) {
        List<HubEndpointInfo> searchResult = new ArrayList<>();
        synchronized (mLock) {
            for (HubEndpointInfo.HubEndpointIdentifier endpointId : mHubEndpointInfos.keySet()) {
                if (endpointId.getEndpoint() == endpointIdQuery) {
                    searchResult.add(mHubEndpointInfos.get(endpointId));
                }
            }
        }
        return searchResult;
    }

    /**
     * Return a list of {@link HubEndpointInfo} that represents endpoints with the matching service.
     */
    public List<HubEndpointInfo> findEndpointsWithService(String serviceDescriptor) {
        List<HubEndpointInfo> searchResult = new ArrayList<>();
        synchronized (mLock) {
            for (HubEndpointInfo endpointInfo : mHubEndpointInfos.values()) {
                for (HubServiceInfo serviceInfo : endpointInfo.getServiceInfoCollection()) {
                    if (serviceDescriptor.equals(serviceInfo.getServiceDescriptor())) {
                        searchResult.add(endpointInfo);
                    }
                }
            }
        }
        return searchResult;
    }

    /* package */
    void registerEndpointDiscoveryCallback(
            long endpointId, IContextHubEndpointDiscoveryCallback callback) {
        Objects.requireNonNull(callback, "callback cannot be null");
        synchronized (mCallbackLock) {
            checkCallbackAlreadyRegistered(callback);
            mEndpointDiscoveryCallbacks.add(new DiscoveryCallback(callback, endpointId));
        }
    }

    /* package */
    void registerEndpointDiscoveryCallback(
            String serviceDescriptor, IContextHubEndpointDiscoveryCallback callback) {
        Objects.requireNonNull(callback, "callback cannot be null");
        synchronized (mCallbackLock) {
            checkCallbackAlreadyRegistered(callback);
            mEndpointDiscoveryCallbacks.add(new DiscoveryCallback(callback, serviceDescriptor));
        }
    }

    /* package */
    void unregisterEndpointDiscoveryCallback(IContextHubEndpointDiscoveryCallback callback) {
        Objects.requireNonNull(callback, "callback cannot be null");
        synchronized (mCallbackLock) {
            for (DiscoveryCallback discoveryCallback : mEndpointDiscoveryCallbacks) {
                if (discoveryCallback.getCallback().asBinder() == callback.asBinder()) {
                    mEndpointDiscoveryCallbacks.remove(discoveryCallback);
                    break;
                }
            }
        }
    }

    private void checkCallbackAlreadyRegistered(
            IContextHubEndpointDiscoveryCallback callback) {
        synchronized (mCallbackLock) {
            for (DiscoveryCallback discoveryCallback : mEndpointDiscoveryCallbacks) {
                if (discoveryCallback.mCallback.asBinder() == callback.asBinder()) {
                    throw new IllegalArgumentException("Callback is already registered");
                }
            }
        }
    }

    /**
     * Iterates through all registered discovery callbacks and invokes a given callback for those
     * that match the endpoints the callback is targeted for.
     *
     * @param endpointInfos The list of endpoint infos to check for a match.
     * @param consumer The callback to invoke, which consumes the callback object and the list of
     *     matched endpoint infos.
     */
    private void invokeForMatchingEndpoints(
            HubEndpointInfo[] endpointInfos,
            BiConsumer<IContextHubEndpointDiscoveryCallback, HubEndpointInfo[]> consumer) {
        synchronized (mCallbackLock) {
            for (DiscoveryCallback discoveryCallback : mEndpointDiscoveryCallbacks) {
                ArrayList<HubEndpointInfo> infoList = new ArrayList<>();
                for (HubEndpointInfo endpointInfo : endpointInfos) {
                    if (discoveryCallback.isMatch(endpointInfo)) {
                        infoList.add(endpointInfo);
                    }
                }

                consumer.accept(
                        discoveryCallback.getCallback(),
                        infoList.toArray(new HubEndpointInfo[infoList.size()]));
            }
        }
    }

    void dump(IndentingPrintWriter ipw) {
        synchronized (mLock) {
            dumpLocked(ipw);
        }
    }

    @GuardedBy("mLock")
    private void dumpLocked(IndentingPrintWriter ipw) {
        ipw.println(TAG);

        ipw.increaseIndent();
        ipw.println("Hubs");
        for (HubInfo hubInfo : mHubsInfo) {
            ipw.println(hubInfo);
        }
        ipw.decreaseIndent();

        ipw.println();

        ipw.increaseIndent();
        ipw.println("Endpoints");
        for (HubEndpointInfo endpointInfo : mHubEndpointInfos.values()) {
            ipw.println(endpointInfo);
        }
        ipw.decreaseIndent();

        ipw.println();
    }
}
