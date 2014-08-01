/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.annotation.Nullable;
import android.bluetooth.BluetoothUuid;
import android.os.Parcel;
import android.os.ParcelUuid;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Advertise data packet container for Bluetooth LE advertising. This represents the data to be
 * advertised as well as the scan response data for active scans.
 * <p>
 * Use {@link AdvertiseData.Builder} to create an instance of {@link AdvertiseData} to be
 * advertised.
 *
 * @see BluetoothLeAdvertiser
 * @see ScanRecord
 */
public final class AdvertiseData implements Parcelable {

    @Nullable
    private final List<ParcelUuid> mServiceUuids;

    private final int mManufacturerId;
    @Nullable
    private final byte[] mManufacturerSpecificData;

    @Nullable
    private final ParcelUuid mServiceDataUuid;
    @Nullable
    private final byte[] mServiceData;

    private final boolean mIncludeTxPowerLevel;
    private final boolean mIncludeDeviceName;

    private AdvertiseData(List<ParcelUuid> serviceUuids,
            ParcelUuid serviceDataUuid, byte[] serviceData,
            int manufacturerId,
            byte[] manufacturerSpecificData, boolean includeTxPowerLevel,
            boolean includeDeviceName) {
        mServiceUuids = serviceUuids;
        mManufacturerId = manufacturerId;
        mManufacturerSpecificData = manufacturerSpecificData;
        mServiceDataUuid = serviceDataUuid;
        mServiceData = serviceData;
        mIncludeTxPowerLevel = includeTxPowerLevel;
        mIncludeDeviceName = includeDeviceName;
    }

    /**
     * Returns a list of service UUIDs within the advertisement that are used to identify the
     * Bluetooth GATT services.
     */
    public List<ParcelUuid> getServiceUuids() {
        return mServiceUuids;
    }

    /**
     * Returns the manufacturer identifier, which is a non-negative number assigned by Bluetooth
     * SIG.
     */
    public int getManufacturerId() {
        return mManufacturerId;
    }

    /**
     * Returns the manufacturer specific data which is the content of manufacturer specific data
     * field. The first 2 bytes of the data contain the company id.
     */
    public byte[] getManufacturerSpecificData() {
        return mManufacturerSpecificData;
    }

    /**
     * Returns a 16-bit UUID of the service that the service data is associated with.
     */
    public ParcelUuid getServiceDataUuid() {
        return mServiceDataUuid;
    }

    /**
     * Returns service data.
     */
    public byte[] getServiceData() {
        return mServiceData;
    }

    /**
     * Whether the transmission power level will be included in the advertisement packet.
     */
    public boolean getIncludeTxPowerLevel() {
        return mIncludeTxPowerLevel;
    }

    /**
     * Whether the device name will be included in the advertisement packet.
     */
    public boolean getIncludeDeviceName() {
        return mIncludeDeviceName;
    }

    /**
     * @hide
     */
    @Override
    public int hashCode() {
        return Objects.hash(mServiceUuids, mManufacturerId, mManufacturerSpecificData,
                mServiceDataUuid, mServiceData, mIncludeDeviceName, mIncludeTxPowerLevel);
    }

    /**
     * @hide
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        AdvertiseData other = (AdvertiseData) obj;
        return Objects.equals(mServiceUuids, other.mServiceUuids) &&
                mManufacturerId == other.mManufacturerId &&
                Objects.deepEquals(mManufacturerSpecificData, other.mManufacturerSpecificData) &&
                Objects.equals(mServiceDataUuid, other.mServiceDataUuid) &&
                Objects.deepEquals(mServiceData, other.mServiceData) &&
                        mIncludeDeviceName == other.mIncludeDeviceName &&
                        mIncludeTxPowerLevel == other.mIncludeTxPowerLevel;
    }

    @Override
    public String toString() {
        return "AdvertiseData [mServiceUuids=" + mServiceUuids + ", mManufacturerId="
                + mManufacturerId + ", mManufacturerSpecificData="
                + Arrays.toString(mManufacturerSpecificData) + ", mServiceDataUuid="
                + mServiceDataUuid + ", mServiceData=" + Arrays.toString(mServiceData)
                + ", mIncludeTxPowerLevel=" + mIncludeTxPowerLevel + ", mIncludeDeviceName="
                + mIncludeDeviceName + "]";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeList(mServiceUuids);

        dest.writeInt(mManufacturerId);
        if (mManufacturerSpecificData == null) {
            dest.writeInt(0);
        } else {
            dest.writeInt(1);
            dest.writeInt(mManufacturerSpecificData.length);
            dest.writeByteArray(mManufacturerSpecificData);
        }
        dest.writeParcelable(mServiceDataUuid, flags);
        if (mServiceData == null) {
            dest.writeInt(0);
        } else {
            dest.writeInt(1);
            dest.writeInt(mServiceData.length);
            dest.writeByteArray(mServiceData);
        }
        dest.writeByte((byte) (getIncludeTxPowerLevel() ? 1 : 0));
        dest.writeByte((byte) (getIncludeDeviceName() ? 1 : 0));
    }

    /**
     * @hide
     */
    public static final Parcelable.Creator<AdvertiseData> CREATOR =
            new Creator<AdvertiseData>() {
            @Override
                public AdvertiseData[] newArray(int size) {
                    return new AdvertiseData[size];
                }

            @Override
                public AdvertiseData createFromParcel(Parcel in) {
                    Builder builder = new Builder();
                    @SuppressWarnings("unchecked")
                    List<ParcelUuid> uuids = in.readArrayList(ParcelUuid.class.getClassLoader());
                    if (uuids != null) {
                        for (ParcelUuid uuid : uuids) {
                            builder.addServiceUuid(uuid);
                        }
                    }
                    int manufacturerId = in.readInt();
                    if (in.readInt() == 1) {
                        int manufacturerDataLength = in.readInt();
                        byte[] manufacturerData = new byte[manufacturerDataLength];
                        in.readByteArray(manufacturerData);
                        builder.setManufacturerData(manufacturerId, manufacturerData);
                    }
                    ParcelUuid serviceDataUuid = in.readParcelable(
                            ParcelUuid.class.getClassLoader());
                    if (in.readInt() == 1) {
                        int serviceDataLength = in.readInt();
                        byte[] serviceData = new byte[serviceDataLength];
                        in.readByteArray(serviceData);
                        builder.setServiceData(serviceDataUuid, serviceData);
                    }
                    builder.setIncludeTxPowerLevel(in.readByte() == 1);
                    builder.setIncludeDeviceName(in.readByte() == 1);
                    return builder.build();
                }
            };

