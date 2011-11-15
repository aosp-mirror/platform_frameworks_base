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

import android.telephony.SmsMessage;

import com.android.internal.util.HexDump;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import java.util.ArrayList;

/**
 * SMS user data header, as specified in TS 23.040 9.2.3.24.
 */
public class SmsHeader {

    // TODO(cleanup): this data structure is generally referred to as
    // the 'user data header' or UDH, and so the class name should
    // change to reflect this...

    /** SMS user data header information element identifiers.
     * (see TS 23.040 9.2.3.24)
     */
    public static final int ELT_ID_CONCATENATED_8_BIT_REFERENCE       = 0x00;
    public static final int ELT_ID_SPECIAL_SMS_MESSAGE_INDICATION     = 0x01;
    public static final int ELT_ID_APPLICATION_PORT_ADDRESSING_8_BIT  = 0x04;
    public static final int ELT_ID_APPLICATION_PORT_ADDRESSING_16_BIT = 0x05;
    public static final int ELT_ID_SMSC_CONTROL_PARAMS                = 0x06;
    public static final int ELT_ID_UDH_SOURCE_INDICATION              = 0x07;
    public static final int ELT_ID_CONCATENATED_16_BIT_REFERENCE      = 0x08;
    public static final int ELT_ID_WIRELESS_CTRL_MSG_PROTOCOL         = 0x09;
    public static final int ELT_ID_TEXT_FORMATTING                    = 0x0A;
    public static final int ELT_ID_PREDEFINED_SOUND                   = 0x0B;
    public static final int ELT_ID_USER_DEFINED_SOUND                 = 0x0C;
    public static final int ELT_ID_PREDEFINED_ANIMATION               = 0x0D;
    public static final int ELT_ID_LARGE_ANIMATION                    = 0x0E;
    public static final int ELT_ID_SMALL_ANIMATION                    = 0x0F;
    public static final int ELT_ID_LARGE_PICTURE                      = 0x10;
    public static final int ELT_ID_SMALL_PICTURE                      = 0x11;
    public static final int ELT_ID_VARIABLE_PICTURE                   = 0x12;
    public static final int ELT_ID_USER_PROMPT_INDICATOR              = 0x13;
    public static final int ELT_ID_EXTENDED_OBJECT                    = 0x14;
    public static final int ELT_ID_REUSED_EXTENDED_OBJECT             = 0x15;
    public static final int ELT_ID_COMPRESSION_CONTROL                = 0x16;
    public static final int ELT_ID_OBJECT_DISTR_INDICATOR             = 0x17;
    public static final int ELT_ID_STANDARD_WVG_OBJECT                = 0x18;
    public static final int ELT_ID_CHARACTER_SIZE_WVG_OBJECT          = 0x19;
    public static final int ELT_ID_EXTENDED_OBJECT_DATA_REQUEST_CMD   = 0x1A;
    public static final int ELT_ID_RFC_822_EMAIL_HEADER               = 0x20;
    public static final int ELT_ID_HYPERLINK_FORMAT_ELEMENT           = 0x21;
    public static final int ELT_ID_REPLY_ADDRESS_ELEMENT              = 0x22;
    public static final int ELT_ID_ENHANCED_VOICE_MAIL_INFORMATION    = 0x23;
    public static final int ELT_ID_NATIONAL_LANGUAGE_SINGLE_SHIFT     = 0x24;
    public static final int ELT_ID_NATIONAL_LANGUAGE_LOCKING_SHIFT    = 0x25;

    public static final int PORT_WAP_PUSH = 2948;
    public static final int PORT_WAP_WSP  = 9200;

    public static class PortAddrs {
        public int destPort;
        public int origPort;
        public boolean areEightBits;
    }

    public static class ConcatRef {
        public int refNumber;
        public int seqNumber;
        public int msgCount;
        public boolean isEightBits;
    }

