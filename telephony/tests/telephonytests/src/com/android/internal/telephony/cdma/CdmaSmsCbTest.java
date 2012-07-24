/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.internal.telephony.cdma;

import android.os.Parcel;
import android.telephony.SmsCbCmasInfo;
import android.telephony.SmsCbMessage;
import android.telephony.cdma.CdmaSmsCbProgramData;
import android.test.AndroidTestCase;
import android.util.Log;

import com.android.internal.telephony.GsmAlphabet;
import com.android.internal.telephony.IccUtils;
import com.android.internal.telephony.cdma.sms.BearerData;
import com.android.internal.telephony.cdma.sms.CdmaSmsAddress;
import com.android.internal.telephony.cdma.sms.SmsEnvelope;
import com.android.internal.telephony.cdma.sms.UserData;
import com.android.internal.util.BitwiseOutputStream;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Test cases for basic SmsCbMessage operation for CDMA.
 */
public class CdmaSmsCbTest extends AndroidTestCase {

    /* Copy of private subparameter identifier constants from BearerData class. */
    private static final byte SUBPARAM_MESSAGE_IDENTIFIER   = (byte) 0x00;
    private static final byte SUBPARAM_USER_DATA            = (byte) 0x01;
    private static final byte SUBPARAM_PRIORITY_INDICATOR   = (byte) 0x08;
    private static final byte SUBPARAM_LANGUAGE_INDICATOR   = (byte) 0x0D;
    private static final byte SUBPARAM_SERVICE_CATEGORY_PROGRAM_DATA    = 0x12;

    /**
     * Initialize a Parcel for an incoming CDMA cell broadcast. The caller will write the
     * bearer data and then convert it to an SmsMessage.
     * @param serviceCategory the CDMA service category
     * @return the initialized Parcel
     */
    private static Parcel createBroadcastParcel(int serviceCategory) {
        Parcel p = Parcel.obtain();

        p.writeInt(SmsEnvelope.TELESERVICE_NOT_SET);
        p.writeByte((byte) 1);  // non-zero for MESSAGE_TYPE_BROADCAST
        p.writeInt(serviceCategory);

        // dummy address (RIL may generate a different dummy address for broadcasts)
        p.writeInt(CdmaSmsAddress.DIGIT_MODE_4BIT_DTMF);            // sAddress.digit_mode
        p.writeInt(CdmaSmsAddress.NUMBER_MODE_NOT_DATA_NETWORK);    // sAddress.number_mode
        p.writeInt(CdmaSmsAddress.TON_UNKNOWN);                     // sAddress.number_type
        p.writeInt(CdmaSmsAddress.NUMBERING_PLAN_ISDN_TELEPHONY);   // sAddress.number_plan
        p.writeByte((byte) 0);      // sAddress.number_of_digits
        p.writeInt((byte) 0);       // sSubAddress.subaddressType
        p.writeByte((byte) 0);      // sSubAddress.odd
        p.writeByte((byte) 0);      // sSubAddress.number_of_digits
        return p;
    }

    /**
     * Initialize a BitwiseOutputStream with the CDMA bearer data subparameters except for
     * user data. The caller will append the user data and add it to the parcel.
     * @param messageId the 16-bit message identifier
     * @param priority message priority
     * @param language message language code
     * @return the initialized BitwiseOutputStream
     */
    private static BitwiseOutputStream createBearerDataStream(int messageId, int priority,
            int language) throws BitwiseOutputStream.AccessException {
        BitwiseOutputStream bos = new BitwiseOutputStream(10);
        bos.write(8, SUBPARAM_MESSAGE_IDENTIFIER);
        bos.write(8, 3);    // length: 3 bytes
        bos.write(4, BearerData.MESSAGE_TYPE_DELIVER);
        bos.write(8, ((messageId >>> 8) & 0xff));
        bos.write(8, (messageId & 0xff));
        bos.write(1, 0);    // no User Data Header
        bos.write(3, 0);    // reserved

        if (priority != -1) {
            bos.write(8, SUBPARAM_PRIORITY_INDICATOR);
            bos.write(8, 1);    // length: 1 byte
            bos.write(2, (priority & 0x03));
            bos.write(6, 0);    // reserved
        }

        if (language != -1) {
            bos.write(8, SUBPARAM_LANGUAGE_INDICATOR);
            bos.write(8, 1);    // length: 1 byte
            bos.write(8, (language & 0xff));
        }

        return bos;
    }

    /**
     * Write the bearer data array to the parcel, then return a new SmsMessage from the parcel.
     * @param p the parcel containing the CDMA SMS headers
     * @param bearerData the bearer data byte array to append to the parcel
     * @return the new SmsMessage created from the parcel
     */
    private static SmsMessage createMessageFromParcel(Parcel p, byte[] bearerData) {
        p.writeInt(bearerData.length);
        for (byte b : bearerData) {
            p.writeByte(b);
        }
        p.setDataPosition(0);   // reset position for reading
        SmsMessage message = SmsMessage.newFromParcel(p);
        p.recycle();
        return message;
    }

