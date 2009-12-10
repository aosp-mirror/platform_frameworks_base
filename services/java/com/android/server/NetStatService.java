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

public class NetStatService extends INetStatService.Stub {

    public NetStatService(Context context) {

    }

    public long getMobileTxPackets() {
        return TrafficStats.getMobileTxPkts();
    }

    public long getMobileRxPackets() {
        return TrafficStats.getMobileRxPkts();
    }

    public long getMobileTxBytes() {
        return TrafficStats.getMobileTxBytes();
    }

    public long getMobileRxBytes() {
        return TrafficStats.getMobileRxBytes();
    }

    public long getTotalTxPackets() {
        return TrafficStats.getTotalTxPkts();
    }

    public long getTotalRxPackets() {
        return TrafficStats.getTotalRxPkts();
    }

    public long getTotalTxBytes() {
        return TrafficStats.getTotalTxBytes();
    }

    public long getTotalRxBytes() {
        return TrafficStats.getTotalRxBytes();
    }
}
