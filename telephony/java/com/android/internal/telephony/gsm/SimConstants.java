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

/**
 * {@hide}
 */
public interface SimConstants {
    // SIM file ids from TS 51.011
    public static final int EF_ADN = 0x6F3A;
    public static final int EF_FDN = 0x6F3B;
    public static final int EF_SDN = 0x6F49;
    public static final int EF_EXT1 = 0x6F4A;
    public static final int EF_EXT2 = 0x6F4B;
    public static final int EF_EXT3 = 0x6F4C;
    public static final int EF_EXT6 = 0x6fc8;   // Ext record for EF[MBDN]
    public static final int EF_MWIS = 0x6FCA;
    public static final int EF_MBDN = 0x6fc7;
    public static final int EF_PNN = 0x6fc5;
    public static final int EF_SPN = 0x6F46;
    public static final int EF_SMS = 0x6F3C;
    public static final int EF_ICCID = 0x2fe2;
    public static final int EF_AD = 0x6FAD;
    public static final int EF_MBI = 0x6fc9;
    public static final int EF_MSISDN = 0x6f40;
    public static final int EF_SPDI = 0x6fcd;
    public static final int EF_SST = 0x6f38;
    public static final int EF_CFIS = 0x6FCB;
    public static final int EF_IMG = 0x4f20;

    // SIM file ids from CPHS (phase 2, version 4.2) CPHS4_2.WW6
    public static final int EF_MAILBOX_CPHS = 0x6F17;
    public static final int EF_VOICE_MAIL_INDICATOR_CPHS = 0x6F11;
    public static final int EF_CFF_CPHS = 0x6F13;
    public static final int EF_SPN_CPHS = 0x6f14;
    public static final int EF_SPN_SHORT_CPHS = 0x6f18;
    public static final int EF_INFO_CPHS = 0x6f16;

    // SMS record length from TS 51.011 10.5.3
    static public final int SMS_RECORD_LENGTH = 176;
}
