/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.twilight;

import java.text.DateFormat;
import java.util.Date;

/**
 * Describes whether it is day or night.
 * This object is immutable.
 */
public class TwilightState {
    private final boolean mIsNight;
    private final float mAmount;

    TwilightState(boolean isNight, float amount) {
        mIsNight = isNight;
        mAmount = amount;
    }

    /**
     * Returns true if it is currently night time.
     */
    public boolean isNight() {
        return mIsNight;
    }

    /**
     * For twilight affects that change gradually over time, this is the amount they
     * should currently be in effect.
     */
    public float getAmount() {
        return mAmount;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TwilightState && equals((TwilightState)o);
    }

    public boolean equals(TwilightState other) {
        return other != null
                && mIsNight == other.mIsNight
                && mAmount == other.mAmount;
    }

    @Override
    public int hashCode() {
        return 0; // don't care
    }

    @Override
    public String toString() {
        DateFormat f = DateFormat.getDateTimeInstance();
        return "{TwilightState: isNight=" + mIsNight
                + ", mAmount=" + mAmount
                + "}";
    }
}
