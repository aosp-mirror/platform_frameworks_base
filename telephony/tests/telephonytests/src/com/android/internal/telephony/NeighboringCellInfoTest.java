/*
 * Copyright (C) 2009 The Android Open Source Project
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
package com.android.internal.telephony;

import android.os.Parcel;
import android.test.AndroidTestCase;
import android.telephony.NeighboringCellInfo;
import android.test. suitebuilder.annotation.SmallTest;

import static android.telephony.TelephonyManager.NETWORK_TYPE_UNKNOWN;
import static android.telephony.TelephonyManager.NETWORK_TYPE_EDGE;
import static android.telephony.TelephonyManager.NETWORK_TYPE_GPRS;
import static android.telephony.TelephonyManager.NETWORK_TYPE_UMTS;

public class NeighboringCellInfoTest extends AndroidTestCase {
    @SmallTest
    public void testConstructor() {
        int rssi = 31;
        NeighboringCellInfo nc;

        nc = new NeighboringCellInfo(rssi, "FFFFFFF", NETWORK_TYPE_EDGE);
        assertEquals(NETWORK_TYPE_EDGE, nc.getNetworkType());
        assertEquals(rssi, nc.getRssi());
        assertEquals(0xfff, nc.getLac());
        assertEquals(0xffff, nc.getCid());
        assertEquals(NeighboringCellInfo.UNKNOWN_CID, nc.getPsc());

        nc = new NeighboringCellInfo(rssi, "1FF", NETWORK_TYPE_UMTS);
        assertEquals(NETWORK_TYPE_UMTS, nc.getNetworkType());
        assertEquals(rssi, nc.getRssi());
        assertEquals(NeighboringCellInfo.UNKNOWN_CID, nc.getCid());
        assertEquals(NeighboringCellInfo.UNKNOWN_CID, nc.getLac());
        assertEquals(0x1ff, nc.getPsc());

        nc = new NeighboringCellInfo(rssi, "1FF", NETWORK_TYPE_UNKNOWN);
        assertEquals(NETWORK_TYPE_UNKNOWN, nc.getNetworkType());
        assertEquals(rssi, nc.getRssi());
        assertEquals(NeighboringCellInfo.UNKNOWN_CID, nc.getCid());
        assertEquals(NeighboringCellInfo.UNKNOWN_CID, nc.getLac());
        assertEquals(NeighboringCellInfo.UNKNOWN_CID, nc.getPsc());
    }

    @SmallTest
    public void testParcel() {
        int rssi = 20;

        NeighboringCellInfo nc = new NeighboringCellInfo(rssi, "12345678", NETWORK_TYPE_GPRS);
        assertEquals(NETWORK_TYPE_GPRS, nc.getNetworkType());
        assertEquals(rssi, nc.getRssi());
        assertEquals(0x1234, nc.getLac());
        assertEquals(0x5678, nc.getCid());
        assertEquals(NeighboringCellInfo.UNKNOWN_CID, nc.getPsc());

        Parcel p = Parcel.obtain();
        p.setDataPosition(0);
        nc.writeToParcel(p, 0);

        p.setDataPosition(0);
        NeighboringCellInfo nw = new NeighboringCellInfo(p);
        assertEquals(NETWORK_TYPE_GPRS, nw.getNetworkType());
        assertEquals(rssi, nw.getRssi());
        assertEquals(0x1234, nw.getLac());
        assertEquals(0x5678, nw.getCid());
        assertEquals(NeighboringCellInfo.UNKNOWN_CID, nw.getPsc());
     }
}
