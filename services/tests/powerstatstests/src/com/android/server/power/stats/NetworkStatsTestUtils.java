/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.server.power.stats;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.NetworkStats;
import android.platform.test.ravenwood.RavenwoodRule;

import java.util.ArrayList;
import java.util.List;

public class NetworkStatsTestUtils {
    /**
     * Equivalent to NetworkStats.subtract, reimplementing the method for Ravenwood tests.
     */
    @NonNull
    public static NetworkStats networkStatsDelta(@NonNull NetworkStats currentStats,
            @Nullable NetworkStats lastStats) {
        if (!RavenwoodRule.isOnRavenwood()) {
            if (lastStats == null) {
                return currentStats;
            }
            return currentStats.subtract(lastStats);
        }

        List<NetworkStats.Entry> entries = new ArrayList<>();
        for (NetworkStats.Entry entry : currentStats) {
            NetworkStats.Entry lastEntry = null;
            int uid = entry.getUid();
            if (lastStats != null) {
                for (NetworkStats.Entry e : lastStats) {
                    if (e.getUid() == uid && e.getSet() == entry.getSet()
                            && e.getTag() == entry.getTag()
                            && e.getMetered() == entry.getMetered()
                            && e.getRoaming() == entry.getRoaming()
                            && e.getDefaultNetwork() == entry.getDefaultNetwork()
                        /*&& Objects.equals(e.getIface(), entry.getIface())*/) {
                        lastEntry = e;
                        break;
                    }
                }
            }
            long rxBytes, rxPackets, txBytes, txPackets;
            if (lastEntry != null) {
                rxBytes = Math.max(0, entry.getRxBytes() - lastEntry.getRxBytes());
                rxPackets = Math.max(0, entry.getRxPackets() - lastEntry.getRxPackets());
                txBytes = Math.max(0, entry.getTxBytes() - lastEntry.getTxBytes());
                txPackets = Math.max(0, entry.getTxPackets() - lastEntry.getTxPackets());
            } else {
                rxBytes = entry.getRxBytes();
                rxPackets = entry.getRxPackets();
                txBytes = entry.getTxBytes();
                txPackets = entry.getTxPackets();
            }

            NetworkStats.Entry uidEntry = mock(NetworkStats.Entry.class);
            when(uidEntry.getUid()).thenReturn(uid);
            when(uidEntry.getRxBytes()).thenReturn(rxBytes);
            when(uidEntry.getRxPackets()).thenReturn(rxPackets);
            when(uidEntry.getTxBytes()).thenReturn(txBytes);
            when(uidEntry.getTxPackets()).thenReturn(txPackets);

            entries.add(uidEntry);
        }
        NetworkStats delta = mock(NetworkStats.class);
        when(delta.iterator()).thenAnswer(inv -> entries.iterator());
        return delta;
    }
}
