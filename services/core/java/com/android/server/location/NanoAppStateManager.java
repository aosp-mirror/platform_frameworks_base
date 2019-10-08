/*
 * Copyright 2017 The Android Open Source Project
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

package com.android.server.location;

import android.annotation.Nullable;
import android.hardware.contexthub.V1_0.HubAppInfo;
import android.hardware.location.NanoAppInstanceInfo;
import android.util.Log;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Manages the state of loaded nanoapps at the Context Hubs.
 *
 * This class maintains a list of nanoapps that have been informed as loaded at the hubs. The state
 * should be updated based on the hub callbacks (defined in IContexthubCallback.hal), as a result
 * of either loadNanoApp, unloadNanoApp, or queryApps.
 *
 * The state tracked by this manager is used by clients of ContextHubService that use the old APIs.
 *
 * TODO(b/69270990): Remove this class and its logic once the old API is deprecated.
 *
 * @hide
 */
/* package */ class NanoAppStateManager {
    private static final String TAG = "NanoAppStateManager";

    /*
     * Enables verbose debug logs for this class.
     */
    private static final boolean ENABLE_LOG_DEBUG = true;

    /*
     * Service cache maintaining of handle to nanoapp infos.
     */
    private final HashMap<Integer, NanoAppInstanceInfo> mNanoAppHash = new HashMap<>();

    /*
     * The next nanoapp handle to use.
     */
    private int mNextHandle = 0;

    /**
     * @param nanoAppHandle the nanoapp handle
     * @return the NanoAppInstanceInfo for the given nanoapp, or null if the nanoapp does not exist
     *         in the cache
     */
    @Nullable
    /* package */
    synchronized NanoAppInstanceInfo getNanoAppInstanceInfo(int nanoAppHandle) {
        return mNanoAppHash.get(nanoAppHandle);
    }

    /**
     * Invokes a Consumer operation for each NanoAppInstanceInfo entry in the cache
     *
     * @param consumer the Consumer operation to perform
     */
    /* package */
    synchronized void foreachNanoAppInstanceInfo(Consumer<NanoAppInstanceInfo> consumer) {
        for (NanoAppInstanceInfo info : mNanoAppHash.values()) {
            consumer.accept(info);
        }
    }

    /**
     * @param contextHubId the ID of the hub to search for the instance
     * @param nanoAppId the unique 64-bit ID of the nanoapp
     * @return the nanoapp handle, -1 if the nanoapp is not in the cache
     */
    /* package */
    synchronized int getNanoAppHandle(int contextHubId, long nanoAppId) {
        for (NanoAppInstanceInfo info : mNanoAppHash.values()) {
            if (info.getContexthubId() == contextHubId && info.getAppId() == nanoAppId) {
                return info.getHandle();
            }
        }

        return -1;
    }

    /**
     * Adds a nanoapp instance to the cache.
     *
     * If the cache already contained the nanoapp, the entry is removed and a new nanoapp handle is
     * generated.
     *
     * @param contextHubId the ID of the hub the nanoapp is loaded in
     * @param nanoAppId the unique 64-bit ID of the nanoapp
     * @param nanoAppVersion the version of the nanoapp
     */
    /* package */
    synchronized void addNanoAppInstance(int contextHubId, long nanoAppId, int nanoAppVersion) {
        removeNanoAppInstance(contextHubId, nanoAppId);
        if (mNanoAppHash.size() == Integer.MAX_VALUE) {
            Log.e(TAG, "Error adding nanoapp instance: max limit exceeded");
            return;
        }

        int nanoAppHandle = mNextHandle;
        for (int i = 0; i <= Integer.MAX_VALUE; i++) {
            if (!mNanoAppHash.containsKey(nanoAppHandle)) {
                mNanoAppHash.put(nanoAppHandle, new NanoAppInstanceInfo(
                        nanoAppHandle, nanoAppId, nanoAppVersion, contextHubId));
                mNextHandle = (nanoAppHandle == Integer.MAX_VALUE) ? 0 : nanoAppHandle + 1;
                break;
            }
            nanoAppHandle = (nanoAppHandle == Integer.MAX_VALUE) ? 0 : nanoAppHandle + 1;
        }

        if (ENABLE_LOG_DEBUG) {
            Log.v(TAG, "Added app instance with handle " + nanoAppHandle + " to hub "
                    + contextHubId + ": ID=0x" + Long.toHexString(nanoAppId)
                    + ", version=0x" + Integer.toHexString(nanoAppVersion));
        }
    }

    /**
     * Removes a nanoapp instance from the cache.
     *
     * @param nanoAppId the ID of the nanoapp to remove the instance of
     */
    /* package */
    synchronized void removeNanoAppInstance(int contextHubId, long nanoAppId) {
        int nanoAppHandle = getNanoAppHandle(contextHubId, nanoAppId);
        mNanoAppHash.remove(nanoAppHandle);
    }

    /**
     * Performs a batch update of the nanoapp cache given a nanoapp query response.
     *
     * @param contextHubId    the ID of the hub the response came from
     * @param nanoAppInfoList the list of loaded nanoapps
     */
    /* package */
    synchronized void updateCache(int contextHubId, List<HubAppInfo> nanoAppInfoList) {
        HashSet<Long> nanoAppIdSet = new HashSet<>();
        for (HubAppInfo appInfo : nanoAppInfoList) {
            handleQueryAppEntry(contextHubId, appInfo.appId, appInfo.version);
            nanoAppIdSet.add(appInfo.appId);
        }

        Iterator<NanoAppInstanceInfo> iterator = mNanoAppHash.values().iterator();
        while (iterator.hasNext()) {
            NanoAppInstanceInfo info = iterator.next();
            if (info.getContexthubId() == contextHubId &&
                    !nanoAppIdSet.contains(info.getAppId())) {
                iterator.remove();
            }
        }
    }

    /**
     * If the nanoapp exists in the cache, then the entry is updated. Otherwise, inserts a new
     * instance of the nanoapp in the cache. This method should only be invoked from updateCache.
     *
     * @param contextHubId the ID of the hub the nanoapp is loaded in
     * @param nanoAppId the unique 64-bit ID of the nanoapp
     * @param nanoAppVersion the version of the nanoapp
     */
    private void handleQueryAppEntry(int contextHubId, long nanoAppId, int nanoAppVersion) {
        int nanoAppHandle = getNanoAppHandle(contextHubId, nanoAppId);
        if (nanoAppHandle == -1) {
            addNanoAppInstance(contextHubId, nanoAppId, nanoAppVersion);
        } else {
            NanoAppInstanceInfo info = mNanoAppHash.get(nanoAppHandle);
            if (info.getAppVersion() != nanoAppVersion) {
                mNanoAppHash.put(nanoAppHandle, new NanoAppInstanceInfo(
                        nanoAppHandle, nanoAppId, nanoAppVersion, contextHubId));
                if (ENABLE_LOG_DEBUG) {
                    Log.v(TAG, "Updated app instance with handle " + nanoAppHandle + " at hub "
                            + contextHubId + ": ID=0x" + Long.toHexString(nanoAppId)
                            + ", version=0x" + Integer.toHexString(nanoAppVersion));
                }
            }
        }
    }
}
