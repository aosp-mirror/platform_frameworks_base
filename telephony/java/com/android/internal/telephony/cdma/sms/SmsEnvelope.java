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

import android.compat.annotation.UnsupportedAppUsage;
import android.telephony.cdma.CdmaSmsCbProgramData;

public final class SmsEnvelope {
    /**
     * Message Types
     * (See 3GPP2 C.S0015-B 3.4.1)
     */
    static public final int MESSAGE_TYPE_POINT_TO_POINT   = 0x00;
    static public final int MESSAGE_TYPE_BROADCAST        = 0x01;
    static public final int MESSAGE_TYPE_ACKNOWLEDGE      = 0x02;

    /**
     * Supported Teleservices
     * (See 3GPP2 N.S0005 and TIA-41)
     */
    static public final int TELESERVICE_NOT_SET           = 0x0000;
    static public final int TELESERVICE_WMT               = 0x1002;
    static public final int TELESERVICE_VMN               = 0x1003;
    static public final int TELESERVICE_WAP               = 0x1004;
    static public final int TELESERVICE_WEMT              = 0x1005;
    static public final int TELESERVICE_SCPT              = 0x1006;

    /** Carriers specific Teleservice IDs. */
    public static final int TELESERVICE_FDEA_WAP = 0xFDEA; // 65002

    /**
     * The following are defined as extensions to the standard teleservices
     */
    // Voice mail notification through Message Waiting Indication in CDMA mode or Analog mode.
    // Defined in 3GPP2 C.S-0005, 3.7.5.6, an Info Record containing an 8-bit number with the
    // number of messages waiting, it's used by some CDMA carriers for a voice mail count.
    static public final int TELESERVICE_MWI               = 0x40000;

    // Service Categories for Cell Broadcast, see 3GPP2 C.R1001 table 9.3.1-1
    // static final int SERVICE_CATEGORY_EMERGENCY      = 0x0001;
    //...

    // CMAS alert service category assignments, see 3GPP2 C.R1001 table 9.3.3-1
    public static final int SERVICE_CATEGORY_CMAS_PRESIDENTIAL_LEVEL_ALERT  =
            CdmaSmsCbProgramData.CATEGORY_CMAS_PRESIDENTIAL_LEVEL_ALERT;  // = 4096
    public static final int SERVICE_CATEGORY_CMAS_EXTREME_THREAT            =
            CdmaSmsCbProgramData.CATEGORY_CMAS_EXTREME_THREAT;            // = 4097
    public static final int SERVICE_CATEGORY_CMAS_SEVERE_THREAT             =
            CdmaSmsCbProgramData.CATEGORY_CMAS_SEVERE_THREAT;             // = 4098
    public static final int SERVICE_CATEGORY_CMAS_CHILD_ABDUCTION_EMERGENCY =
            CdmaSmsCbProgramData.CATEGORY_CMAS_CHILD_ABDUCTION_EMERGENCY; // = 4099
    public static final int SERVICE_CATEGORY_CMAS_TEST_MESSAGE              =
            CdmaSmsCbProgramData.CATEGORY_CMAS_TEST_MESSAGE;              // = 4100
    public static final int SERVICE_CATEGORY_CMAS_LAST_RESERVED_VALUE       =
            CdmaSmsCbProgramData.CATEGORY_CMAS_LAST_RESERVED_VALUE;       // = 4351

    /**
     * Provides the type of a SMS message like point to point, broadcast or acknowledge
     */
    public int messageType;

    /**
     * The 16-bit Teleservice parameter identifies which upper layer service access point is sending
     * or receiving the message.
     * (See 3GPP2 C.S0015-B, v2, 3.4.3.1)
     */
    @UnsupportedAppUsage
    public int teleService = TELESERVICE_NOT_SET;

    /**
     * The 16-bit service category parameter identifies the type of service provided
     * by the SMS message.
     * (See 3GPP2 C.S0015-B, v2, 3.4.3.2)
     */
    @UnsupportedAppUsage
    public int serviceCategory;

    /**
     * The origination address identifies the originator of the SMS message.
     * (See 3GPP2 C.S0015-B, v2, 3.4.3.3)
     */
    public CdmaSmsAddress origAddress;

    /**
     * The destination address identifies the target of the SMS message.
     * (See 3GPP2 C.S0015-B, v2, 3.4.3.3)
     */
    public CdmaSmsAddress destAddress;

    /**
     * The origination subaddress identifies the originator of the SMS message.
     * (See 3GPP2 C.S0015-B, v2, 3.4.3.4)
     */
    public CdmaSmsSubaddress origSubaddress;

    /**
     * The destination subaddress identifies the target of the SMS message.
     * (See 3GPP2 C.S0015-B, v2, 3.4.3.4)
     */
    public CdmaSmsSubaddress destSubaddress;

    /**
     * The 6-bit bearer reply parameter is used to request the return of a
     * SMS Acknowledge Message.
     * (See 3GPP2 C.S0015-B, v2, 3.4.3.5)
     */
    public int bearerReply;

    /**
     * Cause Code values:
     * The cause code parameters are an indication whether an SMS error has occurred and if so,
     * whether the condition is considered temporary or permanent.
     * ReplySeqNo 6-bit value,
     * ErrorClass 2-bit value,
     * CauseCode 0-bit or 8-bit value
     * (See 3GPP2 C.S0015-B, v2, 3.4.3.6)
     */
    public byte replySeqNo;
    public byte errorClass;
    public byte causeCode;

    /**
     * encoded bearer data
     * (See 3GPP2 C.S0015-B, v2, 3.4.3.7)
     */
    @UnsupportedAppUsage
    public byte[] bearerData;

    @UnsupportedAppUsage
    public SmsEnvelope() {
        // nothing to see here
    }

}

