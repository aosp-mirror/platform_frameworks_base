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

import android.os.ServiceManager;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.Suppress;

import java.util.List;

import junit.framework.TestCase;

public class SimSmsTest extends TestCase {

    @MediumTest
    @Suppress // TODO: suppress this test for now since it doesn't work on the emulator
    public void testBasic() throws Exception {

        ISms sms = ISms.Stub.asInterface(ServiceManager.getService("isms"));
        assertNotNull(sms);

        List<SmsRawData> records = sms.getAllMessagesFromIccEf();
        assertNotNull(records);
        assertTrue(records.size() >= 0);

        int firstNullIndex = -1;
        int firstValidIndex = -1;
        byte[] pdu = null;
        for (int i = 0; i < records.size(); i++) {
            SmsRawData data = records.get(i);
            if (data != null && firstValidIndex == -1) {
                firstValidIndex = i;
                pdu = data.getBytes();
            }
            if (data == null && firstNullIndex == -1) {
                firstNullIndex = i;
            }
            if (firstNullIndex != -1 && firstValidIndex != -1) {
                break;
            }
        }
        if (firstNullIndex == -1 || firstValidIndex == -1)
            return;
        assertNotNull(pdu);
    }
}
