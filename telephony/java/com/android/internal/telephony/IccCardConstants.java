/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.compat.annotation.UnsupportedAppUsage;
import android.content.Intent;
import android.telephony.TelephonyManager;

/**
 * {@hide}
 */
public class IccCardConstants {

    /* The extra data for broadcasting intent INTENT_ICC_STATE_CHANGE */
    public static final String INTENT_KEY_ICC_STATE = Intent.EXTRA_SIM_STATE;
    /* UNKNOWN means the ICC state is unknown */
    public static final String INTENT_VALUE_ICC_UNKNOWN = Intent.SIM_STATE_UNKNOWN;
    /* NOT_READY means the ICC interface is not ready (eg, radio is off or powering on) */
    public static final String INTENT_VALUE_ICC_NOT_READY = Intent.SIM_STATE_NOT_READY;
    /* ABSENT means ICC is missing */
    public static final String INTENT_VALUE_ICC_ABSENT = Intent.SIM_STATE_ABSENT;
    /* PRESENT means ICC is present */
    public static final String INTENT_VALUE_ICC_PRESENT = Intent.SIM_STATE_PRESENT;
    /* CARD_IO_ERROR means for three consecutive times there was SIM IO error */
    static public final String INTENT_VALUE_ICC_CARD_IO_ERROR = Intent.SIM_STATE_CARD_IO_ERROR;
    /* CARD_RESTRICTED means card is present but not usable due to carrier restrictions */
    static public final String INTENT_VALUE_ICC_CARD_RESTRICTED = Intent.SIM_STATE_CARD_RESTRICTED;
    /* LOCKED means ICC is locked by pin or by network */
    public static final String INTENT_VALUE_ICC_LOCKED = Intent.SIM_STATE_LOCKED;
    /* READY means ICC is ready to access */
    public static final String INTENT_VALUE_ICC_READY = Intent.SIM_STATE_READY;
    /* IMSI means ICC IMSI is ready in property */
    public static final String INTENT_VALUE_ICC_IMSI = Intent.SIM_STATE_IMSI;
    /* LOADED means all ICC records, including IMSI, are loaded */
    public static final String INTENT_VALUE_ICC_LOADED = Intent.SIM_STATE_LOADED;
    /* The extra data for broadcasting intent INTENT_ICC_STATE_CHANGE */
    public static final String INTENT_KEY_LOCKED_REASON = Intent.EXTRA_SIM_LOCKED_REASON;
    /* PIN means ICC is locked on PIN1 */
    public static final String INTENT_VALUE_LOCKED_ON_PIN = Intent.SIM_LOCKED_ON_PIN;
    /* PUK means ICC is locked on PUK1 */
    public static final String INTENT_VALUE_LOCKED_ON_PUK = Intent.SIM_LOCKED_ON_PUK;
    /* NETWORK means ICC is locked on NETWORK PERSONALIZATION */
    public static final String INTENT_VALUE_LOCKED_NETWORK = Intent.SIM_LOCKED_NETWORK;
    /* PERM_DISABLED means ICC is permanently disabled due to puk fails */
    public static final String INTENT_VALUE_ABSENT_ON_PERM_DISABLED =
            Intent.SIM_ABSENT_ON_PERM_DISABLED;

    /**
     * This is combination of IccCardStatus.CardState and IccCardApplicationStatus.AppState
     * for external apps (like PhoneApp) to use
     *
     * UNKNOWN is a transient state, for example, after user inputs ICC pin under
     * PIN_REQUIRED state, the query for ICC status returns UNKNOWN before it
     * turns to READY
     *
     * The ordinal values much match {@link TelephonyManager#SIM_STATE_UNKNOWN} ...
     */
    @UnsupportedAppUsage(implicitMember =
            "values()[Lcom/android/internal/telephony/IccCardConstants$State;")
    public enum State {
        @UnsupportedAppUsage
        UNKNOWN,        /** ordinal(0) == {@See TelephonyManager#SIM_STATE_UNKNOWN} */
        @UnsupportedAppUsage
        ABSENT,         /** ordinal(1) == {@See TelephonyManager#SIM_STATE_ABSENT} */
        @UnsupportedAppUsage
        PIN_REQUIRED,   /** ordinal(2) == {@See TelephonyManager#SIM_STATE_PIN_REQUIRED} */
        @UnsupportedAppUsage
        PUK_REQUIRED,   /** ordinal(3) == {@See TelephonyManager#SIM_STATE_PUK_REQUIRED} */
        @UnsupportedAppUsage
        NETWORK_LOCKED, /** ordinal(4) == {@See TelephonyManager#SIM_STATE_NETWORK_LOCKED} */
        @UnsupportedAppUsage
        READY,          /** ordinal(5) == {@See TelephonyManager#SIM_STATE_READY} */
        @UnsupportedAppUsage
        NOT_READY,      /** ordinal(6) == {@See TelephonyManager#SIM_STATE_NOT_READY} */
        @UnsupportedAppUsage
        PERM_DISABLED,  /** ordinal(7) == {@See TelephonyManager#SIM_STATE_PERM_DISABLED} */
        @UnsupportedAppUsage
        CARD_IO_ERROR,  /** ordinal(8) == {@See TelephonyManager#SIM_STATE_CARD_IO_ERROR} */
        CARD_RESTRICTED,/** ordinal(9) == {@See TelephonyManager#SIM_STATE_CARD_RESTRICTED} */
        LOADED;         /** ordinal(9) == {@See TelephonyManager#SIM_STATE_LOADED} */

        public boolean isPinLocked() {
            return ((this == PIN_REQUIRED) || (this == PUK_REQUIRED));
        }

        public boolean iccCardExist() {
            return ((this == PIN_REQUIRED) || (this == PUK_REQUIRED)
                    || (this == NETWORK_LOCKED) || (this == READY) || (this == NOT_READY)
                    || (this == PERM_DISABLED) || (this == CARD_IO_ERROR)
                    || (this == CARD_RESTRICTED) || (this == LOADED));
        }

        public static State intToState(int state) throws IllegalArgumentException {
            switch(state) {
                case 0: return UNKNOWN;
                case 1: return ABSENT;
                case 2: return PIN_REQUIRED;
                case 3: return PUK_REQUIRED;
                case 4: return NETWORK_LOCKED;
                case 5: return READY;
                case 6: return NOT_READY;
                case 7: return PERM_DISABLED;
                case 8: return CARD_IO_ERROR;
                case 9: return CARD_RESTRICTED;
                case 10: return LOADED;
                default:
                    throw new IllegalArgumentException();
            }
        }
    }
}
