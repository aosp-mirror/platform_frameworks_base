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

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothDevice.AddressType;
import android.os.Parcel;
import android.os.ParcelUuid;
import android.os.Parcelable;

import com.android.internal.util.BitUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Criteria for filtering result from Bluetooth LE scans. A {@link ScanFilter} allows clients to
 * restrict scan results to only those that are of interest to them.
 * <p>
 * Current filtering on the following fields are supported:
 * <li>Service UUIDs which identify the bluetooth gatt services running on the device.
 * <li>Name of remote Bluetooth LE device.
 * <li>Mac address of the remote device.
 * <li>Service data which is the data associated with a service.
 * <li>Manufacturer specific data which is the data associated with a particular manufacturer.
 *
 * @see ScanResult
 * @see BluetoothLeScanner
 */
public final class ScanFilter implements Parcelable {

    @Nullable
    private final String mDeviceName;

    @Nullable
    private final String mDeviceAddress;

    private final @AddressType int mAddressType;

    @Nullable
    private final byte[] mIrk;

    @Nullable
    private final ParcelUuid mServiceUuid;
    @Nullable
    private final ParcelUuid mServiceUuidMask;

    @Nullable
    private final ParcelUuid mServiceSolicitationUuid;
    @Nullable
    private final ParcelUuid mServiceSolicitationUuidMask;

    @Nullable
    private final ParcelUuid mServiceDataUuid;
    @Nullable
    private final byte[] mServiceData;
    @Nullable
    private final byte[] mServiceDataMask;

    private final int mManufacturerId;
    @Nullable
    private final byte[] mManufacturerData;
    @Nullable
    private final byte[] mManufacturerDataMask;

    /** @hide */
    public static final ScanFilter EMPTY = new ScanFilter.Builder().build();

    private ScanFilter(String name, String deviceAddress, ParcelUuid uuid,
            ParcelUuid uuidMask, ParcelUuid solicitationUuid,
            ParcelUuid solicitationUuidMask, ParcelUuid serviceDataUuid,
            byte[] serviceData, byte[] serviceDataMask,
            int manufacturerId, byte[] manufacturerData, byte[] manufacturerDataMask,
            @AddressType int addressType, @Nullable byte[] irk) {
        mDeviceName = name;
        mServiceUuid = uuid;
        mServiceUuidMask = uuidMask;
        mServiceSolicitationUuid = solicitationUuid;
        mServiceSolicitationUuidMask = solicitationUuidMask;
        mDeviceAddress = deviceAddress;
        mServiceDataUuid = serviceDataUuid;
        mServiceData = serviceData;
        mServiceDataMask = serviceDataMask;
        mManufacturerId = manufacturerId;
        mManufacturerData = manufacturerData;
        mManufacturerDataMask = manufacturerDataMask;
        mAddressType = addressType;
        mIrk = irk;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mDeviceName == null ? 0 : 1);
        if (mDeviceName != null) {
            dest.writeString(mDeviceName);
        }
        dest.writeInt(mDeviceAddress == null ? 0 : 1);
        if (mDeviceAddress != null) {
            dest.writeString(mDeviceAddress);
        }
        dest.writeInt(mServiceUuid == null ? 0 : 1);
        if (mServiceUuid != null) {
            dest.writeParcelable(mServiceUuid, flags);
            dest.writeInt(mServiceUuidMask == null ? 0 : 1);
            if (mServiceUuidMask != null) {
                dest.writeParcelable(mServiceUuidMask, flags);
            }
        }
        dest.writeInt(mServiceSolicitationUuid == null ? 0 : 1);
        if (mServiceSolicitationUuid != null) {
            dest.writeParcelable(mServiceSolicitationUuid, flags);
            dest.writeInt(mServiceSolicitationUuidMask == null ? 0 : 1);
            if (mServiceSolicitationUuidMask != null) {
                dest.writeParcelable(mServiceSolicitationUuidMask, flags);
            }
        }
        dest.writeInt(mServiceDataUuid == null ? 0 : 1);
        if (mServiceDataUuid != null) {
            dest.writeParcelable(mServiceDataUuid, flags);
            dest.writeInt(mServiceData == null ? 0 : 1);
            if (mServiceData != null) {
                dest.writeInt(mServiceData.length);
                dest.writeByteArray(mServiceData);

                dest.writeInt(mServiceDataMask == null ? 0 : 1);
                if (mServiceDataMask != null) {
                    dest.writeInt(mServiceDataMask.length);
                    dest.writeByteArray(mServiceDataMask);
                }
            }
        }
        dest.writeInt(mManufacturerId);
        dest.writeInt(mManufacturerData == null ? 0 : 1);
        if (mManufacturerData != null) {
            dest.writeInt(mManufacturerData.length);
            dest.writeByteArray(mManufacturerData);

            dest.writeInt(mManufacturerDataMask == null ? 0 : 1);
            if (mManufacturerDataMask != null) {
                dest.writeInt(mManufacturerDataMask.length);
                dest.writeByteArray(mManufacturerDataMask);
            }
        }

