/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.android.internal.os;

import static android.os.BatteryStats.STATS_SINCE_CHARGED;

import android.os.WorkSource;
import android.support.test.filters.SmallTest;

import junit.framework.TestCase;

/**
 * Test various BatteryStatsImpl noteStart methods.
 */
public class BatteryStatsNoteTest extends TestCase{
    private static final int UID = 10500;
    private static final WorkSource WS = new WorkSource(UID);

    /** Test that BatteryStatsImpl.Uid.noteBluetoothScanResultLocked. */
    @SmallTest
    public void testNoteBluetoothScanResultLocked() throws Exception {
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(new MockClocks());
        bi.updateTimeBasesLocked(true, true, 0, 0);

        bi.noteBluetoothScanResultFromSourceLocked(WS);
        bi.noteBluetoothScanResultFromSourceLocked(WS);
        assertEquals(2,
                bi.getUidStats().get(UID).getBluetoothScanResultCounter()
                        .getCountLocked(STATS_SINCE_CHARGED));
    }
}
