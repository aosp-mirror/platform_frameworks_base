/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.bluetooth.le;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * The {@link PeriodicAdvertisingParameters} provide a way to adjust periodic
 * advertising preferences for each Bluetooth LE advertising set. Use {@link
 * AdvertisingSetParameters.Builder} to create an instance of this class.
 */
public final class PeriodicAdvertisingParameters implements Parcelable {

    private static final int INTERVAL_MIN = 80;
    private static final int INTERVAL_MAX = 65519;

    private final boolean mIncludeTxPower;
    private final int mInterval;

    private PeriodicAdvertisingParameters(boolean includeTxPower, int interval) {
        mIncludeTxPower = includeTxPower;
        mInterval = interval;
    }

    private PeriodicAdvertisingParameters(Parcel in) {
        mIncludeTxPower = in.readInt() != 0;
        mInterval = in.readInt();
    }

    /**
     * Returns whether the TX Power will be included.
     */
    public boolean getIncludeTxPower() {
        return mIncludeTxPower;
    }

    /**
     * Returns the periodic advertising interval, in 1.25ms unit.
     * Valid values are from 80 (100ms) to 65519 (81.89875s).
     */
    public int getInterval() {
        return mInterval;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mIncludeTxPower ? 1 : 0);
        dest.writeInt(mInterval);
    }

    public static final Parcelable
            .Creator<PeriodicAdvertisingParameters> CREATOR =
            new Creator<PeriodicAdvertisingParameters>() {
                @Override
                public PeriodicAdvertisingParameters[] newArray(int size) {
                    return new PeriodicAdvertisingParameters[size];
                }

                @Override
                public PeriodicAdvertisingParameters createFromParcel(Parcel in) {
                    return new PeriodicAdvertisingParameters(in);
                }
            };

    public static final class Builder {
        private boolean mIncludeTxPower = false;
        private int mInterval = INTERVAL_MAX;

        /**
         * Whether the transmission power level should be included in the periodic
         * packet.
         */
        public Builder setIncludeTxPower(boolean includeTxPower) {
            mIncludeTxPower = includeTxPower;
            return this;
        }

        /**
         * Set advertising interval for periodic advertising, in 1.25ms unit.
         * Valid values are from 80 (100ms) to 65519 (81.89875s).
         * Value from range [interval, interval+20ms] will be picked as the actual value.
         *
         * @throws IllegalArgumentException If the interval is invalid.
         */
        public Builder setInterval(int interval) {
            if (interval < INTERVAL_MIN || interval > INTERVAL_MAX) {
                throw new IllegalArgumentException("Invalid interval (must be " + INTERVAL_MIN
                        + "-" + INTERVAL_MAX + ")");
            }
            mInterval = interval;
            return this;
        }

        /**
         * Build the {@link AdvertisingSetParameters} object.
         */
        public PeriodicAdvertisingParameters build() {
            return new PeriodicAdvertisingParameters(mIncludeTxPower, mInterval);
        }
    }
}