    /**
     * Create a parcel for an incoming CMAS broadcast, then return a new SmsMessage created
     * from the parcel.
     * @param serviceCategory the CDMA service category
     * @param messageId the 16-bit message identifier
     * @param priority message priority
     * @param language message language code
     * @param body message body
     * @param cmasCategory CMAS category (or -1 to skip adding CMAS type 1 elements record)
     * @param responseType CMAS response type
     * @param severity CMAS severity
     * @param urgency CMAS urgency
     * @param certainty CMAS certainty
     * @return the newly created SmsMessage object
     */
    private static SmsMessage createCmasSmsMessage(int serviceCategory, int messageId, int priority,
            int language, int encoding, String body, int cmasCategory, int responseType,
            int severity, int urgency, int certainty) throws Exception {
        BitwiseOutputStream cmasBos = new BitwiseOutputStream(10);
        cmasBos.write(8, 0);    // CMAE protocol version 0

        if (body != null) {
            cmasBos.write(8, 0);        // Type 0 elements (alert text)
            encodeBody(encoding, body, true, cmasBos);
        }

        if (cmasCategory != SmsCbCmasInfo.CMAS_CATEGORY_UNKNOWN) {
            cmasBos.write(8, 1);    // Type 1 elements
            cmasBos.write(8, 4);    // length: 4 bytes
            cmasBos.write(8, (cmasCategory & 0xff));
            cmasBos.write(8, (responseType & 0xff));
            cmasBos.write(4, (severity & 0x0f));
            cmasBos.write(4, (urgency & 0x0f));
            cmasBos.write(4, (certainty & 0x0f));
            cmasBos.write(4, 0);    // pad to octet boundary
        }

        byte[] cmasUserData = cmasBos.toByteArray();

        Parcel p = createBroadcastParcel(serviceCategory);
        BitwiseOutputStream bos = createBearerDataStream(messageId, priority, language);

        bos.write(8, SUBPARAM_USER_DATA);
        bos.write(8, cmasUserData.length + 2);  // add 2 bytes for msg_encoding and num_fields
        bos.write(5, UserData.ENCODING_OCTET);
        bos.write(8, cmasUserData.length);
        bos.writeByteArray(cmasUserData.length * 8, cmasUserData);
        bos.write(3, 0);    // pad to byte boundary

        return createMessageFromParcel(p, bos.toByteArray());
    }

    /**
     * Create a parcel for an incoming CDMA cell broadcast, then return a new SmsMessage created
     * from the parcel.
     * @param serviceCategory the CDMA service category
     * @param messageId the 16-bit message identifier
     * @param priority message priority
     * @param language message language code
     * @param encoding user data encoding method
     * @param body the message body
     * @return the newly created SmsMessage object
     */
    private static SmsMessage createBroadcastSmsMessage(int serviceCategory, int messageId,
            int priority, int language, int encoding, String body) throws Exception {
        Parcel p = createBroadcastParcel(serviceCategory);
        BitwiseOutputStream bos = createBearerDataStream(messageId, priority, language);

        bos.write(8, SUBPARAM_USER_DATA);
        encodeBody(encoding, body, false, bos);

        return createMessageFromParcel(p, bos.toByteArray());
    }

