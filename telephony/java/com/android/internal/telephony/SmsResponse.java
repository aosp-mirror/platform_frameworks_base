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

/**
 * Object returned by the RIL upon successful completion of sendSMS.
 * Contains message reference and ackPdu.
 *
 */
public class SmsResponse {
    /** Message reference of the just-sent SMS. */
    int messageRef;
    /** ackPdu for the just-sent SMS. */
    String ackPdu;
    /**
     * errorCode: See 3GPP 27.005, 3.2.5 for GSM/UMTS,
     * 3GPP2 N.S0005 (IS-41C) Table 171 for CDMA, -1 if unknown or not applicable.
     */
    int errorCode;

    public SmsResponse(int messageRef, String ackPdu, int errorCode) {
        this.messageRef = messageRef;
        this.ackPdu = ackPdu;
        this.errorCode = errorCode;
    }

    public String toString() {
        String ret = "{ messageRef = " + messageRef
                        + ", errorCode = " + errorCode
                        + ", ackPdu = " + ackPdu
                        + "}";
        return ret;
    }
}
