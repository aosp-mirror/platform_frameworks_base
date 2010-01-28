/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.test.suitebuilder.annotation.MediumTest;
import com.android.internal.telephony.TestPhoneNotifier;
import com.android.internal.telephony.gsm.SmsMessage;
import com.android.internal.telephony.test.SimulatedCommands;
import com.android.internal.telephony.test.SimulatedRadioControl;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.Suppress;

import java.util.Iterator;

/**
 * {@hide}
 */
public class SMSDispatcherTest extends AndroidTestCase {
    @MediumTest
    public void testCMT1() throws Exception {
        SmsMessage sms;
        SmsHeader header;

        String[] lines = new String[2];

        lines[0] = "+CMT: ,158";
        lines[1] = "07914140279510F6440A8111110301003BF56080426101748A8C0B05040B"
                 + "8423F000035502010106276170706C69636174696F6E2F766E642E776170"
                 + "2E6D6D732D6D65737361676500AF848D0185B4848C8298524F347839776F"
                 + "7547514D4141424C3641414141536741415A4B554141414141008D908918"
                 + "802B31363530323438363137392F545950453D504C4D4E008A808E028000"
                 + "88058103093A8083687474703A2F2F36";

        sms = SmsMessage.newFromCMT(lines);
        header = sms.getUserDataHeader();
        assertNotNull(header);
        assertNotNull(sms.getUserData());
        assertNotNull(header.concatRef);
        assertEquals(header.concatRef.refNumber, 85);
        assertEquals(header.concatRef.msgCount, 2);
        assertEquals(header.concatRef.seqNumber, 1);
        assertEquals(header.concatRef.isEightBits, true);
        assertNotNull(header.portAddrs);
        assertEquals(header.portAddrs.destPort, 2948);
        assertEquals(header.portAddrs.origPort, 9200);
        assertEquals(header.portAddrs.areEightBits, false);
    }

    @MediumTest
    public void testCMT2() throws Exception {
        SmsMessage sms;
        SmsHeader header;

        String[] lines = new String[2];

        lines[0] = "+CMT: ,77";
        lines[1] = "07914140279510F6440A8111110301003BF56080426101848A3B0B05040B8423F"
                 + "00003550202362E3130322E3137312E3135302F524F347839776F7547514D4141"
                 + "424C3641414141536741415A4B55414141414100";

        sms = SmsMessage.newFromCMT(lines);
        header = sms.getUserDataHeader();
        assertNotNull(header);
        assertNotNull(sms.getUserData());
        assertNotNull(header.concatRef);
        assertEquals(header.concatRef.refNumber, 85);
        assertEquals(header.concatRef.msgCount, 2);
        assertEquals(header.concatRef.seqNumber, 2);
        assertEquals(header.concatRef.isEightBits, true);
        assertNotNull(header.portAddrs);
        assertEquals(header.portAddrs.destPort, 2948);
        assertEquals(header.portAddrs.origPort, 9200);
        assertEquals(header.portAddrs.areEightBits, false);
    }

    @MediumTest
    public void testEfRecord() throws Exception {
        SmsMessage sms;

        String s = "03029111000c9194981492631000f269206190022000a053e4534a05358bd3"
                 + "69f05804259da0219418a40641536a110a0aea408080604028180e888462c1"
                 + "50341c0f484432a1542c174c46b3e1743c9f9068442a994ea8946ac56ab95e"
                 + "b0986c46abd96eb89c6ec7ebf97ec0a070482c1a8fc8a472c96c3a9fd0a874"
                 + "4aad5aafd8ac76cbed7abfe0b0784c2e9bcfe8b47acd6ebbdff0b87c4eafdb"
                 + "eff8bc7ecfeffbffffffffffffffffffffffffffff";
       byte[] data = IccUtils.hexStringToBytes(s);

       sms = SmsMessage.createFromEfRecord(1, data);
       assertNotNull(sms.getMessageBody());
    }
}