    /**
     * Append the message length, encoding, and body to the BearerData output stream.
     * This is used for writing the User Data subparameter for non-CMAS broadcasts and for
     * writing the alert text for CMAS broadcasts.
     * @param encoding one of the CDMA UserData encoding values
     * @param body the message body
     * @param isCmasRecord true if this is a CMAS type 0 elements record; false for user data
     * @param bos the BitwiseOutputStream to write to
     * @throws Exception on any encoding error
     */
    private static void encodeBody(int encoding, String body, boolean isCmasRecord,
            BitwiseOutputStream bos) throws Exception {
        if (encoding == UserData.ENCODING_7BIT_ASCII || encoding == UserData.ENCODING_IA5) {
            int charCount = body.length();
            int recordBits = (charCount * 7) + 5;       // add 5 bits for char set field
            int recordOctets = (recordBits + 7) / 8;    // round up to octet boundary
            int padBits = (recordOctets * 8) - recordBits;

            if (!isCmasRecord) {
                recordOctets++;                         // add 8 bits for num_fields
            }

            bos.write(8, recordOctets);
            bos.write(5, (encoding & 0x1f));

            if (!isCmasRecord) {
                bos.write(8, charCount);
            }

            for (int i = 0; i < charCount; i++) {
                bos.write(7, body.charAt(i));
            }

            bos.write(padBits, 0);      // pad to octet boundary
        } else if (encoding == UserData.ENCODING_GSM_7BIT_ALPHABET
                || encoding == UserData.ENCODING_GSM_DCS) {
            // convert to 7-bit packed encoding with septet count in index 0 of byte array
            byte[] encodedBody = GsmAlphabet.stringToGsm7BitPacked(body);

            int charCount = encodedBody[0];             // septet count
            int recordBits = (charCount * 7) + 5;       // add 5 bits for char set field
            int recordOctets = (recordBits + 7) / 8;    // round up to octet boundary
            int padBits = (recordOctets * 8) - recordBits;

            if (!isCmasRecord) {
                recordOctets++;                         // add 8 bits for num_fields
                if (encoding == UserData.ENCODING_GSM_DCS) {
                    recordOctets++;                     // add 8 bits for DCS (message type)
                }
            }

            bos.write(8, recordOctets);
            bos.write(5, (encoding & 0x1f));

            if (!isCmasRecord && encoding == UserData.ENCODING_GSM_DCS) {
                bos.write(8, 0);        // GSM DCS: 7 bit default alphabet, no msg class
            }

            if (!isCmasRecord) {
                bos.write(8, charCount);
            }
            byte[] bodySeptets = Arrays.copyOfRange(encodedBody, 1, encodedBody.length);
            bos.writeByteArray(charCount * 7, bodySeptets);
            bos.write(padBits, 0);      // pad to octet boundary
        } else if (encoding == UserData.ENCODING_IS91_EXTENDED_PROTOCOL) {
            // 6 bit packed encoding with 0x20 offset (ASCII 0x20 - 0x60)
            int charCount = body.length();
            int recordBits = (charCount * 6) + 21;      // add 21 bits for header fields
            int recordOctets = (recordBits + 7) / 8;    // round up to octet boundary
            int padBits = (recordOctets * 8) - recordBits;

            bos.write(8, recordOctets);

            bos.write(5, (encoding & 0x1f));
            bos.write(8, UserData.IS91_MSG_TYPE_SHORT_MESSAGE);
            bos.write(8, charCount);

            for (int i = 0; i < charCount; i++) {
                bos.write(6, ((int) body.charAt(i) - 0x20));
            }

            bos.write(padBits, 0);      // pad to octet boundary
        } else {
            byte[] encodedBody;
            switch (encoding) {
                case UserData.ENCODING_UNICODE_16:
                    encodedBody = body.getBytes("UTF-16BE");
                    break;

                case UserData.ENCODING_SHIFT_JIS:
                    encodedBody = body.getBytes("Shift_JIS");
                    break;

                case UserData.ENCODING_KOREAN:
                    encodedBody = body.getBytes("KSC5601");
                    break;

                case UserData.ENCODING_LATIN_HEBREW:
                    encodedBody = body.getBytes("ISO-8859-8");
                    break;

                case UserData.ENCODING_LATIN:
                default:
                    encodedBody = body.getBytes("ISO-8859-1");
                    break;
            }
            int charCount = body.length();              // use actual char count for num fields
            int recordOctets = encodedBody.length + 1;  // add 1 byte for encoding and pad bits
            if (!isCmasRecord) {
                recordOctets++;                         // add 8 bits for num_fields
            }
            bos.write(8, recordOctets);
            bos.write(5, (encoding & 0x1f));
            if (!isCmasRecord) {
                bos.write(8, charCount);
            }
            bos.writeByteArray(encodedBody.length * 8, encodedBody);
            bos.write(3, 0);            // pad to octet boundary
        }
    }

    private static final String TEST_TEXT = "This is a test CDMA cell broadcast message..."
            + "678901234567890123456789012345678901234567890";

    private static final String PRES_ALERT =
            "THE PRESIDENT HAS ISSUED AN EMERGENCY ALERT. CHECK LOCAL MEDIA FOR MORE DETAILS";

    private static final String EXTREME_ALERT = "FLASH FLOOD WARNING FOR SOUTH COCONINO COUNTY"
            + " - NORTH CENTRAL ARIZONA UNTIL 415 PM MST";

    private static final String SEVERE_ALERT = "SEVERE WEATHER WARNING FOR SOMERSET COUNTY"
            + " - NEW JERSEY UNTIL 415 PM MST";

    private static final String AMBER_ALERT =
            "AMBER ALERT:Mountain View,CA VEH'07 Blue Honda Civic CA LIC 5ABC123";

    private static final String MONTHLY_TEST_ALERT = "This is a test of the emergency alert system."
            + " This is only a test. 89012345678901234567890";

    private static final String IS91_TEXT = "IS91 SHORT MSG";   // max length 14 chars

    /**
     * Verify that the SmsCbMessage has the correct values for CDMA.
     * @param cbMessage the message to test
     */
    private static void verifyCbValues(SmsCbMessage cbMessage) {
        assertEquals(SmsCbMessage.MESSAGE_FORMAT_3GPP2, cbMessage.getMessageFormat());
        assertEquals(SmsCbMessage.GEOGRAPHICAL_SCOPE_PLMN_WIDE, cbMessage.getGeographicalScope());
        assertEquals(false, cbMessage.isEtwsMessage()); // ETWS on CDMA not currently supported
    }

    private static void doTestNonEmergencyBroadcast(int encoding) throws Exception {
        SmsMessage msg = createBroadcastSmsMessage(123, 456, BearerData.PRIORITY_NORMAL,
                BearerData.LANGUAGE_ENGLISH, encoding, TEST_TEXT);

        SmsCbMessage cbMessage = msg.parseBroadcastSms();
        verifyCbValues(cbMessage);
        assertEquals(123, cbMessage.getServiceCategory());
        assertEquals(456, cbMessage.getSerialNumber());
        assertEquals(SmsCbMessage.MESSAGE_PRIORITY_NORMAL, cbMessage.getMessagePriority());
        assertEquals("en", cbMessage.getLanguageCode());
        assertEquals(TEST_TEXT, cbMessage.getMessageBody());
        assertEquals(false, cbMessage.isEmergencyMessage());
        assertEquals(false, cbMessage.isCmasMessage());
    }

