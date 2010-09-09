/*
 * Copyright (C) 2006 The Android Open Source Project
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

import com.android.internal.telephony.gsm.SimTlv;
import com.android.internal.telephony.IccUtils;
import junit.framework.TestCase;
import android.test.suitebuilder.annotation.SmallTest;


public class SimUtilsTest extends TestCase {

    @SmallTest
    public void testBasic() throws Exception {
        byte[] data, data2;

        /*
         * bcdToString()
         */

        // An EF[ICCID] record
        data = IccUtils.hexStringToBytes("981062400510444868f2");
        assertEquals("8901260450014484862", IccUtils.bcdToString(data, 0, data.length));

        // skip the first and last bytes
        assertEquals("0126045001448486", IccUtils.bcdToString(data, 1, data.length - 2));

        // Stops on invalid BCD value
        data = IccUtils.hexStringToBytes("98E062400510444868f2");
        assertEquals("890", IccUtils.bcdToString(data, 0, data.length));

        // skip the high nibble 'F' since some PLMNs have it
        data = IccUtils.hexStringToBytes("98F062400510444868f2");
        assertEquals("890260450014484862", IccUtils.bcdToString(data, 0, data.length));

        /*
         * gsmBcdByteToInt()
         */

        assertEquals(98, IccUtils.gsmBcdByteToInt((byte) 0x89));

        // Out of range is treated as 0
        assertEquals(8, IccUtils.gsmBcdByteToInt((byte) 0x8c));

        /*
         * cdmaBcdByteToInt()
         */

        assertEquals(89, IccUtils.cdmaBcdByteToInt((byte) 0x89));

        // Out of range is treated as 0
        assertEquals(80, IccUtils.cdmaBcdByteToInt((byte) 0x8c));

        /*
         * adnStringFieldToString()
         */


        data = IccUtils.hexStringToBytes("00566f696365204d61696c07918150367742f3ffffffffffff");
        // Again, skip prepended 0
        // (this is an EF[ADN] record)
        assertEquals("Voice Mail", IccUtils.adnStringFieldToString(data, 1, data.length - 15));

        data = IccUtils.hexStringToBytes("809673539A5764002F004DFFFFFFFFFF");
        // (this is from an EF[ADN] record)
        assertEquals("\u9673\u539A\u5764/M", IccUtils.adnStringFieldToString(data, 0, data.length));

        data = IccUtils.hexStringToBytes("810A01566fec6365204de0696cFFFFFF");
        // (this is made up to test since I don't have a real one)
        assertEquals("Vo\u00ECce M\u00E0il", IccUtils.adnStringFieldToString(data, 0, data.length));

        data = IccUtils.hexStringToBytes("820505302D82d32d31");
        // Example from 3GPP TS 11.11 V18.1.3.0 annex B
        assertEquals("-\u0532\u0583-1", IccUtils.adnStringFieldToString(data, 0, data.length));
    }

}
