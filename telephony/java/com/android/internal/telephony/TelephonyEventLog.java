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

package com.android.internal.telephony;

/* This class contains the details related to Telephony Event Logging */
public final class TelephonyEventLog {

    /* Event log tags */
    public static final int EVENT_LOG_BAD_DNS_ADDRESS = 50100;
    public static final int EVENT_LOG_RADIO_RESET_COUNTDOWN_TRIGGERED = 50101;
    public static final int EVENT_LOG_RADIO_RESET = 50102;
    public static final int EVENT_LOG_PDP_RESET = 50103;
    public static final int EVENT_LOG_REREGISTER_NETWORK = 50104;
    public static final int EVENT_LOG_RADIO_PDP_SETUP_FAIL = 50105;
    public static final int EVENT_LOG_CALL_DROP = 50106;
    public static final int EVENT_LOG_CGREG_FAIL = 50107;
    public static final int EVENT_LOG_DATA_STATE_RADIO_OFF = 50108;
    public static final int EVENT_LOG_PDP_NETWORK_DROP = 50109;
    public static final int EVENT_LOG_CDMA_DATA_SETUP_FAILED = 50110;
    public static final int EVENT_LOG_CDMA_DATA_DROP = 50111;
}