    public void testNonEmergencyBroadcast7bitAscii() throws Exception {
        doTestNonEmergencyBroadcast(UserData.ENCODING_7BIT_ASCII);
    }

    public void testNonEmergencyBroadcast7bitGsm() throws Exception {
        doTestNonEmergencyBroadcast(UserData.ENCODING_GSM_7BIT_ALPHABET);
    }

    public void testNonEmergencyBroadcast16bitUnicode() throws Exception {
        doTestNonEmergencyBroadcast(UserData.ENCODING_UNICODE_16);
    }

    public void testNonEmergencyBroadcastIs91Extended() throws Exception {
        // IS-91 doesn't support language or priority subparameters, max 14 chars text
        SmsMessage msg = createBroadcastSmsMessage(987, 654, -1, -1,
                UserData.ENCODING_IS91_EXTENDED_PROTOCOL, IS91_TEXT);

        SmsCbMessage cbMessage = msg.parseBroadcastSms();
        verifyCbValues(cbMessage);
        assertEquals(987, cbMessage.getServiceCategory());
        assertEquals(654, cbMessage.getSerialNumber());
        assertEquals(SmsCbMessage.MESSAGE_PRIORITY_NORMAL, cbMessage.getMessagePriority());
        assertEquals(null, cbMessage.getLanguageCode());
        assertEquals(IS91_TEXT, cbMessage.getMessageBody());
        assertEquals(false, cbMessage.isEmergencyMessage());
        assertEquals(false, cbMessage.isCmasMessage());
    }

    private static void doTestCmasBroadcast(int serviceCategory, int messageClass, String body)
            throws Exception {
        SmsMessage msg = createCmasSmsMessage(
                serviceCategory, 1234, BearerData.PRIORITY_EMERGENCY, BearerData.LANGUAGE_ENGLISH,
                UserData.ENCODING_7BIT_ASCII, body, -1, -1, -1, -1, -1);

        SmsCbMessage cbMessage = msg.parseBroadcastSms();
        verifyCbValues(cbMessage);
        assertEquals(serviceCategory, cbMessage.getServiceCategory());
        assertEquals(1234, cbMessage.getSerialNumber());
        assertEquals(SmsCbMessage.MESSAGE_PRIORITY_EMERGENCY, cbMessage.getMessagePriority());
        assertEquals("en", cbMessage.getLanguageCode());
        assertEquals(body, cbMessage.getMessageBody());
        assertEquals(true, cbMessage.isEmergencyMessage());
        assertEquals(true, cbMessage.isCmasMessage());
        SmsCbCmasInfo cmasInfo = cbMessage.getCmasWarningInfo();
        assertEquals(messageClass, cmasInfo.getMessageClass());
        assertEquals(SmsCbCmasInfo.CMAS_CATEGORY_UNKNOWN, cmasInfo.getCategory());
        assertEquals(SmsCbCmasInfo.CMAS_RESPONSE_TYPE_UNKNOWN, cmasInfo.getResponseType());
        assertEquals(SmsCbCmasInfo.CMAS_SEVERITY_UNKNOWN, cmasInfo.getSeverity());
        assertEquals(SmsCbCmasInfo.CMAS_URGENCY_UNKNOWN, cmasInfo.getUrgency());
        assertEquals(SmsCbCmasInfo.CMAS_CERTAINTY_UNKNOWN, cmasInfo.getCertainty());
    }

    public void testCmasPresidentialAlert() throws Exception {
        doTestCmasBroadcast(SmsEnvelope.SERVICE_CATEGORY_CMAS_PRESIDENTIAL_LEVEL_ALERT,
                SmsCbCmasInfo.CMAS_CLASS_PRESIDENTIAL_LEVEL_ALERT, PRES_ALERT);
    }

    public void testCmasExtremeAlert() throws Exception {
        doTestCmasBroadcast(SmsEnvelope.SERVICE_CATEGORY_CMAS_EXTREME_THREAT,
                SmsCbCmasInfo.CMAS_CLASS_EXTREME_THREAT, EXTREME_ALERT);
    }

    public void testCmasSevereAlert() throws Exception {
        doTestCmasBroadcast(SmsEnvelope.SERVICE_CATEGORY_CMAS_SEVERE_THREAT,
                SmsCbCmasInfo.CMAS_CLASS_SEVERE_THREAT, SEVERE_ALERT);
    }

    public void testCmasAmberAlert() throws Exception {
        doTestCmasBroadcast(SmsEnvelope.SERVICE_CATEGORY_CMAS_CHILD_ABDUCTION_EMERGENCY,
                SmsCbCmasInfo.CMAS_CLASS_CHILD_ABDUCTION_EMERGENCY, AMBER_ALERT);
    }

