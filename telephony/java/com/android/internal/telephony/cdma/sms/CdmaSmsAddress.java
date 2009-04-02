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

import com.android.internal.telephony.SmsAddress;

public class CdmaSmsAddress extends SmsAddress {
    /**
     * digit mode indicators
     * (See 3GPP2 C.S0015-B, v2, 3.4.3.3)
     */
    static public final int DIGIT_MODE_4BIT_DTMF              = 0x00;
    static public final int DIGIT_MODE_8BIT_CHAR              = 0x01;

    /**
     * number mode indicators
     * (See 3GPP2 C.S0015-B, v2, 3.4.3.3)
     */
    static public final int NUMBER_MODE_NOT_DATA_NETWORK      = 0x00;
    static public final int NUMBER_MODE_DATA_NETWORK          = 0x01;

    /**
     *  number types for data networks
     *  (See 3GPP2 C.S0015-B, v2, 3.4.3.3)
     */
    static public final int TON_UNKNOWN                   = 0x00;
    static public final int TON_INTERNATIONAL_OR_IP       = 0x01;
    static public final int TON_NATIONAL_OR_EMAIL         = 0x02;
    static public final int TON_NETWORK                   = 0x03;
    static public final int TON_SUBSCRIBER                = 0x04;
    static public final int TON_ALPHANUMERIC              = 0x05;
    static public final int TON_ABBREVIATED               = 0x06;
    static public final int TON_RESERVED                  = 0x07;

    /**
     *  maximum lengths for fields as defined in ril_cdma_sms.h
     */
    static public final int SMS_ADDRESS_MAX          =  36;
    static public final int SMS_SUBADDRESS_MAX       =  36;

    /**
     *  Supported numbering plan identification
     *  (See C.S005-D, v1.0, table 2.7.1.3.2.4-3)
     */
    static public final int NUMBERING_PLAN_UNKNOWN           = 0x0;
    static public final int NUMBERING_PLAN_ISDN_TELEPHONY    = 0x1;
    //static protected final int NUMBERING_PLAN_DATA              = 0x3;
    //static protected final int NUMBERING_PLAN_TELEX             = 0x4;
    //static protected final int NUMBERING_PLAN_PRIVATE           = 0x9;

    /**
     * 1-bit value that indicates whether the address digits are 4-bit DTMF codes
     * or 8-bit codes.
     * (See 3GPP2 C.S0015-B, v2, 3.4.3.3)
     */
    public byte digitMode;

    /**
     * 1-bit value that indicates whether the address type is a data network address or not.
     * (See 3GPP2 C.S0015-B, v2, 3.4.3.3)
     */
    public byte numberMode;

    // use parent class member ton instead public byte numberType;

    /**
     * 0 or 4-bit value that indicates which numbering plan identification is set.
     * (See 3GPP2, C.S0015-B, v2, 3.4.3.3 and C.S005-D, table2.7.1.3.2.4-3)
     */
    public byte numberPlan;

    /**
     * This field shall be set to the number of address digits
     * (See 3GPP2 C.S0015-B, v2, 3.4.3.3)
     */
    public byte numberOfDigits;

    // use parent class member orig_bytes instead of public byte[] digits;

    // Constructor
    public CdmaSmsAddress(){
    }

}
