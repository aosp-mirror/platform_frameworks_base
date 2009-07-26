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

package com.android.internal.telephony;

import java.util.ArrayList;

/**
 * See also RIL_CardStatus in include/telephony/ril.h
 *
 * {@hide}
 */
public class IccCardStatus {
    static final int CARD_MAX_APPS = 8;

    public enum CardState {
        CARDSTATE_ABSENT,
        CARDSTATE_PRESENT,
        CARDSTATE_ERROR;

        boolean isCardPresent() {
            return this == CARDSTATE_PRESENT;
        }
    };

    public enum PinState {
        PINSTATE_UNKNOWN,
        PINSTATE_ENABLED_NOT_VERIFIED,
        PINSTATE_ENABLED_VERIFIED,
        PINSTATE_DISABLED,
        PINSTATE_ENABLED_BLOCKED,
        PINSTATE_ENABLED_PERM_BLOCKED
    };

    public CardState  card_state;
    public PinState   universal_pin_state;
    public int        gsm_umts_subscription_app_index;
    public int        cdma_subscription_app_index;
    public int        num_applications;

    ArrayList<IccCardApplication> application = new ArrayList<IccCardApplication>(CARD_MAX_APPS);

    CardState CardStateFromRILInt(int state) {
        CardState newState;
        /* RIL_CardState ril.h */
        switch(state) {
            case 0: newState = CardState.CARDSTATE_ABSENT; break;
            case 1: newState = CardState.CARDSTATE_PRESENT; break;
            case 2: newState = CardState.CARDSTATE_ERROR; break;
            default:
                throw new RuntimeException(
                            "Unrecognized RIL_CardState: " +state);
        }
        return newState;
    }

    PinState PinStateFromRILInt(int state) {
        PinState newState;
        /* RIL_PinState ril.h */
        switch(state) {
            case 0: newState = PinState.PINSTATE_UNKNOWN; break;
            case 1: newState = PinState.PINSTATE_ENABLED_NOT_VERIFIED; break;
            case 2: newState = PinState.PINSTATE_ENABLED_VERIFIED; break;
            case 3: newState = PinState.PINSTATE_DISABLED; break;
            case 4: newState = PinState.PINSTATE_ENABLED_BLOCKED; break;
            case 5: newState = PinState.PINSTATE_ENABLED_PERM_BLOCKED; break;
            default:
                throw new RuntimeException(
                            "Unrecognized RIL_PinState: " +state);
        }
        return newState;
    }
}
