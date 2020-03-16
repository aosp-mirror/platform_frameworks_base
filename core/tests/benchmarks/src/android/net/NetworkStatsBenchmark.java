/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package android.net;

import com.google.caliper.BeforeExperiment;
import com.google.caliper.Param;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class NetworkStatsBenchmark {
    private static final String[] UNDERLYING_IFACES = {"wlan0", "rmnet0"};
    private static final String TUN_IFACE = "tun0";
    private static final int TUN_UID = 999999999;

    @Param({"100", "1000"})
    private int mSize;
    /**
     * Should not be more than the length of {@link #UNDERLYING_IFACES}.
     */
    @Param({"1", "2"})
    private int mNumUnderlyingIfaces;
    private NetworkStats mNetworkStats;

    @BeforeExperiment
    protected void setUp() throws Exception {
        mNetworkStats = new NetworkStats(0, mSize + 2);
        int uid = 0;
        NetworkStats.Entry recycle = new NetworkStats.Entry();
        final List<String> allIfaces = getAllIfacesForBenchmark(); // also contains TUN_IFACE.
        final int totalIfaces = allIfaces.size();
        for (int i = 0; i < mSize; i++) {
            recycle.iface = allIfaces.get(i % totalIfaces);
            recycle.uid = uid;
            recycle.set = i % 2;
            recycle.tag = NetworkStats.TAG_NONE;
            recycle.rxBytes = 60000;
            recycle.rxPackets = 60;
            recycle.txBytes = 150000;
            recycle.txPackets = 1500;
            recycle.operations = 0;
            mNetworkStats.insertEntry(recycle);
            if (recycle.set == 1) {
                uid++;
            }
        }

        for (int i = 0; i < mNumUnderlyingIfaces; i++) {
            recycle.iface = UNDERLYING_IFACES[i];
            recycle.uid = TUN_UID;
            recycle.set = NetworkStats.SET_FOREGROUND;
            recycle.tag = NetworkStats.TAG_NONE;
            recycle.rxBytes = 90000 * mSize;
            recycle.rxPackets = 40 * mSize;
            recycle.txBytes = 180000 * mSize;
            recycle.txPackets = 1200 * mSize;
            recycle.operations = 0;
            mNetworkStats.insertEntry(recycle);
        }
    }

    private String[] getVpnUnderlyingIfaces() {
        return Arrays.copyOf(UNDERLYING_IFACES, mNumUnderlyingIfaces);
    }

    /**
     * Same as {@link #getVpnUnderlyingIfaces}, but also contains {@link #TUN_IFACE}.
     */
    private List<String> getAllIfacesForBenchmark() {
        List<String> ifaces = new ArrayList<>();
        ifaces.add(TUN_IFACE);
        ifaces.addAll(Arrays.asList(getVpnUnderlyingIfaces()));
        return ifaces;
    }

    public void timeMigrateTun(int reps) {
        for (int i = 0; i < reps; i++) {
            NetworkStats stats = mNetworkStats.clone();
            stats.migrateTun(TUN_UID, TUN_IFACE, getVpnUnderlyingIfaces());
        }
    }

    /**
     * Since timeMigrateTun() includes a clone() call on the NetworkStats object,
     * we need to measure the cost of the clone() call itself in order to get more
     * accurate measurement on the migrateTun() method.
     */
    public void timeClone(int reps) {
        for (int i = 0; i < reps; i++) {
            NetworkStats stats = mNetworkStats.clone();
        }
    }
}