    public void testCmasTestMessage() throws Exception {
        doTestCmasBroadcast(SmsEnvelope.SERVICE_CATEGORY_CMAS_TEST_MESSAGE,
                SmsCbCmasInfo.CMAS_CLASS_REQUIRED_MONTHLY_TEST, MONTHLY_TEST_ALERT);
    }

    public void testCmasExtremeAlertType1Elements() throws Exception {
        SmsMessage msg = createCmasSmsMessage(SmsEnvelope.SERVICE_CATEGORY_CMAS_EXTREME_THREAT,
                5678, BearerData.PRIORITY_EMERGENCY, BearerData.LANGUAGE_ENGLISH,
                UserData.ENCODING_7BIT_ASCII, EXTREME_ALERT, SmsCbCmasInfo.CMAS_CATEGORY_ENV,
                SmsCbCmasInfo.CMAS_RESPONSE_TYPE_MONITOR, SmsCbCmasInfo.CMAS_SEVERITY_SEVERE,
                SmsCbCmasInfo.CMAS_URGENCY_EXPECTED, SmsCbCmasInfo.CMAS_CERTAINTY_LIKELY);

        SmsCbMessage cbMessage = msg.parseBroadcastSms();
        verifyCbValues(cbMessage);
        assertEquals(SmsEnvelope.SERVICE_CATEGORY_CMAS_EXTREME_THREAT,
                cbMessage.getServiceCategory());
        assertEquals(5678, cbMessage.getSerialNumber());
        assertEquals(SmsCbMessage.MESSAGE_PRIORITY_EMERGENCY, cbMessage.getMessagePriority());
        assertEquals("en", cbMessage.getLanguageCode());
        assertEquals(EXTREME_ALERT, cbMessage.getMessageBody());
        assertEquals(true, cbMessage.isEmergencyMessage());
        assertEquals(true, cbMessage.isCmasMessage());
        SmsCbCmasInfo cmasInfo = cbMessage.getCmasWarningInfo();
        assertEquals(SmsCbCmasInfo.CMAS_CLASS_EXTREME_THREAT, cmasInfo.getMessageClass());
        assertEquals(SmsCbCmasInfo.CMAS_CATEGORY_ENV, cmasInfo.getCategory());
        assertEquals(SmsCbCmasInfo.CMAS_RESPONSE_TYPE_MONITOR, cmasInfo.getResponseType());
        assertEquals(SmsCbCmasInfo.CMAS_SEVERITY_SEVERE, cmasInfo.getSeverity());
        assertEquals(SmsCbCmasInfo.CMAS_URGENCY_EXPECTED, cmasInfo.getUrgency());
        assertEquals(SmsCbCmasInfo.CMAS_CERTAINTY_LIKELY, cmasInfo.getCertainty());
    }

    // VZW requirement is to discard message with unsupported charset. Verify that we return null
    // for this unsupported character set.
    public void testCmasUnsupportedCharSet() throws Exception {
        SmsMessage msg = createCmasSmsMessage(SmsEnvelope.SERVICE_CATEGORY_CMAS_EXTREME_THREAT,
                12345, BearerData.PRIORITY_EMERGENCY, BearerData.LANGUAGE_ENGLISH,
                UserData.ENCODING_GSM_DCS, EXTREME_ALERT, -1, -1, -1, -1, -1);

        SmsCbMessage cbMessage = msg.parseBroadcastSms();
        assertNull("expected null for unsupported charset", cbMessage);
    }

    // VZW requirement is to discard message with unsupported charset. Verify that we return null
    // for this unsupported character set.
    public void testCmasUnsupportedCharSet2() throws Exception {
        SmsMessage msg = createCmasSmsMessage(SmsEnvelope.SERVICE_CATEGORY_CMAS_EXTREME_THREAT,
                67890, BearerData.PRIORITY_EMERGENCY, BearerData.LANGUAGE_ENGLISH,
                UserData.ENCODING_KOREAN, EXTREME_ALERT, -1, -1, -1, -1, -1);

        SmsCbMessage cbMessage = msg.parseBroadcastSms();
        assertNull("expected null for unsupported charset", cbMessage);
    }

    // VZW requirement is to discard message without record type 0. The framework will decode it
    // and the app will discard it.
    public void testCmasNoRecordType0() throws Exception {
        SmsMessage msg = createCmasSmsMessage(
                SmsEnvelope.SERVICE_CATEGORY_CMAS_PRESIDENTIAL_LEVEL_ALERT, 1234,
                BearerData.PRIORITY_EMERGENCY, BearerData.LANGUAGE_ENGLISH,
                UserData.ENCODING_7BIT_ASCII, null, -1, -1, -1, -1, -1);

        SmsCbMessage cbMessage = msg.parseBroadcastSms();
        verifyCbValues(cbMessage);
        assertEquals(SmsEnvelope.SERVICE_CATEGORY_CMAS_PRESIDENTIAL_LEVEL_ALERT,
                cbMessage.getServiceCategory());
        assertEquals(1234, cbMessage.getSerialNumber());
        assertEquals(SmsCbMessage.MESSAGE_PRIORITY_EMERGENCY, cbMessage.getMessagePriority());
        assertEquals("en", cbMessage.getLanguageCode());
        assertEquals(null, cbMessage.getMessageBody());
        assertEquals(true, cbMessage.isEmergencyMessage());
        assertEquals(true, cbMessage.isCmasMessage());
        SmsCbCmasInfo cmasInfo = cbMessage.getCmasWarningInfo();
        assertEquals(SmsCbCmasInfo.CMAS_CLASS_PRESIDENTIAL_LEVEL_ALERT, cmasInfo.getMessageClass());
        assertEquals(SmsCbCmasInfo.CMAS_CATEGORY_UNKNOWN, cmasInfo.getCategory());
        assertEquals(SmsCbCmasInfo.CMAS_RESPONSE_TYPE_UNKNOWN, cmasInfo.getResponseType());
        assertEquals(SmsCbCmasInfo.CMAS_SEVERITY_UNKNOWN, cmasInfo.getSeverity());
        assertEquals(SmsCbCmasInfo.CMAS_URGENCY_UNKNOWN, cmasInfo.getUrgency());
        assertEquals(SmsCbCmasInfo.CMAS_CERTAINTY_UNKNOWN, cmasInfo.getCertainty());
    }

