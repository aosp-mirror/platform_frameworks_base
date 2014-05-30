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
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.os.Parcel;
import android.os.ParcelUuid;
import android.os.Parcelable;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link ScanFilter} abstracts different scan filters across Bluetooth Advertisement packet fields.
 * <p>
 * Current filtering on the following fields are supported:
 * <li>Service UUIDs which identify the bluetooth gatt services running on the device.
 * <li>Name of remote Bluetooth LE device.
 * <li>Mac address of the remote device.
 * <li>Rssi which indicates the received power level.
 * <li>Service data which is the data associated with a service.
 * <li>Manufacturer specific data which is the data associated with a particular manufacturer.
 *
 * @see ScanRecord
 * @see BluetoothLeScanner
 */
public final class ScanFilter implements Parcelable {

    @Nullable
    private final String mLocalName;

    @Nullable
    private final String mMacAddress;

    @Nullable
    private final ParcelUuid mServiceUuid;
    @Nullable
    private final ParcelUuid mServiceUuidMask;

    @Nullable
    private final byte[] mServiceData;
    @Nullable
    private final byte[] mServiceDataMask;

    private final int mManufacturerId;
    @Nullable
    private final byte[] mManufacturerData;
    @Nullable
    private final byte[] mManufacturerDataMask;

    private final int mMinRssi;
    private final int mMaxRssi;

