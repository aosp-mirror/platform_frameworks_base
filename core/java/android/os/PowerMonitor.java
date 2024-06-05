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

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A PowerMonitor represents either an ODPM rail (on-device power rail monitor) or a modeled
 * energy consumer.
 * <p/>
 * ODPM rail names are device-specific. No assumptions should be made about the names and
 * exact purpose of ODPM rails across different device models. A rail name may be something
 * like "S2S_VDD_G3D"; specific knowledge of the device hardware is required to interpret
 * the corresponding power monitor data.
 * <p/>
 * Energy consumer have more human-readable names, e.g. "GPU", "MODEM" etc. However, developers
 * must be extra cautious about using energy consumers across different device models,
 * as their exact implementations are also hardware dependent and are customized by OEMs.
 */
@FlaggedApi("com.android.server.power.optimization.power_monitor_api")
public final class PowerMonitor implements Parcelable {

    /**
     * Power monitor corresponding to a subsystem. The energy value may be a direct pass-through
     * power rail measurement, or modeled in some fashion.  For example, an energy consumer may
     * represent a combination of multiple rails or a portion of a rail shared between subsystems,
     * e.g. WiFi and Bluetooth are often handled by the same chip, powered by a shared rail.
     * Some consumer names are standardized, others are not.
     */
    @FlaggedApi("com.android.server.power.optimization.power_monitor_api")
    public static final int POWER_MONITOR_TYPE_CONSUMER = 0;

    /**
     * Power monitor corresponding to a directly measured power rail. Rails are device-specific:
     * no assumptions can be made about the source of those measurements across different devices,
     * even if they have the same name.
     */
    @FlaggedApi("com.android.server.power.optimization.power_monitor_api")
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
    private final int mType;
    @NonNull
    private final String mName;

    /**
     * @hide
     */
    public PowerMonitor(int index, int type, @NonNull String name) {
        this.index = index;
        this.mType = type;
        this.mName = name;
    }

    /**
     * Returns the type of the power monitor.
     */
    @FlaggedApi("com.android.server.power.optimization.power_monitor_api")
    @PowerMonitorType
    public int getType() {
        return mType;
    }

    /**
     * Returns the name of the power monitor, either a power rail or an energy consumer.
     */
    @FlaggedApi("com.android.server.power.optimization.power_monitor_api")
    @NonNull
    public String getName() {
        return mName;
    }

    private PowerMonitor(Parcel in) {
        index = in.readInt();
        mType = in.readInt();
        mName = in.readString8();
    }

    @FlaggedApi("com.android.server.power.optimization.power_monitor_api")
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(index);
        dest.writeInt(mType);
        dest.writeString8(mName);
    }

    @FlaggedApi("com.android.server.power.optimization.power_monitor_api")
    @Override
    public int describeContents() {
        return 0;
    }

    @FlaggedApi("com.android.server.power.optimization.power_monitor_api")
    @NonNull
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
