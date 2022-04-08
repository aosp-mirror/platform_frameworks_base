/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server;

import android.annotation.NonNull;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

/**
 * Class used to reserve and release net IDs.
 *
 * <p>Instances of this class are thread-safe.
 */
public class NetIdManager {
    // Sequence number for Networks; keep in sync with system/netd/NetworkController.cpp
    public static final int MIN_NET_ID = 100; // some reserved marks
    // Top IDs reserved by IpSecService
    public static final int MAX_NET_ID = 65535 - IpSecService.TUN_INTF_NETID_RANGE;

    @GuardedBy("mNetIdInUse")
    private final SparseBooleanArray mNetIdInUse = new SparseBooleanArray();

    @GuardedBy("mNetIdInUse")
    private int mLastNetId = MIN_NET_ID - 1;

    private final int mMaxNetId;

    public NetIdManager() {
        this(MAX_NET_ID);
    }

    @VisibleForTesting
    NetIdManager(int maxNetId) {
        mMaxNetId = maxNetId;
    }

    /**
     * Get the first netId that follows the provided lastId and is available.
     */
    private int getNextAvailableNetIdLocked(
            int lastId, @NonNull SparseBooleanArray netIdInUse) {
        int netId = lastId;
        for (int i = MIN_NET_ID; i <= mMaxNetId; i++) {
            netId = netId < mMaxNetId ? netId + 1 : MIN_NET_ID;
            if (!netIdInUse.get(netId)) {
                return netId;
            }
        }
        throw new IllegalStateException("No free netIds");
    }

    /**
     * Reserve a new ID for a network.
     */
    public int reserveNetId() {
        synchronized (mNetIdInUse) {
            mLastNetId = getNextAvailableNetIdLocked(mLastNetId, mNetIdInUse);
            // Make sure NetID unused.  http://b/16815182
            mNetIdInUse.put(mLastNetId, true);
            return mLastNetId;
        }
    }

    /**
     * Clear a previously reserved ID for a network.
     */
    public void releaseNetId(int id) {
        synchronized (mNetIdInUse) {
            mNetIdInUse.delete(id);
        }
    }
}
