/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.net.metrics;

import android.net.MacAddress;
import android.os.Process;
import android.os.SystemClock;
import android.util.SparseIntArray;

import java.util.StringJoiner;

/**
 * An event logged per interface and that aggregates WakeupEvents for that interface.
 * {@hide}
 */
public class WakeupStats {

    private static final int NO_UID = -1;

    public final long creationTimeMs = SystemClock.elapsedRealtime();
    public final String iface;

    public long totalWakeups = 0;
    public long rootWakeups = 0;
    public long systemWakeups = 0;
    public long nonApplicationWakeups = 0;
    public long applicationWakeups = 0;
    public long noUidWakeups = 0;
    public long durationSec = 0;

    public long l2UnicastCount = 0;
    public long l2MulticastCount = 0;
    public long l2BroadcastCount = 0;

    public final SparseIntArray ethertypes = new SparseIntArray();
    public final SparseIntArray ipNextHeaders = new SparseIntArray();

    public WakeupStats(String iface) {
        this.iface = iface;
    }

    /** Update durationSec with current time. */
    public void updateDuration() {
        durationSec = (SystemClock.elapsedRealtime() - creationTimeMs) / 1000;
    }

    /** Update wakeup counters for the given WakeupEvent. */
    public void countEvent(WakeupEvent ev) {
        totalWakeups++;
        switch (ev.uid) {
            case Process.ROOT_UID:
                rootWakeups++;
                break;
            case Process.SYSTEM_UID:
                systemWakeups++;
                break;
            case NO_UID:
                noUidWakeups++;
                break;
            default:
                if (ev.uid >= Process.FIRST_APPLICATION_UID) {
                    applicationWakeups++;
                } else {
                    nonApplicationWakeups++;
                }
                break;
        }

        switch (ev.dstHwAddr.getAddressType()) {
            case MacAddress.TYPE_UNICAST:
                l2UnicastCount++;
                break;
            case MacAddress.TYPE_MULTICAST:
                l2MulticastCount++;
                break;
            case MacAddress.TYPE_BROADCAST:
                l2BroadcastCount++;
                break;
            default:
                break;
        }

        increment(ethertypes, ev.ethertype);
        if (ev.ipNextHeader >= 0) {
            increment(ipNextHeaders, ev.ipNextHeader);
        }
    }

    @Override
    public String toString() {
        updateDuration();
        StringJoiner j = new StringJoiner(", ", "WakeupStats(", ")");
        j.add(iface);
        j.add("" + durationSec + "s");
        j.add("total: " + totalWakeups);
        j.add("root: " + rootWakeups);
        j.add("system: " + systemWakeups);
        j.add("apps: " + applicationWakeups);
        j.add("non-apps: " + nonApplicationWakeups);
        j.add("no uid: " + noUidWakeups);
        j.add(String.format("l2 unicast/multicast/broadcast: %d/%d/%d",
                l2UnicastCount, l2MulticastCount, l2BroadcastCount));
        for (int i = 0; i < ethertypes.size(); i++) {
            int eth = ethertypes.keyAt(i);
            int count = ethertypes.valueAt(i);
            j.add(String.format("ethertype 0x%x: %d", eth, count));
        }
        for (int i = 0; i < ipNextHeaders.size(); i++) {
            int proto = ipNextHeaders.keyAt(i);
            int count = ipNextHeaders.valueAt(i);
            j.add(String.format("ipNxtHdr %d: %d", proto, count));
        }
        return j.toString();
    }

    private static void increment(SparseIntArray counters, int key) {
        int newcount = counters.get(key, 0) + 1;
        counters.put(key, newcount);
    }
}
