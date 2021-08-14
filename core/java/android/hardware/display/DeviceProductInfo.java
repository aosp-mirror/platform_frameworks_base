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

package android.hardware.display;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Product-specific information about the display or the directly connected device on the
 * display chain. For example, if the display is transitively connected, this field may contain
 * product information about the intermediate device.
 */
public final class DeviceProductInfo implements Parcelable {
    /** @hide */
    @IntDef(prefix = {"CONNECTION_TO_SINK_"}, value = {
            CONNECTION_TO_SINK_UNKNOWN,
            CONNECTION_TO_SINK_BUILT_IN,
            CONNECTION_TO_SINK_DIRECT,
            CONNECTION_TO_SINK_TRANSITIVE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ConnectionToSinkType { }

    /** The device connection to the display sink is unknown. */
    public static final int CONNECTION_TO_SINK_UNKNOWN =
            IDeviceProductInfoConstants.CONNECTION_TO_SINK_UNKNOWN;

    /** The display sink is built-in to the device */
    public static final int CONNECTION_TO_SINK_BUILT_IN =
            IDeviceProductInfoConstants.CONNECTION_TO_SINK_BUILT_IN;

    /** The device is directly connected to the display sink. */
    public static final int CONNECTION_TO_SINK_DIRECT =
            IDeviceProductInfoConstants.CONNECTION_TO_SINK_DIRECT;

    /** The device is transitively connected to the display sink. */
    public static final int CONNECTION_TO_SINK_TRANSITIVE =
            IDeviceProductInfoConstants.CONNECTION_TO_SINK_TRANSITIVE;

    private final String mName;
    private final String mManufacturerPnpId;
    private final String mProductId;
    private final Integer mModelYear;
    private final ManufactureDate mManufactureDate;
    private final @ConnectionToSinkType int mConnectionToSinkType;

    /** @hide */
    public DeviceProductInfo(
            String name,
            String manufacturerPnpId,
            String productId,
            Integer modelYear,
            ManufactureDate manufactureDate,
            int connectionToSinkType) {
        mName = name;
        mManufacturerPnpId = manufacturerPnpId;
        mProductId = productId;
        mModelYear = modelYear;
        mManufactureDate = manufactureDate;
        mConnectionToSinkType = connectionToSinkType;
    }

    public DeviceProductInfo(
            @Nullable String name,
            @NonNull String manufacturerPnpId,
            @NonNull String productId,
            @IntRange(from = 1990) int modelYear,
            @ConnectionToSinkType int connectionToSinkType) {
        mName = name;
        mManufacturerPnpId = Objects.requireNonNull(manufacturerPnpId);
        mProductId = Objects.requireNonNull(productId);
        mModelYear = modelYear;
        mManufactureDate = null;
        mConnectionToSinkType = connectionToSinkType;
    }

    private DeviceProductInfo(Parcel in) {
        mName = in.readString();
        mManufacturerPnpId = in.readString();
        mProductId = (String) in.readValue(null);
        mModelYear = (Integer) in.readValue(null);
        mManufactureDate = (ManufactureDate) in.readValue(null);
        mConnectionToSinkType = in.readInt();
    }

    /**
     * @return Display name.
     */
    @Nullable
    public String getName() {
        return mName;
    }

    /**
     * Returns the Manufacturer Plug and Play ID. This ID identifies the manufacture according to
     * the list: https://uefi.org/PNP_ID_List. It consist of 3 characters, each character
     * is an uppercase letter (A-Z).
     * @return Manufacturer Plug and Play ID.
     */
    @NonNull
    public String getManufacturerPnpId() {
        return mManufacturerPnpId;
    }

    /**
     * @return Manufacturer product ID.
     */
    @NonNull
    public String getProductId() {
        return mProductId;
    }

    /**
     * @return Model year of the device. Return -1 if not available. Typically,
     * one of model year or manufacture year is available.
     */
    @IntRange(from = -1)
    public int getModelYear()  {
        return mModelYear != null ? mModelYear : -1;
    }

    /**
     * @return The year of manufacture, or -1 it is not available. Typically,
     * one of model year or manufacture year is available.
     */
    @IntRange(from = -1)
    public int getManufactureYear()  {
        if (mManufactureDate == null) {
            return -1;
        }
        return mManufactureDate.mYear != null ? mManufactureDate.mYear : -1;
    }

    /**
     * @return The week of manufacture which ranges from 1 to 53, or -1 it is not available.
     * Typically, it is not present if model year is available.
     */
    @IntRange(from = -1, to = 53)
    public int getManufactureWeek() {
        if (mManufactureDate == null) {
            return -1;
        }
        return mManufactureDate.mWeek != null ?  mManufactureDate.mWeek : -1;
    }

    /**
     * @return Manufacture date. Typically exactly one of model year or manufacture
     * date will be present.
     *
     * @hide
     */
    public ManufactureDate getManufactureDate() {
        return mManufactureDate;
    }

    /**
     * @return How the current device is connected to the display sink. For example, the display
     * can be connected immediately to the device or there can be a receiver in between.
     */
    @ConnectionToSinkType
    public int getConnectionToSinkType() {
        return mConnectionToSinkType;
    }

    @Override
    public String toString() {
        return "DeviceProductInfo{"
                + "name="
                + mName
                + ", manufacturerPnpId="
                + mManufacturerPnpId
                + ", productId="
                + mProductId
                + ", modelYear="
                + mModelYear
                + ", manufactureDate="
                + mManufactureDate
                + ", connectionToSinkType="
                + mConnectionToSinkType
                + '}';
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DeviceProductInfo that = (DeviceProductInfo) o;
        return Objects.equals(mName, that.mName)
                && Objects.equals(mManufacturerPnpId, that.mManufacturerPnpId)
                && Objects.equals(mProductId, that.mProductId)
                && Objects.equals(mModelYear, that.mModelYear)
                && Objects.equals(mManufactureDate, that.mManufactureDate)
                && mConnectionToSinkType == that.mConnectionToSinkType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mName, mManufacturerPnpId, mProductId, mModelYear, mManufactureDate,
                mConnectionToSinkType);
    }

    @NonNull public static final Creator<DeviceProductInfo> CREATOR =
            new Creator<DeviceProductInfo>() {
                @Override
                public DeviceProductInfo createFromParcel(Parcel in) {
                    return new DeviceProductInfo(in);
                }

                @Override
                public DeviceProductInfo[] newArray(int size) {
                    return new DeviceProductInfo[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mName);
        dest.writeString(mManufacturerPnpId);
        dest.writeValue(mProductId);
        dest.writeValue(mModelYear);
        dest.writeValue(mManufactureDate);
        dest.writeInt(mConnectionToSinkType);
    }

    /**
     * Stores information about the date of manufacture.
     *
     * @hide
     */
    public static class ManufactureDate implements Parcelable {
        private final Integer mWeek;
        private final Integer mYear;

        public ManufactureDate(Integer week, Integer year) {
            mWeek = week;
            mYear = year;
        }

        protected ManufactureDate(Parcel in) {
            mWeek = (Integer) in.readValue(null);
            mYear = (Integer) in.readValue(null);
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeValue(mWeek);
            dest.writeValue(mYear);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final Creator<ManufactureDate> CREATOR =
                new Creator<ManufactureDate>() {
                    @Override
                    public ManufactureDate createFromParcel(Parcel in) {
                        return new ManufactureDate(in);
                    }

                    @Override
                    public ManufactureDate[] newArray(int size) {
                        return new ManufactureDate[size];
                    }
                };

        public Integer getYear() {
            return mYear;
        }

        public Integer getWeek() {
            return mWeek;
        }

        @Override
        public String toString() {
            return "ManufactureDate{week=" + mWeek + ", year=" + mYear + '}';
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ManufactureDate that = (ManufactureDate) o;
            return Objects.equals(mWeek, that.mWeek) && Objects.equals(mYear, that.mYear);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mWeek, mYear);
        }
    }
}
