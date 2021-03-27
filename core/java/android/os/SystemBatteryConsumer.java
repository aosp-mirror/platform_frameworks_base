/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.util.Slog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;


/**
 * Contains power consumption data attributed to a system-wide drain type.
 *
 * {@hide}
 */
public class SystemBatteryConsumer extends BatteryConsumer implements Parcelable {
    private static final String TAG = "SystemBatteryConsumer";

    //                           ****************
    // This list must be kept current with atoms.proto (frameworks/base/cmds/statsd/src/atoms.proto)
    // so the constant values must never change.
    //                           ****************
    @IntDef(prefix = {"DRAIN_TYPE_"}, value = {
            DRAIN_TYPE_AMBIENT_DISPLAY,
            // Reserved: APP
            DRAIN_TYPE_BLUETOOTH,
            DRAIN_TYPE_CAMERA,
            DRAIN_TYPE_MOBILE_RADIO,
            DRAIN_TYPE_FLASHLIGHT,
            DRAIN_TYPE_IDLE,
            DRAIN_TYPE_MEMORY,
            // Reserved: OVERCOUNTED,
            DRAIN_TYPE_PHONE,
            DRAIN_TYPE_SCREEN,
            // Reserved: UNACCOUNTED,
            // Reserved: USER,
            DRAIN_TYPE_WIFI,
            DRAIN_TYPE_CUSTOM,
    })
    @Retention(RetentionPolicy.SOURCE)
    public static @interface DrainType {
    }

    public static final int DRAIN_TYPE_AMBIENT_DISPLAY = 0;
    public static final int DRAIN_TYPE_BLUETOOTH = 2;
    public static final int DRAIN_TYPE_CAMERA = 3;
    public static final int DRAIN_TYPE_MOBILE_RADIO = 4;
    public static final int DRAIN_TYPE_FLASHLIGHT = 5;
    public static final int DRAIN_TYPE_IDLE = 6;
    public static final int DRAIN_TYPE_MEMORY = 7;
    public static final int DRAIN_TYPE_PHONE = 9;
    public static final int DRAIN_TYPE_SCREEN = 10;
    public static final int DRAIN_TYPE_WIFI = 13;
    public static final int DRAIN_TYPE_CUSTOM = 14;

    @DrainType
    private final int mDrainType;

    private final double mPowerConsumedByAppsMah;

    @DrainType
    public int getDrainType() {
        return mDrainType;
    }

    private SystemBatteryConsumer(@NonNull SystemBatteryConsumer.Builder builder) {
        super(builder.mPowerComponentsBuilder.build());
        mDrainType = builder.mDrainType;
        mPowerConsumedByAppsMah = builder.mPowerConsumedByAppsMah;
        if (mPowerConsumedByAppsMah > getConsumedPower()) {
            Slog.wtf(TAG,
                    "Power attributed to apps exceeds total: drain type = " + mDrainType
                            + " total consumed power = " + getConsumedPower()
                            + " power consumed by apps = " + mPowerConsumedByAppsMah);
        }
    }

    private SystemBatteryConsumer(Parcel in) {
        super(new PowerComponents(in));
        mDrainType = in.readInt();
        mPowerConsumedByAppsMah = in.readDouble();
    }

    public double getPowerConsumedByApps() {
        return mPowerConsumedByAppsMah;
    }

    /**
     * Writes the contents into a Parcel.
     */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(mDrainType);
        dest.writeDouble(mPowerConsumedByAppsMah);
    }

    public static final Creator<SystemBatteryConsumer> CREATOR =
            new Creator<SystemBatteryConsumer>() {
                @Override
                public SystemBatteryConsumer createFromParcel(Parcel in) {
                    return new SystemBatteryConsumer(in);
                }

                @Override
                public SystemBatteryConsumer[] newArray(int size) {
                    return new SystemBatteryConsumer[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Builder for SystemBatteryConsumer.
     */
    public static final class Builder extends BaseBuilder<Builder> {
        @DrainType
        private final int mDrainType;
        private double mPowerConsumedByAppsMah;
        private List<UidBatteryConsumer.Builder> mUidBatteryConsumers;

        Builder(int customPowerComponentCount, int customTimeComponentCount,
                @DrainType int drainType) {
            super(customPowerComponentCount, customTimeComponentCount);
            mDrainType = drainType;
        }

        /**
         * Sets the amount of power used by this system component that is attributed to apps.
         * It should not exceed the total consumed power.
         */
        public Builder setPowerConsumedByApps(double powerConsumedByAppsMah) {
            mPowerConsumedByAppsMah = powerConsumedByAppsMah;
            return this;
        }

        /**
         * Add a UidBatteryConsumer to this SystemBatteryConsumer. For example,
         * the UidBatteryConsumer with the UID == {@link Process#BLUETOOTH_UID} should
         * be added to the SystemBatteryConsumer with the drain type == {@link
         * #DRAIN_TYPE_BLUETOOTH}.
         * <p>
         * Calculated power and duration components of the added battery consumers
         * are aggregated at the time the SystemBatteryConsumer is built by the {@link #build()}
         * method.
         * </p>
         */
        public void addUidBatteryConsumer(UidBatteryConsumer.Builder uidBatteryConsumerBuilder) {
            if (mUidBatteryConsumers == null) {
                mUidBatteryConsumers = new ArrayList<>();
            }
            mUidBatteryConsumers.add(uidBatteryConsumerBuilder);
        }

        /**
         * Creates a read-only object out of the Builder values.
         */
        @NonNull
        public SystemBatteryConsumer build() {
            if (mUidBatteryConsumers != null) {
                for (int i = mUidBatteryConsumers.size() - 1; i >= 0; i--) {
                    UidBatteryConsumer.Builder uidBatteryConsumer = mUidBatteryConsumers.get(i);
                    mPowerComponentsBuilder.addPowerAndDuration(
                            uidBatteryConsumer.mPowerComponentsBuilder);
                }
            }
            return new SystemBatteryConsumer(this);
        }
    }
}
