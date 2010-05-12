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
 * Call fail causes from TS 24.008 .
 * These are mostly the cause codes we need to distinguish for the UI.
 * See 22.001 Annex F.4 for mapping of cause codes to local tones.
 *
 * {@hide}
 *
 */
public interface CallFailCause {
    // Unassigned/Unobtainable number
    static final int UNOBTAINABLE_NUMBER = 1;

    static final int NORMAL_CLEARING     = 16;
    // Busy Tone
    static final int USER_BUSY           = 17;

    // No Tone
    static final int NUMBER_CHANGED      = 22;
    static final int STATUS_ENQUIRY      = 30;
    static final int NORMAL_UNSPECIFIED  = 31;

    // Congestion Tone
    static final int NO_CIRCUIT_AVAIL    = 34;
    static final int TEMPORARY_FAILURE   = 41;
    static final int SWITCHING_CONGESTION    = 42;
    static final int CHANNEL_NOT_AVAIL   = 44;
    static final int QOS_NOT_AVAIL       = 49;
    static final int BEARER_NOT_AVAIL    = 58;

    // others
    static final int ACM_LIMIT_EXCEEDED = 68;
    static final int CALL_BARRED        = 240;
    static final int FDN_BLOCKED        = 241;
    static final int ERROR_UNSPECIFIED = 0xffff;
}