    /**
     * A header element that is not explicitly parsed, meaning not
     * PortAddrs or ConcatRef.
     */
    public static class MiscElt {
        public int id;
        public byte[] data;
    }

    public PortAddrs portAddrs;
    public ConcatRef concatRef;
    public ArrayList<MiscElt> miscEltList = new ArrayList<MiscElt>();

    /** 7 bit national language locking shift table, or 0 for GSM default 7 bit alphabet. */
    public int languageTable;

    /** 7 bit national language single shift table, or 0 for GSM default 7 bit extension table. */
    public int languageShiftTable;

    public SmsHeader() {}

    /**
     * Create structured SmsHeader object from serialized byte array representation.
     * (see TS 23.040 9.2.3.24)
     * @param data is user data header bytes
     * @return SmsHeader object
     */
    public static SmsHeader fromByteArray(byte[] data) {
        ByteArrayInputStream inStream = new ByteArrayInputStream(data);
        SmsHeader smsHeader = new SmsHeader();
        while (inStream.available() > 0) {
            /**
             * NOTE: as defined in the spec, ConcatRef and PortAddr
             * fields should not reoccur, but if they do the last
             * occurrence is to be used.  Also, for ConcatRef
             * elements, if the count is zero, sequence is zero, or
             * sequence is larger than count, the entire element is to
             * be ignored.
             */
            int id = inStream.read();
            int length = inStream.read();
            ConcatRef concatRef;
            PortAddrs portAddrs;
            switch (id) {
            case ELT_ID_CONCATENATED_8_BIT_REFERENCE:
                concatRef = new ConcatRef();
                concatRef.refNumber = inStream.read();
                concatRef.msgCount = inStream.read();
                concatRef.seqNumber = inStream.read();
                concatRef.isEightBits = true;
                if (concatRef.msgCount != 0 && concatRef.seqNumber != 0 &&
                        concatRef.seqNumber <= concatRef.msgCount) {
                    smsHeader.concatRef = concatRef;
                }
                break;
            case ELT_ID_CONCATENATED_16_BIT_REFERENCE:
                concatRef = new ConcatRef();
                concatRef.refNumber = (inStream.read() << 8) | inStream.read();
                concatRef.msgCount = inStream.read();
                concatRef.seqNumber = inStream.read();
                concatRef.isEightBits = false;
                if (concatRef.msgCount != 0 && concatRef.seqNumber != 0 &&
                        concatRef.seqNumber <= concatRef.msgCount) {
                    smsHeader.concatRef = concatRef;
                }
                break;
            case ELT_ID_APPLICATION_PORT_ADDRESSING_8_BIT:
                portAddrs = new PortAddrs();
                portAddrs.destPort = inStream.read();
                portAddrs.origPort = inStream.read();
                portAddrs.areEightBits = true;
                smsHeader.portAddrs = portAddrs;
                break;
            case ELT_ID_APPLICATION_PORT_ADDRESSING_16_BIT:
                portAddrs = new PortAddrs();
                portAddrs.destPort = (inStream.read() << 8) | inStream.read();
                portAddrs.origPort = (inStream.read() << 8) | inStream.read();
                portAddrs.areEightBits = false;
                smsHeader.portAddrs = portAddrs;
                break;
            case ELT_ID_NATIONAL_LANGUAGE_SINGLE_SHIFT:
                smsHeader.languageShiftTable = inStream.read();
                break;
            case ELT_ID_NATIONAL_LANGUAGE_LOCKING_SHIFT:
                smsHeader.languageTable = inStream.read();
                break;
            default:
                MiscElt miscElt = new MiscElt();
                miscElt.id = id;
                miscElt.data = new byte[length];
                inStream.read(miscElt.data, 0, length);
                smsHeader.miscEltList.add(miscElt);
            }
        }
        return smsHeader;
    }