    /**
     * Builder for {@link AdvertiseData}.
     */
    public static final class Builder {
        @Nullable
        private List<ParcelUuid> mServiceUuids = new ArrayList<ParcelUuid>();
        private int mManufacturerId = -1;
        @Nullable
        private byte[] mManufacturerSpecificData;
        @Nullable
        private ParcelUuid mServiceDataUuid;
        @Nullable
        private byte[] mServiceData;
        private boolean mIncludeTxPowerLevel;
        private boolean mIncludeDeviceName;

        /**
         * Add a service UUID to advertise data.
         *
         * @param serviceUuid A service UUID to be advertised.
         * @throws IllegalArgumentException If the {@code serviceUuids} are null.
         */
        public Builder addServiceUuid(ParcelUuid serviceUuid) {
            if (serviceUuid == null) {
                throw new IllegalArgumentException("serivceUuids are null");
            }
            mServiceUuids.add(serviceUuid);
            return this;
        }

        /**
         * Add service data to advertise data.
         *
         * @param serviceDataUuid 16-bit UUID of the service the data is associated with
         * @param serviceData Service data
         * @throws IllegalArgumentException If the {@code serviceDataUuid} or {@code serviceData} is
         *             empty.
         */
        public Builder setServiceData(ParcelUuid serviceDataUuid, byte[] serviceData) {
            if (serviceDataUuid == null || serviceData == null) {
                throw new IllegalArgumentException(
                        "serviceDataUuid or serviceDataUuid is null");
            }
            mServiceDataUuid = serviceDataUuid;
            mServiceData = serviceData;
            return this;
        }

        /**
         * Set manufacturer specific data.
         * <p>
         * Please refer to the Bluetooth Assigned Numbers document provided by the <a
         * href="https://www.bluetooth.org">Bluetooth SIG</a> for a list of existing company
         * identifiers.
         *
         * @param manufacturerId Manufacturer ID assigned by Bluetooth SIG.
         * @param manufacturerSpecificData Manufacturer specific data
         * @throws IllegalArgumentException If the {@code manufacturerId} is negative or
         *             {@code manufacturerSpecificData} is null.
         */
        public Builder setManufacturerData(int manufacturerId, byte[] manufacturerSpecificData) {
            if (manufacturerId < 0) {
                throw new IllegalArgumentException(
                        "invalid manufacturerId - " + manufacturerId);
            }
            if (manufacturerSpecificData == null) {
                throw new IllegalArgumentException("manufacturerSpecificData is null");
            }
            mManufacturerId = manufacturerId;
            mManufacturerSpecificData = manufacturerSpecificData;
            return this;
        }

        /**
         * Whether the transmission power level should be included in the advertise packet. Tx power
         * level field takes 3 bytes in advertise packet.
         */
        public Builder setIncludeTxPowerLevel(boolean includeTxPowerLevel) {
            mIncludeTxPowerLevel = includeTxPowerLevel;
            return this;
        }

        /**
         * Set whether the device name should be included in advertise packet.
         */
        public Builder setIncludeDeviceName(boolean includeDeviceName) {
            mIncludeDeviceName = includeDeviceName;
            return this;
        }

        /**
         * Build the {@link AdvertiseData}.
         */
        public AdvertiseData build() {
            return new AdvertiseData(mServiceUuids,
                    mServiceDataUuid,
                    mServiceData, mManufacturerId, mManufacturerSpecificData,
                    mIncludeTxPowerLevel, mIncludeDeviceName);
        }
    }
}