    // Make sure we don't throw an exception if we feed completely random data to BearerStream.
    public void testRandomBearerStreamData() {
        Random r = new Random(54321);
        for (int run = 0; run < 1000; run++) {
            int len = r.nextInt(140);
            byte[] data = new byte[len];
            for (int i = 0; i < len; i++) {
                data[i] = (byte) r.nextInt(256);
            }
            // Log.d("CdmaSmsCbTest", "trying random bearer data run " + run + " length " + len);
            try {
                int category = 0x0ff0 + r.nextInt(32);  // half CMAS, half non-CMAS
                Parcel p = createBroadcastParcel(category);
                SmsMessage msg = createMessageFromParcel(p, data);
                SmsCbMessage cbMessage = msg.parseBroadcastSms();
                // with random input, cbMessage will almost always be null (log when it isn't)
                if (cbMessage != null) {
                    Log.d("CdmaSmsCbTest", "success: " + cbMessage);
                }
            } catch (Exception e) {
                Log.d("CdmaSmsCbTest", "exception thrown", e);
                fail("Exception in decoder at run " + run + " length " + len + ": " + e);
            }
        }
    }

    // Make sure we don't throw an exception if we put random data in the UserData subparam.
    public void testRandomUserData() {
        Random r = new Random(94040);
        for (int run = 0; run < 1000; run++) {
            int category = 0x0ff0 + r.nextInt(32);  // half CMAS, half non-CMAS
            Parcel p = createBroadcastParcel(category);
            int len = r.nextInt(140);
            // Log.d("CdmaSmsCbTest", "trying random user data run " + run + " length " + len);

            try {
                BitwiseOutputStream bos = createBearerDataStream(r.nextInt(65536), r.nextInt(4),
                        r.nextInt(256));

                bos.write(8, SUBPARAM_USER_DATA);
                bos.write(8, len);

                for (int i = 0; i < len; i++) {
                    bos.write(8, r.nextInt(256));
                }

                SmsMessage msg = createMessageFromParcel(p, bos.toByteArray());
                SmsCbMessage cbMessage = msg.parseBroadcastSms();
            } catch (Exception e) {
                Log.d("CdmaSmsCbTest", "exception thrown", e);
                fail("Exception in decoder at run " + run + " length " + len + ": " + e);
            }
        }
    }

    /**
     * Initialize a Parcel for incoming Service Category Program Data teleservice. The caller will
     * write the bearer data and then convert it to an SmsMessage.
     * @return the initialized Parcel
     */
    private static Parcel createServiceCategoryProgramDataParcel() {
        Parcel p = Parcel.obtain();

        p.writeInt(SmsEnvelope.TELESERVICE_SCPT);
        p.writeByte((byte) 0);  // non-zero for MESSAGE_TYPE_BROADCAST
        p.writeInt(0);

        // dummy address (RIL may generate a different dummy address for broadcasts)
        p.writeInt(CdmaSmsAddress.DIGIT_MODE_4BIT_DTMF);            // sAddress.digit_mode
        p.writeInt(CdmaSmsAddress.NUMBER_MODE_NOT_DATA_NETWORK);    // sAddress.number_mode
        p.writeInt(CdmaSmsAddress.TON_UNKNOWN);                     // sAddress.number_type
        p.writeInt(CdmaSmsAddress.NUMBERING_PLAN_ISDN_TELEPHONY);   // sAddress.number_plan
        p.writeByte((byte) 0);      // sAddress.number_of_digits
        p.writeInt((byte) 0);       // sSubAddress.subaddressType
        p.writeByte((byte) 0);      // sSubAddress.odd
        p.writeByte((byte) 0);      // sSubAddress.number_of_digits
        return p;
    }

    private static final String CAT_EXTREME_THREAT = "Extreme Threat to Life and Property";
    private static final String CAT_SEVERE_THREAT = "Severe Threat to Life and Property";
    private static final String CAT_AMBER_ALERTS = "AMBER Alerts";

