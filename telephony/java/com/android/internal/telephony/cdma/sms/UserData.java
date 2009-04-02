/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.telephony.cdma.sms;

import com.android.internal.telephony.SmsHeader;

public class UserData{

    /**
     * Supported user data encoding types
     * (See 3GPP2 C.R1001-F, v1.0, table 9.1-1)
     */
    public static final int UD_ENCODING_OCTET                      = 0x00;
    //public static final int UD_ENCODING_EXTENDED_PROTOCOL          = 0x01;
    public static final int UD_ENCODING_7BIT_ASCII                 = 0x02;
    public static final int UD_ENCODING_IA5                        = 0x03;
    public static final int UD_ENCODING_UNICODE_16                 = 0x04;
    //public static final int UD_ENCODING_SHIFT_JIS                  = 0x05;
    //public static final int UD_ENCODING_KOREAN                     = 0x06;
    //public static final int UD_ENCODING_LATIN_HEBREW               = 0x07;
    //public static final int UD_ENCODING_LATIN                      = 0x08;
    public static final int UD_ENCODING_GSM_7BIT_ALPHABET          = 0x09;
    //public static final int UD_ENCODING_GSM_DCS                    = 0x0A;

    /**
     * Contains the data header of the user data
     */
    public SmsHeader userDataHeader;

    /**
     * Contains the data encoding type for the SMS message
     */
    public int userDataEncoding;

    // needed when encoding is IS91 or DCS (not supported yet):
    //public int messageType;

    /**
     * Number of invalid bits in the last byte of data.
     */
    public int paddingBits;

    /**
     * Contains the user data of a SMS message
     * (See 3GPP2 C.S0015-B, v2, 4.5.2)
     */
    public byte[] userData;

}
