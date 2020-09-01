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

package com.android.internal.telephony.gsm;

import android.compat.annotation.UnsupportedAppUsage;
import android.telephony.PhoneNumberUtils;

import com.android.internal.telephony.GsmAlphabet;
import com.android.internal.telephony.SmsAddress;

import java.text.ParseException;

public class GsmSmsAddress extends SmsAddress {

    static final int OFFSET_ADDRESS_LENGTH = 0;

    static final int OFFSET_TOA = 1;

    static final int OFFSET_ADDRESS_VALUE = 2;

    /**
     * New GsmSmsAddress from TS 23.040 9.1.2.5 Address Field
     *
     * @param offset the offset of the Address-Length byte
     * @param length the length in bytes rounded up, e.g. "2 +
     *        (addressLength + 1) / 2"
     * @throws ParseException
     */
    @UnsupportedAppUsage
    public GsmSmsAddress(byte[] data, int offset, int length) throws ParseException {
        origBytes = new byte[length];
        System.arraycopy(data, offset, origBytes, 0, length);

        // addressLength is the count of semi-octets, not bytes
        int addressLength = origBytes[OFFSET_ADDRESS_LENGTH] & 0xff;

        int toa = origBytes[OFFSET_TOA] & 0xff;
        ton = 0x7 & (toa >> 4);

        // TOA must have its high bit set
        if ((toa & 0x80) != 0x80) {
            throw new ParseException("Invalid TOA - high bit must be set. toa = " + toa,
                    offset + OFFSET_TOA);
        }

        if (isAlphanumeric()) {
            // An alphanumeric address
            int countSeptets = addressLength * 4 / 7;

            address = GsmAlphabet.gsm7BitPackedToString(origBytes,
                    OFFSET_ADDRESS_VALUE, countSeptets);
        } else {
            // TS 23.040 9.1.2.5 says
            // that "the MS shall interpret reserved values as 'Unknown'
            // but shall store them exactly as received"

            byte lastByte = origBytes[length - 1];

            if ((addressLength & 1) == 1) {
                // Make sure the final unused BCD digit is 0xf
                origBytes[length - 1] |= 0xf0;
            }
            address = PhoneNumberUtils.calledPartyBCDToString(
                    origBytes,
                    OFFSET_TOA,
                    length - OFFSET_TOA,
                    PhoneNumberUtils.BCD_EXTENDED_TYPE_CALLED_PARTY);

            // And restore origBytes
            origBytes[length - 1] = lastByte;
        }
    }

    @Override
    public String getAddressString() {
        return address;
    }

    /**
     * Returns true if this is an alphanumeric address
     */
    @Override
    public boolean isAlphanumeric() {
        return ton == TON_ALPHANUMERIC;
    }

    @Override
    public boolean isNetworkSpecific() {
        return ton == TON_NETWORK;
    }

    /**
     * Returns true of this is a valid CPHS voice message waiting indicator
     * address
     */
    public boolean isCphsVoiceMessageIndicatorAddress() {
        // CPHS-style MWI message
        // See CPHS 4.7 B.4.2.1
        //
        // Basically:
        //
        // - Originating address should be 4 bytes long and alphanumeric
        // - Decode will result with two chars:
        // - Char 1
        // 76543210
        // ^ set/clear indicator (0 = clear)
        // ^^^ type of indicator (000 = voice)
        // ^^^^ must be equal to 0001
        // - Char 2:
        // 76543210
        // ^ line number (0 = line 1)
        // ^^^^^^^ set to 0
        //
        // Remember, since the alpha address is stored in 7-bit compact form,
        // the "line number" is really the top bit of the first address value
        // byte

        return (origBytes[OFFSET_ADDRESS_LENGTH] & 0xff) == 4
                && isAlphanumeric() && (origBytes[OFFSET_TOA] & 0x0f) == 0;
    }

    /**
     * Returns true if this is a valid CPHS voice message waiting indicator
     * address indicating a "set" of "indicator 1" of type "voice message
     * waiting"
     */
    @UnsupportedAppUsage
    public boolean isCphsVoiceMessageSet() {
        // 0x11 means "set" "voice message waiting" "indicator 1"
        return isCphsVoiceMessageIndicatorAddress()
                && (origBytes[OFFSET_ADDRESS_VALUE] & 0xff) == 0x11;

    }

    /**
     * Returns true if this is a valid CPHS voice message waiting indicator
     * address indicating a "clear" of "indicator 1" of type "voice message
     * waiting"
     */
    @UnsupportedAppUsage
    public boolean isCphsVoiceMessageClear() {
        // 0x10 means "clear" "voice message waiting" "indicator 1"
        return isCphsVoiceMessageIndicatorAddress()
                && (origBytes[OFFSET_ADDRESS_VALUE] & 0xff) == 0x10;

    }
}
