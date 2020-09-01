/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.soundtrigger_middleware;

import static org.junit.Assert.assertEquals;

import android.hardware.audio.common.V2_0.Uuid;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ConversionUtilTest {
    private static final String TAG = "ConversionUtilTest";

    @Test
    public void testUuidRoundTrip() {
        Uuid hidl = new Uuid();
        hidl.timeLow = 0xFEDCBA98;
        hidl.timeMid = (short) 0xEDCB;
        hidl.versionAndTimeHigh = (short) 0xDCBA;
        hidl.variantAndClockSeqHigh = (short) 0xCBA9;
        hidl.node = new byte[] { 0x11, 0x12, 0x13, 0x14, 0x15, 0x16 };

        String aidl = ConversionUtil.hidl2aidlUuid(hidl);
        assertEquals("fedcba98-edcb-dcba-cba9-111213141516", aidl);

        Uuid reconstructed = ConversionUtil.aidl2hidlUuid(aidl);
        assertEquals(hidl, reconstructed);
    }
}
