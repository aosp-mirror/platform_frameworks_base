/*
 * Copyright (c) 2019 The Android Open Source Project
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
import android.hardware.thermal.V2_0.CoolingType;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Cooling device values used by IThermalService.
 *
 * @hide
 */
public final class CoolingDevice implements Parcelable {
    /**
     * Current throttle state of the cooling device. The value can any unsigned integer
     * numbers between 0 and max_state defined in its driver, usually representing the
     * associated device's power state. 0 means device is not in throttling, higher value
     * means deeper throttling.
     */
    private final long mValue;
    /** A cooling device type from ThermalHAL */
    private final int mType;
    /** Name of this cooling device */
    private final String mName;

    @IntDef(prefix = { "TYPE_" }, value = {
            TYPE_FAN,
            TYPE_BATTERY,
            TYPE_CPU,
            TYPE_GPU,
            TYPE_MODEM,
            TYPE_NPU,
            TYPE_COMPONENT,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Type {}

    /** Keep in sync with hardware/interfaces/thermal/2.0/types.hal */
    /** Fan for active cooling */
    public static final int TYPE_FAN = CoolingType.FAN;
    /** Battery charging cooling deivice */
    public static final int TYPE_BATTERY = CoolingType.BATTERY;
    /** CPU cooling deivice */
    public static final int TYPE_CPU = CoolingType.CPU;
    /** GPU cooling deivice */
    public static final int TYPE_GPU = CoolingType.GPU;
    /** Modem cooling deivice */
    public static final int TYPE_MODEM = CoolingType.MODEM;
    /** NPU/TPU cooling deivice */
    public static final int TYPE_NPU = CoolingType.NPU;
    /** Generic passive cooling deivice */
    public static final int TYPE_COMPONENT = CoolingType.COMPONENT;

    /**
     * Verify a valid cooling device type.
     *
     * @return true if a cooling device type is valid otherwise false.
     */
    public static boolean isValidType(@Type int type) {
        return type >= TYPE_FAN && type <= TYPE_COMPONENT;
    }

    public CoolingDevice(long value, @Type int type, @NonNull String name) {
        Preconditions.checkArgument(isValidType(type), "Invalid Type");
        mValue = value;
        mType = type;
        mName = Preconditions.checkStringNotEmpty(name);
    }

    /**
     * Return the cooling device value.
     *
     * @return a cooling device value in int.
     */
    public long getValue() {
        return mValue;
    }

    /**
     * Return the cooling device type.
     *
     * @return a cooling device type: TYPE_*
     */
    public @Type int getType() {
        return mType;
    }

    /**
     * Return the cooling device name.
     *
     * @return a cooling device name as String.
     */
    public String getName() {
        return mName;
    }

    @Override
    public String toString() {
        return "CoolingDevice{mValue=" + mValue + ", mType=" + mType + ", mName=" + mName + "}";
    }

    @Override
    public int hashCode() {
        int hash = mName.hashCode();
        hash = 31 * hash + Long.hashCode(mValue);
        hash = 31 * hash + mType;
        return hash;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof CoolingDevice)) {
            return false;
        }
        CoolingDevice other = (CoolingDevice) o;
        return other.mValue == mValue && other.mType == mType && other.mName.equals(mName);
    }

    @Override
    public void writeToParcel(Parcel p, int flags) {
        p.writeLong(mValue);
        p.writeInt(mType);
        p.writeString(mName);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<CoolingDevice> CREATOR =
            new Parcelable.Creator<CoolingDevice>() {
                @Override
                public CoolingDevice createFromParcel(Parcel p) {
                    long value = p.readLong();
                    int type = p.readInt();
                    String name = p.readString();
                    return new CoolingDevice(value, type, name);
                }

                @Override
                public CoolingDevice[] newArray(int size) {
                    return new CoolingDevice[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }
}
