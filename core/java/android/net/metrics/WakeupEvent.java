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

import java.util.StringJoiner;

/**
 * An event logged when NFLOG notifies userspace of a wakeup packet for
 * watched interfaces.
 * {@hide}
 */
public class WakeupEvent {
    public String iface;
    public int uid;
    public int ethertype;
    public MacAddress dstHwAddr;
    public String srcIp;
    public String dstIp;
    public int ipNextHeader;
    public int srcPort;
    public int dstPort;
    public long timestampMs;

    @Override
    public String toString() {
        StringJoiner j = new StringJoiner(", ", "WakeupEvent(", ")");
        j.add(String.format("%tT.%tL", timestampMs, timestampMs));
        j.add(iface);
        j.add("uid: " + Integer.toString(uid));
        j.add("eth=0x" + Integer.toHexString(ethertype));
        j.add("dstHw=" + dstHwAddr);
        if (ipNextHeader > 0) {
            j.add("ipNxtHdr=" + ipNextHeader);
            j.add("srcIp=" + srcIp);
            j.add("dstIp=" + dstIp);
            if (srcPort > -1) {
                j.add("srcPort=" + srcPort);
            }
            if (dstPort > -1) {
                j.add("dstPort=" + dstPort);
            }
        }
        return j.toString();
    }
}
