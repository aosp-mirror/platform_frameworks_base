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

/**
 * Advertisement data packet for Bluetooth LE advertising. This represents the data to be
 * broadcasted in Bluetooth LE advertising as well as the scan response for active scan.
 * <p>
 * Use {@link AdvertisementData.Builder} to create an instance of {@link AdvertisementData} to be
 * advertised.
 *
 * @see BluetoothLeAdvertiser
 * @see ScanRecord
 */
public final class AdvertisementData implements Parcelable {

    @Nullable
    private final List<ParcelUuid> mServiceUuids;

    private final int mManufacturerId;
    @Nullable
    private final byte[] mManufacturerSpecificData;

    @Nullable
    private final ParcelUuid mServiceDataUuid;
    @Nullable
    private final byte[] mServiceData;

    private boolean mIncludeTxPowerLevel;

    private AdvertisementData(List<ParcelUuid> serviceUuids,
            ParcelUuid serviceDataUuid, byte[] serviceData,
            int manufacturerId,
            byte[] manufacturerSpecificData, boolean includeTxPowerLevel) {
        mServiceUuids = serviceUuids;
        mManufacturerId = manufacturerId;
        mManufacturerSpecificData = manufacturerSpecificData;
        mServiceDataUuid = serviceDataUuid;
        mServiceData = serviceData;
        mIncludeTxPowerLevel = includeTxPowerLevel;
    }

    /**
     * Returns a list of service uuids within the advertisement that are used to identify the
     * bluetooth GATT services.
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
     * Returns a 16 bit uuid of the service that the service data is associated with.
     */
    public ParcelUuid getServiceDataUuid() {
        return mServiceDataUuid;
    }

    /**
     * Returns service data. The first two bytes should be a 16 bit service uuid associated with the
     * service data.
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

    @Override
    public String toString() {
        return "AdvertisementData [mServiceUuids=" + mServiceUuids + ", mManufacturerId="
                + mManufacturerId + ", mManufacturerSpecificData="
                + Arrays.toString(mManufacturerSpecificData) + ", mServiceDataUuid="
                + mServiceDataUuid + ", mServiceData=" + Arrays.toString(mServiceData)
                + ", mIncludeTxPowerLevel=" + mIncludeTxPowerLevel + "]";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (mServiceUuids == null) {
            dest.writeInt(0);
        } else {
            dest.writeInt(mServiceUuids.size());
            dest.writeList(mServiceUuids);
        }

        dest.writeInt(mManufacturerId);
        if (mManufacturerSpecificData == null) {
            dest.writeInt(0);
        } else {
            dest.writeInt(mManufacturerSpecificData.length);
            dest.writeByteArray(mManufacturerSpecificData);
        }

        if (mServiceDataUuid == null) {
            dest.writeInt(0);
        } else {
            dest.writeInt(1);
            dest.writeParcelable(mServiceDataUuid, flags);
            if (mServiceData == null) {
                dest.writeInt(0);
            } else {
                dest.writeInt(mServiceData.length);
                dest.writeByteArray(mServiceData);
            }
        }
        dest.writeByte((byte) (getIncludeTxPowerLevel() ? 1 : 0));
    }

    public static final Parcelable.Creator<AdvertisementData> CREATOR =
            new Creator<AdvertisementData>() {
            @Override
                public AdvertisementData[] newArray(int size) {
                    return new AdvertisementData[size];
                }

            @Override
                public AdvertisementData createFromParcel(Parcel in) {
                    Builder builder = new Builder();
                    if (in.readInt() > 0) {
                        List<ParcelUuid> uuids = new ArrayList<ParcelUuid>();
                        in.readList(uuids, ParcelUuid.class.getClassLoader());
                        builder.setServiceUuids(uuids);
                    }
                    int manufacturerId = in.readInt();
                    int manufacturerDataLength = in.readInt();
                    if (manufacturerDataLength > 0) {
                        byte[] manufacturerData = new byte[manufacturerDataLength];
                        in.readByteArray(manufacturerData);
                        builder.setManufacturerData(manufacturerId, manufacturerData);
                    }
                    if (in.readInt() == 1) {
                        ParcelUuid serviceDataUuid = in.readParcelable(
                                ParcelUuid.class.getClassLoader());
                        int serviceDataLength = in.readInt();
                        if (serviceDataLength > 0) {
                            byte[] serviceData = new byte[serviceDataLength];
                            in.readByteArray(serviceData);
                            builder.setServiceData(serviceDataUuid, serviceData);
                        }
                    }
                    builder.setIncludeTxPowerLevel(in.readByte() == 1);
                    return builder.build();
                }
            };

    /**
     * Builder for {@link AdvertisementData}.
     */
    public static final class Builder {
        private static final int MAX_ADVERTISING_DATA_BYTES = 31;
        // Each fields need one byte for field length and another byte for field type.
        private static final int OVERHEAD_BYTES_PER_FIELD = 2;
        // Flags field will be set by system.
        private static final int FLAGS_FIELD_BYTES = 3;

