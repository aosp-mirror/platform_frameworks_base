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

package com.android.unit_tests;

import com.android.internal.telephony.gsm.GsmAlphabet;
import com.android.internal.telephony.gsm.SmsHeader;
import com.android.internal.util.HexDump;
import android.telephony.gsm.SmsMessage;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import java.util.Iterator;

public class SMSTest extends AndroidTestCase {

    @SmallTest
    public void testOne() throws Exception {
        String pdu = "07914151551512f2040B916105551511f100006060605130308A04D4F29C0E";

        SmsMessage sms = SmsMessage.createFromPdu(HexDump.hexStringToByteArray(pdu));

        assertEquals("+14155551212", sms.getServiceCenterAddress());
        assertEquals("+16505551111", sms.getOriginatingAddress());
        assertEquals("Test", sms.getMessageBody());
        //assertTrue(sms.scTimeMillis == 1152223383000L);

        pdu = "07914151551512f2040B916105551511f100036060924180008A0DA"
                + "8695DAC2E8FE9296A794E07";

        sms = SmsMessage.createFromPdu(HexDump.hexStringToByteArray(pdu));

        assertEquals("+14155551212", sms.getServiceCenterAddress());
        assertEquals("+16505551111", sms.getOriginatingAddress());
        assertEquals("(Subject)Test", sms.getMessageBody());

        /*        lines[0] = "+CMT: ,45";
                lines[1] = "07914140279510F6440A8111110301003BF56070624111958A8C0B05040B8423F"
                         + "000033702010106276170706C69636174696F6E2F766E642E7761702E6D6D732D"
                         + "6D65737361676500AF848D018BB4848C8298524D66616E304A6D7135514141416"
                         + "57341414141546741414E4E304141414141008D908918802B3136353032343836"
                         + "3137392F545950453D504C4D4E009646573A20008A808E0222C788058103093A7"
                         + "F836874";

                sms = SMSMessage.createFromPdu(mContext, lines);
        */

        pdu = "07914140279510F6440A8111110301003BF56080207130138A8C0B05040B8423F"
                + "000032A02010106276170706C69636174696F6E2F766E642E7761702E6D6D732D"
                + "6D65737361676500AF848D0185B4848C8298524E453955304A6D7135514141426"
                + "66C414141414D7741414236514141414141008D908918802B3135313232393737"
                + "3638332F545950453D504C4D4E008A808E022B918805810306977F83687474703"
                + "A2F2F36";

        sms = SmsMessage.createFromPdu(HexDump.hexStringToByteArray(pdu));

        SmsHeader header = sms.getUserDataHeader();
        assertNotNull(header);

        Iterator<SmsHeader.Element> elements = header.getElements().iterator();
        assertNotNull(elements);


        pdu = "07914140279510F6440A8111110301003BF56080207130238A3B0B05040B8423F"
                + "000032A0202362E3130322E3137312E3135302F524E453955304A6D7135514141"
                + "42666C414141414D774141423651414141414100";
        sms = SmsMessage.createFromPdu(HexDump.hexStringToByteArray(pdu));

        header = sms.getUserDataHeader();
        assertNotNull(header);

        elements = header.getElements().iterator();
        assertNotNull(elements);

        /*
        * UCS-2 encoded SMS
        */

        pdu = "07912160130300F4040B914151245584F600087010807121352B10212200A900AE00680065006C006C006F";

        sms = SmsMessage.createFromPdu(HexDump.hexStringToByteArray(pdu));
        assertEquals("\u2122\u00a9\u00aehello", sms.getMessageBody());

        // Entire alphabet (minus the escape character)

        /****
         lines[0] = "+CMT: ";
         lines[1] = "0001000A8114455245680000808080604028180E888462C168381E90886442A9582E988C06C0E9783EA09068442A994EA8946AC56AB95EB0986C46ABD96EB89C6EC7EBF97EC0A070482C1A8FC8A472C96C3A9FD0A8744AAD5AAFD8AC76CBED7ABFE0B0784C2E9BCFE8B47ACD6EBBDFF0B87C4EAFDBEFF8BC7ECFEFFBFF";
         sms = SMSMessage.createFromPdu(mContext, lines);

         System.out.println("full alphabet message body len: "
         + sms.getMessageBody().length());

         System.out.println("'" + sms.getMessageBody() +"'");

         assertTrue(sms.getMessageBody().length() == 128);
         ****/

        /*
        * Multi-part text SMS with data in septets
        */
        pdu = "07916163838408F6440B816105224431F700007060217175830AA0050003"
                + "00020162B1582C168BC562B1582C168BC562B1582C168BC562B1582C"
                + "168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C"
                + "168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C"
                + "168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C"
                + "168BC562B1582C168BC562B1582C168BC562B1582C168BC562";
        sms = SmsMessage.createFromPdu(HexDump.hexStringToByteArray(pdu));
        assertEquals(sms.getMessageBody(),
                "1111111111111111111111111111111111111111"
                        + "1111111111111111111111111111111111111111"
                        + "1111111111111111111111111111111111111111"
                        + "111111111111111111111111111111111");

        pdu = "07916163838408F6440B816105224431F700007060217185000A23050003"
                + "00020262B1582C168BC96432994C2693C96432994C2693C96432990C";
        sms = SmsMessage.createFromPdu(HexDump.hexStringToByteArray(pdu));
        assertEquals("1111111222222222222222222222", sms.getMessageBody());
    }