        // IRK
        if (mDeviceAddress != null) {
            dest.writeInt(mAddressType);
            dest.writeInt(mIrk == null ? 0 : 1);
            if (mIrk != null) {
                dest.writeByteArray(mIrk);
            }
        }
    }

    /**
     * A {@link android.os.Parcelable.Creator} to create {@link ScanFilter} from parcel.
     */
    public static final @android.annotation.NonNull Creator<ScanFilter> CREATOR =
            new Creator<ScanFilter>() {

        @Override
        public ScanFilter[] newArray(int size) {
            return new ScanFilter[size];
        }

        @Override
        public ScanFilter createFromParcel(Parcel in) {
            Builder builder = new Builder();
            if (in.readInt() == 1) {
                builder.setDeviceName(in.readString());
            }
            String address = null;
            // If we have a non-null address
            if (in.readInt() == 1) {
                address = in.readString();
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
            if (in.readInt() == 1) {
                ParcelUuid solicitationUuid = in.readParcelable(
                        ParcelUuid.class.getClassLoader());
                builder.setServiceSolicitationUuid(solicitationUuid);
                if (in.readInt() == 1) {
                    ParcelUuid solicitationUuidMask = in.readParcelable(
                            ParcelUuid.class.getClassLoader());
                    builder.setServiceSolicitationUuid(solicitationUuid,
                            solicitationUuidMask);
                }
            }
            if (in.readInt() == 1) {
                ParcelUuid servcieDataUuid =
                        in.readParcelable(ParcelUuid.class.getClassLoader());
                if (in.readInt() == 1) {
                    int serviceDataLength = in.readInt();
                    byte[] serviceData = new byte[serviceDataLength];
                    in.readByteArray(serviceData);
                    if (in.readInt() == 0) {
                        builder.setServiceData(servcieDataUuid, serviceData);
                    } else {
                        int serviceDataMaskLength = in.readInt();
                        byte[] serviceDataMask = new byte[serviceDataMaskLength];
                        in.readByteArray(serviceDataMask);
                        builder.setServiceData(
                                servcieDataUuid, serviceData, serviceDataMask);
                    }
                }
            }

            int manufacturerId = in.readInt();
            if (in.readInt() == 1) {
                int manufacturerDataLength = in.readInt();
                byte[] manufacturerData = new byte[manufacturerDataLength];
                in.readByteArray(manufacturerData);
                if (in.readInt() == 0) {
                    builder.setManufacturerData(manufacturerId, manufacturerData);
                } else {
                    int manufacturerDataMaskLength = in.readInt();
                    byte[] manufacturerDataMask = new byte[manufacturerDataMaskLength];
                    in.readByteArray(manufacturerDataMask);
                    builder.setManufacturerData(manufacturerId, manufacturerData,
                            manufacturerDataMask);
                }
            }

            // IRK
            if (address != null) {
                final int addressType = in.readInt();
                if (in.readInt() == 1) {
                    final byte[] irk = new byte[16];
                    in.readByteArray(irk);
                    builder.setDeviceAddress(address, addressType, irk);
                } else {
                    builder.setDeviceAddress(address, addressType);
                }
            }
            return builder.build();
        }
    };

    /**
     * Returns the filter set the device name field of Bluetooth advertisement data.
     */
    @Nullable
    public String getDeviceName() {
        return mDeviceName;
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

    /**
     * Returns the filter set on the service Solicitation uuid.
     */
    @Nullable
    public ParcelUuid getServiceSolicitationUuid() {
        return mServiceSolicitationUuid;
    }

    /**
     * Returns the filter set on the service Solicitation uuid mask.
     */
    @Nullable
    public ParcelUuid getServiceSolicitationUuidMask() {
        return mServiceSolicitationUuidMask;
    }

    @Nullable
    public String getDeviceAddress() {
        return mDeviceAddress;
    }

    /**
     * @hide
     */
    @SystemApi
    public @AddressType int getAddressType() {
        return mAddressType;
    }

    /**
     * @hide
     */
    @SystemApi
    @Nullable
    public byte[] getIrk() {
        return mIrk;
    }

    @Nullable
    public byte[] getServiceData() {
        return mServiceData;
    }

    @Nullable
    public byte[] getServiceDataMask() {
        return mServiceDataMask;
    }

    @Nullable
    public ParcelUuid getServiceDataUuid() {
        return mServiceDataUuid;
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
     * Check if the scan filter matches a {@code scanResult}. A scan result is considered as a match
     * if it matches all the field filters.
     */
    public boolean matches(ScanResult scanResult) {
        if (scanResult == null) {
            return false;
        }
        BluetoothDevice device = scanResult.getDevice();
        // Device match.
        if (mDeviceAddress != null
                && (device == null || !mDeviceAddress.equals(device.getAddress()))) {
            return false;
        }

        ScanRecord scanRecord = scanResult.getScanRecord();

        // Scan record is null but there exist filters on it.
        if (scanRecord == null
                && (mDeviceName != null || mServiceUuid != null || mManufacturerData != null
                || mServiceData != null || mServiceSolicitationUuid != null)) {
            return false;
        }

        // Local name match.
        if (mDeviceName != null && !mDeviceName.equals(scanRecord.getDeviceName())) {
            return false;
        }

        // UUID match.
        if (mServiceUuid != null && !matchesServiceUuids(mServiceUuid, mServiceUuidMask,
                scanRecord.getServiceUuids())) {
            return false;
        }

        // solicitation UUID match.
        if (mServiceSolicitationUuid != null && !matchesServiceSolicitationUuids(
                mServiceSolicitationUuid, mServiceSolicitationUuidMask,
                scanRecord.getServiceSolicitationUuids())) {
            return false;
        }

        // Service data match
        if (mServiceDataUuid != null) {
            if (!matchesPartialData(mServiceData, mServiceDataMask,
                    scanRecord.getServiceData(mServiceDataUuid))) {
                return false;
            }
        }

        // Manufacturer data match.
        if (mManufacturerId >= 0) {
            if (!matchesPartialData(mManufacturerData, mManufacturerDataMask,
                    scanRecord.getManufacturerSpecificData(mManufacturerId))) {
                return false;
            }
        }
        // All filters match.
        return true;
    }

    /**
     * Check if the uuid pattern is contained in a list of parcel uuids.
     *
     * @hide
     */
    public static boolean matchesServiceUuids(ParcelUuid uuid, ParcelUuid parcelUuidMask,
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
    private static boolean matchesServiceUuid(UUID uuid, UUID mask, UUID data) {
        return BitUtils.maskedEquals(data, uuid, mask);
    }

    /**
     * Check if the solicitation uuid pattern is contained in a list of parcel uuids.
     *
     */
    private static boolean matchesServiceSolicitationUuids(ParcelUuid solicitationUuid,
            ParcelUuid parcelSolicitationUuidMask, List<ParcelUuid> solicitationUuids) {
        if (solicitationUuid == null) {
            return true;
        }
        if (solicitationUuids == null) {
            return false;
        }

        for (ParcelUuid parcelSolicitationUuid : solicitationUuids) {
            UUID solicitationUuidMask = parcelSolicitationUuidMask == null
                    ? null : parcelSolicitationUuidMask.getUuid();
            if (matchesServiceUuid(solicitationUuid.getUuid(), solicitationUuidMask,
                    parcelSolicitationUuid.getUuid())) {
                return true;
            }
        }
        return false;
    }

    // Check if the solicitation uuid pattern matches the particular service solicitation uuid.
    private static boolean matchesServiceSolicitationUuid(UUID solicitationUuid,
            UUID solicitationUuidMask, UUID data) {
        return BitUtils.maskedEquals(data, solicitationUuid, solicitationUuidMask);
    }

    // Check whether the data pattern matches the parsed data.
    private boolean matchesPartialData(byte[] data, byte[] dataMask, byte[] parsedData) {
        if (parsedData == null || parsedData.length < data.length) {
            return false;
        }
        if (dataMask == null) {
            for (int i = 0; i < data.length; ++i) {
                if (parsedData[i] != data[i]) {
                    return false;
                }
            }
            return true;
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
        return "BluetoothLeScanFilter [mDeviceName=" + mDeviceName + ", mDeviceAddress="
                + mDeviceAddress
                + ", mUuid=" + mServiceUuid + ", mUuidMask=" + mServiceUuidMask
                + ", mServiceSolicitationUuid=" + mServiceSolicitationUuid
                + ", mServiceSolicitationUuidMask=" + mServiceSolicitationUuidMask
                + ", mServiceDataUuid=" + Objects.toString(mServiceDataUuid) + ", mServiceData="
                + Arrays.toString(mServiceData) + ", mServiceDataMask="
                + Arrays.toString(mServiceDataMask) + ", mManufacturerId=" + mManufacturerId
                + ", mManufacturerData=" + Arrays.toString(mManufacturerData)
                + ", mManufacturerDataMask=" + Arrays.toString(mManufacturerDataMask) + "]";
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDeviceName, mDeviceAddress, mManufacturerId,
                Arrays.hashCode(mManufacturerData),
                Arrays.hashCode(mManufacturerDataMask),
                mServiceDataUuid,
                Arrays.hashCode(mServiceData),
                Arrays.hashCode(mServiceDataMask),
                mServiceUuid, mServiceUuidMask,
                mServiceSolicitationUuid, mServiceSolicitationUuidMask);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        ScanFilter other = (ScanFilter) obj;
        return Objects.equals(mDeviceName, other.mDeviceName)
                && Objects.equals(mDeviceAddress, other.mDeviceAddress)
                && mManufacturerId == other.mManufacturerId
                && Objects.deepEquals(mManufacturerData, other.mManufacturerData)
                && Objects.deepEquals(mManufacturerDataMask, other.mManufacturerDataMask)
                && Objects.equals(mServiceDataUuid, other.mServiceDataUuid)
                && Objects.deepEquals(mServiceData, other.mServiceData)
                && Objects.deepEquals(mServiceDataMask, other.mServiceDataMask)
                && Objects.equals(mServiceUuid, other.mServiceUuid)
                && Objects.equals(mServiceUuidMask, other.mServiceUuidMask)
                && Objects.equals(mServiceSolicitationUuid, other.mServiceSolicitationUuid)
                && Objects.equals(mServiceSolicitationUuidMask,
                        other.mServiceSolicitationUuidMask);
    }

    /**
     * Checks if the scanfilter is empty
     *
     * @hide
     */
    public boolean isAllFieldsEmpty() {
        return EMPTY.equals(this);
    }

    /**
     * Builder class for {@link ScanFilter}.
     */
    public static final class Builder {

        /**
         * @hide
         */
        @SystemApi
        public static final int LEN_IRK_OCTETS = 16;

        private String mDeviceName;
        private String mDeviceAddress;
        private @AddressType int mAddressType = BluetoothDevice.ADDRESS_TYPE_PUBLIC;
        private byte[] mIrk;

        private ParcelUuid mServiceUuid;
        private ParcelUuid mUuidMask;

        private ParcelUuid mServiceSolicitationUuid;
        private ParcelUuid mServiceSolicitationUuidMask;

        private ParcelUuid mServiceDataUuid;
        private byte[] mServiceData;
        private byte[] mServiceDataMask;

        private int mManufacturerId = -1;
        private byte[] mManufacturerData;
        private byte[] mManufacturerDataMask;

        /**
         * Set filter on device name.
         */
        public Builder setDeviceName(String deviceName) {
            mDeviceName = deviceName;
            return this;
        }

        /**
         * Set filter on device address.
         *
         * @param deviceAddress The device Bluetooth address for the filter. It needs to be in the
         * format of "01:02:03:AB:CD:EF". The device address can be validated using {@link
         * BluetoothAdapter#checkBluetoothAddress}.  The @AddressType is defaulted to {@link
         * BluetoothDevice#ADDRESS_TYPE_PUBLIC}
         * @throws IllegalArgumentException If the {@code deviceAddress} is invalid.
         */
        public Builder setDeviceAddress(String deviceAddress) {
            if (deviceAddress == null) {
                mDeviceAddress = deviceAddress;
                return this;
            }
            return setDeviceAddress(deviceAddress, BluetoothDevice.ADDRESS_TYPE_PUBLIC);
        }

        /**
         * Set filter on Address with AddressType
         *
         * <p>This key is used to resolve a private address from a public address.
         *
         * @param deviceAddress The device Bluetooth address for the filter. It needs to be in the
         * format of "01:02:03:AB:CD:EF". The device address can be validated using {@link
         * BluetoothAdapter#checkBluetoothAddress}. May be any type of address.
         * @param addressType indication of the type of address
         * e.g. {@link BluetoothDevice#ADDRESS_TYPE_PUBLIC}
         * or {@link BluetoothDevice#ADDRESS_TYPE_RANDOM}
         *
         * @throws IllegalArgumentException If the {@code deviceAddress} is invalid.
         * @throws IllegalArgumentException If the {@code addressType} is invalid length
         * @throws NullPointerException if {@code deviceAddress} is null.
         *
         * @hide
         */
        @NonNull
        @SystemApi
        public Builder setDeviceAddress(@NonNull String deviceAddress,
                                        @AddressType int addressType) {
            return setDeviceAddressInternal(deviceAddress, addressType, null);
        }

        /**
         * Set filter on Address with AddressType and the Identity Resolving Key (IRK).
         *
         * <p>The IRK is used to resolve a {@link BluetoothDevice#ADDRESS_TYPE_PUBLIC} from
         * a PRIVATE_ADDRESS type.
         *
         * @param deviceAddress The device Bluetooth address for the filter. It needs to be in the
         * format of "01:02:03:AB:CD:EF". The device address can be validated using {@link
         * BluetoothAdapter#checkBluetoothAddress}.  This Address type must only be PUBLIC OR RANDOM
         * STATIC.
         * @param addressType indication of the type of address
         * e.g. {@link BluetoothDevice#ADDRESS_TYPE_PUBLIC}
         * or {@link BluetoothDevice#ADDRESS_TYPE_RANDOM}
         * @param irk non-null byte array representing the Identity Resolving Key
         *
         * @throws IllegalArgumentException If the {@code deviceAddress} is invalid.
         * @throws IllegalArgumentException if the {@code irk} is invalid length.
         * @throws IllegalArgumentException If the {@code addressType} is invalid length or is not
         * PUBLIC or RANDOM STATIC when an IRK is present.
         * @throws NullPointerException if {@code deviceAddress} or {@code irk} is null.
         *
         * @hide
         */
        @NonNull
        @SystemApi
        public Builder setDeviceAddress(@NonNull String deviceAddress,
                                        @AddressType int addressType,
                                        @NonNull byte[] irk) {
            requireNonNull(irk);
            if (irk.length != LEN_IRK_OCTETS) {
                throw new IllegalArgumentException("'irk' is invalid length!");
            }
            return setDeviceAddressInternal(deviceAddress, addressType, irk);
        }

        /**
         * Set filter on Address with AddressType and the Identity Resolving Key (IRK).
         *
         * <p>Internal setter for the device address
         *
         * @param deviceAddress The device Bluetooth address for the filter. It needs to be in the
         * format of "01:02:03:AB:CD:EF". The device address can be validated using {@link
         * BluetoothAdapter#checkBluetoothAddress}.
         * @param addressType indication of the type of address
         * e.g. {@link BluetoothDevice#ADDRESS_TYPE_PUBLIC}
         * @param irk non-null byte array representing the Identity Resolving Address; nullable
         * internally.
         *
         * @throws IllegalArgumentException If the {@code deviceAddress} is invalid.
         * @throws IllegalArgumentException If the {@code addressType} is invalid length.
         * @throws NullPointerException if {@code deviceAddress} is null.
         *
         * @hide
         */
        @NonNull
        private Builder setDeviceAddressInternal(@NonNull String deviceAddress,
                                                 @AddressType int addressType,
                                                 @Nullable byte[] irk) {

            // Make sure our deviceAddress is valid!
            requireNonNull(deviceAddress);
            if (!BluetoothAdapter.checkBluetoothAddress(deviceAddress)) {
                throw new IllegalArgumentException("invalid device address " + deviceAddress);
            }

            // Verify type range
            if (addressType < BluetoothDevice.ADDRESS_TYPE_PUBLIC
                || addressType > BluetoothDevice.ADDRESS_TYPE_RANDOM) {
                throw new IllegalArgumentException("'addressType' is invalid!");
            }

            // IRK can only be used for a PUBLIC or RANDOM (STATIC) Address.
            if (addressType == BluetoothDevice.ADDRESS_TYPE_RANDOM) {
                // Don't want a bad combination of address and irk!
                if (irk != null) {
                    // Since there are 3 possible RANDOM subtypes we must check to make sure
                    // the correct type of address is used.
                    if (!BluetoothAdapter.isAddressRandomStatic(deviceAddress)) {
                        throw new IllegalArgumentException(
                                "Invalid combination: IRK requires either a PUBLIC or "
                                + "RANDOM (STATIC) Address");
                    }
                }
            }

            // PUBLIC doesn't require extra work
            // Without an IRK any address may be accepted

            mDeviceAddress = deviceAddress;
            mAddressType = addressType;
            mIrk = irk;
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
         * @throws IllegalArgumentException If {@code serviceUuid} is {@code null} but {@code
         * uuidMask} is not {@code null}.
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
         * Set filter on service solicitation uuid.
         */
        public @NonNull Builder setServiceSolicitationUuid(
                @Nullable ParcelUuid serviceSolicitationUuid) {
            mServiceSolicitationUuid = serviceSolicitationUuid;
            if (serviceSolicitationUuid == null) {
                mServiceSolicitationUuidMask = null;
            }
            return this;
        }


        /**
         * Set filter on partial service Solicitation uuid. The {@code SolicitationUuidMask} is the
         * bit mask for the {@code serviceSolicitationUuid}. Set any bit in the mask to 1 to
         * indicate a match is needed for the bit in {@code serviceSolicitationUuid}, and 0 to
         * ignore that bit.
         *
         * @param serviceSolicitationUuid can only be null if solicitationUuidMask is null.
         * @param solicitationUuidMask can be null or a mask with no restriction.
         *
         * @throws IllegalArgumentException If {@code serviceSolicitationUuid} is {@code null} but
         *             {@code serviceSolicitationUuidMask} is not {@code null}.
         */
        public @NonNull Builder setServiceSolicitationUuid(
                @Nullable ParcelUuid serviceSolicitationUuid,
                @Nullable ParcelUuid solicitationUuidMask) {
            if (solicitationUuidMask != null && serviceSolicitationUuid == null) {
                throw new IllegalArgumentException(
                        "SolicitationUuid is null while SolicitationUuidMask is not null!");
            }
            mServiceSolicitationUuid = serviceSolicitationUuid;
            mServiceSolicitationUuidMask = solicitationUuidMask;
            return this;
        }

        /**
         * Set filtering on service data.
         *
         * @throws IllegalArgumentException If {@code serviceDataUuid} is null.
         */
        public Builder setServiceData(ParcelUuid serviceDataUuid, byte[] serviceData) {
            if (serviceDataUuid == null) {
                throw new IllegalArgumentException("serviceDataUuid is null");
            }
            mServiceDataUuid = serviceDataUuid;
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
         * @throws IllegalArgumentException If {@code serviceDataUuid} is null or {@code
         * serviceDataMask} is {@code null} while {@code serviceData} is not or {@code
         * serviceDataMask} and {@code serviceData} has different length.
         */
        public Builder setServiceData(ParcelUuid serviceDataUuid,
                byte[] serviceData, byte[] serviceDataMask) {
            if (serviceDataUuid == null) {
                throw new IllegalArgumentException("serviceDataUuid is null");
            }
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
            mServiceDataUuid = serviceDataUuid;
            mServiceData = serviceData;
            mServiceDataMask = serviceDataMask;
            return this;
        }

        /**
         * Set filter on on manufacturerData. A negative manufacturerId is considered as invalid id.
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
         * Set filter on partial manufacture data. For any bit in the mask, set it the 1 if it needs
         * to match the one in manufacturer data, otherwise set it to 0.
         * <p>
         * The {@code manufacturerDataMask} must have the same length of {@code manufacturerData}.
         *
         * @throws IllegalArgumentException If the {@code manufacturerId} is invalid, or {@code
         * manufacturerData} is null while {@code manufacturerDataMask} is not, or {@code
         * manufacturerData} and {@code manufacturerDataMask} have different length.
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
         * Build {@link ScanFilter}.
         *
         * @throws IllegalArgumentException If the filter cannot be built.
         */
        public ScanFilter build() {
            return new ScanFilter(mDeviceName, mDeviceAddress,
                    mServiceUuid, mUuidMask, mServiceSolicitationUuid,
                    mServiceSolicitationUuidMask,
                    mServiceDataUuid, mServiceData, mServiceDataMask,
                    mManufacturerId, mManufacturerData, mManufacturerDataMask,
                    mAddressType, mIrk);
        }
    }
}
