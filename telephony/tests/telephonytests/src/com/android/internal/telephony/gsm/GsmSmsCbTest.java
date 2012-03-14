/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.telephony.SmsCbEtwsInfo;
import android.telephony.SmsCbLocation;
import android.telephony.SmsCbMessage;
import android.test.AndroidTestCase;
import android.util.Log;

import com.android.internal.telephony.IccUtils;

import java.util.Random;

/**
 * Test cases for basic SmsCbMessage operations
 */
public class GsmSmsCbTest extends AndroidTestCase {

    private static final String TAG = "GsmSmsCbTest";

    private static final SmsCbLocation sTestLocation = new SmsCbLocation("94040", 1234, 5678);

    private static SmsCbMessage createFromPdu(byte[] pdu) {
        try {
            SmsCbHeader header = new SmsCbHeader(pdu);
            byte[][] pdus = new byte[1][];
            pdus[0] = pdu;
            return GsmSmsCbMessage.createSmsCbMessage(header, sTestLocation, pdus);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void doTestGeographicalScopeValue(byte[] pdu, byte b, int expectedGs) {
        pdu[0] = b;
        SmsCbMessage msg = createFromPdu(pdu);

        assertEquals("Unexpected geographical scope decoded", expectedGs, msg
                .getGeographicalScope());
    }

    public void testCreateNullPdu() {
        SmsCbMessage msg = createFromPdu(null);
        assertNull("createFromPdu(byte[] with null pdu should return null", msg);
    }

    public void testCreateTooShortPdu() {
        byte[] pdu = new byte[4];
        SmsCbMessage msg = createFromPdu(pdu);

        assertNull("createFromPdu(byte[] with too short pdu should return null", msg);
    }

    public void testGetGeographicalScope() {
        byte[] pdu = {
                (byte)0xC0, (byte)0x00, (byte)0x00, (byte)0x32, (byte)0x40, (byte)0x11, (byte)0x41,
                (byte)0xD0, (byte)0x71, (byte)0xDA, (byte)0x04, (byte)0x91, (byte)0xCB, (byte)0xE6,
                (byte)0x70, (byte)0x9D, (byte)0x4D, (byte)0x07, (byte)0x85, (byte)0xD9, (byte)0x70,
                (byte)0x74, (byte)0x58, (byte)0x5C, (byte)0xA6, (byte)0x83, (byte)0xDA, (byte)0xE5,
                (byte)0xF9, (byte)0x3C, (byte)0x7C, (byte)0x2E, (byte)0x83, (byte)0xEE, (byte)0x69,
                (byte)0x3A, (byte)0x1A, (byte)0x34, (byte)0x0E, (byte)0xCB, (byte)0xE5, (byte)0xE9,
                (byte)0xF0, (byte)0xB9, (byte)0x0C, (byte)0x92, (byte)0x97, (byte)0xE9, (byte)0x75,
                (byte)0xB9, (byte)0x1B, (byte)0x04, (byte)0x0F, (byte)0x93, (byte)0xC9, (byte)0x69,
                (byte)0xF7, (byte)0xB9, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x00
        };

        doTestGeographicalScopeValue(pdu, (byte)0x00,
                SmsCbMessage.GEOGRAPHICAL_SCOPE_CELL_WIDE_IMMEDIATE);
        doTestGeographicalScopeValue(pdu, (byte)0x40, SmsCbMessage.GEOGRAPHICAL_SCOPE_PLMN_WIDE);
        doTestGeographicalScopeValue(pdu, (byte)0x80, SmsCbMessage.GEOGRAPHICAL_SCOPE_LA_WIDE);
        doTestGeographicalScopeValue(pdu, (byte)0xC0, SmsCbMessage.GEOGRAPHICAL_SCOPE_CELL_WIDE);
    }

    public void testGetGeographicalScopeUmts() {
        byte[] pdu = {
                (byte)0x01, (byte)0x00, (byte)0x32, (byte)0xC0, (byte)0x00, (byte)0x40,

                (byte)0x01,

                (byte)0x41, (byte)0xD0, (byte)0x71, (byte)0xDA, (byte)0x04, (byte)0x91,
                (byte)0xCB, (byte)0xE6, (byte)0x70, (byte)0x9D, (byte)0x4D, (byte)0x07,
                (byte)0x85, (byte)0xD9, (byte)0x70, (byte)0x74, (byte)0x58, (byte)0x5C,
                (byte)0xA6, (byte)0x83, (byte)0xDA, (byte)0xE5, (byte)0xF9, (byte)0x3C,
                (byte)0x7C, (byte)0x2E, (byte)0x83, (byte)0xEE, (byte)0x69, (byte)0x3A,
                (byte)0x1A, (byte)0x34, (byte)0x0E, (byte)0xCB, (byte)0xE5, (byte)0xE9,
                (byte)0xF0, (byte)0xB9, (byte)0x0C, (byte)0x92, (byte)0x97, (byte)0xE9,
                (byte)0x75, (byte)0xB9, (byte)0x1B, (byte)0x04, (byte)0x0F, (byte)0x93,
                (byte)0xC9, (byte)0x69, (byte)0xF7, (byte)0xB9, (byte)0xD1, (byte)0x68,
                (byte)0x34, (byte)0x1A, (byte)0x8D, (byte)0x46, (byte)0xA3, (byte)0xD1,
                (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D, (byte)0x46, (byte)0xA3,
                (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D, (byte)0x46,
                (byte)0xA3, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x00,

                (byte)0x34
        };

        SmsCbMessage msg = createFromPdu(pdu);

        assertEquals("Unexpected geographical scope decoded",
                SmsCbMessage.GEOGRAPHICAL_SCOPE_CELL_WIDE, msg.getGeographicalScope());
    }

    public void testGetMessageBody7Bit() {
        byte[] pdu = {
                (byte)0xC0, (byte)0x00, (byte)0x00, (byte)0x32, (byte)0x40, (byte)0x11, (byte)0x41,
                (byte)0xD0, (byte)0x71, (byte)0xDA, (byte)0x04, (byte)0x91, (byte)0xCB, (byte)0xE6,
                (byte)0x70, (byte)0x9D, (byte)0x4D, (byte)0x07, (byte)0x85, (byte)0xD9, (byte)0x70,
                (byte)0x74, (byte)0x58, (byte)0x5C, (byte)0xA6, (byte)0x83, (byte)0xDA, (byte)0xE5,
                (byte)0xF9, (byte)0x3C, (byte)0x7C, (byte)0x2E, (byte)0x83, (byte)0xEE, (byte)0x69,
                (byte)0x3A, (byte)0x1A, (byte)0x34, (byte)0x0E, (byte)0xCB, (byte)0xE5, (byte)0xE9,
                (byte)0xF0, (byte)0xB9, (byte)0x0C, (byte)0x92, (byte)0x97, (byte)0xE9, (byte)0x75,
                (byte)0xB9, (byte)0x1B, (byte)0x04, (byte)0x0F, (byte)0x93, (byte)0xC9, (byte)0x69,
                (byte)0xF7, (byte)0xB9, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x00
        };
        SmsCbMessage msg = createFromPdu(pdu);

        assertEquals("Unexpected 7-bit string decoded",
                "A GSM default alphabet message with carriage return padding",
                msg.getMessageBody());
    }

    public void testGetMessageBody7BitUmts() {
        byte[] pdu = {
                (byte)0x01, (byte)0x00, (byte)0x32, (byte)0xC0, (byte)0x00, (byte)0x40,

                (byte)0x01,

                (byte)0x41, (byte)0xD0, (byte)0x71, (byte)0xDA, (byte)0x04, (byte)0x91,
                (byte)0xCB, (byte)0xE6, (byte)0x70, (byte)0x9D, (byte)0x4D, (byte)0x07,
                (byte)0x85, (byte)0xD9, (byte)0x70, (byte)0x74, (byte)0x58, (byte)0x5C,
                (byte)0xA6, (byte)0x83, (byte)0xDA, (byte)0xE5, (byte)0xF9, (byte)0x3C,
                (byte)0x7C, (byte)0x2E, (byte)0x83, (byte)0xEE, (byte)0x69, (byte)0x3A,
                (byte)0x1A, (byte)0x34, (byte)0x0E, (byte)0xCB, (byte)0xE5, (byte)0xE9,
                (byte)0xF0, (byte)0xB9, (byte)0x0C, (byte)0x92, (byte)0x97, (byte)0xE9,
                (byte)0x75, (byte)0xB9, (byte)0x1B, (byte)0x04, (byte)0x0F, (byte)0x93,
                (byte)0xC9, (byte)0x69, (byte)0xF7, (byte)0xB9, (byte)0xD1, (byte)0x68,
                (byte)0x34, (byte)0x1A, (byte)0x8D, (byte)0x46, (byte)0xA3, (byte)0xD1,
                (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D, (byte)0x46, (byte)0xA3,
                (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D, (byte)0x46,
                (byte)0xA3, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x00,

                (byte)0x34
        };
        SmsCbMessage msg = createFromPdu(pdu);

        assertEquals("Unexpected 7-bit string decoded",
                "A GSM default alphabet message with carriage return padding",
                msg.getMessageBody());
    }

    public void testGetMessageBody7BitMultipageUmts() {
        byte[] pdu = {
                (byte)0x01, (byte)0x00, (byte)0x01, (byte)0xC0, (byte)0x00, (byte)0x40,

                (byte)0x02,

                (byte)0xC6, (byte)0xB4, (byte)0x7C, (byte)0x4E, (byte)0x07, (byte)0xC1,
                (byte)0xC3, (byte)0xE7, (byte)0xF2, (byte)0xAA, (byte)0xD1, (byte)0x68,
                (byte)0x34, (byte)0x1A, (byte)0x8D, (byte)0x46, (byte)0xA3, (byte)0xD1,
                (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D, (byte)0x46, (byte)0xA3,
                (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D, (byte)0x46,
                (byte)0xA3, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A,
                (byte)0x8D, (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x68, (byte)0x34,
                (byte)0x1A, (byte)0x8D, (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x68,
                (byte)0x34, (byte)0x1A, (byte)0x8D, (byte)0x46, (byte)0xA3, (byte)0xD1,
                (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D, (byte)0x46, (byte)0xA3,
                (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D, (byte)0x46,
                (byte)0xA3, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x00,

                (byte)0x0A,

                (byte)0xD3, (byte)0xF2, (byte)0xF8, (byte)0xED, (byte)0x26, (byte)0x83,
                (byte)0xE0, (byte)0xE1, (byte)0x73, (byte)0xB9, (byte)0xD1, (byte)0x68,
                (byte)0x34, (byte)0x1A, (byte)0x8D, (byte)0x46, (byte)0xA3, (byte)0xD1,
                (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D, (byte)0x46, (byte)0xA3,
                (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D, (byte)0x46,
                (byte)0xA3, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A,
                (byte)0x8D, (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x68, (byte)0x34,
                (byte)0x1A, (byte)0x8D, (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x68,
                (byte)0x34, (byte)0x1A, (byte)0x8D, (byte)0x46, (byte)0xA3, (byte)0xD1,
                (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D, (byte)0x46, (byte)0xA3,
                (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D, (byte)0x46,
                (byte)0xA3, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x00,

                (byte)0x0A
        };
        SmsCbMessage msg = createFromPdu(pdu);

        assertEquals("Unexpected multipage 7-bit string decoded",
                "First page+Second page",
                msg.getMessageBody());
    }

    public void testGetMessageBody7BitFull() {
        byte[] pdu = {
                (byte)0xC0, (byte)0x00, (byte)0x00, (byte)0x32, (byte)0x40, (byte)0x11, (byte)0x41,
                (byte)0xD0, (byte)0x71, (byte)0xDA, (byte)0x04, (byte)0x91, (byte)0xCB, (byte)0xE6,
                (byte)0x70, (byte)0x9D, (byte)0x4D, (byte)0x07, (byte)0x85, (byte)0xD9, (byte)0x70,
                (byte)0x74, (byte)0x58, (byte)0x5C, (byte)0xA6, (byte)0x83, (byte)0xDA, (byte)0xE5,
                (byte)0xF9, (byte)0x3C, (byte)0x7C, (byte)0x2E, (byte)0x83, (byte)0xC4, (byte)0xE5,
                (byte)0xB4, (byte)0xFB, (byte)0x0C, (byte)0x2A, (byte)0xE3, (byte)0xC3, (byte)0x63,
                (byte)0x3A, (byte)0x3B, (byte)0x0F, (byte)0xCA, (byte)0xCD, (byte)0x40, (byte)0x63,
                (byte)0x74, (byte)0x58, (byte)0x1E, (byte)0x1E, (byte)0xD3, (byte)0xCB, (byte)0xF2,
                (byte)0x39, (byte)0x88, (byte)0xFD, (byte)0x76, (byte)0x9F, (byte)0x59, (byte)0xA0,
                (byte)0x76, (byte)0x39, (byte)0xEC, (byte)0x4E, (byte)0xBB, (byte)0xCF, (byte)0x20,
                (byte)0x3A, (byte)0xBA, (byte)0x2C, (byte)0x2F, (byte)0x83, (byte)0xD2, (byte)0x73,
                (byte)0x90, (byte)0xFB, (byte)0x0D, (byte)0x82, (byte)0x87, (byte)0xC9, (byte)0xE4,
                (byte)0xB4, (byte)0xFB, (byte)0x1C, (byte)0x02
        };
        SmsCbMessage msg = createFromPdu(pdu);

        assertEquals(
                "Unexpected 7-bit string decoded",
                "A GSM default alphabet message being exactly 93 characters long, " +
                "meaning there is no padding!",
                msg.getMessageBody());
    }

    public void testGetMessageBody7BitFullUmts() {
        byte[] pdu = {
                (byte)0x01, (byte)0x00, (byte)0x32, (byte)0xC0, (byte)0x00, (byte)0x40,

                (byte)0x01,

                (byte)0x41, (byte)0xD0, (byte)0x71, (byte)0xDA, (byte)0x04, (byte)0x91,
                (byte)0xCB, (byte)0xE6, (byte)0x70, (byte)0x9D, (byte)0x4D, (byte)0x07,
                (byte)0x85, (byte)0xD9, (byte)0x70, (byte)0x74, (byte)0x58, (byte)0x5C,
                (byte)0xA6, (byte)0x83, (byte)0xDA, (byte)0xE5, (byte)0xF9, (byte)0x3C,
                (byte)0x7C, (byte)0x2E, (byte)0x83, (byte)0xC4, (byte)0xE5, (byte)0xB4,
                (byte)0xFB, (byte)0x0C, (byte)0x2A, (byte)0xE3, (byte)0xC3, (byte)0x63,
                (byte)0x3A, (byte)0x3B, (byte)0x0F, (byte)0xCA, (byte)0xCD, (byte)0x40,
                (byte)0x63, (byte)0x74, (byte)0x58, (byte)0x1E, (byte)0x1E, (byte)0xD3,
                (byte)0xCB, (byte)0xF2, (byte)0x39, (byte)0x88, (byte)0xFD, (byte)0x76,
                (byte)0x9F, (byte)0x59, (byte)0xA0, (byte)0x76, (byte)0x39, (byte)0xEC,
                (byte)0x4E, (byte)0xBB, (byte)0xCF, (byte)0x20, (byte)0x3A, (byte)0xBA,
                (byte)0x2C, (byte)0x2F, (byte)0x83, (byte)0xD2, (byte)0x73, (byte)0x90,
                (byte)0xFB, (byte)0x0D, (byte)0x82, (byte)0x87, (byte)0xC9, (byte)0xE4,
                (byte)0xB4, (byte)0xFB, (byte)0x1C, (byte)0x02,

                (byte)0x52
        };
        SmsCbMessage msg = createFromPdu(pdu);

        assertEquals(
                "Unexpected 7-bit string decoded",
                "A GSM default alphabet message being exactly 93 characters long, " +
                "meaning there is no padding!",
                msg.getMessageBody());
    }

    public void testGetMessageBody7BitWithLanguage() {
        byte[] pdu = {
                (byte)0xC0, (byte)0x00, (byte)0x00, (byte)0x32, (byte)0x04, (byte)0x11, (byte)0x41,
                (byte)0xD0, (byte)0x71, (byte)0xDA, (byte)0x04, (byte)0x91, (byte)0xCB, (byte)0xE6,
                (byte)0x70, (byte)0x9D, (byte)0x4D, (byte)0x07, (byte)0x85, (byte)0xD9, (byte)0x70,
                (byte)0x74, (byte)0x58, (byte)0x5C, (byte)0xA6, (byte)0x83, (byte)0xDA, (byte)0xE5,
                (byte)0xF9, (byte)0x3C, (byte)0x7C, (byte)0x2E, (byte)0x83, (byte)0xEE, (byte)0x69,
                (byte)0x3A, (byte)0x1A, (byte)0x34, (byte)0x0E, (byte)0xCB, (byte)0xE5, (byte)0xE9,
                (byte)0xF0, (byte)0xB9, (byte)0x0C, (byte)0x92, (byte)0x97, (byte)0xE9, (byte)0x75,
                (byte)0xB9, (byte)0x1B, (byte)0x04, (byte)0x0F, (byte)0x93, (byte)0xC9, (byte)0x69,
                (byte)0xF7, (byte)0xB9, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x00
        };
        SmsCbMessage msg = createFromPdu(pdu);

        assertEquals("Unexpected 7-bit string decoded",
                "A GSM default alphabet message with carriage return padding",
                msg.getMessageBody());

        assertEquals("Unexpected language indicator decoded", "es", msg.getLanguageCode());
    }

    public void testGetMessageBody7BitWithLanguageInBody() {
        byte[] pdu = {
                (byte)0xC0, (byte)0x00, (byte)0x00, (byte)0x32, (byte)0x10, (byte)0x11, (byte)0x73,
                (byte)0x7B, (byte)0x23, (byte)0x08, (byte)0x3A, (byte)0x4E, (byte)0x9B, (byte)0x20,
                (byte)0x72, (byte)0xD9, (byte)0x1C, (byte)0xAE, (byte)0xB3, (byte)0xE9, (byte)0xA0,
                (byte)0x30, (byte)0x1B, (byte)0x8E, (byte)0x0E, (byte)0x8B, (byte)0xCB, (byte)0x74,
                (byte)0x50, (byte)0xBB, (byte)0x3C, (byte)0x9F, (byte)0x87, (byte)0xCF, (byte)0x65,
                (byte)0xD0, (byte)0x3D, (byte)0x4D, (byte)0x47, (byte)0x83, (byte)0xC6, (byte)0x61,
                (byte)0xB9, (byte)0x3C, (byte)0x1D, (byte)0x3E, (byte)0x97, (byte)0x41, (byte)0xF2,
                (byte)0x32, (byte)0xBD, (byte)0x2E, (byte)0x77, (byte)0x83, (byte)0xE0, (byte)0x61,
                (byte)0x32, (byte)0x39, (byte)0xED, (byte)0x3E, (byte)0x37, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x00
        };
        SmsCbMessage msg = createFromPdu(pdu);

        assertEquals("Unexpected 7-bit string decoded",
                "A GSM default alphabet message with carriage return padding",
                msg.getMessageBody());

        assertEquals("Unexpected language indicator decoded", "sv", msg.getLanguageCode());
    }

    public void testGetMessageBody7BitWithLanguageInBodyUmts() {
        byte[] pdu = {
                (byte)0x01, (byte)0x00, (byte)0x32, (byte)0xC0, (byte)0x00, (byte)0x10,

                (byte)0x01,

                (byte)0x73, (byte)0x7B, (byte)0x23, (byte)0x08, (byte)0x3A, (byte)0x4E,
                (byte)0x9B, (byte)0x20, (byte)0x72, (byte)0xD9, (byte)0x1C, (byte)0xAE,
                (byte)0xB3, (byte)0xE9, (byte)0xA0, (byte)0x30, (byte)0x1B, (byte)0x8E,
                (byte)0x0E, (byte)0x8B, (byte)0xCB, (byte)0x74, (byte)0x50, (byte)0xBB,
                (byte)0x3C, (byte)0x9F, (byte)0x87, (byte)0xCF, (byte)0x65, (byte)0xD0,
                (byte)0x3D, (byte)0x4D, (byte)0x47, (byte)0x83, (byte)0xC6, (byte)0x61,
                (byte)0xB9, (byte)0x3C, (byte)0x1D, (byte)0x3E, (byte)0x97, (byte)0x41,
                (byte)0xF2, (byte)0x32, (byte)0xBD, (byte)0x2E, (byte)0x77, (byte)0x83,
                (byte)0xE0, (byte)0x61, (byte)0x32, (byte)0x39, (byte)0xED, (byte)0x3E,
                (byte)0x37, (byte)0x1A, (byte)0x8D, (byte)0x46, (byte)0xA3, (byte)0xD1,
                (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D, (byte)0x46, (byte)0xA3,
                (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D, (byte)0x46,
                (byte)0xA3, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x00,

                (byte)0x37
        };
        SmsCbMessage msg = createFromPdu(pdu);

        assertEquals("Unexpected 7-bit string decoded",
                "A GSM default alphabet message with carriage return padding",
                msg.getMessageBody());

        assertEquals("Unexpected language indicator decoded", "sv", msg.getLanguageCode());
    }

    public void testGetMessageBody8Bit() {
        byte[] pdu = {
                (byte)0xC0, (byte)0x00, (byte)0x00, (byte)0x32, (byte)0x44, (byte)0x11, (byte)0x41,
                (byte)0x42, (byte)0x43, (byte)0x44, (byte)0x45, (byte)0x46, (byte)0x47, (byte)0x41,
                (byte)0x42, (byte)0x43, (byte)0x44, (byte)0x45, (byte)0x46, (byte)0x47, (byte)0x41,
                (byte)0x42, (byte)0x43, (byte)0x44, (byte)0x45, (byte)0x46, (byte)0x47, (byte)0x41,
                (byte)0x42, (byte)0x43, (byte)0x44, (byte)0x45, (byte)0x46, (byte)0x47, (byte)0x41,
                (byte)0x42, (byte)0x43, (byte)0x44, (byte)0x45, (byte)0x46, (byte)0x47, (byte)0x41,
                (byte)0x42, (byte)0x43, (byte)0x44, (byte)0x45, (byte)0x46, (byte)0x47, (byte)0x41,
                (byte)0x42, (byte)0x43, (byte)0x44, (byte)0x45, (byte)0x46, (byte)0x47, (byte)0x41,
                (byte)0x42, (byte)0x43, (byte)0x44, (byte)0x45, (byte)0x46, (byte)0x47, (byte)0x41,
                (byte)0x42, (byte)0x43, (byte)0x44, (byte)0x45, (byte)0x46, (byte)0x47, (byte)0x41,
                (byte)0x42, (byte)0x43, (byte)0x44, (byte)0x45, (byte)0x46, (byte)0x47, (byte)0x41,
                (byte)0x42, (byte)0x43, (byte)0x44, (byte)0x45, (byte)0x46, (byte)0x47, (byte)0x41,
                (byte)0x42, (byte)0x43, (byte)0x44, (byte)0x45
        };
        SmsCbMessage msg = createFromPdu(pdu);

        assertEquals("8-bit message body should be empty", "", msg.getMessageBody());
    }

    public void testGetMessageBodyUcs2() {
        byte[] pdu = {
                (byte)0xC0, (byte)0x00, (byte)0x00, (byte)0x32, (byte)0x48, (byte)0x11, (byte)0x00,
                (byte)0x41, (byte)0x00, (byte)0x20, (byte)0x00, (byte)0x55, (byte)0x00, (byte)0x43,
                (byte)0x00, (byte)0x53, (byte)0x00, (byte)0x32, (byte)0x00, (byte)0x20, (byte)0x00,
                (byte)0x6D, (byte)0x00, (byte)0x65, (byte)0x00, (byte)0x73, (byte)0x00, (byte)0x73,
                (byte)0x00, (byte)0x61, (byte)0x00, (byte)0x67, (byte)0x00, (byte)0x65, (byte)0x00,
                (byte)0x20, (byte)0x00, (byte)0x63, (byte)0x00, (byte)0x6F, (byte)0x00, (byte)0x6E,
                (byte)0x00, (byte)0x74, (byte)0x00, (byte)0x61, (byte)0x00, (byte)0x69, (byte)0x00,
                (byte)0x6E, (byte)0x00, (byte)0x69, (byte)0x00, (byte)0x6E, (byte)0x00, (byte)0x67,
                (byte)0x00, (byte)0x20, (byte)0x00, (byte)0x61, (byte)0x00, (byte)0x20, (byte)0x04,
                (byte)0x34, (byte)0x00, (byte)0x20, (byte)0x00, (byte)0x63, (byte)0x00, (byte)0x68,
                (byte)0x00, (byte)0x61, (byte)0x00, (byte)0x72, (byte)0x00, (byte)0x61, (byte)0x00,
                (byte)0x63, (byte)0x00, (byte)0x74, (byte)0x00, (byte)0x65, (byte)0x00, (byte)0x72,
                (byte)0x00, (byte)0x0D, (byte)0x00, (byte)0x0D
        };
        SmsCbMessage msg = createFromPdu(pdu);

        assertEquals("Unexpected 7-bit string decoded",
                "A UCS2 message containing a \u0434 character", msg.getMessageBody());
    }

    public void testGetMessageBodyUcs2Umts() {
        byte[] pdu = {
                (byte)0x01, (byte)0x00, (byte)0x32, (byte)0xC0, (byte)0x00, (byte)0x48,

                (byte)0x01,

                (byte)0x00, (byte)0x41, (byte)0x00, (byte)0x20, (byte)0x00, (byte)0x55,
                (byte)0x00, (byte)0x43, (byte)0x00, (byte)0x53, (byte)0x00, (byte)0x32,
                (byte)0x00, (byte)0x20, (byte)0x00, (byte)0x6D, (byte)0x00, (byte)0x65,
                (byte)0x00, (byte)0x73, (byte)0x00, (byte)0x73, (byte)0x00, (byte)0x61,
                (byte)0x00, (byte)0x67, (byte)0x00, (byte)0x65, (byte)0x00, (byte)0x20,
                (byte)0x00, (byte)0x63, (byte)0x00, (byte)0x6F, (byte)0x00, (byte)0x6E,
                (byte)0x00, (byte)0x74, (byte)0x00, (byte)0x61, (byte)0x00, (byte)0x69,
                (byte)0x00, (byte)0x6E, (byte)0x00, (byte)0x69, (byte)0x00, (byte)0x6E,
                (byte)0x00, (byte)0x67, (byte)0x00, (byte)0x20, (byte)0x00, (byte)0x61,
                (byte)0x00, (byte)0x20, (byte)0x04, (byte)0x34, (byte)0x00, (byte)0x20,
                (byte)0x00, (byte)0x63, (byte)0x00, (byte)0x68, (byte)0x00, (byte)0x61,
                (byte)0x00, (byte)0x72, (byte)0x00, (byte)0x61, (byte)0x00, (byte)0x63,
                (byte)0x00, (byte)0x74, (byte)0x00, (byte)0x65, (byte)0x00, (byte)0x72,
                (byte)0x00, (byte)0x0D, (byte)0x00, (byte)0x0D,

                (byte)0x4E
        };
        SmsCbMessage msg = createFromPdu(pdu);

        assertEquals("Unexpected 7-bit string decoded",
                "A UCS2 message containing a \u0434 character", msg.getMessageBody());
    }

    public void testGetMessageBodyUcs2MultipageUmts() {
        byte[] pdu = {
                (byte)0x01, (byte)0x00, (byte)0x32, (byte)0xC0, (byte)0x00, (byte)0x48,

                (byte)0x02,

                (byte)0x00, (byte)0x41, (byte)0x00, (byte)0x41, (byte)0x00, (byte)0x41,
                (byte)0x00, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D,
                (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D,
                (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D,
                (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D,
                (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D,
                (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D,
                (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D,
                (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D,
                (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D,
                (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D,
                (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D,
                (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D,
                (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D,

                (byte)0x06,

                (byte)0x00, (byte)0x42, (byte)0x00, (byte)0x42, (byte)0x00, (byte)0x42,
                (byte)0x00, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D,
                (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D,
                (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D,
                (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D,
                (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D,
                (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D,
                (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D,
                (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D,
                (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D,
                (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D,
                (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D,
                (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D,
                (byte)0x0D, (byte)0x0D, (byte)0x0D, (byte)0x0D,

                (byte)0x06
        };
        SmsCbMessage msg = createFromPdu(pdu);

        assertEquals("Unexpected multipage UCS2 string decoded",
                "AAABBB", msg.getMessageBody());
    }

    public void testGetMessageBodyUcs2WithLanguageInBody() {
        byte[] pdu = {
                (byte)0xC0, (byte)0x00, (byte)0x00, (byte)0x32, (byte)0x11, (byte)0x11, (byte)0x78,
                (byte)0x3C, (byte)0x00, (byte)0x41, (byte)0x00, (byte)0x20, (byte)0x00, (byte)0x55,
                (byte)0x00, (byte)0x43, (byte)0x00, (byte)0x53, (byte)0x00, (byte)0x32, (byte)0x00,
                (byte)0x20, (byte)0x00, (byte)0x6D, (byte)0x00, (byte)0x65, (byte)0x00, (byte)0x73,
                (byte)0x00, (byte)0x73, (byte)0x00, (byte)0x61, (byte)0x00, (byte)0x67, (byte)0x00,
                (byte)0x65, (byte)0x00, (byte)0x20, (byte)0x00, (byte)0x63, (byte)0x00, (byte)0x6F,
                (byte)0x00, (byte)0x6E, (byte)0x00, (byte)0x74, (byte)0x00, (byte)0x61, (byte)0x00,
                (byte)0x69, (byte)0x00, (byte)0x6E, (byte)0x00, (byte)0x69, (byte)0x00, (byte)0x6E,
                (byte)0x00, (byte)0x67, (byte)0x00, (byte)0x20, (byte)0x00, (byte)0x61, (byte)0x00,
                (byte)0x20, (byte)0x04, (byte)0x34, (byte)0x00, (byte)0x20, (byte)0x00, (byte)0x63,
                (byte)0x00, (byte)0x68, (byte)0x00, (byte)0x61, (byte)0x00, (byte)0x72, (byte)0x00,
                (byte)0x61, (byte)0x00, (byte)0x63, (byte)0x00, (byte)0x74, (byte)0x00, (byte)0x65,
                (byte)0x00, (byte)0x72, (byte)0x00, (byte)0x0D
        };
        SmsCbMessage msg = createFromPdu(pdu);

        assertEquals("Unexpected 7-bit string decoded",
                "A UCS2 message containing a \u0434 character", msg.getMessageBody());

        assertEquals("Unexpected language indicator decoded", "xx", msg.getLanguageCode());
    }

    public void testGetMessageBodyUcs2WithLanguageInBodyUmts() {
        byte[] pdu = {
                (byte)0x01, (byte)0x00, (byte)0x32, (byte)0xC0, (byte)0x00, (byte)0x11,

                (byte)0x01,

                (byte)0x78, (byte)0x3C, (byte)0x00, (byte)0x41, (byte)0x00, (byte)0x20,
                (byte)0x00, (byte)0x55, (byte)0x00, (byte)0x43, (byte)0x00, (byte)0x53,
                (byte)0x00, (byte)0x32, (byte)0x00, (byte)0x20, (byte)0x00, (byte)0x6D,
                (byte)0x00, (byte)0x65, (byte)0x00, (byte)0x73, (byte)0x00, (byte)0x73,
                (byte)0x00, (byte)0x61, (byte)0x00, (byte)0x67, (byte)0x00, (byte)0x65,
                (byte)0x00, (byte)0x20, (byte)0x00, (byte)0x63, (byte)0x00, (byte)0x6F,
                (byte)0x00, (byte)0x6E, (byte)0x00, (byte)0x74, (byte)0x00, (byte)0x61,
                (byte)0x00, (byte)0x69, (byte)0x00, (byte)0x6E, (byte)0x00, (byte)0x69,
                (byte)0x00, (byte)0x6E, (byte)0x00, (byte)0x67, (byte)0x00, (byte)0x20,
                (byte)0x00, (byte)0x61, (byte)0x00, (byte)0x20, (byte)0x04, (byte)0x34,
                (byte)0x00, (byte)0x20, (byte)0x00, (byte)0x63, (byte)0x00, (byte)0x68,
                (byte)0x00, (byte)0x61, (byte)0x00, (byte)0x72, (byte)0x00, (byte)0x61,
                (byte)0x00, (byte)0x63, (byte)0x00, (byte)0x74, (byte)0x00, (byte)0x65,
                (byte)0x00, (byte)0x72, (byte)0x00, (byte)0x0D,

                (byte)0x50
        };
        SmsCbMessage msg = createFromPdu(pdu);

        assertEquals("Unexpected 7-bit string decoded",
                "A UCS2 message containing a \u0434 character", msg.getMessageBody());

        assertEquals("Unexpected language indicator decoded", "xx", msg.getLanguageCode());
    }

    public void testGetMessageIdentifier() {
        byte[] pdu = {
                (byte)0xC0, (byte)0x00, (byte)0x30, (byte)0x39, (byte)0x40, (byte)0x11, (byte)0x41,
                (byte)0xD0, (byte)0x71, (byte)0xDA, (byte)0x04, (byte)0x91, (byte)0xCB, (byte)0xE6,
                (byte)0x70, (byte)0x9D, (byte)0x4D, (byte)0x07, (byte)0x85, (byte)0xD9, (byte)0x70,
                (byte)0x74, (byte)0x58, (byte)0x5C, (byte)0xA6, (byte)0x83, (byte)0xDA, (byte)0xE5,
                (byte)0xF9, (byte)0x3C, (byte)0x7C, (byte)0x2E, (byte)0x83, (byte)0xEE, (byte)0x69,
                (byte)0x3A, (byte)0x1A, (byte)0x34, (byte)0x0E, (byte)0xCB, (byte)0xE5, (byte)0xE9,
                (byte)0xF0, (byte)0xB9, (byte)0x0C, (byte)0x92, (byte)0x97, (byte)0xE9, (byte)0x75,
                (byte)0xB9, (byte)0x1B, (byte)0x04, (byte)0x0F, (byte)0x93, (byte)0xC9, (byte)0x69,
                (byte)0xF7, (byte)0xB9, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x00
        };

        SmsCbMessage msg = createFromPdu(pdu);

        assertEquals("Unexpected message identifier decoded", 12345, msg.getServiceCategory());
    }

    public void testGetMessageIdentifierUmts() {
        byte[] pdu = {
                (byte)0x01, (byte)0x30, (byte)0x39, (byte)0x2A, (byte)0xA5, (byte)0x40,

                (byte)0x01,

                (byte)0x41, (byte)0xD0, (byte)0x71, (byte)0xDA, (byte)0x04, (byte)0x91,
                (byte)0xCB, (byte)0xE6, (byte)0x70, (byte)0x9D, (byte)0x4D, (byte)0x07,
                (byte)0x85, (byte)0xD9, (byte)0x70, (byte)0x74, (byte)0x58, (byte)0x5C,
                (byte)0xA6, (byte)0x83, (byte)0xDA, (byte)0xE5, (byte)0xF9, (byte)0x3C,
                (byte)0x7C, (byte)0x2E, (byte)0x83, (byte)0xEE, (byte)0x69, (byte)0x3A,
                (byte)0x1A, (byte)0x34, (byte)0x0E, (byte)0xCB, (byte)0xE5, (byte)0xE9,
                (byte)0xF0, (byte)0xB9, (byte)0x0C, (byte)0x92, (byte)0x97, (byte)0xE9,
                (byte)0x75, (byte)0xB9, (byte)0x1B, (byte)0x04, (byte)0x0F, (byte)0x93,
                (byte)0xC9, (byte)0x69, (byte)0xF7, (byte)0xB9, (byte)0xD1, (byte)0x68,
                (byte)0x34, (byte)0x1A, (byte)0x8D, (byte)0x46, (byte)0xA3, (byte)0xD1,
                (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D, (byte)0x46, (byte)0xA3,
                (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D, (byte)0x46,
                (byte)0xA3, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x00,

                (byte)0x34
        };

        SmsCbMessage msg = createFromPdu(pdu);

        assertEquals("Unexpected message identifier decoded", 12345, msg.getServiceCategory());
    }

    public void testGetMessageCode() {
        byte[] pdu = {
                (byte)0x2A, (byte)0xA5, (byte)0x30, (byte)0x39, (byte)0x40, (byte)0x11, (byte)0x41,
                (byte)0xD0, (byte)0x71, (byte)0xDA, (byte)0x04, (byte)0x91, (byte)0xCB, (byte)0xE6,
                (byte)0x70, (byte)0x9D, (byte)0x4D, (byte)0x07, (byte)0x85, (byte)0xD9, (byte)0x70,
                (byte)0x74, (byte)0x58, (byte)0x5C, (byte)0xA6, (byte)0x83, (byte)0xDA, (byte)0xE5,
                (byte)0xF9, (byte)0x3C, (byte)0x7C, (byte)0x2E, (byte)0x83, (byte)0xEE, (byte)0x69,
                (byte)0x3A, (byte)0x1A, (byte)0x34, (byte)0x0E, (byte)0xCB, (byte)0xE5, (byte)0xE9,
                (byte)0xF0, (byte)0xB9, (byte)0x0C, (byte)0x92, (byte)0x97, (byte)0xE9, (byte)0x75,
                (byte)0xB9, (byte)0x1B, (byte)0x04, (byte)0x0F, (byte)0x93, (byte)0xC9, (byte)0x69,
                (byte)0xF7, (byte)0xB9, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x00
        };

        SmsCbMessage msg = createFromPdu(pdu);
        int messageCode = (msg.getSerialNumber() & 0x3ff0) >> 4;

        assertEquals("Unexpected message code decoded", 682, messageCode);
    }

    public void testGetMessageCodeUmts() {
        byte[] pdu = {
                (byte)0x01, (byte)0x30, (byte)0x39, (byte)0x2A, (byte)0xA5, (byte)0x40,

                (byte)0x01,

                (byte)0x41, (byte)0xD0, (byte)0x71, (byte)0xDA, (byte)0x04, (byte)0x91,
                (byte)0xCB, (byte)0xE6, (byte)0x70, (byte)0x9D, (byte)0x4D, (byte)0x07,
                (byte)0x85, (byte)0xD9, (byte)0x70, (byte)0x74, (byte)0x58, (byte)0x5C,
                (byte)0xA6, (byte)0x83, (byte)0xDA, (byte)0xE5, (byte)0xF9, (byte)0x3C,
                (byte)0x7C, (byte)0x2E, (byte)0x83, (byte)0xEE, (byte)0x69, (byte)0x3A,
                (byte)0x1A, (byte)0x34, (byte)0x0E, (byte)0xCB, (byte)0xE5, (byte)0xE9,
                (byte)0xF0, (byte)0xB9, (byte)0x0C, (byte)0x92, (byte)0x97, (byte)0xE9,
                (byte)0x75, (byte)0xB9, (byte)0x1B, (byte)0x04, (byte)0x0F, (byte)0x93,
                (byte)0xC9, (byte)0x69, (byte)0xF7, (byte)0xB9, (byte)0xD1, (byte)0x68,
                (byte)0x34, (byte)0x1A, (byte)0x8D, (byte)0x46, (byte)0xA3, (byte)0xD1,
                (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D, (byte)0x46, (byte)0xA3,
                (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D, (byte)0x46,
                (byte)0xA3, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x00,

                (byte)0x34
        };

        SmsCbMessage msg = createFromPdu(pdu);
        int messageCode = (msg.getSerialNumber() & 0x3ff0) >> 4;

        assertEquals("Unexpected message code decoded", 682, messageCode);
    }

    public void testGetUpdateNumber() {
        byte[] pdu = {
                (byte)0x2A, (byte)0xA5, (byte)0x30, (byte)0x39, (byte)0x40, (byte)0x11, (byte)0x41,
                (byte)0xD0, (byte)0x71, (byte)0xDA, (byte)0x04, (byte)0x91, (byte)0xCB, (byte)0xE6,
                (byte)0x70, (byte)0x9D, (byte)0x4D, (byte)0x07, (byte)0x85, (byte)0xD9, (byte)0x70,
                (byte)0x74, (byte)0x58, (byte)0x5C, (byte)0xA6, (byte)0x83, (byte)0xDA, (byte)0xE5,
                (byte)0xF9, (byte)0x3C, (byte)0x7C, (byte)0x2E, (byte)0x83, (byte)0xEE, (byte)0x69,
                (byte)0x3A, (byte)0x1A, (byte)0x34, (byte)0x0E, (byte)0xCB, (byte)0xE5, (byte)0xE9,
                (byte)0xF0, (byte)0xB9, (byte)0x0C, (byte)0x92, (byte)0x97, (byte)0xE9, (byte)0x75,
                (byte)0xB9, (byte)0x1B, (byte)0x04, (byte)0x0F, (byte)0x93, (byte)0xC9, (byte)0x69,
                (byte)0xF7, (byte)0xB9, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x00
        };

        SmsCbMessage msg = createFromPdu(pdu);
        int updateNumber = msg.getSerialNumber() & 0x000f;

        assertEquals("Unexpected update number decoded", 5, updateNumber);
    }

    public void testGetUpdateNumberUmts() {
        byte[] pdu = {
                (byte)0x01, (byte)0x30, (byte)0x39, (byte)0x2A, (byte)0xA5, (byte)0x40,

                (byte)0x01,

                (byte)0x41, (byte)0xD0, (byte)0x71, (byte)0xDA, (byte)0x04, (byte)0x91,
                (byte)0xCB, (byte)0xE6, (byte)0x70, (byte)0x9D, (byte)0x4D, (byte)0x07,
                (byte)0x85, (byte)0xD9, (byte)0x70, (byte)0x74, (byte)0x58, (byte)0x5C,
                (byte)0xA6, (byte)0x83, (byte)0xDA, (byte)0xE5, (byte)0xF9, (byte)0x3C,
                (byte)0x7C, (byte)0x2E, (byte)0x83, (byte)0xEE, (byte)0x69, (byte)0x3A,
                (byte)0x1A, (byte)0x34, (byte)0x0E, (byte)0xCB, (byte)0xE5, (byte)0xE9,
                (byte)0xF0, (byte)0xB9, (byte)0x0C, (byte)0x92, (byte)0x97, (byte)0xE9,
                (byte)0x75, (byte)0xB9, (byte)0x1B, (byte)0x04, (byte)0x0F, (byte)0x93,
                (byte)0xC9, (byte)0x69, (byte)0xF7, (byte)0xB9, (byte)0xD1, (byte)0x68,
                (byte)0x34, (byte)0x1A, (byte)0x8D, (byte)0x46, (byte)0xA3, (byte)0xD1,
                (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D, (byte)0x46, (byte)0xA3,
                (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D, (byte)0x46,
                (byte)0xA3, (byte)0xD1, (byte)0x68, (byte)0x34, (byte)0x1A, (byte)0x8D,
                (byte)0x46, (byte)0xA3, (byte)0xD1, (byte)0x00,

                (byte)0x34
        };

        SmsCbMessage msg = createFromPdu(pdu);
        int updateNumber = msg.getSerialNumber() & 0x000f;

        assertEquals("Unexpected update number decoded", 5, updateNumber);
    }

    /* ETWS Test message including header */
    private static final byte[] etwsMessageNormal = IccUtils.hexStringToBytes("000011001101" +
            "0D0A5BAE57CE770C531790E85C716CBF3044573065B930675730" +
            "9707767A751F30025F37304463FA308C306B5099304830664E0B30553044FF086C178C615E81FF09" +
            "0000000000000000000000000000");

    private static final byte[] etwsMessageCancel = IccUtils.hexStringToBytes("000011001101" +
            "0D0A5148307B3069002800310030003A0035" +
            "00320029306E7DCA602557309707901F5831309253D66D883057307E3059FF086C178C615E81FF09" +
            "00000000000000000000000000000000000000000000");

    private static final byte[] etwsMessageTest = IccUtils.hexStringToBytes("000011031101" +
            "0D0A5BAE57CE770C531790E85C716CBF3044" +
            "573065B9306757309707300263FA308C306B5099304830664E0B30553044FF086C178C615E81FF09" +
            "00000000000000000000000000000000000000000000");

    // FIXME: add example of ETWS primary notification PDU

    public void testEtwsMessageNormal() {
        SmsCbMessage msg = createFromPdu(etwsMessageNormal);
        Log.d(TAG, msg.toString());
        assertEquals("GS mismatch", 0, msg.getGeographicalScope());
        assertEquals("serial number mismatch", 0, msg.getSerialNumber());
        assertEquals("message ID mismatch", 0x1100, msg.getServiceCategory());
        assertEquals("warning type mismatch", SmsCbEtwsInfo.ETWS_WARNING_TYPE_EARTHQUAKE,
                msg.getEtwsWarningInfo().getWarningType());
    }

    public void testEtwsMessageCancel() {
        SmsCbMessage msg = createFromPdu(etwsMessageCancel);
        Log.d(TAG, msg.toString());
        assertEquals("GS mismatch", 0, msg.getGeographicalScope());
        assertEquals("serial number mismatch", 0, msg.getSerialNumber());
        assertEquals("message ID mismatch", 0x1100, msg.getServiceCategory());
        assertEquals("warning type mismatch", SmsCbEtwsInfo.ETWS_WARNING_TYPE_EARTHQUAKE,
                msg.getEtwsWarningInfo().getWarningType());
    }

    public void testEtwsMessageTest() {
        SmsCbMessage msg = createFromPdu(etwsMessageTest);
        Log.d(TAG, msg.toString());
        assertEquals("GS mismatch", 0, msg.getGeographicalScope());
        assertEquals("serial number mismatch", 0, msg.getSerialNumber());
        assertEquals("message ID mismatch", 0x1103, msg.getServiceCategory());
        assertEquals("warning type mismatch", SmsCbEtwsInfo.ETWS_WARNING_TYPE_TEST_MESSAGE,
                msg.getEtwsWarningInfo().getWarningType());
    }

    // Make sure we don't throw an exception if we feed random data to the PDU parser.
    public void testRandomPdus() {
        Random r = new Random(94040);
        for (int run = 0; run < 10000; run++) {
            int len = r.nextInt(140);
            byte[] data = new byte[len];
            for (int i = 0; i < len; i++) {
                data[i] = (byte) r.nextInt(256);
            }
            try {
                // this should return a SmsCbMessage object or null for invalid data
                SmsCbMessage msg = createFromPdu(data);
            } catch (Exception e) {
                Log.d(TAG, "exception thrown", e);
                fail("Exception in decoder at run " + run + " length " + len + ": " + e);
            }
        }
    }
}