    public void testServiceCategoryProgramDataAddCategory() throws Exception {
        Parcel p = createServiceCategoryProgramDataParcel();
        BitwiseOutputStream bos = createBearerDataStream(123, -1, -1);

        int categoryNameLength = CAT_EXTREME_THREAT.length();
        int subparamLengthBits = (53 + (categoryNameLength * 7));
        int subparamLengthBytes = (subparamLengthBits + 7) / 8;
        int subparamPadBits = (subparamLengthBytes * 8) - subparamLengthBits;

        bos.write(8, SUBPARAM_SERVICE_CATEGORY_PROGRAM_DATA);
        bos.write(8, subparamLengthBytes);
        bos.write(5, UserData.ENCODING_7BIT_ASCII);

        bos.write(4, CdmaSmsCbProgramData.OPERATION_ADD_CATEGORY);
        bos.write(8, (SmsEnvelope.SERVICE_CATEGORY_CMAS_EXTREME_THREAT >>> 8));
        bos.write(8, (SmsEnvelope.SERVICE_CATEGORY_CMAS_EXTREME_THREAT & 0xff));
        bos.write(8, BearerData.LANGUAGE_ENGLISH);
        bos.write(8, 100);  // max messages
        bos.write(4, CdmaSmsCbProgramData.ALERT_OPTION_DEFAULT_ALERT);

        bos.write(8, categoryNameLength);
        for (int i = 0; i < categoryNameLength; i++) {
            bos.write(7, CAT_EXTREME_THREAT.charAt(i));
        }
        bos.write(subparamPadBits, 0);

        SmsMessage msg = createMessageFromParcel(p, bos.toByteArray());
        assertNotNull(msg);
        msg.parseSms();
        List<CdmaSmsCbProgramData> programDataList = msg.getSmsCbProgramData();
        assertNotNull(programDataList);
        assertEquals(1, programDataList.size());
        CdmaSmsCbProgramData programData = programDataList.get(0);
        assertEquals(CdmaSmsCbProgramData.OPERATION_ADD_CATEGORY, programData.getOperation());
        assertEquals(SmsEnvelope.SERVICE_CATEGORY_CMAS_EXTREME_THREAT, programData.getCategory());
        assertEquals(CAT_EXTREME_THREAT, programData.getCategoryName());
        assertEquals(BearerData.LANGUAGE_ENGLISH, programData.getLanguage());
        assertEquals(100, programData.getMaxMessages());
        assertEquals(CdmaSmsCbProgramData.ALERT_OPTION_DEFAULT_ALERT, programData.getAlertOption());
    }

    public void testServiceCategoryProgramDataDeleteTwoCategories() throws Exception {
        Parcel p = createServiceCategoryProgramDataParcel();
        BitwiseOutputStream bos = createBearerDataStream(456, -1, -1);

        int category1NameLength = CAT_SEVERE_THREAT.length();
        int category2NameLength = CAT_AMBER_ALERTS.length();

        int subparamLengthBits = (101 + (category1NameLength * 7) + (category2NameLength * 7));
        int subparamLengthBytes = (subparamLengthBits + 7) / 8;
        int subparamPadBits = (subparamLengthBytes * 8) - subparamLengthBits;

        bos.write(8, SUBPARAM_SERVICE_CATEGORY_PROGRAM_DATA);
        bos.write(8, subparamLengthBytes);
        bos.write(5, UserData.ENCODING_7BIT_ASCII);

        bos.write(4, CdmaSmsCbProgramData.OPERATION_DELETE_CATEGORY);
        bos.write(8, (SmsEnvelope.SERVICE_CATEGORY_CMAS_SEVERE_THREAT >>> 8));
        bos.write(8, (SmsEnvelope.SERVICE_CATEGORY_CMAS_SEVERE_THREAT & 0xff));
        bos.write(8, BearerData.LANGUAGE_ENGLISH);
        bos.write(8, 0);  // max messages
        bos.write(4, CdmaSmsCbProgramData.ALERT_OPTION_NO_ALERT);

        bos.write(8, category1NameLength);
        for (int i = 0; i < category1NameLength; i++) {
            bos.write(7, CAT_SEVERE_THREAT.charAt(i));
        }

        bos.write(4, CdmaSmsCbProgramData.OPERATION_DELETE_CATEGORY);
        bos.write(8, (SmsEnvelope.SERVICE_CATEGORY_CMAS_CHILD_ABDUCTION_EMERGENCY >>> 8));
        bos.write(8, (SmsEnvelope.SERVICE_CATEGORY_CMAS_CHILD_ABDUCTION_EMERGENCY & 0xff));
        bos.write(8, BearerData.LANGUAGE_ENGLISH);
        bos.write(8, 0);  // max messages
        bos.write(4, CdmaSmsCbProgramData.ALERT_OPTION_NO_ALERT);

        bos.write(8, category2NameLength);
        for (int i = 0; i < category2NameLength; i++) {
            bos.write(7, CAT_AMBER_ALERTS.charAt(i));
        }

        bos.write(subparamPadBits, 0);

        SmsMessage msg = createMessageFromParcel(p, bos.toByteArray());
        assertNotNull(msg);
        msg.parseSms();
        List<CdmaSmsCbProgramData> programDataList = msg.getSmsCbProgramData();
        assertNotNull(programDataList);
        assertEquals(2, programDataList.size());

        CdmaSmsCbProgramData programData = programDataList.get(0);
        assertEquals(CdmaSmsCbProgramData.OPERATION_DELETE_CATEGORY, programData.getOperation());
        assertEquals(SmsEnvelope.SERVICE_CATEGORY_CMAS_SEVERE_THREAT, programData.getCategory());
        assertEquals(CAT_SEVERE_THREAT, programData.getCategoryName());
        assertEquals(BearerData.LANGUAGE_ENGLISH, programData.getLanguage());
        assertEquals(0, programData.getMaxMessages());
        assertEquals(CdmaSmsCbProgramData.ALERT_OPTION_NO_ALERT, programData.getAlertOption());

        programData = programDataList.get(1);
        assertEquals(CdmaSmsCbProgramData.OPERATION_DELETE_CATEGORY, programData.getOperation());
        assertEquals(SmsEnvelope.SERVICE_CATEGORY_CMAS_CHILD_ABDUCTION_EMERGENCY,
                programData.getCategory());
        assertEquals(CAT_AMBER_ALERTS, programData.getCategoryName());
        assertEquals(BearerData.LANGUAGE_ENGLISH, programData.getLanguage());
        assertEquals(0, programData.getMaxMessages());
        assertEquals(CdmaSmsCbProgramData.ALERT_OPTION_NO_ALERT, programData.getAlertOption());
    }

