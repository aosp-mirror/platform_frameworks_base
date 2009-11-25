/*
 * Copyright (C) 2009 The Android Open Source Project
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

import java.util.HashMap;
import java.util.HashSet;
import android.util.Log;
import android.media.ToneGenerator;

public class SignalToneUtil {
    /** A marker that isn't a valid TONE */
    public static final int CDMA_INVALID_TONE = -1;

    // public final int int IS95_CONST_IR_SIGNAL_TYPE_TYPE;
    static public final int IS95_CONST_IR_SIGNAL_TONE = 0;
    static public final int IS95_CONST_IR_SIGNAL_ISDN = 1;
    static public final int IS95_CONST_IR_SIGNAL_IS54B = 2;
    static public final int IS95_CONST_IR_SIGNAL_USR_DEFD_ALERT = 4;

    // public final int int IS95_CONST_IR_ALERT_PITCH_TYPE;
    static public final int IS95_CONST_IR_ALERT_MED = 0;
    static public final int IS95_CONST_IR_ALERT_HIGH = 1;
    static public final int IS95_CONST_IR_ALERT_LOW = 2;

    // Based on 3GPP2 C.S0005-E, seciton 3.7.5.5 Signal,
    // set TAPIAMSSCDMA_SIGNAL_PITCH_UNKNOWN to 0 to avoid
    // the alert pitch to be involved in hash calculation for
    // signal type other than IS54B.
    static public final int TAPIAMSSCDMA_SIGNAL_PITCH_UNKNOWN = 0;

    // public final int int IS95_CONST_IR_SIGNAL_TYPE;
    static public final int IS95_CONST_IR_SIG_ISDN_NORMAL = 0;
    static public final int IS95_CONST_IR_SIG_ISDN_INTGRP = 1;
    static public final int IS95_CONST_IR_SIG_ISDN_SP_PRI = 2;
    static public final int IS95_CONST_IR_SIG_ISDN_PAT_3 = 3;
    static public final int IS95_CONST_IR_SIG_ISDN_PING = 4;
    static public final int IS95_CONST_IR_SIG_ISDN_PAT_5 = 5;
    static public final int IS95_CONST_IR_SIG_ISDN_PAT_6 = 6;
    static public final int IS95_CONST_IR_SIG_ISDN_PAT_7 = 7;
    static public final int IS95_CONST_IR_SIG_ISDN_OFF = 15;
    static public final int IS95_CONST_IR_SIG_TONE_DIAL = 0;
    static public final int IS95_CONST_IR_SIG_TONE_RING = 1;
    static public final int IS95_CONST_IR_SIG_TONE_INT = 2;
    static public final int IS95_CONST_IR_SIG_TONE_ABB_INT = 3;
    static public final int IS95_CONST_IR_SIG_TONE_REORDER = 4;
    static public final int IS95_CONST_IR_SIG_TONE_ABB_RE = 5;
    static public final int IS95_CONST_IR_SIG_TONE_BUSY = 6;
    static public final int IS95_CONST_IR_SIG_TONE_CONFIRM = 7;
    static public final int IS95_CONST_IR_SIG_TONE_ANSWER = 8;
    static public final int IS95_CONST_IR_SIG_TONE_CALL_W = 9;
    static public final int IS95_CONST_IR_SIG_TONE_PIP = 10;
    static public final int IS95_CONST_IR_SIG_TONE_NO_TONE = 63;
    static public final int IS95_CONST_IR_SIG_IS54B_NO_TONE = 0;
    static public final int IS95_CONST_IR_SIG_IS54B_L = 1;
    static public final int IS95_CONST_IR_SIG_IS54B_SS = 2;
    static public final int IS95_CONST_IR_SIG_IS54B_SSL = 3;
    static public final int IS95_CONST_IR_SIG_IS54B_SS_2 = 4;
    static public final int IS95_CONST_IR_SIG_IS54B_SLS = 5;
    static public final int IS95_CONST_IR_SIG_IS54B_S_X4 = 6;
    static public final int IS95_CONST_IR_SIG_IS54B_PBX_L = 7;
    static public final int IS95_CONST_IR_SIG_IS54B_PBX_SS = 8;
    static public final int IS95_CONST_IR_SIG_IS54B_PBX_SSL = 9;
    static public final int IS95_CONST_IR_SIG_IS54B_PBX_SLS = 10;
    static public final int IS95_CONST_IR_SIG_IS54B_PBX_S_X4 = 11;
    static public final int IS95_CONST_IR_SIG_TONE_ABBR_ALRT = 0;

    // Hashmap to map signalInfo To AudioTone
    static private HashMap<Integer, Integer> hm = new HashMap<Integer, Integer>();

    private static Integer signalParamHash(int signalType, int alertPitch, int signal) {
        if ((signalType < 0) || (signalType > 256) || (alertPitch > 256) ||
                (alertPitch < 0) || (signal > 256) || (signal < 0)) {
            return new Integer(CDMA_INVALID_TONE);
        }
        // Based on 3GPP2 C.S0005-E, seciton 3.7.5.5 Signal,
        // the alert pitch field is ignored by the mobile station unless
        // SIGNAL_TYPE is '10',IS-54B Alerting.
        // Set alert pitch to TAPIAMSSCDMA_SIGNAL_PITCH_UNKNOWN
        // so the alert pitch is not involved in hash calculation
        // when signal type is not IS-54B.
        if (signalType != IS95_CONST_IR_SIGNAL_IS54B) {
            alertPitch = TAPIAMSSCDMA_SIGNAL_PITCH_UNKNOWN;
        }
        return new Integer(signalType * 256 * 256 + alertPitch * 256 + signal);
    }

    public static int getAudioToneFromSignalInfo(int signalType, int alertPitch, int signal) {
        Integer result = hm.get(signalParamHash(signalType, alertPitch, signal));
        if (result == null) {
            return CDMA_INVALID_TONE;
        }
        return result;
    }

    static {

        /* SIGNAL_TYPE_ISDN */
        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_ISDN, TAPIAMSSCDMA_SIGNAL_PITCH_UNKNOWN,
                IS95_CONST_IR_SIG_ISDN_NORMAL), ToneGenerator.TONE_CDMA_CALL_SIGNAL_ISDN_NORMAL);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_ISDN, TAPIAMSSCDMA_SIGNAL_PITCH_UNKNOWN,
                        IS95_CONST_IR_SIG_ISDN_INTGRP),
                        ToneGenerator.TONE_CDMA_CALL_SIGNAL_ISDN_INTERGROUP);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_ISDN, TAPIAMSSCDMA_SIGNAL_PITCH_UNKNOWN,
                IS95_CONST_IR_SIG_ISDN_SP_PRI), ToneGenerator.TONE_CDMA_CALL_SIGNAL_ISDN_SP_PRI);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_ISDN, TAPIAMSSCDMA_SIGNAL_PITCH_UNKNOWN,
                IS95_CONST_IR_SIG_ISDN_PAT_3), ToneGenerator.TONE_CDMA_CALL_SIGNAL_ISDN_PAT3);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_ISDN, TAPIAMSSCDMA_SIGNAL_PITCH_UNKNOWN,
                IS95_CONST_IR_SIG_ISDN_PING), ToneGenerator.TONE_CDMA_CALL_SIGNAL_ISDN_PING_RING);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_ISDN, TAPIAMSSCDMA_SIGNAL_PITCH_UNKNOWN,
                IS95_CONST_IR_SIG_ISDN_PAT_5), ToneGenerator.TONE_CDMA_CALL_SIGNAL_ISDN_PAT5);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_ISDN, TAPIAMSSCDMA_SIGNAL_PITCH_UNKNOWN,
                IS95_CONST_IR_SIG_ISDN_PAT_6), ToneGenerator.TONE_CDMA_CALL_SIGNAL_ISDN_PAT6);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_ISDN, TAPIAMSSCDMA_SIGNAL_PITCH_UNKNOWN,
                IS95_CONST_IR_SIG_ISDN_PAT_7), ToneGenerator.TONE_CDMA_CALL_SIGNAL_ISDN_PAT7);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_ISDN, TAPIAMSSCDMA_SIGNAL_PITCH_UNKNOWN,
                IS95_CONST_IR_SIG_ISDN_OFF), ToneGenerator.TONE_CDMA_SIGNAL_OFF);

        /* SIGNAL_TYPE_TONE */

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_TONE, TAPIAMSSCDMA_SIGNAL_PITCH_UNKNOWN,
                IS95_CONST_IR_SIG_TONE_DIAL), ToneGenerator.TONE_CDMA_DIAL_TONE_LITE);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_TONE, TAPIAMSSCDMA_SIGNAL_PITCH_UNKNOWN,
                IS95_CONST_IR_SIG_TONE_RING), ToneGenerator.TONE_CDMA_NETWORK_USA_RINGBACK);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_TONE, TAPIAMSSCDMA_SIGNAL_PITCH_UNKNOWN,
                IS95_CONST_IR_SIG_TONE_INT), ToneGenerator.TONE_SUP_INTERCEPT);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_TONE, TAPIAMSSCDMA_SIGNAL_PITCH_UNKNOWN,
                IS95_CONST_IR_SIG_TONE_ABB_INT), ToneGenerator.TONE_SUP_INTERCEPT_ABBREV);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_TONE, TAPIAMSSCDMA_SIGNAL_PITCH_UNKNOWN,
                IS95_CONST_IR_SIG_TONE_REORDER), ToneGenerator.TONE_CDMA_REORDER);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_TONE, TAPIAMSSCDMA_SIGNAL_PITCH_UNKNOWN,
                IS95_CONST_IR_SIG_TONE_ABB_RE), ToneGenerator.TONE_CDMA_ABBR_REORDER);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_TONE, TAPIAMSSCDMA_SIGNAL_PITCH_UNKNOWN,
                IS95_CONST_IR_SIG_TONE_BUSY), ToneGenerator.TONE_CDMA_NETWORK_BUSY);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_TONE, TAPIAMSSCDMA_SIGNAL_PITCH_UNKNOWN,
                IS95_CONST_IR_SIG_TONE_CONFIRM), ToneGenerator.TONE_SUP_CONFIRM);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_TONE, TAPIAMSSCDMA_SIGNAL_PITCH_UNKNOWN,
                IS95_CONST_IR_SIG_TONE_ANSWER), ToneGenerator.TONE_CDMA_ANSWER);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_TONE, TAPIAMSSCDMA_SIGNAL_PITCH_UNKNOWN,
                IS95_CONST_IR_SIG_TONE_CALL_W), ToneGenerator.TONE_CDMA_NETWORK_CALLWAITING);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_TONE, TAPIAMSSCDMA_SIGNAL_PITCH_UNKNOWN,
                IS95_CONST_IR_SIG_TONE_PIP), ToneGenerator.TONE_CDMA_PIP);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_TONE, TAPIAMSSCDMA_SIGNAL_PITCH_UNKNOWN,
                IS95_CONST_IR_SIG_TONE_NO_TONE), ToneGenerator.TONE_CDMA_SIGNAL_OFF);

        /* SIGNAL_TYPE_IS54B */
        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_IS54B, IS95_CONST_IR_ALERT_HIGH,
                IS95_CONST_IR_SIG_IS54B_L), ToneGenerator.TONE_CDMA_HIGH_L);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_IS54B, IS95_CONST_IR_ALERT_MED,
                IS95_CONST_IR_SIG_IS54B_L), ToneGenerator.TONE_CDMA_MED_L);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_IS54B, IS95_CONST_IR_ALERT_LOW,
                IS95_CONST_IR_SIG_IS54B_L), ToneGenerator.TONE_CDMA_LOW_L);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_IS54B, IS95_CONST_IR_ALERT_HIGH,
                IS95_CONST_IR_SIG_IS54B_SS), ToneGenerator.TONE_CDMA_HIGH_SS);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_IS54B, IS95_CONST_IR_ALERT_MED,
                IS95_CONST_IR_SIG_IS54B_SS), ToneGenerator.TONE_CDMA_MED_SS);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_IS54B, IS95_CONST_IR_ALERT_LOW,
                IS95_CONST_IR_SIG_IS54B_SS), ToneGenerator.TONE_CDMA_LOW_SS);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_IS54B, IS95_CONST_IR_ALERT_HIGH,
                IS95_CONST_IR_SIG_IS54B_SSL), ToneGenerator.TONE_CDMA_HIGH_SSL);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_IS54B, IS95_CONST_IR_ALERT_MED,
                IS95_CONST_IR_SIG_IS54B_SSL), ToneGenerator.TONE_CDMA_MED_SSL);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_IS54B, IS95_CONST_IR_ALERT_LOW,
                IS95_CONST_IR_SIG_IS54B_SSL), ToneGenerator.TONE_CDMA_LOW_SSL);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_IS54B, IS95_CONST_IR_ALERT_HIGH,
                IS95_CONST_IR_SIG_IS54B_SS_2), ToneGenerator.TONE_CDMA_HIGH_SS_2);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_IS54B, IS95_CONST_IR_ALERT_MED,
                IS95_CONST_IR_SIG_IS54B_SS_2), ToneGenerator.TONE_CDMA_MED_SS_2);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_IS54B, IS95_CONST_IR_ALERT_LOW,
                IS95_CONST_IR_SIG_IS54B_SS_2), ToneGenerator.TONE_CDMA_LOW_SS_2);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_IS54B, IS95_CONST_IR_ALERT_HIGH,
                IS95_CONST_IR_SIG_IS54B_SLS), ToneGenerator.TONE_CDMA_HIGH_SLS);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_IS54B, IS95_CONST_IR_ALERT_MED,
                IS95_CONST_IR_SIG_IS54B_SLS), ToneGenerator.TONE_CDMA_MED_SLS);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_IS54B, IS95_CONST_IR_ALERT_LOW,
                IS95_CONST_IR_SIG_IS54B_SLS), ToneGenerator.TONE_CDMA_LOW_SLS);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_IS54B, IS95_CONST_IR_ALERT_HIGH,
                IS95_CONST_IR_SIG_IS54B_S_X4), ToneGenerator.TONE_CDMA_HIGH_S_X4);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_IS54B, IS95_CONST_IR_ALERT_MED,
                IS95_CONST_IR_SIG_IS54B_S_X4), ToneGenerator.TONE_CDMA_MED_S_X4);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_IS54B, IS95_CONST_IR_ALERT_LOW,
                IS95_CONST_IR_SIG_IS54B_S_X4), ToneGenerator.TONE_CDMA_LOW_S_X4);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_IS54B, IS95_CONST_IR_ALERT_HIGH,
                IS95_CONST_IR_SIG_IS54B_PBX_L), ToneGenerator.TONE_CDMA_HIGH_PBX_L);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_IS54B, IS95_CONST_IR_ALERT_MED,
                IS95_CONST_IR_SIG_IS54B_PBX_L), ToneGenerator.TONE_CDMA_MED_PBX_L);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_IS54B, IS95_CONST_IR_ALERT_LOW,
                IS95_CONST_IR_SIG_IS54B_PBX_L), ToneGenerator.TONE_CDMA_LOW_PBX_L);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_IS54B, IS95_CONST_IR_ALERT_HIGH,
                IS95_CONST_IR_SIG_IS54B_PBX_SS), ToneGenerator.TONE_CDMA_HIGH_PBX_SS);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_IS54B, IS95_CONST_IR_ALERT_MED,
                IS95_CONST_IR_SIG_IS54B_PBX_SS), ToneGenerator.TONE_CDMA_MED_PBX_SS);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_IS54B, IS95_CONST_IR_ALERT_LOW,
                IS95_CONST_IR_SIG_IS54B_PBX_SS), ToneGenerator.TONE_CDMA_LOW_PBX_SS);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_IS54B, IS95_CONST_IR_ALERT_HIGH,
                IS95_CONST_IR_SIG_IS54B_PBX_SSL), ToneGenerator.TONE_CDMA_HIGH_PBX_SSL);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_IS54B, IS95_CONST_IR_ALERT_MED,
                IS95_CONST_IR_SIG_IS54B_PBX_SSL), ToneGenerator.TONE_CDMA_MED_PBX_SSL);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_IS54B, IS95_CONST_IR_ALERT_LOW,
                IS95_CONST_IR_SIG_IS54B_PBX_SSL), ToneGenerator.TONE_CDMA_LOW_PBX_SSL);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_IS54B, IS95_CONST_IR_ALERT_HIGH,
                IS95_CONST_IR_SIG_IS54B_PBX_SLS), ToneGenerator.TONE_CDMA_HIGH_PBX_SLS);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_IS54B, IS95_CONST_IR_ALERT_MED,
                IS95_CONST_IR_SIG_IS54B_PBX_SLS), ToneGenerator.TONE_CDMA_MED_PBX_SLS);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_IS54B, IS95_CONST_IR_ALERT_LOW,
                IS95_CONST_IR_SIG_IS54B_PBX_SLS), ToneGenerator.TONE_CDMA_LOW_PBX_SLS);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_IS54B, IS95_CONST_IR_ALERT_HIGH,
                IS95_CONST_IR_SIG_IS54B_PBX_S_X4), ToneGenerator.TONE_CDMA_HIGH_PBX_S_X4);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_IS54B, IS95_CONST_IR_ALERT_MED,
                IS95_CONST_IR_SIG_IS54B_PBX_S_X4), ToneGenerator.TONE_CDMA_MED_PBX_S_X4);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_IS54B, IS95_CONST_IR_ALERT_LOW,
                IS95_CONST_IR_SIG_IS54B_PBX_S_X4), ToneGenerator.TONE_CDMA_LOW_PBX_S_X4);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_IS54B, TAPIAMSSCDMA_SIGNAL_PITCH_UNKNOWN,
                IS95_CONST_IR_SIG_IS54B_NO_TONE), ToneGenerator.TONE_CDMA_SIGNAL_OFF);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_USR_DEFD_ALERT,
                TAPIAMSSCDMA_SIGNAL_PITCH_UNKNOWN, IS95_CONST_IR_SIG_TONE_ABBR_ALRT),
                ToneGenerator.TONE_CDMA_ABBR_ALERT);

        hm.put(signalParamHash(IS95_CONST_IR_SIGNAL_USR_DEFD_ALERT,
                TAPIAMSSCDMA_SIGNAL_PITCH_UNKNOWN, IS95_CONST_IR_SIG_TONE_NO_TONE),
                ToneGenerator.TONE_CDMA_ABBR_ALERT);

    }

    // suppress default constructor for noninstantiability
    private SignalToneUtil() {
    }
}
