/*
 * Copyright (c) 2017 The Android Open Source Project
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
import android.hardware.thermal.V2_0.TemperatureType;
import android.hardware.thermal.V2_0.ThrottlingSeverity;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Temperature values used by IThermalService.
 *
 * @hide
 */
public final class Temperature implements Parcelable {
    /** Temperature value */
    private float mValue;
    /** A temperature type from ThermalHAL */
    private int mType;
    /** Name of this temperature */
    private String mName;
    /** The level of the sensor is currently in throttling */
    private int mStatus;

    @IntDef(prefix = { "THROTTLING_" }, value = {
            THROTTLING_NONE,
            THROTTLING_LIGHT,
            THROTTLING_MODERATE,
            THROTTLING_SEVERE,
            THROTTLING_CRITICAL,
            THROTTLING_EMERGENCY,
            THROTTLING_SHUTDOWN,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ThrottlingStatus {}

    /** Keep in sync with hardware/interfaces/thermal/2.0/types.hal */
    public static final int THROTTLING_NONE = ThrottlingSeverity.NONE;
    public static final int THROTTLING_LIGHT = ThrottlingSeverity.LIGHT;
    public static final int THROTTLING_MODERATE = ThrottlingSeverity.MODERATE;
    public static final int THROTTLING_SEVERE = ThrottlingSeverity.SEVERE;
    public static final int THROTTLING_CRITICAL = ThrottlingSeverity.CRITICAL;
    public static final int THROTTLING_EMERGENCY = ThrottlingSeverity.EMERGENCY;
    public static final int THROTTLING_SHUTDOWN = ThrottlingSeverity.SHUTDOWN;

    @IntDef(prefix = { "TYPE_" }, value = {
            TYPE_UNKNOWN,
            TYPE_CPU,
            TYPE_GPU,
            TYPE_BATTERY,
            TYPE_SKIN,
            TYPE_USB_PORT,
            TYPE_POWER_AMPLIFIER,
            TYPE_BCL_VOLTAGE,
            TYPE_BCL_CURRENT,
            TYPE_BCL_PERCENTAGE,
            TYPE_NPU,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Type {}

    /* Keep in sync with hardware/interfaces/thermal/2.0/types.hal */
    public static final int TYPE_UNKNOWN = TemperatureType.UNKNOWN;
    public static final int TYPE_CPU = TemperatureType.CPU;
    public static final int TYPE_GPU = TemperatureType.GPU;
    public static final int TYPE_BATTERY = TemperatureType.BATTERY;
    public static final int TYPE_SKIN = TemperatureType.SKIN;
    public static final int TYPE_USB_PORT = TemperatureType.USB_PORT;
    public static final int TYPE_POWER_AMPLIFIER = TemperatureType.POWER_AMPLIFIER;
    public static final int TYPE_BCL_VOLTAGE = TemperatureType.BCL_VOLTAGE;
    public static final int TYPE_BCL_CURRENT = TemperatureType.BCL_CURRENT;
    public static final int TYPE_BCL_PERCENTAGE = TemperatureType.BCL_PERCENTAGE;
    public static final int TYPE_NPU = TemperatureType.NPU;

    /**
     * Verify a valid temperature type.
     *
     * @return true if a temperature type is valid otherwise false.
     */
    public static boolean isValidType(@Type int type) {
        return type >= TYPE_UNKNOWN && type <= TYPE_NPU;
    }

    /**
     * Verify a valid throttling status.
     *
     * @return true if a status is valid otherwise false.
     */
    public static boolean isValidStatus(@ThrottlingStatus int status) {
        return status >= THROTTLING_NONE && status <= THROTTLING_SHUTDOWN;
    }

    public Temperature() {
        this(Float.NaN, TYPE_UNKNOWN, "", THROTTLING_NONE);
    }

    public Temperature(float value, @Type int type, String name, @ThrottlingStatus int status) {
        mValue = value;
        mType = isValidType(type) ? type : TYPE_UNKNOWN;
        mName = name;
        mStatus = isValidStatus(status) ? status : THROTTLING_NONE;
    }

    /**
     * Return the temperature value.
     *
     * @return a temperature value in floating point could be NaN.
     */
    public float getValue() {
        return mValue;
    }

    /**
     * Return the temperature type.
     *
     * @return a temperature type: TYPE_*
     */
    public @Type int getType() {
        return mType;
    }

    /**
     * Return the temperature name.
     *
     * @return a temperature name as String.
     */
    public String getName() {
        return mName;
    }

    /**
     * Return the temperature throttling status.
     *
     * @return a temperature throttling status: THROTTLING_*
     */
    public @ThrottlingStatus int getStatus() {
        return mStatus;
    }

    private Temperature(Parcel p) {
        readFromParcel(p);
    }

    /**
     * Fill in Temperature members from a Parcel.
     *
     * @param p the parceled Temperature object.
     */
    public void readFromParcel(Parcel p) {
        mValue = p.readFloat();
        mType = p.readInt();
        mName = p.readString();
        mStatus = p.readInt();
    }

    @Override
    public void writeToParcel(Parcel p, int flags) {
        p.writeFloat(mValue);
        p.writeInt(mType);
        p.writeString(mName);
        p.writeInt(mStatus);
    }

    public static final Parcelable.Creator<Temperature> CREATOR =
            new Parcelable.Creator<Temperature>() {
                @Override
                public Temperature createFromParcel(Parcel p) {
                    return new Temperature(p);
                }

                @Override
                public Temperature[] newArray(int size) {
                    return new Temperature[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }
}
