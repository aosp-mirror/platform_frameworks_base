/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.net;

import static android.net.NetworkStats.DEFAULT_NETWORK_ALL;
import static android.net.NetworkStats.DEFAULT_NETWORK_NO;
import static android.net.NetworkStats.DEFAULT_NETWORK_YES;
import static android.net.NetworkStats.METERED_ALL;
import static android.net.NetworkStats.METERED_NO;
import static android.net.NetworkStats.METERED_YES;
import static android.net.NetworkStats.ROAMING_ALL;
import static android.net.NetworkStats.ROAMING_NO;
import static android.net.NetworkStats.ROAMING_YES;
import static android.net.NetworkStats.SET_ALL;
import static android.net.NetworkStats.SET_DEFAULT;
import static android.net.NetworkStats.SET_FOREGROUND;
import static android.net.NetworkStats.TAG_NONE;

import static org.junit.Assert.assertEquals;

import android.net.NetworkStats;

import com.android.internal.net.VpnInfo;

/** Superclass with utilities for NetworkStats(Service|Factory)Test */
abstract class NetworkStatsBaseTest {
    static final String TEST_IFACE = "test0";
    static final String TEST_IFACE2 = "test1";
    static final String TUN_IFACE = "test_nss_tun0";

    static final int UID_RED = 1001;
    static final int UID_BLUE = 1002;
    static final int UID_GREEN = 1003;
    static final int UID_VPN = 1004;

    void assertValues(NetworkStats stats, String iface, int uid, long rxBytes,
            long rxPackets, long txBytes, long txPackets) {
        assertValues(
                stats, iface, uid, SET_ALL, TAG_NONE, METERED_ALL, ROAMING_ALL, DEFAULT_NETWORK_ALL,
                rxBytes, rxPackets, txBytes, txPackets, 0);
    }

    void assertValues(NetworkStats stats, String iface, int uid, int set, int tag,
            int metered, int roaming, int defaultNetwork, long rxBytes, long rxPackets,
            long txBytes, long txPackets, long operations) {
        final NetworkStats.Entry entry = new NetworkStats.Entry();
        final int[] sets;
        if (set == SET_ALL) {
            sets = new int[] {SET_ALL, SET_DEFAULT, SET_FOREGROUND};
        } else {
            sets = new int[] {set};
        }

        final int[] roamings;
        if (roaming == ROAMING_ALL) {
            roamings = new int[] {ROAMING_ALL, ROAMING_YES, ROAMING_NO};
        } else {
            roamings = new int[] {roaming};
        }

        final int[] meterings;
        if (metered == METERED_ALL) {
            meterings = new int[] {METERED_ALL, METERED_YES, METERED_NO};
        } else {
            meterings = new int[] {metered};
        }

        final int[] defaultNetworks;
        if (defaultNetwork == DEFAULT_NETWORK_ALL) {
            defaultNetworks =
                    new int[] {DEFAULT_NETWORK_ALL, DEFAULT_NETWORK_YES, DEFAULT_NETWORK_NO};
        } else {
            defaultNetworks = new int[] {defaultNetwork};
        }

        for (int s : sets) {
            for (int r : roamings) {
                for (int m : meterings) {
                    for (int d : defaultNetworks) {
                        final int i = stats.findIndex(iface, uid, s, tag, m, r, d);
                        if (i != -1) {
                            entry.add(stats.getValues(i, null));
                        }
                    }
                }
            }
        }

        assertEquals("unexpected rxBytes", rxBytes, entry.rxBytes);
        assertEquals("unexpected rxPackets", rxPackets, entry.rxPackets);
        assertEquals("unexpected txBytes", txBytes, entry.txBytes);
        assertEquals("unexpected txPackets", txPackets, entry.txPackets);
        assertEquals("unexpected operations", operations, entry.operations);
    }

    VpnInfo createVpnInfo(String[] underlyingIfaces) {
        VpnInfo info = new VpnInfo();
        info.ownerUid = UID_VPN;
        info.vpnIface = TUN_IFACE;
        info.underlyingIfaces = underlyingIfaces;
        return info;
    }
}
