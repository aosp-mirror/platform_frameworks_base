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
 * Base class of a modifier that can affect end pricing.
 */
abstract class Modifier {
    static final int COST_MODIFIER_CHARGING = 0;
    static final int COST_MODIFIER_DEVICE_IDLE = 1;
    static final int COST_MODIFIER_POWER_SAVE_MODE = 2;
    static final int COST_MODIFIER_PROCESS_STATE = 3;
    static final int NUM_COST_MODIFIERS = COST_MODIFIER_PROCESS_STATE + 1;

    @IntDef({
            COST_MODIFIER_CHARGING,
            COST_MODIFIER_DEVICE_IDLE,
            COST_MODIFIER_POWER_SAVE_MODE,
            COST_MODIFIER_PROCESS_STATE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CostModifier {
    }

    /**
     * Returns a modified cost to produce based on the modifier's state.
     *
     * @param ctp Current cost to produce
     */
    long getModifiedCostToProduce(long ctp) {
        return ctp;
    }

    /**
     * Returns a modified price based on the modifier's state.
     *
     * @param price Current price
     */
    long getModifiedPrice(long price) {
        return price;
    }

    void setup() {
    }

    void tearDown() {
    }

    abstract void dump(IndentingPrintWriter pw);
}