        @Nullable
        private List<ParcelUuid> mServiceUuids;
        private boolean mIncludeTxPowerLevel;
        private int mManufacturerId;
        @Nullable
        private byte[] mManufacturerSpecificData;
        @Nullable
        private ParcelUuid mServiceDataUuid;
        @Nullable
        private byte[] mServiceData;

        /**
         * Set the service uuids. Note the corresponding bluetooth Gatt services need to be already
         * added on the device before start BLE advertising.
         *
         * @param serviceUuids Service uuids to be advertised, could be 16-bit, 32-bit or 128-bit
         *            uuids.
         * @throws IllegalArgumentException If the {@code serviceUuids} are null.
         */
        public Builder setServiceUuids(List<ParcelUuid> serviceUuids) {
            if (serviceUuids == null) {
                throw new IllegalArgumentException("serivceUuids are null");
            }
            mServiceUuids = serviceUuids;
            return this;
        }

        /**
         * Add service data to advertisement.
         *
         * @param serviceDataUuid A 16 bit uuid of the service data
         * @param serviceData Service data - the first two bytes of the service data are the service
         *            data uuid.
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
         * Set manufacturer id and data. See <a
         * href="https://www.bluetooth.org/en-us/specification/assigned-numbers/company-identifiers">assigned
         * manufacturer identifies</a> for the existing company identifiers.
         *
         * @param manufacturerId Manufacturer id assigned by Bluetooth SIG.
         * @param manufacturerSpecificData Manufacturer specific data - the first two bytes of the
         *            manufacturer specific data are the manufacturer id.
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
         * Whether the transmission power level should be included in the advertising packet.
         */
        public Builder setIncludeTxPowerLevel(boolean includeTxPowerLevel) {
            mIncludeTxPowerLevel = includeTxPowerLevel;
            return this;
        }

        /**
         * Build the {@link AdvertisementData}.
         *
         * @throws IllegalArgumentException If the data size is larger than 31 bytes.
         */
        public AdvertisementData build() {
            if (totalBytes() > MAX_ADVERTISING_DATA_BYTES) {
                throw new IllegalArgumentException(
                        "advertisement data size is larger than 31 bytes");
            }
            return new AdvertisementData(mServiceUuids,
                    mServiceDataUuid,
                    mServiceData, mManufacturerId, mManufacturerSpecificData,
                    mIncludeTxPowerLevel);
        }

        // Compute the size of the advertisement data.
        private int totalBytes() {
            int size = FLAGS_FIELD_BYTES; // flags field is always set.
            if (mServiceUuids != null) {
                int num16BitUuids = 0;
                int num32BitUuids = 0;
                int num128BitUuids = 0;
                for (ParcelUuid uuid : mServiceUuids) {
                    if (BluetoothUuid.is16BitUuid(uuid)) {
                        ++num16BitUuids;
                    } else if (BluetoothUuid.is32BitUuid(uuid)) {
                        ++num32BitUuids;
                    } else {
                        ++num128BitUuids;
                    }
                }
                // 16 bit service uuids are grouped into one field when doing advertising.
                if (num16BitUuids != 0) {
                    size += OVERHEAD_BYTES_PER_FIELD +
                            num16BitUuids * BluetoothUuid.UUID_BYTES_16_BIT;
                }
                // 32 bit service uuids are grouped into one field when doing advertising.
                if (num32BitUuids != 0) {
                    size += OVERHEAD_BYTES_PER_FIELD +
                            num32BitUuids * BluetoothUuid.UUID_BYTES_32_BIT;
                }
                // 128 bit service uuids are grouped into one field when doing advertising.
                if (num128BitUuids != 0) {
                    size += OVERHEAD_BYTES_PER_FIELD +
                            num128BitUuids * BluetoothUuid.UUID_BYTES_128_BIT;
                }
            }
            if (mServiceData != null) {
                size += OVERHEAD_BYTES_PER_FIELD + mServiceData.length;
            }
            if (mManufacturerSpecificData != null) {
                size += OVERHEAD_BYTES_PER_FIELD + mManufacturerSpecificData.length;
            }
            if (mIncludeTxPowerLevel) {
                size += OVERHEAD_BYTES_PER_FIELD + 1; // tx power level value is one byte.
            }
            return size;
        }
    }
}
