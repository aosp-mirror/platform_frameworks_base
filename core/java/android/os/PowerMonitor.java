/*
 * Copyright (C) 2023 The Android Open Source Project
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
 * @hide
 */
public final class PowerMonitor implements Parcelable {

    /**
     * Power monitor corresponding to a subsystem. The energy value may be a direct pass-through
     * power rail measurement, or modeled in some fashion.  For example, an energy consumer may
     * represent a combination of multiple rails or a portion of a rail shared between subsystems,
     * e.g. WiFi and Bluetooth are often handled by the same chip, powered by a shared rail.
     * Some consumer names are standardized (see android.hardware.power.stats.EnergyConsumerType),
     * others are not.
     */
    public static final int POWER_MONITOR_TYPE_CONSUMER = 0;

    /**
     * Power monitor corresponding to a directly measured power rail. Rails are device-specific:
     * no assumptions can be made about the source of those measurements across different devices,
     * even if they have the same name.
     */
    public static final int POWER_MONITOR_TYPE_MEASUREMENT = 1;

    /** @hide */
    @IntDef(flag = true, prefix = {"POWER_MONITOR_TYPE_"}, value = {
            POWER_MONITOR_TYPE_CONSUMER,
            POWER_MONITOR_TYPE_MEASUREMENT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PowerMonitorType {
    }

    /**
     * These indices are not guaranteed to be stable across reboots and should not
     * be persisted.
     *
     * @hide
     */
    public final int index;
    @PowerMonitorType
    public final int type;
    @NonNull
    public final String name;

    /**
     * @hide
     */
    public PowerMonitor(int index, int type, @NonNull String name) {
        this.index = index;
        this.type = type;
        this.name = name;
    }

    private PowerMonitor(Parcel in) {
        index = in.readInt();
        type = in.readInt();
        name = in.readString();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(index);
        dest.writeInt(type);
        dest.writeString(name);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<PowerMonitor> CREATOR = new Creator<>() {
        @Override
        public PowerMonitor createFromParcel(@NonNull Parcel in) {
            return new PowerMonitor(in);
        }

        @Override
        public PowerMonitor[] newArray(int size) {
            return new PowerMonitor[size];
        }
    };
}
