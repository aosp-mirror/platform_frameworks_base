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
import android.hardware.location.HubInfo;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.IndentingPrintWriter;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

class HubInfoRegistry implements ContextHubHalEndpointCallback.IEndpointLifecycleCallback {
    private static final String TAG = "HubInfoRegistry";
    private final Object mLock = new Object();

    private final IContextHubWrapper mContextHubWrapper;

    @GuardedBy("mLock")
    private List<HubInfo> mHubsInfo;

    @GuardedBy("mLock")
    private final ArrayMap<HubEndpointInfo.HubEndpointIdentifier, HubEndpointInfo>
            mHubEndpointInfos = new ArrayMap<>();

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
    }

    @Override
    public void onEndpointStopped(
            HubEndpointInfo.HubEndpointIdentifier[] endpointIds, byte reason) {
        synchronized (mLock) {
            for (HubEndpointInfo.HubEndpointIdentifier endpointId : endpointIds) {
                mHubEndpointInfos.remove(endpointId);
            }
        }
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
