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

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;


/**
 * Contains power consumption data attributed to a system-wide drain type.
 *
 * {@hide}
 */
public class SystemBatteryConsumer extends BatteryConsumer implements Parcelable {

    //                           ****************
    // This list must be kept current with atoms.proto (frameworks/base/cmds/statsd/src/atoms.proto)
    // so the constant values must never change.
    //                           ****************
    @IntDef(prefix = {"DRAIN_TYPE_"}, value = {
            DRAIN_TYPE_AMBIENT_DISPLAY,
            // Reserved: APP
            DRAIN_TYPE_BLUETOOTH,
            DRAIN_TYPE_CAMERA,
            DRAIN_TYPE_CELL,
            DRAIN_TYPE_FLASHLIGHT,
            DRAIN_TYPE_IDLE,
            DRAIN_TYPE_MEMORY,
            DRAIN_TYPE_OVERCOUNTED,
            DRAIN_TYPE_PHONE,
            DRAIN_TYPE_SCREEN,
            DRAIN_TYPE_UNACCOUNTED,
            // Reserved: USER,
            DRAIN_TYPE_WIFI,
    })
    @Retention(RetentionPolicy.SOURCE)
    public static @interface DrainType {
    }

    public static final int DRAIN_TYPE_AMBIENT_DISPLAY = 0;
    public static final int DRAIN_TYPE_BLUETOOTH = 2;
    public static final int DRAIN_TYPE_CAMERA = 3;
    public static final int DRAIN_TYPE_CELL = 4;
    public static final int DRAIN_TYPE_FLASHLIGHT = 5;
    public static final int DRAIN_TYPE_IDLE = 6;
    public static final int DRAIN_TYPE_MEMORY = 7;
    public static final int DRAIN_TYPE_OVERCOUNTED = 8;
    public static final int DRAIN_TYPE_PHONE = 9;
    public static final int DRAIN_TYPE_SCREEN = 10;
    public static final int DRAIN_TYPE_UNACCOUNTED = 11;
    public static final int DRAIN_TYPE_WIFI = 13;

    @DrainType
    private final int mDrainType;

    @DrainType
    public int getDrainType() {
        return mDrainType;
    }

    private SystemBatteryConsumer(@NonNull SystemBatteryConsumer.Builder builder) {
        super(builder.mPowerComponentsBuilder.build());
        mDrainType = builder.mDrainType;
    }

    private SystemBatteryConsumer(Parcel in) {
        super(new PowerComponents(in));
        mDrainType = in.readInt();
    }

    /**
     * Writes the contents into a Parcel.
     */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(mDrainType);
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

        Builder(int customPowerComponentCount, int customTimeComponentCount,
                @DrainType int drainType) {
            super(customPowerComponentCount, customTimeComponentCount);
            mDrainType = drainType;
        }

        /**
         * Creates a read-only object out of the Builder values.
         */
        @NonNull
        public SystemBatteryConsumer build() {
            return new SystemBatteryConsumer(this);
        }
    }
}
