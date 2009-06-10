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

package com.android.internal.telephony.gsm;

import android.telephony.PhoneNumberUtils;

/**
 * Represents a Supplementary Service Notification received from the network.
 *
 * {@hide}
 */
public class SuppServiceNotification {
    /** Type of notification: 0 = MO; 1 = MT */
    public int notificationType;
    /** TS 27.007 7.17 "code1" or "code2" */
    public int code;
    /** TS 27.007 7.17 "index" */
    public int index;
    /** TS 27.007 7.17 "type" (MT only) */
    public int type;
    /** TS 27.007 7.17 "number" (MT only) */
    public String number;

    static public final int MO_CODE_UNCONDITIONAL_CF_ACTIVE     = 0;
    static public final int MO_CODE_SOME_CF_ACTIVE              = 1;
    static public final int MO_CODE_CALL_FORWARDED              = 2;
    static public final int MO_CODE_CALL_IS_WAITING             = 3;
    static public final int MO_CODE_CUG_CALL                    = 4;
    static public final int MO_CODE_OUTGOING_CALLS_BARRED       = 5;
    static public final int MO_CODE_INCOMING_CALLS_BARRED       = 6;
    static public final int MO_CODE_CLIR_SUPPRESSION_REJECTED   = 7;
    static public final int MO_CODE_CALL_DEFLECTED              = 8;

    static public final int MT_CODE_FORWARDED_CALL              = 0;
    static public final int MT_CODE_CUG_CALL                    = 1;
    static public final int MT_CODE_CALL_ON_HOLD                = 2;
    static public final int MT_CODE_CALL_RETRIEVED              = 3;
    static public final int MT_CODE_MULTI_PARTY_CALL            = 4;
    static public final int MT_CODE_ON_HOLD_CALL_RELEASED       = 5;
    static public final int MT_CODE_FORWARD_CHECK_RECEIVED      = 6;
    static public final int MT_CODE_CALL_CONNECTING_ECT         = 7;
    static public final int MT_CODE_CALL_CONNECTED_ECT          = 8;
    static public final int MT_CODE_DEFLECTED_CALL              = 9;
    static public final int MT_CODE_ADDITIONAL_CALL_FORWARDED   = 10;

    public String toString()
    {
        return super.toString() + " mobile"
            + (notificationType == 0 ? " originated " : " terminated ")
            + " code: " + code
            + " index: " + index
            + " \""
            + PhoneNumberUtils.stringFromStringAndTOA(number, type) + "\" ";
    }

}
