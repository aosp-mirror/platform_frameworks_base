/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.stats.pull.netstats;

import static android.net.NetworkTemplate.OEM_MANAGED_ALL;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.NetworkStats;
import android.telephony.TelephonyManager;

import java.util.Arrays;
import java.util.Objects;

/**
 * A data class to store a NetworkStats object with information associated to it.
 *
 * @hide
 */
public class NetworkStatsExt {
    @NonNull
    public final NetworkStats stats;
    public final int[] transports;
    public final boolean slicedByFgbg;
    public final boolean slicedByTag;
    public final boolean slicedByMetered;
    public final int ratType;
    public final int oemManaged;
    @Nullable
    public final SubInfo subInfo;

    public NetworkStatsExt(@NonNull NetworkStats stats, int[] transports, boolean slicedByFgbg) {
        this(stats, transports, slicedByFgbg, /*slicedByTag=*/false, /*slicedByMetered=*/false,
                TelephonyManager.NETWORK_TYPE_UNKNOWN, /*subInfo=*/null, OEM_MANAGED_ALL);
    }

    public NetworkStatsExt(@NonNull NetworkStats stats, int[] transports, boolean slicedByFgbg,
            boolean slicedByTag, boolean slicedByMetered, int ratType,
            @Nullable SubInfo subInfo, int oemManaged) {
        this.stats = stats;

        // Sort transports array so that we can test for equality without considering order.
        this.transports = Arrays.copyOf(transports, transports.length);
        Arrays.sort(this.transports);

        this.slicedByFgbg = slicedByFgbg;
        this.slicedByTag = slicedByTag;
        this.slicedByMetered = slicedByMetered;
        this.ratType = ratType;
        this.subInfo = subInfo;
        this.oemManaged = oemManaged;
    }

    /**
     * A helper function to compare if all fields except NetworkStats are the same.
     */
    public boolean hasSameSlicing(@NonNull NetworkStatsExt other) {
        return Arrays.equals(transports, other.transports) && slicedByFgbg == other.slicedByFgbg
                && slicedByTag == other.slicedByTag && slicedByMetered == other.slicedByMetered
                && ratType == other.ratType && Objects.equals(subInfo, other.subInfo)
                && oemManaged == other.oemManaged;
    }
}