    /**
     * Create serialized byte array representation from structured SmsHeader object.
     * (see TS 23.040 9.2.3.24)
     * @return Byte array representing the SmsHeader
     */
    public static byte[] toByteArray(SmsHeader smsHeader) {
        if ((smsHeader.portAddrs == null) &&
            (smsHeader.concatRef == null) &&
            (smsHeader.miscEltList.isEmpty()) &&
            (smsHeader.languageShiftTable == 0) &&
            (smsHeader.languageTable == 0)) {
            return null;
        }

        ByteArrayOutputStream outStream = new ByteArrayOutputStream(SmsMessage.MAX_USER_DATA_BYTES);
        ConcatRef concatRef = smsHeader.concatRef;
        if (concatRef != null) {
            if (concatRef.isEightBits) {
                outStream.write(ELT_ID_CONCATENATED_8_BIT_REFERENCE);
                outStream.write(3);
                outStream.write(concatRef.refNumber);
            } else {
                outStream.write(ELT_ID_CONCATENATED_16_BIT_REFERENCE);
                outStream.write(4);
                outStream.write(concatRef.refNumber >>> 8);
                outStream.write(concatRef.refNumber & 0x00FF);
            }
            outStream.write(concatRef.msgCount);
            outStream.write(concatRef.seqNumber);
        }
        PortAddrs portAddrs = smsHeader.portAddrs;
        if (portAddrs != null) {
            if (portAddrs.areEightBits) {
                outStream.write(ELT_ID_APPLICATION_PORT_ADDRESSING_8_BIT);
                outStream.write(2);
                outStream.write(portAddrs.destPort);
                outStream.write(portAddrs.origPort);
            } else {
                outStream.write(ELT_ID_APPLICATION_PORT_ADDRESSING_16_BIT);
                outStream.write(4);
                outStream.write(portAddrs.destPort >>> 8);
                outStream.write(portAddrs.destPort & 0x00FF);
                outStream.write(portAddrs.origPort >>> 8);
                outStream.write(portAddrs.origPort & 0x00FF);
            }
        }
        if (smsHeader.languageShiftTable != 0) {
            outStream.write(ELT_ID_NATIONAL_LANGUAGE_SINGLE_SHIFT);
            outStream.write(1);
            outStream.write(smsHeader.languageShiftTable);
        }
        if (smsHeader.languageTable != 0) {
            outStream.write(ELT_ID_NATIONAL_LANGUAGE_LOCKING_SHIFT);
            outStream.write(1);
            outStream.write(smsHeader.languageTable);
        }
        for (MiscElt miscElt : smsHeader.miscEltList) {
            outStream.write(miscElt.id);
            outStream.write(miscElt.data.length);
            outStream.write(miscElt.data, 0, miscElt.data.length);
        }
        return outStream.toByteArray();
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("UserDataHeader ");
        builder.append("{ ConcatRef ");
        if (concatRef == null) {
            builder.append("unset");
        } else {
            builder.append("{ refNumber=" + concatRef.refNumber);
            builder.append(", msgCount=" + concatRef.msgCount);
            builder.append(", seqNumber=" + concatRef.seqNumber);
            builder.append(", isEightBits=" + concatRef.isEightBits);
            builder.append(" }");
        }
        builder.append(", PortAddrs ");
        if (portAddrs == null) {
            builder.append("unset");
        } else {
            builder.append("{ destPort=" + portAddrs.destPort);
            builder.append(", origPort=" + portAddrs.origPort);
            builder.append(", areEightBits=" + portAddrs.areEightBits);
            builder.append(" }");
        }
        if (languageShiftTable != 0) {
            builder.append(", languageShiftTable=" + languageShiftTable);
        }
        if (languageTable != 0) {
            builder.append(", languageTable=" + languageTable);
        }
        for (MiscElt miscElt : miscEltList) {
            builder.append(", MiscElt ");
            builder.append("{ id=" + miscElt.id);
            builder.append(", length=" + miscElt.data.length);
            builder.append(", data=" + HexDump.toHexString(miscElt.data));
            builder.append(" }");
        }
        builder.append(" }");
        return builder.toString();
    }

}
