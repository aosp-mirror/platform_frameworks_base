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
import android.os.INetStatService;
import android.os.NetStat;

public class NetStatService extends INetStatService.Stub {

    public NetStatService(Context context) {

    }

    public long getMobileTxPackets() {
        return NetStat.getMobileTxPkts();
    }

    public long getMobileRxPackets() {
        return NetStat.getMobileRxPkts();
    }

    public long getMobileTxBytes() {
        return NetStat.getMobileTxBytes();
    }

    public long getMobileRxBytes() {
        return NetStat.getMobileRxBytes();
    }

    public long getTotalTxPackets() {
        return NetStat.getTotalTxPkts();
    }

    public long getTotalRxPackets() {
        return NetStat.getTotalRxPkts();
    }

    public long getTotalTxBytes() {
        return NetStat.getTotalTxBytes();
    }

    public long getTotalRxBytes() {
        return NetStat.getTotalRxBytes();
    }
}
