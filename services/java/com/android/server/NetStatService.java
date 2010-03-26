/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.content.Context;
import android.net.TrafficStats;
import android.os.INetStatService;
import android.os.SystemClock;

import java.io.FileDescriptor;
import java.io.PrintWriter;

public class NetStatService extends INetStatService.Stub {
    private final Context mContext;

    public NetStatService(Context context) {
        mContext = context;
    }

    public long getMobileTxPackets() {
        return TrafficStats.getMobileTxPackets();
    }

    public long getMobileRxPackets() {
        return TrafficStats.getMobileRxPackets();
    }

    public long getMobileTxBytes() {
        return TrafficStats.getMobileTxBytes();
    }

    public long getMobileRxBytes() {
        return TrafficStats.getMobileRxBytes();
    }

    public long getTotalTxPackets() {
        return TrafficStats.getTotalTxPackets();
    }

    public long getTotalRxPackets() {
        return TrafficStats.getTotalRxPackets();
    }

    public long getTotalTxBytes() {
        return TrafficStats.getTotalTxBytes();
    }

    public long getTotalRxBytes() {
        return TrafficStats.getTotalRxBytes();
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        // This data is accessible to any app -- no permission check needed.

        pw.print("Elapsed: total=");
        pw.print(SystemClock.elapsedRealtime());
        pw.print("ms awake=");
        pw.print(SystemClock.uptimeMillis());
        pw.println("ms");

        pw.print("Mobile: Tx=");
        pw.print(getMobileTxBytes());
        pw.print("B/");
        pw.print(getMobileTxPackets());
        pw.print("Pkts Rx=");
        pw.print(getMobileRxBytes());
        pw.print("B/");
        pw.print(getMobileRxPackets());
        pw.println("Pkts");

        pw.print("Total: Tx=");
        pw.print(getTotalTxBytes());
        pw.print("B/");
        pw.print(getTotalTxPackets());
        pw.print("Pkts Rx=");
        pw.print(getTotalRxBytes());
        pw.print("B/");
        pw.print(getTotalRxPackets());
        pw.println("Pkts");
    }
}