    private static final byte[] CMAS_TEST_BEARER_DATA = {
        0x00, 0x03, 0x1C, 0x78, 0x00, 0x01, 0x59, 0x02, (byte) 0xB8, 0x00, 0x02, 0x10, (byte) 0xAA,
        0x68, (byte) 0xD3, (byte) 0xCD, 0x06, (byte) 0x9E, 0x68, 0x30, (byte) 0xA0, (byte) 0xE9,
        (byte) 0x97, (byte) 0x9F, 0x44, 0x1B, (byte) 0xF3, 0x20, (byte) 0xE9, (byte) 0xA3,
        0x2A, 0x08, 0x7B, (byte) 0xF6, (byte) 0xED, (byte) 0xCB, (byte) 0xCB, 0x1E, (byte) 0x9C,
        0x3B, 0x10, 0x4D, (byte) 0xDF, (byte) 0x8B, 0x4E,
        (byte) 0xCC, (byte) 0xA8, 0x20, (byte) 0xEC, (byte) 0xCB, (byte) 0xCB, (byte) 0xA2, 0x0A,
        0x7E, 0x79, (byte) 0xF4, (byte) 0xCB, (byte) 0xB5, 0x72, 0x0A, (byte) 0x9A, 0x34,
        (byte) 0xF3, 0x41, (byte) 0xA7, (byte) 0x9A, 0x0D, (byte) 0xFB, (byte) 0xB6, 0x79, 0x41,
        (byte) 0x85, 0x07, 0x4C, (byte) 0xBC, (byte) 0xFA, 0x2E, 0x00, 0x08, 0x20, 0x58, 0x38,
        (byte) 0x88, (byte) 0x80, 0x10, 0x54, 0x06, 0x38, 0x20, 0x60,
        0x30, (byte) 0xA8, (byte) 0x81, (byte) 0x90, 0x20, 0x08
    };

    // Test case for CMAS test message received on the Sprint network.
    public void testDecodeRawBearerData() throws Exception {
        Parcel p = createBroadcastParcel(SmsEnvelope.SERVICE_CATEGORY_CMAS_TEST_MESSAGE);
        SmsMessage msg = createMessageFromParcel(p, CMAS_TEST_BEARER_DATA);

        SmsCbMessage cbMessage = msg.parseBroadcastSms();
        assertNotNull("expected non-null for bearer data", cbMessage);
        assertEquals("geoScope", cbMessage.getGeographicalScope(), 1);
        assertEquals("serialNumber", cbMessage.getSerialNumber(), 51072);
        assertEquals("serviceCategory", cbMessage.getServiceCategory(),
                SmsEnvelope.SERVICE_CATEGORY_CMAS_TEST_MESSAGE);
        assertEquals("payload", cbMessage.getMessageBody(),
                "This is a test of the Commercial Mobile Alert System. This is only a test.");

        SmsCbCmasInfo cmasInfo = cbMessage.getCmasWarningInfo();
        assertNotNull("expected non-null for CMAS info", cmasInfo);
        assertEquals("category", cmasInfo.getCategory(), SmsCbCmasInfo.CMAS_CATEGORY_OTHER);
        assertEquals("responseType", cmasInfo.getResponseType(),
                SmsCbCmasInfo.CMAS_RESPONSE_TYPE_NONE);
        assertEquals("severity", cmasInfo.getSeverity(), SmsCbCmasInfo.CMAS_SEVERITY_SEVERE);
        assertEquals("urgency", cmasInfo.getUrgency(), SmsCbCmasInfo.CMAS_URGENCY_EXPECTED);
        assertEquals("certainty", cmasInfo.getCertainty(), SmsCbCmasInfo.CMAS_CERTAINTY_LIKELY);
    }
}
