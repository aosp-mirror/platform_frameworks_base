/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.internal.telephony.gsm;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * Test UsimServiceTable class.
 */
public class UsimServiceTableTest extends AndroidTestCase {

    @SmallTest
    public void testUsimServiceTable() {
        byte[] noServices = {0x00};
        byte[] service1 = {0x01, 0x00};
        byte[] service8 = {(byte) 0x80, 0x00, 0x00};
        byte[] service8And9 = {(byte) 0x80, 0x01};
        byte[] service28 = {0x00, 0x00, 0x00, 0x08};
        byte[] service89To96 = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x00, (byte) 0xff};

        UsimServiceTable testTable1 = new UsimServiceTable(noServices);
        assertFalse(testTable1.isAvailable(UsimServiceTable.UsimService.PHONEBOOK));
        assertFalse(testTable1.isAvailable(UsimServiceTable.UsimService.FDN));
        assertFalse(testTable1.isAvailable(UsimServiceTable.UsimService.NAS_CONFIG_BY_USIM));

        UsimServiceTable testTable2 = new UsimServiceTable(service1);
        assertTrue(testTable2.isAvailable(UsimServiceTable.UsimService.PHONEBOOK));
        assertFalse(testTable2.isAvailable(UsimServiceTable.UsimService.FDN));
        assertFalse(testTable2.isAvailable(UsimServiceTable.UsimService.NAS_CONFIG_BY_USIM));

        UsimServiceTable testTable3 = new UsimServiceTable(service8);
        assertFalse(testTable3.isAvailable(UsimServiceTable.UsimService.PHONEBOOK));
        assertFalse(testTable3.isAvailable(UsimServiceTable.UsimService.BDN_EXTENSION));
        assertTrue(testTable3.isAvailable(UsimServiceTable.UsimService.OUTGOING_CALL_INFO));
        assertFalse(testTable3.isAvailable(UsimServiceTable.UsimService.INCOMING_CALL_INFO));
        assertFalse(testTable3.isAvailable(UsimServiceTable.UsimService.NAS_CONFIG_BY_USIM));

        UsimServiceTable testTable4 = new UsimServiceTable(service8And9);
        assertFalse(testTable4.isAvailable(UsimServiceTable.UsimService.PHONEBOOK));
        assertFalse(testTable4.isAvailable(UsimServiceTable.UsimService.BDN_EXTENSION));
        assertTrue(testTable4.isAvailable(UsimServiceTable.UsimService.OUTGOING_CALL_INFO));
        assertTrue(testTable4.isAvailable(UsimServiceTable.UsimService.INCOMING_CALL_INFO));
        assertFalse(testTable4.isAvailable(UsimServiceTable.UsimService.SM_STORAGE));
        assertFalse(testTable4.isAvailable(UsimServiceTable.UsimService.NAS_CONFIG_BY_USIM));

        UsimServiceTable testTable5 = new UsimServiceTable(service28);
        assertFalse(testTable5.isAvailable(UsimServiceTable.UsimService.PHONEBOOK));
        assertTrue(testTable5.isAvailable(UsimServiceTable.UsimService.DATA_DL_VIA_SMS_PP));
        assertFalse(testTable5.isAvailable(UsimServiceTable.UsimService.NAS_CONFIG_BY_USIM));

        UsimServiceTable testTable6 = new UsimServiceTable(service89To96);
        assertFalse(testTable6.isAvailable(UsimServiceTable.UsimService.PHONEBOOK));
        assertFalse(testTable6.isAvailable(UsimServiceTable.UsimService.HPLMN_DIRECT_ACCESS));
        assertTrue(testTable6.isAvailable(UsimServiceTable.UsimService.ECALL_DATA));
        assertTrue(testTable6.isAvailable(UsimServiceTable.UsimService.SM_OVER_IP));
        assertTrue(testTable6.isAvailable(UsimServiceTable.UsimService.UICC_ACCESS_TO_IMS));
        assertTrue(testTable6.isAvailable(UsimServiceTable.UsimService.NAS_CONFIG_BY_USIM));
    }
}