    private ScanFilter(String name, String macAddress, ParcelUuid uuid,
            ParcelUuid uuidMask, byte[] serviceData, byte[] serviceDataMask,
            int manufacturerId, byte[] manufacturerData, byte[] manufacturerDataMask,
            int minRssi, int maxRssi) {
        mLocalName = name;
        mServiceUuid = uuid;
        mServiceUuidMask = uuidMask;
        mMacAddress = macAddress;
        mServiceData = serviceData;
        mServiceDataMask = serviceDataMask;
        mManufacturerId = manufacturerId;
        mManufacturerData = manufacturerData;
        mManufacturerDataMask = manufacturerDataMask;
        mMinRssi = minRssi;
        mMaxRssi = maxRssi;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mLocalName == null ? 0 : 1);
        if (mLocalName != null) {
            dest.writeString(mLocalName);
        }
        dest.writeInt(mMacAddress == null ? 0 : 1);
        if (mMacAddress != null) {
            dest.writeString(mMacAddress);
        }
        dest.writeInt(mServiceUuid == null ? 0 : 1);
        if (mServiceUuid != null) {
            dest.writeParcelable(mServiceUuid, flags);
            dest.writeInt(mServiceUuidMask == null ? 0 : 1);
            if (mServiceUuidMask != null) {
                dest.writeParcelable(mServiceUuidMask, flags);
            }
        }
        dest.writeInt(mServiceData == null ? 0 : mServiceData.length);
        if (mServiceData != null) {
            dest.writeByteArray(mServiceData);
            dest.writeInt(mServiceDataMask == null ? 0 : mServiceDataMask.length);
            if (mServiceDataMask != null) {
                dest.writeByteArray(mServiceDataMask);
            }
        }
        dest.writeInt(mManufacturerId);
        dest.writeInt(mManufacturerData == null ? 0 : mManufacturerData.length);
        if (mManufacturerData != null) {
            dest.writeByteArray(mManufacturerData);
            dest.writeInt(mManufacturerDataMask == null ? 0 : mManufacturerDataMask.length);
            if (mManufacturerDataMask != null) {
                dest.writeByteArray(mManufacturerDataMask);
            }
        }
        dest.writeInt(mMinRssi);
        dest.writeInt(mMaxRssi);
    }

    /**
     * A {@link android.os.Parcelable.Creator} to create {@link ScanFilter} form parcel.
     */
    public static final Creator<ScanFilter>
            CREATOR = new Creator<ScanFilter>() {

                    @Override
                public ScanFilter[] newArray(int size) {
                    return new ScanFilter[size];
                }

                    @Override
                public ScanFilter createFromParcel(Parcel in) {
                    Builder builder = new Builder();
                    if (in.readInt() == 1) {
                        builder.setName(in.readString());
                    }
                    if (in.readInt() == 1) {
                        builder.setMacAddress(in.readString());
                    }
                    if (in.readInt() == 1) {
                        ParcelUuid uuid = in.readParcelable(ParcelUuid.class.getClassLoader());
                        builder.setServiceUuid(uuid);
                        if (in.readInt() == 1) {
                            ParcelUuid uuidMask = in.readParcelable(
                                    ParcelUuid.class.getClassLoader());
                            builder.setServiceUuid(uuid, uuidMask);
                        }
                    }

                    int serviceDataLength = in.readInt();
                    if (serviceDataLength > 0) {
                        byte[] serviceData = new byte[serviceDataLength];
                        in.readByteArray(serviceData);
                        builder.setServiceData(serviceData);
                        int serviceDataMaskLength = in.readInt();
                        if (serviceDataMaskLength > 0) {
                            byte[] serviceDataMask = new byte[serviceDataMaskLength];
                            in.readByteArray(serviceDataMask);
                            builder.setServiceData(serviceData, serviceDataMask);
                        }
                    }

                    int manufacturerId = in.readInt();
                    int manufacturerDataLength = in.readInt();
                    if (manufacturerDataLength > 0) {
                        byte[] manufacturerData = new byte[manufacturerDataLength];
                        in.readByteArray(manufacturerData);
                        builder.setManufacturerData(manufacturerId, manufacturerData);
                        int manufacturerDataMaskLength = in.readInt();
                        if (manufacturerDataMaskLength > 0) {
                            byte[] manufacturerDataMask = new byte[manufacturerDataMaskLength];
                            in.readByteArray(manufacturerDataMask);
                            builder.setManufacturerData(manufacturerId, manufacturerData,
                                    manufacturerDataMask);
                        }
                    }

                    int minRssi = in.readInt();
                    int maxRssi = in.readInt();
                    builder.setRssiRange(minRssi, maxRssi);
                    return builder.build();
                }
            };

    /**
     * Returns the filter set the local name field of Bluetooth advertisement data.
     */
    @Nullable
    public String getLocalName() {
        return mLocalName;
    }

    /**
     * Returns the filter set on the service uuid.
     */
    @Nullable
    public ParcelUuid getServiceUuid() {
        return mServiceUuid;
    }

    @Nullable
    public ParcelUuid getServiceUuidMask() {
        return mServiceUuidMask;
    }

    @Nullable
    public String getDeviceAddress() {
        return mMacAddress;
    }

    @Nullable
    public byte[] getServiceData() {
        return mServiceData;
    }

    @Nullable
    public byte[] getServiceDataMask() {
        return mServiceDataMask;
    }

    /**
     * Returns the manufacturer id. -1 if the manufacturer filter is not set.
     */
    public int getManufacturerId() {
        return mManufacturerId;
    }

    @Nullable
    public byte[] getManufacturerData() {
        return mManufacturerData;
    }

    @Nullable
    public byte[] getManufacturerDataMask() {
        return mManufacturerDataMask;
    }

    /**
     * Returns minimum value of rssi for the scan filter. {@link Integer#MIN_VALUE} if not set.
     */
    public int getMinRssi() {
        return mMinRssi;
    }

    /**
     * Returns maximum value of the rssi for the scan filter. {@link Integer#MAX_VALUE} if not set.
     */
    public int getMaxRssi() {
        return mMaxRssi;
    }

    /**
     * Check if the scan filter matches a {@code scanResult}. A scan result is considered as a match
     * if it matches all the field filters.
     */
    public boolean matches(ScanResult scanResult) {
        if (scanResult == null) {
            return false;
        }
        BluetoothDevice device = scanResult.getDevice();
        // Device match.
        if (mMacAddress != null && (device == null || !mMacAddress.equals(device.getAddress()))) {
            return false;
        }

        int rssi = scanResult.getRssi();
        if (rssi < mMinRssi || rssi > mMaxRssi) {
            return false;
        }

        byte[] scanRecordBytes = scanResult.getScanRecord();
        ScanRecord scanRecord = ScanRecord.parseFromBytes(scanRecordBytes);

        // Scan record is null but there exist filters on it.
        if (scanRecord == null
                && (mLocalName != null || mServiceUuid != null || mManufacturerData != null
                        || mServiceData != null)) {
            return false;
        }

        // Local name match.
        if (mLocalName != null && !mLocalName.equals(scanRecord.getLocalName())) {
            return false;
        }

        // UUID match.
        if (mServiceUuid != null && !matchesServiceUuids(mServiceUuid, mServiceUuidMask,
                scanRecord.getServiceUuids())) {
            return false;
        }

        // Service data match
        if (mServiceData != null &&
                !matchesPartialData(mServiceData, mServiceDataMask, scanRecord.getServiceData())) {
            return false;
        }

        // Manufacturer data match.
        if (mManufacturerData != null && !matchesPartialData(mManufacturerData,
                mManufacturerDataMask, scanRecord.getManufacturerSpecificData())) {
            return false;
        }
        // All filters match.
        return true;
    }

    // Check if the uuid pattern is contained in a list of parcel uuids.
    private boolean matchesServiceUuids(ParcelUuid uuid, ParcelUuid parcelUuidMask,
            List<ParcelUuid> uuids) {
        if (uuid == null) {
            return true;
        }
        if (uuids == null) {
            return false;
        }

        for (ParcelUuid parcelUuid : uuids) {
            UUID uuidMask = parcelUuidMask == null ? null : parcelUuidMask.getUuid();
            if (matchesServiceUuid(uuid.getUuid(), uuidMask, parcelUuid.getUuid())) {
                return true;
            }
        }
        return false;
    }

    // Check if the uuid pattern matches the particular service uuid.
    private boolean matchesServiceUuid(UUID uuid, UUID mask, UUID data) {
        if (mask == null) {
            return uuid.equals(data);
        }
        if ((uuid.getLeastSignificantBits() & mask.getLeastSignificantBits()) !=
                (data.getLeastSignificantBits() & mask.getLeastSignificantBits())) {
            return false;
        }
        return ((uuid.getMostSignificantBits() & mask.getMostSignificantBits()) ==
                (data.getMostSignificantBits() & mask.getMostSignificantBits()));
    }

    // Check whether the data pattern matches the parsed data.
    private boolean matchesPartialData(byte[] data, byte[] dataMask, byte[] parsedData) {
        if (dataMask == null) {
            return Arrays.equals(data, parsedData);
        }
        if (parsedData == null) {
            return false;
        }
        for (int i = 0; i < data.length; ++i) {
            if ((dataMask[i] & parsedData[i]) != (dataMask[i] & data[i])) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "BluetoothLeScanFilter [mLocalName=" + mLocalName + ", mMacAddress=" + mMacAddress
                + ", mUuid=" + mServiceUuid + ", mUuidMask=" + mServiceUuidMask + ", mServiceData="
                + Arrays.toString(mServiceData) + ", mServiceDataMask="
                + Arrays.toString(mServiceDataMask) + ", mManufacturerId=" + mManufacturerId
                + ", mManufacturerData=" + Arrays.toString(mManufacturerData)
                + ", mManufacturerDataMask=" + Arrays.toString(mManufacturerDataMask)
                + ", mMinRssi=" + mMinRssi + ", mMaxRssi=" + mMaxRssi + "]";
    }

    @Override
    public int hashCode() {
        return Objects.hash(mLocalName, mMacAddress, mManufacturerId, mManufacturerData,
                mManufacturerDataMask, mMaxRssi, mMinRssi, mServiceData, mServiceDataMask,
                mServiceUuid, mServiceUuidMask);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        ScanFilter other = (ScanFilter) obj;
        return Objects.equals(mLocalName, other.mLocalName) &&
                Objects.equals(mMacAddress, other.mMacAddress) &&
                        mManufacturerId == other.mManufacturerId &&
                Objects.deepEquals(mManufacturerData, other.mManufacturerData) &&
                Objects.deepEquals(mManufacturerDataMask, other.mManufacturerDataMask) &&
                        mMinRssi == other.mMinRssi && mMaxRssi == other.mMaxRssi &&
                Objects.deepEquals(mServiceData, other.mServiceData) &&
                Objects.deepEquals(mServiceDataMask, other.mServiceDataMask) &&
                Objects.equals(mServiceUuid, other.mServiceUuid) &&
                Objects.equals(mServiceUuidMask, other.mServiceUuidMask);
    }

    /**
     * Builder class for {@link ScanFilter}.
     */
    public static final class Builder {

        private String mLocalName;
        private String mMacAddress;

        private ParcelUuid mServiceUuid;
        private ParcelUuid mUuidMask;

        private byte[] mServiceData;
        private byte[] mServiceDataMask;

        private int mManufacturerId = -1;
        private byte[] mManufacturerData;
        private byte[] mManufacturerDataMask;

        private int mMinRssi = Integer.MIN_VALUE;
        private int mMaxRssi = Integer.MAX_VALUE;

        /**
         * Set filter on local name.
         */
        public Builder setName(String localName) {
            mLocalName = localName;
            return this;
        }

        /**
         * Set filter on device mac address.
         *
         * @param macAddress The device mac address for the filter. It needs to be in the format of
         *            "01:02:03:AB:CD:EF". The mac address can be validated using
         *            {@link BluetoothAdapter#checkBluetoothAddress}.
         * @throws IllegalArgumentException If the {@code macAddress} is invalid.
         */
        public Builder setMacAddress(String macAddress) {
            if (macAddress != null && !BluetoothAdapter.checkBluetoothAddress(macAddress)) {
                throw new IllegalArgumentException("invalid mac address " + macAddress);
            }
            mMacAddress = macAddress;
            return this;
        }

        /**
         * Set filter on service uuid.
         */
        public Builder setServiceUuid(ParcelUuid serviceUuid) {
            mServiceUuid = serviceUuid;
            mUuidMask = null; // clear uuid mask
            return this;
        }

        /**
         * Set filter on partial service uuid. The {@code uuidMask} is the bit mask for the
         * {@code serviceUuid}. Set any bit in the mask to 1 to indicate a match is needed for the
         * bit in {@code serviceUuid}, and 0 to ignore that bit.
         *
         * @throws IllegalArgumentException If {@code serviceUuid} is {@code null} but
         *             {@code uuidMask} is not {@code null}.
         */
        public Builder setServiceUuid(ParcelUuid serviceUuid, ParcelUuid uuidMask) {
            if (mUuidMask != null && mServiceUuid == null) {
                throw new IllegalArgumentException("uuid is null while uuidMask is not null!");
            }
            mServiceUuid = serviceUuid;
            mUuidMask = uuidMask;
            return this;
        }

        /**
         * Set filtering on service data.
         */
        public Builder setServiceData(byte[] serviceData) {
            mServiceData = serviceData;
            mServiceDataMask = null; // clear service data mask
            return this;
        }

        /**
         * Set partial filter on service data. For any bit in the mask, set it to 1 if it needs to
         * match the one in service data, otherwise set it to 0 to ignore that bit.
         * <p>
         * The {@code serviceDataMask} must have the same length of the {@code serviceData}.
         *
         * @throws IllegalArgumentException If {@code serviceDataMask} is {@code null} while
         *             {@code serviceData} is not or {@code serviceDataMask} and {@code serviceData}
         *             has different length.
         */
        public Builder setServiceData(byte[] serviceData, byte[] serviceDataMask) {
            if (mServiceDataMask != null) {
                if (mServiceData == null) {
                    throw new IllegalArgumentException(
                            "serviceData is null while serviceDataMask is not null");
                }
                // Since the mServiceDataMask is a bit mask for mServiceData, the lengths of the two
                // byte array need to be the same.
                if (mServiceData.length != mServiceDataMask.length) {
                    throw new IllegalArgumentException(
                            "size mismatch for service data and service data mask");
                }
            }
            mServiceData = serviceData;
            mServiceDataMask = serviceDataMask;
            return this;
        }

        /**
         * Set filter on on manufacturerData. A negative manufacturerId is considered as invalid id.
         * <p>
         * Note the first two bytes of the {@code manufacturerData} is the manufacturerId.
         *
         * @throws IllegalArgumentException If the {@code manufacturerId} is invalid.
         */
        public Builder setManufacturerData(int manufacturerId, byte[] manufacturerData) {
            if (manufacturerData != null && manufacturerId < 0) {
                throw new IllegalArgumentException("invalid manufacture id");
            }
            mManufacturerId = manufacturerId;
            mManufacturerData = manufacturerData;
            mManufacturerDataMask = null; // clear manufacturer data mask
            return this;
        }

        /**
         * Set filter on partial manufacture data. For any bit in the mask, set it the 1 if it
         * needs to match the one in manufacturer data, otherwise set it to 0.
         * <p>
         * The {@code manufacturerDataMask} must have the same length of {@code manufacturerData}.
         *
         * @throws IllegalArgumentException If the {@code manufacturerId} is invalid, or
         *             {@code manufacturerData} is null while {@code manufacturerDataMask} is not,
         *             or {@code manufacturerData} and {@code manufacturerDataMask} have different
         *             length.
         */
        public Builder setManufacturerData(int manufacturerId, byte[] manufacturerData,
                byte[] manufacturerDataMask) {
            if (manufacturerData != null && manufacturerId < 0) {
                throw new IllegalArgumentException("invalid manufacture id");
            }
            if (mManufacturerDataMask != null) {
                if (mManufacturerData == null) {
                    throw new IllegalArgumentException(
                            "manufacturerData is null while manufacturerDataMask is not null");
                }
                // Since the mManufacturerDataMask is a bit mask for mManufacturerData, the lengths
                // of the two byte array need to be the same.
                if (mManufacturerData.length != mManufacturerDataMask.length) {
                    throw new IllegalArgumentException(
                            "size mismatch for manufacturerData and manufacturerDataMask");
                }
            }
            mManufacturerId = manufacturerId;
            mManufacturerData = manufacturerData;
            mManufacturerDataMask = manufacturerDataMask;
            return this;
        }

        /**
         * Set the desired rssi range for the filter. A scan result with rssi in the range of
         * [minRssi, maxRssi] will be consider as a match.
         */
        public Builder setRssiRange(int minRssi, int maxRssi) {
            mMinRssi = minRssi;
            mMaxRssi = maxRssi;
            return this;
        }

        /**
         * Build {@link ScanFilter}.
         *
         * @throws IllegalArgumentException If the filter cannot be built.
         */
        public ScanFilter build() {
            return new ScanFilter(mLocalName, mMacAddress,
                    mServiceUuid, mUuidMask,
                    mServiceData, mServiceDataMask,
                    mManufacturerId, mManufacturerData, mManufacturerDataMask, mMinRssi, mMaxRssi);
        }
    }
}
