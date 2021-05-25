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

package android.os;

import android.annotation.NonNull;

import java.io.PrintWriter;

/**
 * Contains power consumption data across the entire device.
 *
 * {@hide}
 */
public final class AggregateBatteryConsumer extends BatteryConsumer implements Parcelable {

    private final double mConsumedPowerMah;

    public AggregateBatteryConsumer(@NonNull Builder builder) {
        super(builder.mPowerComponentsBuilder.build());
        mConsumedPowerMah = builder.mConsumedPowerMah;
    }

    private AggregateBatteryConsumer(@NonNull Parcel source) {
        super(new PowerComponents(source));
        mConsumedPowerMah = source.readDouble();
    }

    @Override
    public void dump(PrintWriter pw, boolean skipEmptyComponents) {
        mPowerComponents.dump(pw, skipEmptyComponents);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeDouble(mConsumedPowerMah);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<AggregateBatteryConsumer> CREATOR =
            new Creator<AggregateBatteryConsumer>() {
                public AggregateBatteryConsumer createFromParcel(@NonNull Parcel source) {
                    return new AggregateBatteryConsumer(source);
                }

                public AggregateBatteryConsumer[] newArray(int size) {
                    return new AggregateBatteryConsumer[size];
                }
            };

    @Override
    public double getConsumedPower() {
        return mConsumedPowerMah;
    }

    /**
     * Builder for DeviceBatteryConsumer.
     */
    public static final class Builder extends BaseBuilder<AggregateBatteryConsumer.Builder> {
        private double mConsumedPowerMah;

        public Builder(@NonNull String[] customPowerComponentNames, boolean includePowerModels) {
            super(customPowerComponentNames, includePowerModels);
        }

        /**
         * Sets the total power included in this aggregate.
         */
        public Builder setConsumedPower(double consumedPowerMah) {
            mConsumedPowerMah = consumedPowerMah;
            return this;
        }

        /**
         * Adds power and usage duration from the supplied AggregateBatteryConsumer.
         */
        public void add(AggregateBatteryConsumer aggregateBatteryConsumer) {
            mConsumedPowerMah += aggregateBatteryConsumer.mConsumedPowerMah;
            mPowerComponentsBuilder.addPowerAndDuration(aggregateBatteryConsumer.mPowerComponents);
        }

        /**
         * Creates a read-only object out of the Builder values.
         */
        @NonNull
        public AggregateBatteryConsumer build() {
            return new AggregateBatteryConsumer(this);
        }
    }
}
