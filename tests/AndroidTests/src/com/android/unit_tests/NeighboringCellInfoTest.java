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
package com.android.unit_tests;

import android.test.AndroidTestCase;
import android.telephony.NeighboringCellInfo;
import android.test. suitebuilder.annotation.SmallTest;

public class NeighboringCellInfoTest extends AndroidTestCase {
    @SmallTest
    public void testConstructor() {
        NeighboringCellInfo empty = new NeighboringCellInfo();
        assertEquals(NeighboringCellInfo.UNKNOWN_RSSI, empty.getRssi());
        assertEquals(NeighboringCellInfo.UNKNOWN_CID, empty.getCid());

        int rssi = 31;
        int cid = 0xffffffff;
        NeighboringCellInfo max = new NeighboringCellInfo(rssi, cid);
        assertEquals(rssi, max.getRssi());
        assertEquals(cid, max.getCid());
    }

    @SmallTest
    public void testGetAndSet() {
        int rssi = 16;
        int cid = 0x12345678;
        NeighboringCellInfo nc = new NeighboringCellInfo();
        nc.setRssi(rssi);
        nc.setCid(cid);
        assertEquals(rssi, nc.getRssi());
        assertEquals(cid, nc.getCid());
    }

    @SmallTest
    public void testToString() {
        NeighboringCellInfo empty = new NeighboringCellInfo();
        assertEquals("[/ at /]", empty.toString());

        NeighboringCellInfo nc = new NeighboringCellInfo(16, 0x12345678);
        assertEquals("[12345678 at 16]", nc.toString());
    }
}
