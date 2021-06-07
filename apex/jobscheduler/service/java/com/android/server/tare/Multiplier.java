/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.tare;

import android.annotation.IntDef;
import android.util.IndentingPrintWriter;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Base class of a multiplier that can affect end pricing.
 */
abstract class Multiplier {
    static final int STATE_MULTIPLIER_CHARGING = 0;
    static final int STATE_MULTIPLIER_DEVICE_IDLE = 1;
    static final int STATE_MULTIPLIER_POWER_SAVE_MODE = 2;
    static final int STATE_MULTIPLIER_PROCESS_STATE = 3;
    static final int NUM_STATE_MULTIPLIERS = STATE_MULTIPLIER_PROCESS_STATE + 1;

    @IntDef({
            STATE_MULTIPLIER_CHARGING,
            STATE_MULTIPLIER_DEVICE_IDLE,
            STATE_MULTIPLIER_POWER_SAVE_MODE,
            STATE_MULTIPLIER_PROCESS_STATE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StateMultiplier {
    }

    /**
     * Affects the "cost to produce" (perform) the action
     *
     * @see EconomicPolicy.Action#costToProduce
     */
    public final boolean affectsCtp;
    /**
     * Affects the final price of the action
     *
     * @see EconomicPolicy.Action#basePrice
     */
    public final boolean affectsPrice;

    Multiplier(boolean affectsCtp, boolean affectsPrice) {
        this.affectsCtp = affectsCtp;
        this.affectsPrice = affectsPrice;
    }

    /** Get the multiplier based on the current device state. */
    abstract double getCurrentMultiplier();

    void onSystemServicesReady() {
    }

    abstract void dump(IndentingPrintWriter pw);
}
