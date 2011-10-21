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

package com.android.internal.telephony;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * Test IccServiceTable class.
 */
public class IccServiceTableTest extends AndroidTestCase {

    static class TestIccServiceTable extends IccServiceTable {
        public enum TestIccService {
            SERVICE1,
            SERVICE2,
            SERVICE3,
            SERVICE4
        }

        public TestIccServiceTable(byte[] table) {
            super(table);
        }

        public boolean isAvailable(TestIccService service) {
            return super.isAvailable(service.ordinal());
        }

        @Override
        protected String getTag() {
            return "TestIccServiceTable";
        }

        @Override
        protected Object[] getValues() {
            return TestIccService.values();
        }
    }

    @SmallTest
    public void testIccServiceTable() {
        byte[] noServices = {0x00};
        byte[] service1 = {0x01};
        byte[] service4 = {0x08};
        byte[] allServices = {0x0f};

        TestIccServiceTable testTable1 = new TestIccServiceTable(noServices);
        assertFalse(testTable1.isAvailable(TestIccServiceTable.TestIccService.SERVICE1));
        assertFalse(testTable1.isAvailable(TestIccServiceTable.TestIccService.SERVICE2));
        assertFalse(testTable1.isAvailable(TestIccServiceTable.TestIccService.SERVICE3));
        assertFalse(testTable1.isAvailable(TestIccServiceTable.TestIccService.SERVICE4));

        TestIccServiceTable testTable2 = new TestIccServiceTable(service1);
        assertTrue(testTable2.isAvailable(TestIccServiceTable.TestIccService.SERVICE1));
        assertFalse(testTable2.isAvailable(TestIccServiceTable.TestIccService.SERVICE2));
        assertFalse(testTable2.isAvailable(TestIccServiceTable.TestIccService.SERVICE3));
        assertFalse(testTable2.isAvailable(TestIccServiceTable.TestIccService.SERVICE4));

        TestIccServiceTable testTable3 = new TestIccServiceTable(service4);
        assertFalse(testTable3.isAvailable(TestIccServiceTable.TestIccService.SERVICE1));
        assertFalse(testTable3.isAvailable(TestIccServiceTable.TestIccService.SERVICE2));
        assertFalse(testTable3.isAvailable(TestIccServiceTable.TestIccService.SERVICE3));
        assertTrue(testTable3.isAvailable(TestIccServiceTable.TestIccService.SERVICE4));

        TestIccServiceTable testTable4 = new TestIccServiceTable(allServices);
        assertTrue(testTable4.isAvailable(TestIccServiceTable.TestIccService.SERVICE1));
        assertTrue(testTable4.isAvailable(TestIccServiceTable.TestIccService.SERVICE2));
        assertTrue(testTable4.isAvailable(TestIccServiceTable.TestIccService.SERVICE3));
        assertTrue(testTable4.isAvailable(TestIccServiceTable.TestIccService.SERVICE4));
    }
}