    @SmallTest
    public void testCPHSVoiceMail() throws Exception {
        // "set MWI flag"

        String pdu = "07912160130310F20404D0110041006060627171118A0120";

        SmsMessage sms = SmsMessage.createFromPdu(HexDump.hexStringToByteArray(pdu));

        assertTrue(sms.isReplace());
        assertEquals("_@", sms.getOriginatingAddress());
        assertEquals(" ", sms.getMessageBody());
        assertTrue(sms.isMWISetMessage());

        // "clear mwi flag"

        pdu = "07912160130310F20404D0100041006021924193352B0120";

        sms = SmsMessage.createFromPdu(HexDump.hexStringToByteArray(pdu));

        assertTrue(sms.isMWIClearMessage());

        // "clear MWI flag"

        pdu = "07912160130310F20404D0100041006060627161058A0120";

        sms = SmsMessage.createFromPdu(HexDump.hexStringToByteArray(pdu));

//        System.out.println("originating address: "
//                + (int) (sms.getOriginatingAddress().charAt(0)) + " "
//                + (int) (sms.getOriginatingAddress().charAt(1)));

        assertTrue(sms.isReplace());
        assertEquals("\u0394@", sms.getOriginatingAddress());
        assertEquals(" ", sms.getMessageBody());
        assertTrue(sms.isMWIClearMessage());
    }

    @SmallTest
    public void testCingularVoiceMail() throws Exception {
        // "set MWI flag"

        String pdu = "07912180958750F84401800500C87020026195702B06040102000200";
        SmsMessage sms = SmsMessage.createFromPdu(HexDump.hexStringToByteArray(pdu));

        assertTrue(sms.isMWISetMessage());
        assertTrue(sms.isMwiDontStore());

        // "clear mwi flag"

        pdu = "07912180958750F84401800500C07020027160112B06040102000000";
        sms = SmsMessage.createFromPdu(HexDump.hexStringToByteArray(pdu));

        assertTrue(sms.isMWIClearMessage());
        assertTrue(sms.isMwiDontStore());
    }

//    public void testTwo() throws Exception {
//        // FIXME need an SMS-SUBMIT test
//
//        System.out.println(
//                "SMS SUBMIT: " + SmsMessage.getSubmitPdu(null, "+14155551212", "test", false));
//    }

    @SmallTest
    public void testEmailGateway() throws Exception {
        // email gateway sms test
        String pdu = "07914151551512f204038105f300007011103164638a28e6f71b50c687db" +
                "7076d9357eb7412f7a794e07cdeb6275794c07bde8e5391d247e93f3";

        SmsMessage sms = SmsMessage.createFromPdu(HexDump.hexStringToByteArray(pdu));

        assertEquals("+14155551212", sms.getServiceCenterAddress());
        assertTrue(sms.isEmail());
        assertEquals("foo@example.com", sms.getEmailFrom());
        assertEquals("foo@example.com", sms.getDisplayOriginatingAddress());
        assertEquals("test subject", sms.getPseudoSubject());
        assertEquals("test body", sms.getDisplayMessageBody());
        assertEquals("test body", sms.getEmailBody());

        // email gateway sms test, including gsm extended character set.
        pdu = "07914151551512f204038105f400007011103105458a29e6f71b50c687db" +
                "7076d9357eb741af0d0a442fcfe9c23739bfe16d289bdee6b5f1813629";

        sms = SmsMessage.createFromPdu(HexDump.hexStringToByteArray(pdu));

        assertEquals("+14155551212", sms.getServiceCenterAddress());
        assertTrue(sms.isEmail());
        assertEquals("foo@example.com", sms.getDisplayOriginatingAddress());
        assertEquals("foo@example.com", sms.getEmailFrom());
        assertEquals("{ testBody[^~\\] }", sms.getDisplayMessageBody());
        assertEquals("{ testBody[^~\\] }", sms.getEmailBody());
    }

    @SmallTest
    public void testExtendedCharacterTable() throws Exception {
        String pdu = "07914151551512f2040B916105551511f100006080615131728A44D4F29C0E2" +
                "AE3E96537B94C068DD16179784C2FCB41F4B0985D06B958ADD00FB0E94536AF9749" +
                "74DA6D281BA00E95E26D509B946FC3DBF87A25D56A04";

        SmsMessage sms = SmsMessage.createFromPdu(HexDump.hexStringToByteArray(pdu));

        assertEquals("+14155551212", sms.getServiceCenterAddress());
        assertEquals("+16505551111", sms.getOriginatingAddress());
        assertEquals("Test extended character table .,-!?@~_\\/&\"';^|:()<{}>[]=%*+#", sms.getMessageBody());
    }

    @SmallTest
    public void testDecode() throws Exception {
        byte[] septets = new byte[(7 * 128 + 7) / 8];

        int bitOffset = 0;

        for (int i = 0; i < 128; i++) {
            int v;
            if (i == 0x1b) {
                // extended escape char
                v = 0;
            } else {
                v = i;
            }

            int byteOffset = bitOffset / 8;
            int shift = bitOffset % 8;

            septets[byteOffset] |= v << shift;

            if (shift > 1) {
                septets[byteOffset + 1] = (byte) (v >> (8 - shift));
            }

            bitOffset += 7;
        }

        String decoded = GsmAlphabet.gsm7BitPackedToString(septets, 0, 128);
        byte[] reEncoded = GsmAlphabet.stringToGsm7BitPacked(decoded);

        // reEncoded has the count septets byte at the front
        assertEquals(reEncoded.length, septets.length + 1);

        for (int i = 0; i < septets.length; i++) {
            assertEquals(reEncoded[i + 1], septets[i]);
        }
    }
}
